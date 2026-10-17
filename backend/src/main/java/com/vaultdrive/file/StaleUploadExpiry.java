package com.vaultdrive.file;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Explicit internal operation; selection and every attempted expiry have independent transactions. */
@Service
@EnableConfigurationProperties(StaleUploadExpiryProperties.class)
public class StaleUploadExpiry {
    private final StaleUploadExpiryTransactions transactions;

    public StaleUploadExpiry(StaleUploadExpiryTransactions transactions) {
        this.transactions = transactions;
    }

    public record Page(UUID nextCursor, int scanned, int expired, int alreadyResolved,
                       int admissionWon, int deferredToPurge, int inconsistent, int failed,
                       int scanFailed, boolean exhausted) { }

    @Transactional(propagation = Propagation.NEVER)
    public Page expirePage(UUID after) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<UUID> candidates;
        try { candidates = transactions.scan(after); }
        catch (RuntimeException failure) {
            return new Page(after, 0, 0, 0, 0, 0, 0, 0, 1, false);
        }
        int scanned = 0, expired = 0, resolved = 0, admission = 0, purge = 0, inconsistent = 0, failed = 0;
        UUID next = after;
        for (UUID candidate : candidates) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) { break; }
            try {
                switch (transactions.expire(candidate)) {
                    case EXPIRED -> expired++;
                    case ALREADY_RESOLVED -> resolved++;
                    case ADMISSION_WON -> admission++;
                    case DEFERRED_TO_PURGE -> purge++;
                    case INCONSISTENT -> inconsistent++;
                }
            } catch (RuntimeException failure) {
                // No raw exception text, storage identities or payloads in reporting.
                failed++;
            }
            scanned++;
            next = candidate;
        }
        return new Page(next, scanned, expired, resolved, admission, purge, inconsistent, failed,
                0, candidates.isEmpty());
    }
}
