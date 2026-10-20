package com.vaultdrive.deletion;

import com.vaultdrive.sharing.ResourceAccessRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/** Read-only projection of retained intent and receipts, never a worker/control API. */
@Service
public class DeletionOperationService {
    private final EntityManager em;

    public DeletionOperationService(EntityManager em) { this.em = em; }

    public enum State { ACCEPTED, PROCESSING, BLOCKED, FAILED, LOGICALLY_COMPLETED }
    public record Status(UUID operationId, String resourceType, UUID resourceId, Instant requestedAt,
                         State state, String reasonCode, Instant logicallyCompletedAt) { }

    @Transactional(readOnly = true, timeout = 5)
    public Status get(UUID actor, UUID operation) {
        em.createNativeQuery("SELECT set_config('statement_timeout', '3000', true)").getSingleResult();
        // Indexed event identity; no subtree traversal, storage/broker I/O or resource-existence inference.
        var rows = em.createNativeQuery("""
                SELECT e.aggregate_type, e.aggregate_id, e.created_at, e.delivery_status, j.status,
                    CASE WHEN e.aggregate_type = 'FILE' THEN w.metadata_finalized_at ELSE r.finalized_at END,
                    EXISTS (SELECT 1 FROM notification_quarantine q WHERE q.event_id = e.id),
                    EXISTS (SELECT 1 FROM folder_purge_file_progress p
                            WHERE p.event_id = e.id AND p.blocked_reason IS NOT NULL AND p.completed_at IS NULL),
                    e.aggregate_type = 'FILE' AND t.event_id IS NULL
                FROM outbox_events e
                LEFT JOIN background_jobs j ON j.id = e.id
                LEFT JOIN file_purge_targets t ON t.event_id = e.id AND t.owner_id = :actor
                LEFT JOIN file_purge_work w ON w.file_id = t.file_id AND w.owner_id = t.owner_id
                    AND w.storage_key = t.storage_key AND w.purge_requested_at = t.requested_at
                    AND w.file_version = t.file_version AND w.storage_namespace = t.storage_namespace
                    AND w.endpoint = t.endpoint AND w.region = t.region AND w.bucket = t.bucket
                LEFT JOIN folder_purge_runs r ON r.event_id = e.id AND r.owner_id = :actor
                    AND r.root_folder_id = e.aggregate_id AND r.requested_at = e.created_at
                WHERE e.id = :id AND e.payload ->> 'ownerId' = :owner
                  AND ((e.event_type = 'FILE_PURGE_REQUESTED' AND e.aggregate_type = 'FILE')
                    OR (e.event_type = 'FOLDER_PURGE_REQUESTED' AND e.aggregate_type = 'FOLDER'))
                """).setParameter("id", operation).setParameter("actor", actor)
                .setParameter("owner", actor.toString()).getResultList();
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Deletion operation not found");
        Object[] r = (Object[]) rows.getFirst();
        Instant completed = ResourceAccessRepository.instant(r[5]);
        State state;
        String reason = null;
        if (completed != null) state = State.LOGICALLY_COMPLETED;
        else if ("FAILED".equals(r[4])) { state = State.FAILED; reason = "EXECUTION_FAILED"; }
        else if (r[4] == null && "FAILED".equals(r[3])) { state = State.FAILED; reason = "DELIVERY_FAILED"; }
        else if (r[4] == null && Boolean.TRUE.equals(r[6])) { state = State.BLOCKED; reason = "DELIVERY_QUARANTINED"; }
        else if (Boolean.TRUE.equals(r[8])) { state = State.BLOCKED; reason = "TARGET_UNAVAILABLE"; }
        else if (Boolean.TRUE.equals(r[7])) { state = State.BLOCKED; reason = "MEMBER_BLOCKED"; }
        else if ("SUCCEEDED".equals(r[4])) { state = State.BLOCKED; reason = "METADATA_PENDING"; }
        else if (r[4] != null || "PUBLISHED".equals(r[3])) state = State.PROCESSING;
        else state = State.ACCEPTED;
        return new Status(operation, (String) r[0], (UUID) r[1], ResourceAccessRepository.instant(r[2]),
                state, reason, completed);
    }
}
