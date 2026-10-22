package com.vaultdrive.worker;

import com.vaultdrive.activity.ResourceActivityService;
import com.vaultdrive.activity.ResourceActivityService.Snapshot;
import static com.vaultdrive.activity.ResourceActivityService.EventType.*;

import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobType;
import com.vaultdrive.storage.StorageTarget;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.UUID;

/** Short resource transactions only; callers perform Garage I/O between these boundaries. */
@Service
public class FilePurgeProgressService {
    private final EntityManager entityManager;
    private final HierarchyCoordinator hierarchy;
    private final FolderRepository folders;
    private final ResourceActivityService activity;

    public FilePurgeProgressService(EntityManager entityManager, HierarchyCoordinator hierarchy,
                                    FolderRepository folders,
            ResourceActivityService activity) {
        this.entityManager = entityManager;
        this.hierarchy = hierarchy;
        this.folders = folders;
        this.activity = activity;
    }

    public record Prepared(StorageTarget target, boolean bytesDeleted) { }
    private record Target(UUID event, UUID file, UUID owner) { }
    private record Work(StorageTarget target, boolean bytesDeleted, boolean finalized) { }

    @Transactional
    public Prepared prepare(ClaimedJob claim) {
        Target target = target(claim);
        lockResource(target);
        verifyAllocation(target);
        createWork(target);
        Work work = lockWork(target);
        if (!work.bytesDeleted() && !matchesFile(target)) {
            throw new IllegalStateException("File purge state does not match target");
        }
        fence(target, claim);
        return new Prepared(work.target(), work.bytesDeleted());
    }

    /** Called only after the storage adapter confirms DELETE or NoSuchKey. */
    @Transactional
    public void recordBytesDeleted(ClaimedJob claim) {
        Target target = target(claim);
        lockResource(target);
        verifyAllocation(target);
        lockWork(target);
        fence(target, claim);
        recordReceipt(target);
        fence(target, claim); // Failure rolls back every progress write, including after a lock wait.
    }

    @Transactional
    public boolean finalizeMetadata(ClaimedJob claim) {
        Target target = target(claim);
        lockResource(target);
        verifyAllocation(target);
        Work work = lockWork(target);
        fence(target, claim); // Job is last in the lock order.
        boolean finalized = finalizeResource(target, work);
        fence(target, claim); // PostgreSQL-clock check at the final write, too.
        return finalized;
    }

    /** Historical SUCCEEDED is authoritative byte success, never rearmed or reinterpreted. */
    @Transactional
    public boolean recoverSucceeded(UUID event) {
        Target target = target(event, null);
        lockResource(target);
        verifyAllocation(target);
        createWork(target);
        // No snapshot can be invented from a missing or changed historical resource.
        if (!hasWork(target)) {
            fence(target, null);
            return false;
        }
        Work work = lockWork(target);
        fence(target, null);
        recordReceipt(target);
        boolean finalized = finalizeResource(target, new Work(work.target(), true, work.finalized()));
        fence(target, null);
        return finalized;
    }

    private Target target(ClaimedJob claim) {
        if (claim.jobType() != JobType.FILE_PURGE || !"FILE".equals(claim.aggregateType())) {
            throw new IllegalStateException("Invalid file purge identity");
        }
        return target(claim.id(), claim.aggregateId());
    }

    private Target target(UUID event, UUID expectedFile) {
        List<?> rows = entityManager.createNativeQuery(
                "SELECT file_id, owner_id FROM file_purge_targets WHERE event_id = :event")
                .setParameter("event", event).getResultList();
        if (rows.isEmpty()) { throw new IllegalStateException("Verified purge target is absent"); }
        Object[] row = (Object[]) rows.getFirst(); // Only immutable identity before hierarchy lock.
        UUID file = (UUID) row[0];
        if (expectedFile != null && !expectedFile.equals(file)) {
            throw new IllegalStateException("Invalid file purge identity");
        }
        return new Target(event, file, (UUID) row[1]);
    }

    private void lockResource(Target target) {
        // Order: owner hierarchy -> fresh resource rows -> work -> job for final fence.
        // Scalar SQL deliberately avoids a possibly stale JPA first-level entity cache.
        hierarchy.acquireShared(target.owner());
        entityManager.createNativeQuery("SELECT id FROM files WHERE id = :file FOR UPDATE")
                .setParameter("file", target.file()).getResultList();
    }

    private void verifyAllocation(Target target) {
        boolean verified = (Boolean) entityManager.createNativeQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM file_purge_targets t JOIN file_storage_objects a
                      ON a.file_id = t.file_id AND a.owner_id = t.owner_id
                     AND a.storage_key = t.storage_key AND a.storage_namespace = t.storage_namespace
                     AND a.endpoint = t.endpoint AND a.region = t.region AND a.bucket = t.bucket
                    WHERE t.event_id = :event AND a.write_started AND (a.upload_protocol = 'SERVER_PUT'
                        OR multipart_completed_allocation(a.file_id)))
                """).setParameter("event", target.event()).getSingleResult();
        if (!verified) { throw new IllegalStateException("Purge allocation does not match target"); }
    }

    private void createWork(Target target) {
        entityManager.createNativeQuery("""
                INSERT INTO file_purge_work(file_id, owner_id, folder_id, file_version, file_status,
                    file_created_at, file_updated_at, deleted_at, purge_requested_at,
                    storage_namespace, endpoint, region, bucket, storage_key)
                SELECT f.id, f.owner_id, f.folder_id, f.version, f.status,
                    f.created_at, f.updated_at, f.deleted_at, f.purge_requested_at,
                    t.storage_namespace, t.endpoint, t.region, t.bucket, t.storage_key
                FROM files f JOIN file_purge_targets t ON t.file_id = f.id
                WHERE t.event_id = :event AND f.owner_id = t.owner_id
                    AND f.storage_key = t.storage_key AND f.version = t.file_version
                    AND f.status = 'READY' AND f.deleted_at IS NOT NULL
                    AND f.purge_requested_at = t.requested_at
                ON CONFLICT (file_id) DO NOTHING
                """).setParameter("event", target.event()).executeUpdate();
    }

    private boolean hasWork(Target target) {
        return (Boolean) entityManager.createNativeQuery(
                "SELECT EXISTS (SELECT 1 FROM file_purge_work WHERE file_id = :file)")
                .setParameter("file", target.file()).getSingleResult();
    }

    private Work lockWork(Target target) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT w.storage_namespace, w.endpoint, w.region, w.bucket, w.storage_key,
                    w.bytes_deleted_at IS NOT NULL, w.metadata_finalized_at IS NOT NULL
                FROM file_purge_work w JOIN file_purge_targets t ON t.file_id = w.file_id
                WHERE t.event_id = :event AND w.owner_id = t.owner_id
                    AND w.file_version = t.file_version AND w.purge_requested_at = t.requested_at
                    AND w.storage_namespace = t.storage_namespace AND w.endpoint = t.endpoint
                    AND w.region = t.region AND w.bucket = t.bucket AND w.storage_key = t.storage_key
                FOR UPDATE OF w
                """).setParameter("event", target.event()).getResultList();
        if (rows.isEmpty()) { throw new IllegalStateException("File purge state does not match target"); }
        Object[] row = (Object[]) rows.getFirst();
        return new Work(new StorageTarget((String) row[0], (String) row[1], (String) row[2],
                (String) row[3], (String) row[4]), (Boolean) row[5], (Boolean) row[6]);
    }

    private void recordReceipt(Target target) {
        entityManager.createNativeQuery("""
                UPDATE file_purge_work SET bytes_deleted_at = clock_timestamp(), byte_receipt_job_id = :event
                WHERE file_id = :file AND bytes_deleted_at IS NULL
                """).setParameter("event", target.event()).setParameter("file", target.file()).executeUpdate();
    }

    private static final String FILE_MATCH = """
            f.id = w.file_id AND f.owner_id = w.owner_id AND f.storage_key = w.storage_key
            AND f.version = w.file_version AND f.status = w.file_status AND f.status = 'READY'
            AND f.folder_id IS NOT DISTINCT FROM w.folder_id
            AND f.created_at = w.file_created_at AND f.updated_at = w.file_updated_at
            AND f.deleted_at = w.deleted_at AND f.deleted_at IS NOT NULL
            AND f.purge_requested_at = w.purge_requested_at AND f.purge_requested_at IS NOT NULL
            """;

    private boolean matchesFile(Target target) {
        return (Boolean) entityManager.createNativeQuery("SELECT EXISTS (SELECT 1 FROM files f "
                + "JOIN file_purge_work w ON " + FILE_MATCH + " WHERE w.file_id = :file)")
                .setParameter("file", target.file()).getSingleResult();
    }

    private boolean finalizeResource(Target target, Work work) {
        if (work.finalized()) { return true; }
        if (!work.bytesDeleted() || !matchesFile(target)) { return false; }
        List<?> rows = entityManager.createNativeQuery("SELECT folder_id FROM files WHERE id = :file")
                .setParameter("file", target.file()).getResultList();
        UUID folder = (UUID) rows.getFirst();
        // Exclusive discovery versus shared finalization makes seal/membership validation atomic.
        // Legacy/unsealed/mismatched pending ancestors still defer; byte deletion is unchanged.
        if (folder != null && folders.hasPendingPurgeInAncestry(target.owner(), folder)
                && !representedInSealedDiscovery(target, folder)) { return false; }
        return removeMatchedResource(target.file());
    }

    /** Caller holds hierarchy, fresh file/allocation and work locks; job fence comes last. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean finalizeFolderMember(UUID event, UUID file, UUID owner) {
        boolean verified = (Boolean) entityManager.createNativeQuery("""
                SELECT folder_purge_file_verified(:event, :file) AND EXISTS (
                    SELECT 1 FROM folder_purge_files m JOIN folder_purge_file_progress p
                        ON p.event_id = m.event_id AND p.file_id = m.file_id
                    WHERE m.event_id = :event AND m.file_id = :file AND m.owner_id = :owner
                        AND p.completed_at IS NOT NULL AND (m.purge_requested_at IS NULL OR EXISTS (
                            SELECT 1 FROM file_purge_targets t WHERE t.event_id = m.standalone_event_id
                                AND t.file_id = m.file_id AND ROW(t.owner_id, t.file_version, t.requested_at,
                                    t.storage_namespace, t.endpoint, t.region, t.bucket, t.storage_key)
                                IS NOT DISTINCT FROM ROW(m.owner_id, m.file_version, m.purge_requested_at,
                                    m.storage_namespace, m.endpoint, m.region, m.bucket, m.storage_key))))
                """).setParameter("event", event).setParameter("file", file).setParameter("owner", owner)
                .getSingleResult();
        if (!verified) { throw new IllegalStateException("Folder file finalization proof is absent"); }
        boolean barrier = (Boolean) entityManager.createNativeQuery("""
                WITH RECURSIVE ancestors AS (
                    SELECT f.id, f.parent_folder_id, f.purge_requested_at FROM folders f
                    JOIN folder_purge_files m ON m.folder_id = f.id WHERE m.event_id = :event AND m.file_id = :file
                    UNION
                    SELECT f.id, f.parent_folder_id, f.purge_requested_at FROM folders f
                    JOIN ancestors a ON f.id = a.parent_folder_id
                )
                SELECT EXISTS (SELECT 1 FROM ancestors a WHERE a.purge_requested_at IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM folder_purge_runs r WHERE r.event_id = :event
                        AND r.owner_id = :owner AND r.root_folder_id = a.id AND r.requested_at = a.purge_requested_at
                        AND r.phase = 'DELETE_FOLDERS' AND r.discovery_sealed_at IS NOT NULL))
                """).setParameter("event", event).setParameter("file", file).setParameter("owner", owner)
                .getSingleResult();
        if (barrier) { throw new IllegalStateException("Unrepresented file purge barrier"); }
        return removeMatchedResource(file);
    }

    /** Shared atomic removal/receipt primitive, including nullable inherited lifecycle snapshots. */
    private boolean removeMatchedResource(UUID file) {
        boolean finalized = (Boolean) entityManager.createNativeQuery("""
                SELECT metadata_finalized_at IS NOT NULL FROM file_purge_work WHERE file_id = :file
                """).setParameter("file", file).getSingleResult();
        if (finalized) {
            boolean exists = (Boolean) entityManager.createNativeQuery("SELECT EXISTS (SELECT 1 FROM files WHERE id = :file)")
                    .setParameter("file", file).getSingleResult();
            if (exists) { throw new IllegalStateException("Finalized file metadata reappeared"); }
            return false;
        }
        Snapshot before = activity.snapshot(ResourceActivityService.ResourceType.FILE, file);
        int deleted = entityManager.createNativeQuery("""
                DELETE FROM files f USING file_purge_work w WHERE w.file_id = :file AND f.id = w.file_id
                    AND ROW(f.owner_id, f.folder_id, f.version, f.status, f.created_at, f.updated_at,
                        f.deleted_at, f.purge_requested_at, f.storage_key)
                    IS NOT DISTINCT FROM ROW(w.owner_id, w.folder_id, w.file_version, w.file_status,
                        w.file_created_at, w.file_updated_at, w.deleted_at, w.purge_requested_at, w.storage_key)
                    AND (w.bytes_deleted_at IS NOT NULL OR w.never_written_at IS NOT NULL
                        OR w.multipart_discarded_at IS NOT NULL)
                """).setParameter("file", file).executeUpdate();
        if (deleted != 1) { throw new IllegalStateException("File finalization lost its matching resource"); }
        entityManager.createNativeQuery("""
                UPDATE file_purge_work SET metadata_finalized_at = clock_timestamp()
                WHERE file_id = :file AND metadata_finalized_at IS NULL
                """).setParameter("file", file).executeUpdate();
        activity.append(before.ownerId(), PERMANENTLY_DELETED, before, null);
        return true;
    }

    private boolean representedInSealedDiscovery(Target target, UUID folder) {
        return (Boolean) entityManager.createNativeQuery("""
                WITH RECURSIVE ancestors AS (
                    SELECT id, parent_folder_id, purge_requested_at FROM folders
                    WHERE id = :folder AND owner_id = :owner
                    UNION
                    SELECT f.id, f.parent_folder_id, f.purge_requested_at FROM folders f
                    JOIN ancestors a ON f.id = a.parent_folder_id WHERE f.owner_id = :owner
                )
                SELECT NOT EXISTS (SELECT 1 FROM ancestors a WHERE a.purge_requested_at IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM folder_purge_runs r
                        JOIN folder_purge_files m ON m.event_id = r.event_id
                        JOIN file_purge_work w ON w.file_id = m.file_id
                        WHERE r.root_folder_id = a.id AND r.owner_id = :owner
                            AND r.requested_at = a.purge_requested_at AND r.phase IN ('DELETE_FILES', 'DELETE_FOLDERS')
                            AND r.discovery_sealed_at IS NOT NULL AND m.file_id = :file
                            AND m.standalone_event_id = :event AND m.owner_id = w.owner_id
                            AND m.folder_id = w.folder_id AND m.storage_key = w.storage_key
                            AND m.file_version = w.file_version AND m.file_status = w.file_status
                            AND m.file_created_at = w.file_created_at AND m.file_updated_at = w.file_updated_at
                            AND m.deleted_at = w.deleted_at AND m.purge_requested_at = w.purge_requested_at
                            AND m.storage_namespace = w.storage_namespace AND m.endpoint = w.endpoint
                            AND m.region = w.region AND m.bucket = w.bucket))
                """).setParameter("folder", folder).setParameter("owner", target.owner())
                .setParameter("file", target.file()).setParameter("event", target.event()).getSingleResult();
    }

    private void fence(Target target, ClaimedJob claim) {
        entityManager.createNativeQuery("SELECT id FROM background_jobs WHERE id = :event FOR UPDATE")
                .setParameter("event", target.event()).getResultList();
        String condition = claim == null ? "status = 'SUCCEEDED' AND completed_at IS NOT NULL"
                : "status = 'RUNNING' AND claim_token = :token AND claim_expires_at > clock_timestamp()";
        var query = entityManager.createNativeQuery("""
                UPDATE background_jobs SET updated_at = updated_at
                WHERE id = :event AND job_type = 'FILE_PURGE' AND aggregate_type = 'FILE'
                    AND aggregate_id = :file AND
                """ + condition).setParameter("event", target.event()).setParameter("file", target.file());
        if (claim != null) { query.setParameter("token", claim.claimToken()); }
        if (query.executeUpdate() != 1) { throw new StaleClaimException(); }
    }

    public static class StaleClaimException extends IllegalStateException {
        StaleClaimException() { super("File purge claim is stale"); }
    }
}
