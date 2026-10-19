package com.vaultdrive.file;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Explicit internal invocation only; no scheduler, endpoint, storage adapter or job integration. */
@Service
@EnableConfigurationProperties(UploadFinalizationRecoveryProperties.class)
public class UploadFinalizationRecovery {
    private final UploadFinalizationTransactions transactions;

    public UploadFinalizationRecovery(UploadFinalizationTransactions transactions) { this.transactions = transactions; }

    public record Page(UUID nextCursor, int scanned, int recovered, int alreadyReady, int blocked,
                       int noReceipt, int failed, int scanFailed, boolean exhausted) { }

    @Transactional(propagation = Propagation.NEVER)
    public Page recoverPage(UUID after) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<UUID> candidates;
        try { candidates = transactions.scan(after); }
        catch (RuntimeException failure) { return new Page(after, 0, 0, 0, 0, 0, 0, 1, false); }
        int scanned = 0, recovered = 0, ready = 0, blocked = 0, absent = 0, failed = 0;
        UUID next = after;
        for (UUID candidate : candidates) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) { break; }
            try {
                switch (transactions.recover(candidate)) {
                    case RECOVERED -> recovered++;
                    case ALREADY_READY -> ready++;
                    case BLOCKED -> blocked++;
                    case NO_RECEIPT -> absent++;
                }
            } catch (RuntimeException failure) { failed++; }
            scanned++;
            next = candidate;
        }
        return new Page(next, scanned, recovered, ready, blocked, absent, failed, 0, candidates.isEmpty());
    }
}
