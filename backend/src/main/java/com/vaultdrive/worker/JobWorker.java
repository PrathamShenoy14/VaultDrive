package com.vaultdrive.worker;

import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

public final class JobWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private final BackgroundJobService jobs;
    private final JobWorkerProperties properties;
    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);
    private final List<JobType> types;
    private final Semaphore slots;
    private final Map<java.util.UUID, Task> active = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor renewals;
    private final Thread poller;
    private final Thread monitor;
    private final AtomicBoolean monitoring = new AtomicBoolean(true);
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final LongSupplier nanoTime;
    private int nextType;

    public JobWorker(BackgroundJobService jobs, List<JobHandler> handlers, JobWorkerProperties properties) {
        this(jobs, handlers, properties, System::nanoTime);
    }

    JobWorker(BackgroundJobService jobs, List<JobHandler> handlers, JobWorkerProperties properties, LongSupplier nanoTime) {
        if (properties.concurrency() < 1 || properties.concurrency() > 32 || !properties.isTimingValid()) {
            throw new IllegalArgumentException("Invalid job worker limits");
        }
        this.jobs = jobs;
        this.properties = properties;
        this.nanoTime = nanoTime;
        for (JobHandler handler : handlers) {
            if (this.handlers.putIfAbsent(Objects.requireNonNull(handler.jobType()), handler) != null) {
                throw new IllegalArgumentException("Duplicate job handler type");
            }
        }
        types = List.copyOf(this.handlers.keySet());
        slots = new Semaphore(properties.concurrency());
        workers = executor("job-work", properties.concurrency());
        renewals = executor("job-renew", properties.concurrency());
        // Timed control loops have no task queues. Database renewals are isolated
        // from the monitor so it can still cancel tasks during a slow renewal.
        poller = daemon("job-poll", () -> loop(this::poll, properties.pollDelay().toMillis(), accepting));
        monitor = daemon("job-monitor", () -> loop(this::maintain, properties.renewalInterval().toMillis(), monitoring));
    }

    public void start() {
        if (accepting.get() && started.compareAndSet(false, true)) {
            poller.start();
            monitor.start();
        }
    }

    public void poll() {
        requireNoTransaction();
        if (!accepting.get() || types.isEmpty() || !polling.compareAndSet(false, true)) { return; }
        try {
            // Capacity is reserved before touching PostgreSQL; at most N claims
            // may be dispatched or executing locally, including handoff work.
            for (int i = 0; i < properties.concurrency() && accepting.get() && slots.tryAcquire(); i++) {
                boolean handedOff = false;
                try {
                    ClaimedJob claim = null;
                    for (int j = 0; j < types.size() && accepting.get(); j++) {
                        JobType type = types.get(nextType);
                        nextType = (nextType + 1) % types.size();
                        var candidate = jobs.claimNext(type);
                        if (candidate.isPresent()) { claim = candidate.orElseThrow(); break; }
                    }
                    if (claim == null) { break; }
                    if (!accepting.get()) { break; } // A late claim commit remains leased for recovery.
                    Task task = new Task(claim);
                    active.put(claim.claimToken(), task);
                    try {
                        workers.execute(() -> execute(task));
                        handedOff = true;
                    } catch (RejectedExecutionException rejected) {
                        active.remove(claim.claimToken(), task);
                        task.cancel(); // Do not fabricate an outcome for a committed claim.
                        break;
                    }
                } finally {
                    if (!handedOff) { slots.release(); }
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Job worker polling paused: DATABASE_OR_DISPATCH_FAILURE");
        } finally {
            polling.set(false);
        }
    }

    void maintain() {
        for (Task task : active.values()) {
            if (!task.context.isActive()) {
                task.cancel();
            } else if (task.renewing.compareAndSet(false, true)) {
                try {
                    // One outstanding renewal per active task; slow database I/O
                    // cannot block the monitor's cancellation/deadline checks.
                    renewals.execute(() -> {
                        try {
                            if (task.context.isActive()) {
                                long requestStartedAt = task.context.renewalStartedAt();
                                if (jobs.renew(task.context.claim())) { task.context.renewed(requestStartedAt); }
                                else { task.cancel(); }
                            }
                        } catch (RuntimeException exception) {
                            task.cancel(); // Unknown renewal outcome is not ownership proof.
                            log.warn("Job worker cancelled: RENEWAL_UNCERTAIN");
                        } finally {
                            task.renewing.set(false);
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    task.renewing.set(false);
                    task.cancel();
                }
            }
        }
    }

    private void execute(Task task) {
        task.attach();
        try {
            requireNoTransaction();
            task.context.checkActive();
            try {
                long requestStartedAt = task.context.renewalStartedAt();
                if (!jobs.renew(task.context.claim())) { task.cancel(); return; }
                task.context.renewed(requestStartedAt);
            } catch (RuntimeException uncertain) {
                task.cancel();
                log.warn("Job worker cancelled: RENEWAL_UNCERTAIN");
                return;
            }
            task.context.checkActive();
            JobHandler.Result result;
            try {
                result = handlers.get(task.context.claim().jobType()).handle(task.context);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception handlerFailure) {
                if (task.context.isActive()) {
                    try { jobs.retry(task.context.claim()); }
                    catch (RuntimeException recordingFailure) { log.warn("Job outcome uncertain: DATABASE_FAILURE"); }
                }
                log.warn("Job handler ended: HANDLER_FAILURE");
                return;
            }
            task.context.checkActive();
            requireNoTransaction();
            try {
                if (result == JobHandler.Result.SUCCEEDED) {
                    jobs.complete(task.context.claim());
                } else if (result == JobHandler.Result.CONTINUE) {
                    jobs.continueJob(task.context.claim());
                } else {
                    jobs.retry(task.context.claim()); // Null is never implicit success.
                }
            } catch (RuntimeException recordingFailure) {
                log.warn("Job outcome uncertain: DATABASE_FAILURE");
            }
        } catch (InterruptedException interrupted) {
            task.cancel(); // Leave the unfinished claim to expire, never immediately rearm it.
            Thread.currentThread().interrupt();
        } catch (Throwable fatalFailure) {
            task.cancel();
            log.warn("Job worker cancelled: UNEXPECTED_FAILURE");
        } finally {
            task.context.cancel();
            task.detach();
            active.remove(task.context.claim().claimToken(), task);
            // Retain the slot until the actual thread returns, even if a handler
            // ignores interruption. Cancellation never spawns replacement threads.
            slots.release();
        }
    }

    public void stopClaiming() {
        accepting.set(false);
        poller.interrupt();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) { return; }
        stopClaiming();
        long deadline = System.nanoTime() + properties.shutdownGrace().toNanos();
        workers.shutdown(); // Active work can finish and renew during the grace window.
        try {
            workers.awaitTermination(properties.shutdownGrace().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            monitoring.set(false);
            monitor.interrupt();
            active.values().forEach(Task::cancel);
            workers.shutdownNow();
            renewals.shutdownNow();
            // One overall waiting budget, not a new grace period per executor.
            awaitWithinBudget(renewals, deadline);
            awaitWithinBudget(poller, deadline);
            awaitWithinBudget(monitor, deadline);
        }
    }

    private static void awaitWithinBudget(java.util.concurrent.ExecutorService executor, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { return; }
        try { executor.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Job orchestration requires no enclosing database transaction");
        }
    }

    private static ThreadPoolExecutor executor(String name, int concurrency) {
        return new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency), runnable -> daemon(name, runnable), new ThreadPoolExecutor.AbortPolicy());
    }

    private static void awaitWithinBudget(Thread thread, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { return; }
        try { TimeUnit.NANOSECONDS.timedJoin(thread, remaining); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void loop(Runnable action, long delayMillis, AtomicBoolean running) {
        while (running.get()) {
            try { action.run(); }
            catch (RuntimeException failure) { log.warn("Job control iteration ended: CONTROL_FAILURE"); }
            if (!running.get()) { break; }
            try { Thread.sleep(delayMillis); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
    }

    private static Thread daemon(String name, Runnable runnable) {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private final class Task {
        final JobExecutionContext context;
        final AtomicBoolean renewing = new AtomicBoolean();
        Thread thread;

        Task(ClaimedJob claim) { context = new JobExecutionContext(claim, properties.maxRuntime().toNanos(), nanoTime); }

        synchronized void attach() { thread = Thread.currentThread(); }

        synchronized void detach() { thread = null; }

        synchronized void cancel() {
            context.cancel();
            Thread running = thread;
            if (running != null) { running.interrupt(); }
        }
    }
}
