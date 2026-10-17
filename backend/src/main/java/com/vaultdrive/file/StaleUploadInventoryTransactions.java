package com.vaultdrive.file;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Scalar MVCC observations only: no hierarchy/row locks and no storage dependency. */
@Service
public class StaleUploadInventoryTransactions {
    private final EntityManager em;
    private final StaleUploadInventoryProperties properties;

    public StaleUploadInventoryTransactions(EntityManager em, StaleUploadInventoryProperties properties) {
        this.em = em;
        this.properties = properties;
    }

    public enum Classification {
        NEVER_ADMITTED, ADMITTED_UNCERTAIN, DEFERRED_TO_PURGE, INCONSISTENT, ALREADY_RESOLVED
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 10)
    public List<UUID> scan(UUID after) {
        bounds();
        var query = em.createNativeQuery("""
                SELECT id FROM files
                WHERE status IN ('UPLOADING', 'FAILED')
                  AND created_at <= statement_timestamp() - :age * interval '1 millisecond'
                """ + (after == null ? "" : " AND id > :after") + " ORDER BY id LIMIT :limit")
                .setParameter("age", properties.minimumAge().toMillis())
                .setParameter("limit", properties.pageSize());
        if (after != null) { query.setParameter("after", after); }
        @SuppressWarnings("unchecked")
        List<UUID> result = query.getResultList();
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 10)
    public Classification inspect(UUID id) {
        bounds();
        // One statement snapshot binds metadata, allocation and ancestry together. A bounded
        // owner-scoped walk detects missing edges/cycles/depth overflow without trusting cache.
        String result = (String) em.createNativeQuery("""
                WITH RECURSIVE f AS (SELECT * FROM files WHERE id = :id),
                ancestry AS (
                    SELECT p.id, p.parent_folder_id, p.purge_requested_at,
                           ARRAY[p.id] AS path, false AS cycle, 1 AS depth
                    FROM folders p JOIN f ON p.id = f.folder_id AND p.owner_id = f.owner_id
                    UNION ALL
                    SELECT p.id, p.parent_folder_id, p.purge_requested_at,
                           a.path || p.id, p.id = ANY(a.path), a.depth + 1
                    FROM ancestry a JOIN folders p ON p.id = a.parent_folder_id
                    JOIN f ON p.owner_id = f.owner_id
                    WHERE NOT a.cycle AND a.depth < 128
                )
                SELECT CASE
                    WHEN f.status = 'READY' THEN 'ALREADY_RESOLVED'
                    WHEN f.id IS NULL OR f.status NOT IN ('UPLOADING', 'FAILED')
                        OR a.file_id IS NULL OR a.owner_id <> f.owner_id
                        OR a.storage_key <> f.storage_key
                        OR num_nonnulls(a.storage_namespace, a.endpoint, a.region, a.bucket) <> 4
                        OR (f.deleted_at IS NOT NULL AND f.purge_requested_at IS NULL)
                        OR (f.folder_id IS NOT NULL AND NOT EXISTS (
                            SELECT 1 FROM ancestry WHERE parent_folder_id IS NULL))
                        OR EXISTS (SELECT 1 FROM ancestry WHERE cycle)
                        THEN 'INCONSISTENT'
                    WHEN f.purge_requested_at IS NOT NULL OR EXISTS (
                        SELECT 1 FROM ancestry WHERE purge_requested_at IS NOT NULL)
                        THEN 'DEFERRED_TO_PURGE'
                    WHEN NOT a.write_started THEN 'NEVER_ADMITTED'
                    ELSE 'ADMITTED_UNCERTAIN'
                END
                FROM (SELECT 1) seed LEFT JOIN f ON true
                LEFT JOIN file_storage_objects a ON a.file_id = f.id
                """).setParameter("id", id).getSingleResult();
        return Classification.valueOf(result);
    }

    private void bounds() {
        // Enforce PostgreSQL read-only even if a future caller changes ORM flush behavior.
        em.createNativeQuery("SET TRANSACTION READ ONLY").executeUpdate();
        em.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
    }
}
