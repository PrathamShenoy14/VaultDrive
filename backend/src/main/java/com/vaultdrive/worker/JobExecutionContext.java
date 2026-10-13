package com.vaultdrive.worker;

import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.BackgroundJobService;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

public final class JobExecutionContext {
    private final ClaimedJob claim;
    private final long startedAt;
    private final long maxRuntimeNanos;
    private final LongSupplier nanoTime;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile long confirmedRenewalStartedAt;

    JobExecutionContext(ClaimedJob claim, long maxRuntimeNanos, LongSupplier nanoTime) {
        this.claim = claim;
        this.nanoTime = nanoTime;
        this.startedAt = nanoTime.getAsLong();
        this.confirmedRenewalStartedAt = startedAt;
        this.maxRuntimeNanos = maxRuntimeNanos;
    }

    /** Identity/token snapshot; its original expiration is not the renewed deadline. */
    public ClaimedJob claim() { return claim; }

    /** Remaining local runtime for choosing a bounded unit or yielding early. */
    public long remainingRuntimeNanos() {
        return Math.max(0, maxRuntimeNanos - (nanoTime.getAsLong() - startedAt));
    }

    public boolean isActive() {
        long now = nanoTime.getAsLong();
        return !cancelled.get() && now - startedAt < maxRuntimeNanos
                && now - confirmedRenewalStartedAt < BackgroundJobService.LEASE.toNanos();
    }

    public void checkActive() throws InterruptedException {
        if (!isActive() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Job execution cancelled");
        }
    }

    void cancel() { cancelled.set(true); }

    long renewalStartedAt() { return nanoTime.getAsLong(); }

    synchronized void renewed(long requestStartedAt) {
        // Count the lease from before the database call, never from its return.
        // A stalled commit/connection cannot grant extra local execution time.
        if (requestStartedAt - confirmedRenewalStartedAt > 0) {
            confirmedRenewalStartedAt = requestStartedAt;
        }
    }
}
