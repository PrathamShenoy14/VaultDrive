package com.vaultdrive.file;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** PostgreSQL only. Inventory classifications never authorize a mutation. */
@Service
public class StaleUploadExpiryTransactions {
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final StaleUploadExpiryProperties properties;

    public StaleUploadExpiryTransactions(EntityManager em, HierarchyCoordinator hierarchy,
                                         StaleUploadExpiryProperties properties) {
        this.em = em;
        this.hierarchy = hierarchy;
        this.properties = properties;
    }

    public enum Outcome { EXPIRED, ALREADY_RESOLVED, ADMISSION_WON, DEFERRED_TO_PURGE, INCONSISTENT }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 10)
    public List<UUID> scan(UUID after) {
        em.createNativeQuery("SET TRANSACTION READ ONLY").executeUpdate();
        bounds();
        // Same immutable UUID keyset/progress contract as B1; FAILED is outside B2's mutation scope.
        var query = em.createNativeQuery("""
                SELECT id FROM files WHERE status = 'UPLOADING'
                  AND created_at <= statement_timestamp() - :age * interval '1 millisecond'
                """ + (after == null ? "" : " AND id > :after") + " ORDER BY id LIMIT :limit")
                .setParameter("age", properties.minimumAge().toMillis()).setParameter("limit", properties.pageSize());
        if (after != null) { query.setParameter("after", after); }
        @SuppressWarnings("unchecked")
        List<UUID> result = query.getResultList();
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Outcome expire(UUID id) {
        bounds();
        List<?> owners = em.createNativeQuery("SELECT owner_id FROM files WHERE id = :id")
                .setParameter("id", id).getResultList(); // Immutable owner identity only, no eligibility read.
        if (owners.isEmpty()) { return Outcome.INCONSISTENT; }
        UUID owner = (UUID) owners.getFirst();
        // Existing order: shared owner hierarchy -> fresh scalar file FOR UPDATE -> allocation FOR UPDATE.
        hierarchy.acquireShared(owner);
        List<?> rows = em.createNativeQuery("SELECT owner_id, version FROM files WHERE id = :id FOR UPDATE")
                .setParameter("id", id).getResultList();
        if (rows.isEmpty() || !owner.equals(((Object[]) rows.getFirst())[0])) { return Outcome.INCONSISTENT; }
        long version = ((Number) ((Object[]) rows.getFirst())[1]).longValue();
        em.createNativeQuery("SELECT file_id FROM file_storage_objects WHERE file_id = :id FOR UPDATE")
                .setParameter("id", id).getResultList();
        String eligibility = (String) em.createNativeQuery("""
                WITH RECURSIVE f AS (SELECT * FROM files WHERE id = :id AND owner_id = :owner),
                ancestry AS (
                    SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
                           ARRAY[p.id] AS path, false AS cycle, 1 AS depth
                    FROM folders p JOIN f ON p.id = f.folder_id AND p.owner_id = f.owner_id
                    UNION ALL
                    SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
                           a.path || p.id, p.id = ANY(a.path), a.depth + 1
                    FROM ancestry a JOIN folders p ON p.id = a.parent_folder_id
                    JOIN f ON p.owner_id = f.owner_id
                    WHERE NOT a.cycle AND a.depth < 128
                )
                SELECT CASE
                    WHEN f.status IN ('READY', 'FAILED') THEN 'ALREADY_RESOLVED'
                    WHEN f.id IS NULL OR f.status <> 'UPLOADING' OR f.version <> :version
                        OR NOT EXISTS (SELECT 1 FROM users WHERE id = f.owner_id)
                        OR a.file_id IS NULL OR a.upload_protocol <> 'SERVER_PUT'
                        OR a.owner_id <> f.owner_id OR a.storage_key <> f.storage_key
                        OR num_nonnulls(a.storage_namespace, a.endpoint, a.region, a.bucket) <> 4
                        OR a.reservation_expired_at IS NOT NULL
                        OR (f.folder_id IS NOT NULL AND NOT EXISTS (
                            SELECT 1 FROM ancestry WHERE parent_folder_id IS NULL))
                        OR EXISTS (SELECT 1 FROM ancestry WHERE cycle) THEN 'INCONSISTENT'
                    WHEN f.purge_requested_at IS NOT NULL OR EXISTS (
                        SELECT 1 FROM ancestry WHERE purge_requested_at IS NOT NULL) THEN 'DEFERRED_TO_PURGE'
                    WHEN f.deleted_at IS NOT NULL OR EXISTS (
                        SELECT 1 FROM ancestry WHERE deleted_at IS NOT NULL) THEN 'INCONSISTENT'
                    WHEN f.created_at > statement_timestamp() - :age * interval '1 millisecond'
                        THEN 'ALREADY_RESOLVED'
                    WHEN a.write_started THEN 'ADMISSION_WON'
                    ELSE 'ELIGIBLE'
                END FROM (SELECT 1) seed LEFT JOIN f ON true
                LEFT JOIN file_storage_objects a ON a.file_id = f.id
                """).setParameter("id", id).setParameter("owner", owner).setParameter("version", version)
                .setParameter("age", properties.minimumAge().toMillis()).getSingleResult();
        if (!"ELIGIBLE".equals(eligibility)) { return Outcome.valueOf(eligibility); }

        // Full retained binding remains locked and immutable; never infer it from today's client settings.
        requireOne(em.createNativeQuery("""
                UPDATE file_storage_objects SET write_precluded_at = COALESCE(write_precluded_at, clock_timestamp())
                WHERE file_id = :id AND owner_id = :owner AND NOT write_started
                    AND storage_key = (SELECT storage_key FROM files WHERE id = :id)
                """).setParameter("id", id).setParameter("owner", owner).executeUpdate());
        requireOne(em.createNativeQuery("""
                UPDATE files SET status = 'FAILED', version = version + 1, updated_at = clock_timestamp()
                WHERE id = :id AND owner_id = :owner AND status = 'UPLOADING' AND version = :version
                    AND deleted_at IS NULL AND purge_requested_at IS NULL
                    AND created_at <= statement_timestamp() - :age * interval '1 millisecond'
                """).setParameter("id", id).setParameter("owner", owner).setParameter("version", version)
                .setParameter("age", properties.minimumAge().toMillis()).executeUpdate());
        requireOne(em.createNativeQuery("""
                UPDATE file_storage_objects SET reservation_expired_at = clock_timestamp(), expired_file_version = :version
                WHERE file_id = :id AND owner_id = :owner AND NOT write_started
                    AND write_precluded_at IS NOT NULL AND reservation_expired_at IS NULL
                """).setParameter("id", id).setParameter("owner", owner).setParameter("version", version + 1)
                .executeUpdate());
        return Outcome.EXPIRED;
    }

    private static void requireOne(int changed) {
        if (changed != 1) { throw new IllegalStateException("Reservation expiry state changed"); }
    }

    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
    }
}
