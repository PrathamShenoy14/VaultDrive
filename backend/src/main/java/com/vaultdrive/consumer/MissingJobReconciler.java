package com.vaultdrive.consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.vaultdrive.consumer.MissingJobReconciliationTransactions.Cursor;

@Service
public class MissingJobReconciler {
    private final MissingJobReconciliationTransactions transactions;
    private final MissingJobReconciliationProperties properties;

    public MissingJobReconciler(MissingJobReconciliationTransactions transactions,
                                 MissingJobReconciliationProperties properties) {
        this.transactions = transactions;
        this.properties = properties;
    }

    /** Counts are per page (at most 100), with no event/owner/key metric labels. */
    public record Page(Cursor nextCursor, int scanned, int created, int alreadyPresent,
                       int skipped, int failed, boolean scanFailed) { }

    @Transactional(propagation = Propagation.NEVER)
    public Page reconcilePage(Cursor after) {
        if (!properties.enabled()) { return new Page(after, 0, 0, 0, 0, 0, false); }
        java.util.List<Cursor> candidates;
        try {
            candidates = transactions.scan(after, properties.pageSize());
        } catch (RuntimeException failure) {
            // No cursor advancement or unbounded immediate retry after a scan timeout.
            return new Page(after, 0, 0, 0, 0, 0, true);
        }
        int created = 0, present = 0, skipped = 0, failed = 0;
        for (Cursor candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) { break; }
            try {
                switch (transactions.ingest(candidate.eventId())) {
                    case CREATED -> created++;
                    case ALREADY_PRESENT -> present++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException failure) {
                // A separate failed transaction leaves intent/proof/job state intact.
                // Unknown commit outcome is retried by a later sweep using the same ID.
                failed++;
            }
        }
        int scanned = created + present + skipped + failed;
        // Empty pages wrap. Interrupted pages resume after the last attempted event.
        Cursor next = scanned == 0 ? (candidates.isEmpty() ? null : after) : candidates.get(scanned - 1);
        return new Page(next, scanned, created, present, skipped, failed, false);
    }
}
