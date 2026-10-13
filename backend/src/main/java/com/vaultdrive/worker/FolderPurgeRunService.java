package com.vaultdrive.worker;

import com.vaultdrive.outbox.OutboxEvent;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FolderPurgeRunService {
    private final EntityManager entityManager;

    public FolderPurgeRunService(EntityManager entityManager) { this.entityManager = entityManager; }

    /** Bounds the existing whole-subtree API check; never accept partial intent on timeout. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void boundTransaction() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
        entityManager.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void create(OutboxEvent event) {
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO folder_purge_runs(event_id, owner_id, root_folder_id, requested_at)
                SELECT e.id, f.owner_id, f.id, e.created_at
                FROM outbox_events e JOIN folders f ON f.id = e.aggregate_id
                WHERE e.id = :event AND e.event_type = 'FOLDER_PURGE_REQUESTED'
                    AND e.aggregate_type = 'FOLDER' AND e.payload_version = 1
                    AND e.payload->>'ownerId' = CAST(f.owner_id AS text)
                    AND e.payload->>'folderId' = CAST(f.id AS text)
                    AND f.deleted_at IS NOT NULL AND f.purge_requested_at = e.created_at
                """).setParameter("event", event.getId()).executeUpdate();
        if (inserted != 1) { throw new IllegalStateException("Folder purge intent does not match event"); }
    }
}
