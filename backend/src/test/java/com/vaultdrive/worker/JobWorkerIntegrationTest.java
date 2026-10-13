package com.vaultdrive.worker;

import com.vaultdrive.job.BackgroundJob;
import com.vaultdrive.job.BackgroundJobRepository;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobStatus;
import com.vaultdrive.job.JobType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=job_worker_test",
        "spring.flyway.schemas=job_worker_test", "spring.flyway.default-schema=job_worker_test"
})
@ActiveProfiles("test")
class JobWorkerIntegrationTest {
    @MockitoSpyBean private BackgroundJobService jobs;
    @Autowired private BackgroundJobRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<UUID> ids = new ArrayList<>();
    private final List<JobWorker> workers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        workers.forEach(JobWorker::close);
        reset(jobs);
        repository.deleteAllById(ids);
    }

    @Test
    void scheduledPollingDispatchesOutsideTransactionAndRecordsExplicitSuccess() throws Exception {
        UUID id = job();
        CountDownLatch completed = observeCompletion(1);
        var worker = worker(1, handler(context -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(load(id).getClaimToken()).isEqualTo(context.claim().claimToken());
            transaction().executeWithoutResult(status -> entityManager.createNativeQuery(
                    "SELECT id FROM background_jobs WHERE id = :id FOR UPDATE NOWAIT")
                    .setParameter("id", id).getSingleResult());
            return JobHandler.Result.SUCCEEDED;
        }));
        worker.start();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(load(id).getAttemptCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = JobHandler.Result.class, names = {"SUCCEEDED", "CONTINUE"})
    void concurrentSchedulersClaimDistinctJobsAndReserveCapacityBeforeClaiming(JobHandler.Result result) throws Exception {
        job(); job(); UUID untouched = job();
        CountDownLatch handling = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        if (result == JobHandler.Result.SUCCEEDED) {
            completed = observeCompletion(2);
        } else {
            CountDownLatch continued = completed;
            doAnswer(call -> { Object outcome = call.callRealMethod(); afterCommit(continued); return outcome; })
                    .when(jobs).continueJob(any());
        }
        List<UUID> claimed = java.util.Collections.synchronizedList(new ArrayList<>());
        var handler = handler(context -> {
            if (result == JobHandler.Result.CONTINUE) { checkpoint(context); }
            claimed.add(context.claim().id());
            handling.countDown();
            await(release);
            return result;
        });
        var one = worker(1, handler);
        var two = worker(1, handler);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { await(start); one.poll(); });
            var second = executor.submit(() -> { await(start); two.poll(); });
            start.countDown();
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertThat(handling.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(claimed).hasSize(2).doesNotHaveDuplicates();
            assertThat(load(untouched).getStatus()).isEqualTo(JobStatus.PENDING);
            for (int i = 0; i < 20; i++) { one.poll(); two.poll(); }
            assertThat(load(untouched).getAttemptCount()).isZero();
            release.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.findAllById(claimed)).allSatisfy(row -> {
                assertThat(row.getStatus()).isEqualTo(result == JobHandler.Result.SUCCEEDED ? JobStatus.SUCCEEDED : JobStatus.PENDING);
                assertThat(row.getAttemptCount()).isEqualTo(1);
                assertThat(row.getFailureCount()).isZero();
            });
        } finally {
            release.countDown(); start.countDown();
            executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void heartbeatRenewsLiveTokenWithoutChangingAttemptAndLostLeaseCancelsHandler() throws Exception {
        UUID id = job();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<ClaimedJob> claim = new AtomicReference<>();
        var worker = new JobWorker(jobs, List.of(new JobHandler() {
            public JobType jobType() { return JobType.FILE_PURGE; }
            public Result handle(JobExecutionContext context) throws Exception {
                claim.set(context.claim()); entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { interrupted.countDown(); throw e; }
                return Result.SUCCEEDED;
            }
        }), new JobWorkerProperties(true, 1, Duration.ofSeconds(5), Duration.ofMillis(50),
                Duration.ofMinutes(5), Duration.ofMillis(100)));
        workers.add(worker);
        worker.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        update(id, "claim_expires_at = clock_timestamp() + interval '2 seconds'");
        CountDownLatch renewed = new CountDownLatch(1);
        doAnswer(call -> { Object result = call.callRealMethod(); afterCommit(renewed); return result; }).when(jobs).renew(any());
        worker.maintain();
        assertThat(renewed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.between(load(id).getUpdatedAt(), load(id).getClaimExpiresAt())).isGreaterThan(Duration.ofSeconds(29));
        assertThat(load(id).getAttemptCount()).isEqualTo(1);
        update(id, "claim_expires_at = clock_timestamp() - interval '1 second', next_attempt_at = clock_timestamp() - interval '1 second'");
        var replacement = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        worker.maintain();
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(jobs.renew(claim.get())).isFalse();
        assertThat(jobs.complete(claim.get())).isFalse();
        assertThat(load(id).getClaimToken()).isEqualTo(replacement.claimToken());
        assertThat(load(id).getAttemptCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = JobHandler.Result.class, names = {"SUCCEEDED", "CONTINUE"})
    void timeoutKeepsCapacityReservedUntilIgnoringHandlerReturnsAndLateResultIsSuppressed(JobHandler.Result result) throws Exception {
        UUID id = job(); UUID pending = job();
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<ClaimedJob> old = new AtomicReference<>();
        var worker = new JobWorker(jobs, List.of(handler(context -> {
            checkpoint(context);
            old.set(context.claim()); entered.countDown();
            while (true) {
                try { if (release.await(5, TimeUnit.SECONDS)) { break; } }
                catch (InterruptedException e) { cancelled.countDown(); }
            }
            returned.countDown();
            return result;
        })), properties(1), time::get);
        workers.add(worker);
        try {
            worker.poll();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            time.set(Duration.ofMinutes(6).toNanos());
            worker.maintain();
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 20; i++) { worker.poll(); }
            assertThat(load(pending).getAttemptCount()).isZero();
            assertThat(load(id).getStatus()).isEqualTo(JobStatus.RUNNING);
            expire(id);
            var replacement = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
            release.countDown();
            assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
            worker.close();
            assertThat(jobs.complete(old.get())).isFalse();
            assertThat(load(id).getClaimToken()).isEqualTo(replacement.claimToken());
            assertThat(load(id).getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(load(id).getProgressVersion()).isEqualTo(1);
            assertThat(load(id).getFailureCount()).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    void abandonedClaimIsRecoveredByLaterSchedulerWithFreshToken() throws Exception {
        UUID id = job();
        var old = jobs.claimNext(JobType.FILE_PURGE).orElseThrow(); // Worker dies before dispatch/outcome.
        expire(id);
        AtomicReference<ClaimedJob> seen = new AtomicReference<>();
        CountDownLatch completed = observeCompletion(1);
        worker(1, handler(context -> { seen.set(context.claim()); return JobHandler.Result.SUCCEEDED; })).poll();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get().claimToken()).isNotEqualTo(old.claimToken());
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(load(id).getAttemptCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = JobHandler.Result.class, names = {"SUCCEEDED", "CONTINUE"})
    void shutdownStopsAdmissionBoundsWaitingAndLeavesUnfinishedClaimRecoverable(JobHandler.Result result) throws Exception {
        UUID id = job(); UUID pending = job();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var worker = worker(1, handler(context -> {
            checkpoint(context);
            entered.countDown();
            while (true) {
                try { if (release.await(5, TimeUnit.SECONDS)) { break; } }
                catch (InterruptedException e) { interrupted.countDown(); }
            }
            return result;
        }));
        try {
            worker.poll();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var closer = Executors.newSingleThreadExecutor();
            try { closer.submit(worker::close).get(2, TimeUnit.SECONDS); }
            finally { closer.shutdownNow(); closer.awaitTermination(2, TimeUnit.SECONDS); }
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll();
            assertThat(load(pending).getAttemptCount()).isZero();
            assertThat(load(id).getStatus()).isEqualTo(JobStatus.RUNNING);
            release.countDown();
            expire(id);
            var replacement = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
            assertThat(replacement.attemptCount()).isEqualTo(2);
            assertThat(load(id).getFailureCount()).isEqualTo(1);
            assertThat(load(id).getProgressVersion()).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    void noHandlerLeavesJobsUntouchedAndOrchestrationRejectsOuterTransaction() {
        UUID id = job();
        var worker = worker(1);
        worker.poll();
        assertThat(load(id).getAttemptCount()).isZero();
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> worker.poll()))
                .hasMessageContaining("no enclosing database transaction");
    }

    @Test
    void stalledRenewalDoesNotBlockLeaseDeadlineCancellationOrBoundedShutdown() throws Exception {
        UUID id = job();
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch renewalEntered = new CountDownLatch(1);
        CountDownLatch releaseRenewal = new CountDownLatch(1);
        CountDownLatch renewalReturned = new CountDownLatch(1);
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("job-renew")) {
                renewalEntered.countDown();
                while (true) {
                    try { if (releaseRenewal.await(5, TimeUnit.SECONDS)) { break; } }
                    catch (InterruptedException ignored) { /* Simulate an unresponsive driver call. */ }
                }
                afterCommit(renewalReturned);
                return true;
            }
            return call.callRealMethod();
        }).when(jobs).renew(any());
        var worker = new JobWorker(jobs, List.of(new JobHandler() {
            public JobType jobType() { return JobType.FILE_PURGE; }
            public Result handle(JobExecutionContext context) throws Exception {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { interrupted.countDown(); throw e; }
                return Result.SUCCEEDED;
            }
        }), properties(1), time::get);
        workers.add(worker);
        try {
            worker.poll();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.maintain();
            assertThat(renewalEntered.await(5, TimeUnit.SECONDS)).isTrue();
            time.set(Duration.ofSeconds(31).toNanos()); // Max runtime is five minutes, but local lease proof has expired.
            worker.maintain();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            var closer = Executors.newSingleThreadExecutor();
            try { closer.submit(worker::close).get(2, TimeUnit.SECONDS); }
            finally { closer.shutdownNow(); closer.awaitTermination(2, TimeUnit.SECONDS); }
            assertThat(load(id).getStatus()).isEqualTo(JobStatus.RUNNING);
        } finally {
            releaseRenewal.countDown();
            assertThat(renewalReturned.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void nullHandlerResultRetriesRatherThanSucceeds() throws Exception {
        UUID id = job();
        CountDownLatch retried = new CountDownLatch(1);
        doAnswer(call -> { Object result = call.callRealMethod(); afterCommit(retried); return result; }).when(jobs).retry(any());
        worker(1, handler(context -> null)).poll();
        assertThat(retried.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(load(id).getLastFailureCode()).isEqualTo("WORK_FAILED");
    }

    @Test
    void continuationRecordsDelayedPendingWithoutRedispatchAndShutdownDuringGraceAllowsYield() throws Exception {
        UUID id = job();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch continued = new CountDownLatch(1);
        doAnswer(call -> { Object result = call.callRealMethod(); afterCommit(continued); return result; })
                .when(jobs).continueJob(any());
        var worker = new JobWorker(jobs, List.of(handler(context -> {
            assertThat(context.remainingRuntimeNanos()).isPositive();
            checkpoint(context);
            entered.countDown(); await(release);
            return JobHandler.Result.CONTINUE;
        })), new JobWorkerProperties(true, 1, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofMinutes(5), Duration.ofSeconds(2)));
        workers.add(worker);
        try {
            worker.poll();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.stopClaiming();
            release.countDown();
            worker.close();
            assertThat(continued.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll();
            assertThat(load(id).getStatus()).isEqualTo(JobStatus.PENDING);
            assertThat(load(id).getAttemptCount()).isEqualTo(1);
            assertThat(load(id).getFailureCount()).isZero();
            assertThat(load(id).getProgressVersion()).isEqualTo(1);
            verify(jobs, times(1)).continueJob(any());
            verify(jobs, never()).retry(any());
        } finally { release.countDown(); }
    }

    @Test
    void handlerReturningContinueWithoutCheckpointRecordsOneFailure() throws Exception {
        UUID id = job();
        CountDownLatch rejected = new CountDownLatch(1);
        doAnswer(call -> { Object result = call.callRealMethod(); afterCommit(rejected); return result; })
                .when(jobs).continueJob(any());
        worker(1, handler(context -> JobHandler.Result.CONTINUE)).poll();
        assertThat(rejected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(load(id).getFailureCount()).isEqualTo(1);
        assertThat(load(id).getLastFailureCode()).isEqualTo("NO_PROGRESS");
        verify(jobs, never()).retry(any());
    }

    private void checkpoint(JobExecutionContext context) {
        transaction().executeWithoutResult(status -> jobs.checkpoint(context.claim(), 0));
    }

    private JobWorker worker(int concurrency, JobHandler... handlers) {
        var worker = new JobWorker(jobs, List.of(handlers), properties(concurrency));
        workers.add(worker);
        return worker;
    }
    private JobWorkerProperties properties(int concurrency) {
        return new JobWorkerProperties(true, concurrency, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofMinutes(5), Duration.ofMillis(100));
    }
    private JobHandler handler(Function<JobExecutionContext, JobHandler.Result> body) {
        return new JobHandler() {
            public JobType jobType() { return JobType.FILE_PURGE; }
            public Result handle(JobExecutionContext context) { return body.apply(context); }
        };
    }
    private CountDownLatch observeCompletion(int count) {
        var latch = new CountDownLatch(count);
        doAnswer(call -> { Object result = call.callRealMethod(); afterCommit(latch); return result; }).when(jobs).complete(any());
        return latch;
    }
    private UUID job() {
        UUID id = UUID.randomUUID(); ids.add(id);
        jobs.create(id, JobType.FILE_PURGE, UUID.randomUUID());
        return id;
    }
    private static void afterCommit(CountDownLatch latch) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { latch.countDown(); }
        });
    }
    private BackgroundJob load(UUID id) { return repository.findById(id).orElseThrow(); }
    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }
    private void expire(UUID id) {
        update(id, "claim_expires_at = clock_timestamp() - interval '1 second', next_attempt_at = clock_timestamp() - interval '1 second'");
    }
    private void update(UUID id, String assignment) {
        transaction().executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE background_jobs SET " + assignment + " WHERE id = :id").setParameter("id", id).executeUpdate());
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Test coordination timeout"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Test interrupted", e); }
    }
}
