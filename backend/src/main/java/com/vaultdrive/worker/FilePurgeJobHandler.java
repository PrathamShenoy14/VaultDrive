package com.vaultdrive.worker;

import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.JobType;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageDeletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class FilePurgeJobHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(FilePurgeJobHandler.class);
    private final FilePurgeProgressService progress;
    private final ObjectStorageService storage;
    private final BackgroundJobService jobs;

    public FilePurgeJobHandler(FilePurgeProgressService progress, ObjectStorageService storage,
                               BackgroundJobService jobs) {
        this.progress = progress;
        this.storage = storage;
        this.jobs = jobs;
    }

    @Override public JobType jobType() { return JobType.FILE_PURGE; }

    @Override
    public Result handle(JobExecutionContext context) throws InterruptedException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("File purge requires no enclosing database transaction");
        }
        context.checkActive();
        try {
            var prepared = progress.prepare(context.claim()); // Short transaction, no Garage I/O.
            context.checkActive();
            long renewalStarted = context.renewalStartedAt();
            if (!jobs.renew(context.claim())) { throw new InterruptedException("File purge claim is stale"); }
            context.renewed(renewalStarted);
            context.checkActive();
            if (!prepared.bytesDeleted()) {
                try {
                    storage.delete(prepared.target());
                } catch (StorageDeletionException failure) {
                    context.checkActive();
                    // Existing job lifecycle bounds all failures, including those needing operator repair.
                    log.warn("File purge deferred: {} retryable={}", failure.kind(), failure.retryable());
                    return Result.RETRY;
                }
                context.checkActive();
                progress.recordBytesDeleted(context.claim());
            }
            context.checkActive();
            progress.finalizeMetadata(context.claim()); // Can defer while byte deletion remains successful.
            context.checkActive();
            return Result.SUCCEEDED; // JobWorker alone records the fenced outcome.
        } catch (FilePurgeProgressService.StaleClaimException stale) {
            throw new InterruptedException("File purge claim is stale");
        }
    }
}
