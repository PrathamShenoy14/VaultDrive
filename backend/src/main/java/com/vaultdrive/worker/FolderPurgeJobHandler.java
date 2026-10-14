package com.vaultdrive.worker;

import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.JobType;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.S3ObjectStorageService;
import com.vaultdrive.storage.StorageDeletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/** Bounded discovery, byte deletion and durable bottom-up metadata finalization. */
@Component
public class FolderPurgeJobHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(FolderPurgeJobHandler.class);
    private static final long UNIT_RUNTIME = S3ObjectStorageService.DELETE_TIMEOUT.plus(Duration.ofSeconds(20)).toNanos();
    private final FolderPurgeDiscovery discovery;
    private final FolderPurgeDiscoveryService checkpoints;
    private final FolderPurgeFileService files;
    private final ObjectStorageService storage;
    private final BackgroundJobService jobs;
    private final FolderPurgeMetadataService metadata;

    public FolderPurgeJobHandler(FolderPurgeDiscovery discovery, FolderPurgeDiscoveryService checkpoints,
                                 FolderPurgeFileService files, ObjectStorageService storage, BackgroundJobService jobs,
                                 FolderPurgeMetadataService metadata) {
        this.discovery = discovery;
        this.checkpoints = checkpoints;
        this.files = files;
        this.storage = storage;
        this.jobs = jobs;
        this.metadata = metadata;
    }

    @Override public JobType jobType() { return JobType.FOLDER_PURGE; }

    @Override public Result handle(JobExecutionContext context) throws InterruptedException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Folder purge requires no enclosing database transaction");
        }
        context.checkActive();
        String phase = files.phase(context.claim());
        if ("DISCOVER".equals(phase)) { return discovery.discover(context, FolderPurgeDiscoveryService.MAX_PAGE_SIZE); }
        if ("DELETE_FOLDERS".equals(phase) || "FINALIZED".equals(phase)) {
            if (context.remainingRuntimeNanos() < Duration.ofSeconds(10).toNanos()) {
                throw new InterruptedException("Insufficient metadata removal runtime");
            }
            Result result = metadata.removeBatch(context, checkpoints.checkpointVersion(context.claim()),
                    FolderPurgeMetadataService.MAX_BATCH_SIZE);
            context.checkActive();
            return result;
        }
        long version = checkpoints.checkpointVersion(context.claim());
        boolean advanced = false;
        boolean storageFailure = false;
        for (var file : files.batch(context.claim(), FolderPurgeFileService.MAX_BATCH_SIZE)) {
            context.checkActive();
            if (context.remainingRuntimeNanos() < UNIT_RUNTIME) {
                if (advanced || storageFailure) { return storageFailure ? Result.RETRY : Result.CONTINUE; }
                throw new InterruptedException("Insufficient file deletion runtime");
            }
            try {
                var prepared = files.prepare(context, file);
                context.checkActive();
                long renewalStarted = context.renewalStartedAt();
                if (!jobs.renew(context.claim())) { throw new InterruptedException("Folder purge claim is stale"); }
                context.renewed(renewalStarted);
                context.checkActive();
                if (!prepared.proven() && !prepared.neverWritten()) {
                    storage.delete(prepared.target()); // Bounded adapter, after preparation commit.
                    context.checkActive();
                }
                if (files.completeFile(context, file, version)) { version++; advanced = true; }
            } catch (FolderPurgeFileService.BlockedFile blocked) {
                context.checkActive();
                if (files.blocked(context, file, blocked.reason(), version)) { version++; advanced = true; }
            } catch (StorageDeletionException failure) {
                context.checkActive();
                log.warn("Folder file deletion deferred: {} retryable={}", failure.kind(), failure.retryable());
                storageFailure = true;
            }
        }
        context.checkActive();
        if (files.advancePhase(context, version)) { advanced = true; }
        context.checkActive();
        // Real Garage failures spend the budget even if earlier receipts committed.
        return storageFailure ? Result.RETRY : advanced ? Result.CONTINUE : Result.RETRY;
    }
}
