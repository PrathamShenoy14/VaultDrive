package com.vaultdrive.file;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.storage.StorageBinding;
import com.vaultdrive.storage.StorageTarget;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Durable abandonment authority. Never opens a storage connection. */
@Service
public class KnownSuccessCleanupTransactions {
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final StorageBinding binding;
    private final KnownSuccessCleanupProperties properties;

    public KnownSuccessCleanupTransactions(EntityManager em, HierarchyCoordinator hierarchy,
                                           StorageBinding binding, KnownSuccessCleanupProperties properties) {
        this.em = em;
        this.hierarchy = hierarchy;
        this.binding = binding;
        this.properties = properties;
    }

    public enum Outcome { CLAIMED, COMPLETE, BLOCKED, BUSY }
    /** Internal capability: never expose storage identity in page reporting or logs. */
    public record Claim(UUID fileId, UUID token, long revision, StorageTarget target) { }
    public record Prepared(Outcome outcome, Claim claim) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 10)
    public List<UUID> scan(UUID after) {
        em.createNativeQuery("SET TRANSACTION READ ONLY").executeUpdate();
        bounds();
        var query = em.createNativeQuery("""
                SELECT file_id FROM (
                    SELECT a.file_id FROM file_storage_objects a JOIN files f ON f.id = a.file_id
                    WHERE a.put_succeeded_at IS NOT NULL AND f.status = 'FAILED'
                    UNION SELECT file_id FROM upload_cleanup_intents
                ) candidates
                """ + (after == null ? "" : " WHERE file_id > :after") + " ORDER BY file_id LIMIT :limit")
                .setParameter("limit", properties.pageSize());
        if (after != null) { query.setParameter("after", after); }
        @SuppressWarnings("unchecked") List<UUID> result = query.getResultList();
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Prepared prepare(UUID id) {
        bounds();
        StorageTarget target = lock(id);
        if (target == null || !binding.matches(target)) { return new Prepared(Outcome.BLOCKED, null); }
        boolean retained = lockIntent(id);
        if (!retained) {
            if (!(Boolean) em.createNativeQuery("SELECT upload_cleanup_eligible(:id)")
                    .setParameter("id", id).getSingleResult()) { return new Prepared(Outcome.BLOCKED, null); }
            // FAILED and its accepted history stay unchanged. This insert is the atomic business decision.
            em.createNativeQuery("""
                    INSERT INTO upload_cleanup_intents(file_id, file_snapshot, put_succeeded_at, reason)
                    SELECT f.id, upload_cleanup_file_snapshot(f), a.put_succeeded_at, 'RECEIPT_BACKED_FAILED'
                    FROM files f JOIN file_storage_objects a ON a.file_id = f.id WHERE f.id = :id
                    """).setParameter("id", id).executeUpdate();
            em.createNativeQuery("INSERT INTO upload_cleanup_progress(file_id) VALUES (:id)")
                    .setParameter("id", id).executeUpdate();
        }
        // Progress/claim lock LAST, after hierarchy, fresh file, allocation and immutable intent.
        em.createNativeQuery("SELECT file_id FROM upload_cleanup_progress WHERE file_id = :id FOR UPDATE")
                .setParameter("id", id).getResultList();
        if (exists("upload_cleanup_receipts", id)) { return new Prepared(Outcome.COMPLETE, null); }
        UUID token = UUID.randomUUID();
        List<?> claimed = em.createNativeQuery("""
                UPDATE upload_cleanup_progress SET claim_token = :token, revision = revision + 1,
                    lease_until = clock_timestamp() + interval '30 seconds',
                    retry_after = clock_timestamp() + interval '31 seconds', last_failure = NULL
                WHERE file_id = :id AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    AND retry_after <= clock_timestamp()
                RETURNING revision
                """).setParameter("id", id).setParameter("token", token).getResultList();
        if (claimed.isEmpty()) { return new Prepared(Outcome.BUSY, null); }
        return new Prepared(Outcome.CLAIMED, new Claim(id, token, ((Number) claimed.getFirst()).longValue(), target));
    }

    /** Only call after ObjectStorageService.delete returned successfully for this claim's exact target. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean complete(Claim claim) {
        bounds();
        requireTarget(claim);
        fenceLock(claim);
        if (exists("upload_cleanup_receipts", claim.fileId())) { return true; }
        // The trigger rechecks the token/revision/lease after the final lock and assigns database time.
        return em.createNativeQuery("""
                INSERT INTO upload_cleanup_receipts(file_id, claim_token, claim_revision, outcome)
                SELECT file_id, claim_token, revision, 'DELETED_OR_ABSENT' FROM upload_cleanup_progress
                WHERE file_id = :id AND claim_token = :token AND revision = :revision
                    AND lease_until > clock_timestamp()
                """).setParameter("id", claim.fileId()).setParameter("token", claim.token())
                .setParameter("revision", claim.revision()).executeUpdate() == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean failed(Claim claim) {
        bounds();
        requireTarget(claim);
        fenceLock(claim);
        if (exists("upload_cleanup_receipts", claim.fileId())) { return false; }
        return em.createNativeQuery("""
                UPDATE upload_cleanup_progress SET claim_token = NULL, lease_until = NULL,
                    retry_after = clock_timestamp() + interval '1 second', last_failure = 'STORAGE_FAILURE'
                WHERE file_id = :id AND claim_token = :token AND revision = :revision
                    AND lease_until > clock_timestamp()
                """).setParameter("id", claim.fileId()).setParameter("token", claim.token())
                .setParameter("revision", claim.revision()).executeUpdate() == 1;
    }

    private void requireTarget(Claim claim) {
        StorageTarget original = lock(claim.fileId());
        if (!claim.target().equals(original) || !lockIntent(claim.fileId())) {
            throw new IllegalStateException("Cleanup identity changed");
        }
    }

    private void fenceLock(Claim claim) {
        em.createNativeQuery("SELECT file_id FROM upload_cleanup_progress WHERE file_id = :id FOR UPDATE")
                .setParameter("id", claim.fileId()).getResultList();
    }

    private boolean lockIntent(UUID id) {
        // Also acquire the receipt FK's retained-parent lock before the final claim fence.
        return !em.createNativeQuery("SELECT file_id FROM upload_cleanup_intents WHERE file_id = :id FOR KEY SHARE")
                .setParameter("id", id).getResultList().isEmpty();
    }

    private StorageTarget lock(UUID id) {
        // Pre-lock read is immutable owner identity only.
        List<?> owners = em.createNativeQuery("SELECT owner_id FROM file_storage_objects WHERE file_id = :id")
                .setParameter("id", id).getResultList();
        if (owners.isEmpty()) { return null; }
        hierarchy.acquireShared((UUID) owners.getFirst());
        em.createNativeQuery("SELECT id FROM files WHERE id = :id FOR UPDATE").setParameter("id", id).getResultList();
        List<?> rows = em.createNativeQuery("""
                SELECT storage_namespace, endpoint, region, bucket, storage_key
                FROM file_storage_objects WHERE file_id = :id FOR UPDATE
                """).setParameter("id", id).getResultList();
        if (rows.isEmpty()) { return null; }
        Object[] row = (Object[]) rows.getFirst();
        for (Object value : row) { if (value == null || value.toString().isBlank()) { return null; } }
        return new StorageTarget((String) row[0], (String) row[1], (String) row[2], (String) row[3], (String) row[4]);
    }

    private boolean exists(String table, UUID id) {
        return (Boolean) em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE file_id = :id)")
                .setParameter("id", id).getSingleResult();
    }

    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
    }
}
