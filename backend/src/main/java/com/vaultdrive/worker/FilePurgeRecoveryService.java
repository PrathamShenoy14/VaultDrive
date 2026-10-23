package com.vaultdrive.worker;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.UUID;

/** Explicit bounded recovery, no automatic startup or terminal-job reset. */
@Service
public class FilePurgeRecoveryService {
    private final EntityManager entityManager;
    private final FilePurgeProgressService progress;

    public FilePurgeRecoveryService(EntityManager entityManager, FilePurgeProgressService progress) {
        this.entityManager = entityManager;
        this.progress = progress;
    }

    public record Page(UUID nextCursor, int examined, int finalized, int blocked) { }

    @Transactional(propagation = Propagation.NEVER)
    public Page recoverPage(UUID afterJobId, int limit) {
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("Recovery page must be 1..100"); }
        String cursor = afterJobId == null ? "" : " AND j.id > :after";
        var query = entityManager.createNativeQuery("""
                SELECT j.id FROM background_jobs j JOIN file_purge_targets t
                    ON t.event_id = j.id AND t.file_id = j.aggregate_id
                LEFT JOIN file_purge_work w ON w.file_id = t.file_id
                WHERE j.job_type = 'FILE_PURGE' AND j.aggregate_type = 'FILE' AND j.status = 'SUCCEEDED'
                    AND w.metadata_finalized_at IS NULL
                """ + cursor + " ORDER BY j.id LIMIT :limit").setParameter("limit", limit)
                .setHint("jakarta.persistence.query.timeout", 5000);
        if (afterJobId != null) { query.setParameter("after", afterJobId); }
        @SuppressWarnings("unchecked")
        List<UUID> ids = query.getResultList();
        int finalized = 0;
        for (UUID id : ids) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                if (progress.recoverSucceeded(id)) { finalized++; }
            } catch (IllegalStateException blocked) {
                // Identity mismatch is retained for repair; no raw durable data/exception logging.
            }
        }
        return new Page(ids.isEmpty() ? null : ids.getLast(), ids.size(), finalized, ids.size() - finalized);
    }
}
