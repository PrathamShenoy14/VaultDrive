package com.vaultdrive.file;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Receipt persistence and metadata recovery are separate, PostgreSQL-only commits. */
@Service
public class UploadFinalizationTransactions {
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final StoredFileRepository files;
    private final UploadFinalizationRecoveryProperties properties;

    public UploadFinalizationTransactions(EntityManager em, HierarchyCoordinator hierarchy,
                                         StoredFileRepository files, UploadFinalizationRecoveryProperties properties) {
        this.em = em;
        this.hierarchy = hierarchy;
        this.files = files;
        this.properties = properties;
    }

    public enum Outcome { RECOVERED, ALREADY_READY, BLOCKED, NO_RECEIPT }

    /** Caller may invoke only after its one admitted PUT and input close both returned successfully. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void recordSuccess(StoredFile original) {
        bounds();
        lock(original.getId(), original.getOwnerId());
        if (!matchesReceiptIdentity(original, false)) {
            throw new IllegalStateException("Upload receipt identity is not eligible");
        }
        // A duplicate is a read-only replay, preserving the first database-generated timestamp.
        if (matchesReceiptIdentity(original, true)) { return; }
        int changed = em.createNativeQuery("""
                UPDATE file_storage_objects a SET put_succeeded_at = clock_timestamp()
                FROM files f WHERE a.file_id = :id AND f.id = a.file_id
                    AND f.owner_id = a.owner_id AND f.storage_key = a.storage_key
                    AND f.status = 'UPLOADING' AND f.version = a.admitted_file_version
                    AND f.folder_id IS NOT DISTINCT FROM a.admitted_folder_id
                    AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL
                    AND a.put_succeeded_at IS NULL AND a.upload_protocol = 'SERVER_PUT'
                """).setParameter("id", original.getId()).executeUpdate();
        if (changed != 1) { throw new IllegalStateException("Upload receipt metadata changed"); }
    }

    /** Independent authoritative reload after a failed/uncertain receipt acknowledgement. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 10)
    public boolean hasSuccess(StoredFile original) {
        em.createNativeQuery("SET TRANSACTION READ ONLY").executeUpdate();
        bounds();
        return matchesReceiptIdentity(original, true);
    }

    private boolean matchesReceiptIdentity(StoredFile original, boolean requireReceipt) {
        return (Boolean) em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM file_storage_objects a WHERE a.file_id = :id
                    AND a.owner_id = :owner AND a.storage_key = :key AND a.write_started
                    AND a.upload_protocol = 'SERVER_PUT'
                    AND a.admitted_file_version = :version
                    AND a.admitted_folder_id IS NOT DISTINCT FROM cast(:folder AS uuid)
                    AND num_nonnulls(a.storage_namespace, a.endpoint, a.region, a.bucket) = 4
                    AND a.write_precluded_at IS NULL AND a.reservation_expired_at IS NULL
                """ + (requireReceipt ? " AND a.put_succeeded_at IS NOT NULL" : "") + ")")
                .setParameter("id", original.getId()).setParameter("owner", original.getOwnerId())
                .setParameter("key", original.getStorageKey()).setParameter("version", original.getVersion())
                .setParameter("folder", original.getFolderId()).getSingleResult();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 10)
    public List<UUID> scan(UUID after) {
        em.createNativeQuery("SET TRANSACTION READ ONLY").executeUpdate();
        bounds();
        // Include retained receipts even without metadata: report blockers, never invent resolution.
        var query = em.createNativeQuery("SELECT file_id FROM file_storage_objects WHERE put_succeeded_at IS NOT NULL"
                + (after == null ? "" : " AND file_id > :after") + " ORDER BY file_id LIMIT :limit")
                .setParameter("limit", properties.pageSize());
        if (after != null) { query.setParameter("after", after); }
        @SuppressWarnings("unchecked") List<UUID> result = query.getResultList();
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Outcome recover(UUID id) {
        bounds();
        // Only retained immutable owner identity is read before hierarchy coordination.
        List<?> owners = em.createNativeQuery("SELECT owner_id FROM file_storage_objects WHERE file_id = :id")
                .setParameter("id", id).getResultList();
        if (owners.isEmpty()) { return Outcome.NO_RECEIPT; }
        UUID owner = (UUID) owners.getFirst();
        lock(id, owner);
        String eligibility = (String) em.createNativeQuery("""
                WITH RECURSIVE f AS (SELECT * FROM files WHERE id = :id),
                ancestry AS (
                    SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
                        ARRAY[p.id] AS path, false AS cycle, 1 AS depth
                    FROM folders p JOIN f ON p.id = f.folder_id AND p.owner_id = f.owner_id
                    UNION ALL
                    SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
                        a.path || p.id, p.id = ANY(a.path), a.depth + 1
                    FROM ancestry a JOIN folders p ON p.id = a.parent_folder_id JOIN f ON p.owner_id = f.owner_id
                    WHERE NOT a.cycle AND a.depth < 128
                )
                SELECT CASE
                    WHEN a.upload_protocol <> 'SERVER_PUT' OR a.put_succeeded_at IS NULL THEN 'NO_RECEIPT'
                    WHEN f.id IS NULL OR f.owner_id <> a.owner_id OR f.storage_key <> a.storage_key
                        OR NOT EXISTS (SELECT 1 FROM users WHERE id = a.owner_id)
                        OR NOT a.write_started OR a.admitted_file_version IS NULL
                        OR num_nonnulls(a.storage_namespace, a.endpoint, a.region, a.bucket) <> 4
                        OR a.write_precluded_at IS NOT NULL OR a.reservation_expired_at IS NOT NULL
                        OR a.expired_file_version IS NOT NULL THEN 'BLOCKED'
                    WHEN f.status = 'READY' AND f.version > a.admitted_file_version THEN 'ALREADY_READY'
                    WHEN f.status = 'READY' THEN 'BLOCKED'
                    WHEN f.status <> 'UPLOADING' OR f.version <> a.admitted_file_version
                        OR f.folder_id IS DISTINCT FROM a.admitted_folder_id
                        OR f.deleted_at IS NOT NULL OR f.purge_requested_at IS NOT NULL
                        OR (f.folder_id IS NOT NULL AND NOT EXISTS (
                            SELECT 1 FROM ancestry WHERE parent_folder_id IS NULL))
                        OR EXISTS (SELECT 1 FROM ancestry WHERE cycle OR deleted_at IS NOT NULL
                            OR purge_requested_at IS NOT NULL)
                        OR EXISTS (SELECT 1 FROM file_purge_targets WHERE file_id = f.id)
                        OR EXISTS (SELECT 1 FROM file_purge_work WHERE file_id = f.id)
                        OR EXISTS (SELECT 1 FROM folder_purge_files WHERE file_id = f.id)
                        OR EXISTS (SELECT 1 FROM upload_cleanup_intents WHERE file_id = f.id)
                        THEN 'BLOCKED'
                    ELSE 'ELIGIBLE' END
                FROM file_storage_objects a LEFT JOIN f ON true WHERE a.file_id = :id
                """).setParameter("id", id).getSingleResult();
        if (!"ELIGIBLE".equals(eligibility)) { return Outcome.valueOf(eligibility); }
        long version = ((Number) em.createNativeQuery(
                "SELECT admitted_file_version FROM file_storage_objects WHERE file_id = :id")
                .setParameter("id", id).getSingleResult()).longValue();
        // Reuse the foreground status/version CAS, under fresh hierarchy/file/allocation coordination.
        if (files.transitionUploadStatus(id, owner, FileStatus.UPLOADING, version, FileStatus.READY, Instant.now()) != 1) {
            throw new IllegalStateException("Upload finalization state changed");
        }
        return Outcome.RECOVERED;
    }

    private void lock(UUID id, UUID owner) {
        hierarchy.acquireShared(owner);
        em.createNativeQuery("SELECT id FROM files WHERE id = :id FOR UPDATE").setParameter("id", id).getResultList();
        em.createNativeQuery("SELECT file_id FROM file_storage_objects WHERE file_id = :id FOR UPDATE")
                .setParameter("id", id).getResultList();
    }

    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
    }
}
