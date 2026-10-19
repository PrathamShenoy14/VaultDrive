package com.vaultdrive.worker;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit discovery adapter invoked by the registered Slice 5 folder handler. */
@Service
public class FolderPurgeDiscovery {
    private final FolderPurgeDiscoveryService discovery;

    public FolderPurgeDiscovery(FolderPurgeDiscoveryService discovery) { this.discovery = discovery; }

    public JobHandler.Result discover(JobExecutionContext context, int pageSize) throws InterruptedException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Discovery requires no enclosing transaction");
        }
        context.checkActive();
        // A page has a ten-second transaction limit. Do not start it close to runtime exhaustion.
        if (context.remainingRuntimeNanos() < java.time.Duration.ofSeconds(10).toNanos()) {
            throw new InterruptedException("Insufficient discovery runtime");
        }
        var batch = discovery.discover(context, discovery.checkpointVersion(context.claim()), pageSize);
        context.checkActive();
        if (!batch.advanced()) { throw new IllegalStateException("Folder discovery made no progress"); }
        return JobHandler.Result.CONTINUE; // Including the sealed phase checkpoint; never claim purge SUCCESS.
    }
}
