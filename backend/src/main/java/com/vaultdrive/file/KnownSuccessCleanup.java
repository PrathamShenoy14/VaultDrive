package com.vaultdrive.file;

import com.vaultdrive.storage.ObjectStorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Trusted internal explicit invocation only. No scheduler, endpoint or purge integration. */
@Service
@EnableConfigurationProperties(KnownSuccessCleanupProperties.class)
public class KnownSuccessCleanup {
    private final KnownSuccessCleanupTransactions transactions;
    private final ObjectStorageService storage;

    public KnownSuccessCleanup(KnownSuccessCleanupTransactions transactions, ObjectStorageService storage) {
        this.transactions = transactions;
        this.storage = storage;
    }

    public record Page(UUID nextCursor, int scanned, int deleted, int alreadyComplete, int blocked,
                       int busy, int failed, int scanFailed, boolean exhausted) { }

    @Transactional(propagation = Propagation.NEVER)
    public Page cleanupPage(UUID after) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<UUID> candidates;
        try { candidates = transactions.scan(after); }
        catch (RuntimeException failure) { return new Page(after, 0, 0, 0, 0, 0, 0, 1, false); }
        int scanned = 0, deleted = 0, complete = 0, blocked = 0, busy = 0, failed = 0;
        UUID next = after;
        for (UUID id : candidates) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) { break; }
            try {
                var prepared = transactions.prepare(id); // Must commit before any DELETE.
                switch (prepared.outcome()) {
                    case BLOCKED -> blocked++;
                    case BUSY -> busy++;
                    case COMPLETE -> complete++;
                    case CLAIMED -> {
                        var claim = prepared.claim();
                        try { storage.delete(claim.target()); }
                        catch (RuntimeException storageFailure) {
                            transactions.failed(claim);
                            failed++;
                            break;
                        }
                        // Unknown database acknowledgement preserves intent/claim for explicit replay.
                        if (transactions.complete(claim)) { deleted++; }
                        else { failed++; }
                    }
                }
            } catch (RuntimeException failure) { failed++; }
            scanned++;
            next = id;
        }
        return new Page(next, scanned, deleted, complete, blocked, busy, failed, 0, candidates.isEmpty());
    }
}
