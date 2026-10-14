package com.vaultdrive.worker;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobType;
import com.vaultdrive.storage.StorageTarget;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Sealed membership authorizes shared file work, never storage I/O in these transactions. */
@Service
public class FolderPurgeFileService {
    public static final int MAX_BATCH_SIZE = 20;
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final BackgroundJobService jobs;
    private final FolderPurgeRunService runs;

    public FolderPurgeFileService(EntityManager em, HierarchyCoordinator hierarchy,
                                  BackgroundJobService jobs, FolderPurgeRunService runs) {
        this.em = em;
        this.hierarchy = hierarchy;
        this.jobs = jobs;
        this.runs = runs;
    }

    public record Prepared(StorageTarget target, boolean proven, boolean neverWritten) { }

    @Transactional(readOnly = true, timeout = 10)
    public String phase(ClaimedJob claim) {
        validateClaim(claim);
        List<?> rows = em.createNativeQuery("SELECT phase FROM folder_purge_runs WHERE event_id = :event")
                .setParameter("event", claim.id()).getResultList();
        return rows.isEmpty() ? "DISCOVER" : (String) rows.getFirst();
    }

    /** Fresh unexamined members first; blocked low UUIDs cannot starve the rest of a run. */
    @Transactional(readOnly = true, timeout = 10)
    public List<UUID> batch(ClaimedJob claim, int limit) {
        validateClaim(claim);
        if (limit < 1 || limit > MAX_BATCH_SIZE) { throw new IllegalArgumentException("Invalid deletion batch size"); }
        runs.boundTransaction();
        @SuppressWarnings("unchecked")
        List<UUID> fresh = em.createNativeQuery("""
                SELECT m.file_id FROM folder_purge_files m WHERE m.event_id = :event
                    AND NOT EXISTS (SELECT 1 FROM folder_purge_file_progress p
                        WHERE p.event_id = m.event_id AND p.file_id = m.file_id)
                ORDER BY m.file_id LIMIT :limit
                """).setParameter("event", claim.id()).setParameter("limit", limit).getResultList();
        if (!fresh.isEmpty()) { return fresh; }
        @SuppressWarnings("unchecked")
        List<UUID> blocked = em.createNativeQuery("""
                SELECT file_id FROM folder_purge_file_progress WHERE event_id = :event AND completed_at IS NULL
                ORDER BY file_id LIMIT :limit
                """).setParameter("event", claim.id()).setParameter("limit", limit).getResultList();
        return blocked;
    }

    @Transactional(timeout = 10, rollbackFor = InterruptedException.class)
    public Prepared prepare(JobExecutionContext context, UUID file) throws InterruptedException {
        lock(context, file);
        Prepared prepared = verify(file, context.claim(), true);
        context.checkActive();
        fence(context.claim()); // LAST: targets cannot commit on a stale claim.
        return prepared;
    }

    /** Confirmed DELETE/absence, reused receipt, or coordinated never-admitted write proof. */
    @Transactional(timeout = 10, rollbackFor = InterruptedException.class)
    public boolean completeFile(JobExecutionContext context, UUID file, long version) throws InterruptedException {
        lock(context, file);
        Prepared prepared = verify(file, context.claim(), false);
        if (!prepared.proven()) {
            if (prepared.neverWritten()) {
                em.createNativeQuery("""
                        UPDATE file_storage_objects SET write_precluded_at = COALESCE(write_precluded_at, clock_timestamp())
                        WHERE file_id = :file AND NOT write_started
                        """).setParameter("file", file).executeUpdate();
                em.createNativeQuery("""
                        UPDATE file_purge_work SET never_written_at = clock_timestamp(), never_written_job_id = :event
                        WHERE file_id = :file AND never_written_at IS NULL AND bytes_deleted_at IS NULL
                        """).setParameter("file", file).setParameter("event", context.claim().id()).executeUpdate();
            } else {
                em.createNativeQuery("""
                        UPDATE file_purge_work SET bytes_deleted_at = clock_timestamp(), byte_receipt_job_id = :event
                        WHERE file_id = :file AND bytes_deleted_at IS NULL
                        """).setParameter("file", file).setParameter("event", context.claim().id()).executeUpdate();
            }
        }
        int changed = em.createNativeQuery("""
                INSERT INTO folder_purge_file_progress(event_id, file_id, completed_at)
                VALUES (:event, :file, clock_timestamp())
                ON CONFLICT (event_id, file_id) DO UPDATE
                SET completed_at = EXCLUDED.completed_at, blocked_reason = NULL
                WHERE folder_purge_file_progress.completed_at IS NULL
                """).setParameter("event", context.claim().id()).setParameter("file", file).executeUpdate();
        context.checkActive();
        if (changed == 1) { jobs.checkpoint(context.claim(), version); }
        else { fence(context.claim()); } // Replayed receipts/attachments are not progress.
        return changed == 1;
    }

    /** First observation is a useful durable disposition; unchanged blocked retries are not checkpoints. */
    @Transactional(timeout = 10, rollbackFor = InterruptedException.class)
    public boolean blocked(JobExecutionContext context, UUID file, String reason, long version) throws InterruptedException {
        lock(context, file);
        int changed = em.createNativeQuery("""
                INSERT INTO folder_purge_file_progress(event_id, file_id, blocked_reason)
                VALUES (:event, :file, :reason) ON CONFLICT (event_id, file_id) DO NOTHING
                """).setParameter("event", context.claim().id()).setParameter("file", file)
                .setParameter("reason", reason).executeUpdate();
        context.checkActive();
        if (changed == 1) { jobs.checkpoint(context.claim(), version); }
        else { fence(context.claim()); }
        return changed == 1;
    }

    @Transactional(timeout = 10, rollbackFor = InterruptedException.class)
    public boolean advancePhase(JobExecutionContext context, long version) throws InterruptedException {
        runs.boundTransaction();
        UUID owner = owner(context.claim());
        hierarchy.acquireExclusive(owner);
        context.checkActive();
        validateRoot(context.claim(), owner);
        em.createNativeQuery("SELECT event_id FROM folder_purge_runs WHERE event_id = :event FOR UPDATE")
                .setParameter("event", context.claim().id()).getSingleResult();
        int changed = em.createNativeQuery("""
                UPDATE folder_purge_runs r SET phase = 'DELETE_FOLDERS'
                WHERE r.event_id = :event AND r.phase = 'DELETE_FILES' AND r.discovery_sealed_at IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM folder_purge_files m LEFT JOIN folder_purge_file_progress p
                        ON p.event_id = m.event_id AND p.file_id = m.file_id
                        WHERE m.event_id = r.event_id AND p.completed_at IS NULL)
                """).setParameter("event", context.claim().id()).executeUpdate();
        context.checkActive();
        if (changed == 1) { jobs.checkpoint(context.claim(), version); }
        else { fence(context.claim()); }
        return changed == 1;
    }

    private UUID owner(ClaimedJob claim) {
        validateClaim(claim);
        return (UUID) em.createNativeQuery("SELECT owner_id FROM folder_purge_runs WHERE event_id = :event")
                .setParameter("event", claim.id()).getSingleResult(); // Immutable identity only.
    }

    private void lock(JobExecutionContext context, UUID file) throws InterruptedException {
        context.checkActive();
        runs.boundTransaction();
        UUID owner = owner(context.claim());
        // hierarchy -> fresh root/file/allocation -> shared work/member progress -> final job fence.
        hierarchy.acquireShared(owner);
        context.checkActive();
        validateRoot(context.claim(), owner);
        boolean member = (Boolean) em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM folder_purge_files m JOIN folder_purge_runs r ON r.event_id = m.event_id
                    JOIN folder_purge_frontier f ON f.event_id = m.event_id AND f.folder_id = m.folder_id
                    WHERE m.event_id = :event AND m.file_id = :file AND m.owner_id = :owner AND f.owner_id = :owner
                        AND r.phase = 'DELETE_FILES' AND r.discovery_sealed_at IS NOT NULL)
                """).setParameter("event", context.claim().id()).setParameter("file", file)
                .setParameter("owner", owner).getSingleResult();
        if (!member) { throw new IllegalStateException("Invalid sealed folder membership"); }
        em.createNativeQuery("SELECT id FROM files WHERE id = :file FOR UPDATE")
                .setParameter("file", file).getResultList();
        em.createNativeQuery("SELECT file_id FROM file_storage_objects WHERE file_id = :file FOR UPDATE")
                .setParameter("file", file).getResultList();
    }

    private void validateRoot(ClaimedJob claim, UUID owner) {
        boolean valid = (Boolean) em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM folder_purge_runs r JOIN folders f ON f.id = r.root_folder_id
                    WHERE r.event_id = :event AND r.root_folder_id = :root AND r.owner_id = :owner
                        AND f.owner_id = r.owner_id AND f.deleted_at IS NOT NULL AND f.purge_requested_at = r.requested_at)
                """).setParameter("event", claim.id()).setParameter("root", claim.aggregateId())
                .setParameter("owner", owner).getSingleResult();
        if (!valid) { throw new IllegalStateException("Folder purge root identity changed"); }
    }

    private static final String SNAPSHOT_MATCH = """
            ROW(f.owner_id, f.folder_id, f.version, f.status, f.created_at, f.updated_at,
                f.deleted_at, f.purge_requested_at, f.storage_key)
            IS NOT DISTINCT FROM ROW(m.owner_id, m.folder_id, m.file_version, m.file_status,
                m.file_created_at, m.file_updated_at, m.deleted_at, m.purge_requested_at, m.storage_key)
            """;

    private Prepared verify(UUID file, ClaimedJob claim, boolean create) {
        List<?> allocations = em.createNativeQuery("""
                SELECT a.storage_namespace, a.endpoint, a.region, a.bucket, a.storage_key, a.write_started,
                    m.file_status, m.purge_requested_at, m.standalone_event_id
                FROM folder_purge_files m JOIN file_storage_objects a ON a.file_id = m.file_id
                WHERE m.event_id = :event AND m.file_id = :file AND a.storage_namespace IS NOT NULL
                    AND ROW(a.owner_id, a.storage_key, a.storage_namespace, a.endpoint, a.region, a.bucket)
                    IS NOT DISTINCT FROM ROW(m.owner_id, m.storage_key, m.storage_namespace, m.endpoint, m.region, m.bucket)
                """).setParameter("event", claim.id()).setParameter("file", file).getResultList();
        if (allocations.isEmpty()) { throw new BlockedFile("UNKNOWN_BINDING"); }
        Object[] allocation = (Object[]) allocations.getFirst();
        boolean neverWritten = !(Boolean) allocation[5];
        if (!neverWritten && !"READY".equals(allocation[6])) { throw new BlockedFile("UNSAFE_WRITER"); }
        if (allocation[7] != null) {
            boolean target = (Boolean) em.createNativeQuery("""
                    SELECT EXISTS (SELECT 1 FROM folder_purge_files m JOIN file_purge_targets t
                        ON t.event_id = m.standalone_event_id AND t.file_id = m.file_id
                        WHERE m.event_id = :event AND m.file_id = :file
                            AND ROW(t.owner_id, t.file_version, t.requested_at, t.storage_namespace,
                                t.endpoint, t.region, t.bucket, t.storage_key)
                            IS NOT DISTINCT FROM ROW(m.owner_id, m.file_version, m.purge_requested_at,
                                m.storage_namespace, m.endpoint, m.region, m.bucket, m.storage_key))
                    """).setParameter("event", claim.id()).setParameter("file", file).getSingleResult();
            if (!target) { throw new BlockedFile("UNKNOWN_TARGET"); }
        }
        boolean drift = (Boolean) em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM files f "
                + "JOIN folder_purge_files m ON m.file_id = f.id WHERE m.event_id = :event AND m.file_id = :file AND NOT ("
                + SNAPSHOT_MATCH + "))").setParameter("event", claim.id()).setParameter("file", file).getSingleResult();
        if (drift) { throw new BlockedFile("IDENTITY_MISMATCH"); }
        if (create) {
            em.createNativeQuery("""
                    INSERT INTO file_purge_work(file_id, owner_id, folder_id, file_version, file_status,
                        file_created_at, file_updated_at, deleted_at, purge_requested_at,
                        storage_namespace, endpoint, region, bucket, storage_key)
                    SELECT m.file_id, m.owner_id, m.folder_id, m.file_version, m.file_status,
                        m.file_created_at, m.file_updated_at, m.deleted_at, m.purge_requested_at,
                        m.storage_namespace, m.endpoint, m.region, m.bucket, m.storage_key
                    FROM folder_purge_files m JOIN files f ON f.id = m.file_id
                    WHERE m.event_id = :event AND m.file_id = :file AND
                    """ + SNAPSHOT_MATCH + " ON CONFLICT (file_id) DO NOTHING")
                    .setParameter("event", claim.id()).setParameter("file", file).executeUpdate();
        }
        List<?> work = em.createNativeQuery("""
                SELECT w.bytes_deleted_at IS NOT NULL OR w.never_written_at IS NOT NULL
                FROM file_purge_work w JOIN folder_purge_files m ON m.file_id = w.file_id
                WHERE m.event_id = :event AND m.file_id = :file
                    AND ROW(w.owner_id, w.folder_id, w.file_version, w.file_status, w.file_created_at,
                        w.file_updated_at, w.deleted_at, w.purge_requested_at, w.storage_namespace,
                        w.endpoint, w.region, w.bucket, w.storage_key)
                    IS NOT DISTINCT FROM ROW(m.owner_id, m.folder_id, m.file_version, m.file_status, m.file_created_at,
                        m.file_updated_at, m.deleted_at, m.purge_requested_at, m.storage_namespace,
                        m.endpoint, m.region, m.bucket, m.storage_key)
                FOR UPDATE OF w
                """).setParameter("event", claim.id()).setParameter("file", file).getResultList();
        if (work.isEmpty()) { throw new BlockedFile("IDENTITY_MISMATCH"); }
        boolean proven = (Boolean) work.getFirst();
        if (proven && !(Boolean) em.createNativeQuery("SELECT folder_purge_file_verified(:event, :file)")
                .setParameter("event", claim.id()).setParameter("file", file).getSingleResult()) {
            throw new BlockedFile("IDENTITY_MISMATCH");
        }
        boolean exists = (Boolean) em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM files WHERE id = :file)")
                .setParameter("file", file).getSingleResult();
        if (!exists && !proven) { throw new BlockedFile("IDENTITY_MISMATCH"); }
        return new Prepared(new StorageTarget((String) allocation[0], (String) allocation[1],
                (String) allocation[2], (String) allocation[3], (String) allocation[4]), proven, neverWritten);
    }

    private void fence(ClaimedJob claim) {
        em.createNativeQuery("SELECT id FROM background_jobs WHERE id = :event FOR UPDATE")
                .setParameter("event", claim.id()).getResultList();
        int changed = em.createNativeQuery("""
                UPDATE background_jobs SET updated_at = updated_at WHERE id = :event AND job_type = 'FOLDER_PURGE'
                    AND aggregate_type = 'FOLDER' AND aggregate_id = :root AND status = 'RUNNING'
                    AND claim_token = :token AND claim_expires_at > clock_timestamp()
                """).setParameter("event", claim.id()).setParameter("root", claim.aggregateId())
                .setParameter("token", claim.claimToken()).executeUpdate();
        if (changed != 1) { throw new IllegalStateException("Folder purge claim is stale"); }
    }

    private void validateClaim(ClaimedJob claim) {
        if (claim.jobType() != JobType.FOLDER_PURGE || !"FOLDER".equals(claim.aggregateType())) {
            throw new IllegalArgumentException("Invalid folder purge claim");
        }
    }

    public static class BlockedFile extends IllegalStateException {
        private final String reason;
        BlockedFile(String reason) { super("Folder file blocked: " + reason); this.reason = reason; }
        public String reason() { return reason; }
    }
}
