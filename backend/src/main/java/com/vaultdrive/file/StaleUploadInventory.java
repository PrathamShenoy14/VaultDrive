package com.vaultdrive.file;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Explicit internal invocation only. One call never loops over the backlog. */
@Service
@EnableConfigurationProperties(StaleUploadInventoryProperties.class)
public class StaleUploadInventory {
    private final StaleUploadInventoryTransactions transactions;

    public StaleUploadInventory(StaleUploadInventoryTransactions transactions) {
        this.transactions = transactions;
    }

    public record Page(UUID nextCursor, int scanned, int neverAdmitted, int admittedUncertain,
                       int deferredToPurge, int inconsistent, int alreadyResolved, int scanFailed,
                       boolean exhausted) { }

    @Transactional(propagation = Propagation.NEVER)
    public Page scanPage(UUID after) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<UUID> candidates;
        try { candidates = transactions.scan(after); }
        catch (RuntimeException failure) {
            return new Page(after, 0, 0, 0, 0, 0, 0, 1, false);
        }
        int scanned = 0, never = 0, uncertain = 0, purge = 0, inconsistent = 0, resolved = 0, failed = 0;
        UUID next = after;
        for (UUID candidate : candidates) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) { break; }
            try {
                switch (transactions.inspect(candidate)) {
                    case NEVER_ADMITTED -> never++;
                    case ADMITTED_UNCERTAIN -> uncertain++;
                    case DEFERRED_TO_PURGE -> purge++;
                    case INCONSISTENT -> inconsistent++;
                    case ALREADY_RESOLVED -> resolved++;
                }
            } catch (RuntimeException failure) {
                // Controlled counts only. Do not expose exception text or storage identity.
                failed++;
            }
            scanned++;
            next = candidate; // Advance even on inconsistency or inspection timeout.
        }
        return new Page(next, scanned, never, uncertain, purge, inconsistent, resolved, failed,
                candidates.isEmpty());
    }
}
