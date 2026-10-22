package com.vaultdrive.worker;

import com.vaultdrive.activity.ResourceActivityService;
import com.vaultdrive.activity.ResourceActivityService.Snapshot;
import static com.vaultdrive.activity.ResourceActivityService.EventType.*;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobType;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Database-only bounded finalization. Retained manifests/receipts are never removed. */
@Service
public class FolderPurgeMetadataService {
    public static final int MAX_BATCH_SIZE = 20;
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final FolderPurgeRunService runs;
    private final FilePurgeProgressService files;
    private final BackgroundJobService jobs;
    private final ResourceActivityService activity;

    public FolderPurgeMetadataService(EntityManager em, HierarchyCoordinator hierarchy,
                                      FolderPurgeRunService runs, FilePurgeProgressService files,
                                      BackgroundJobService jobs,
            ResourceActivityService activity) {
        this.em = em;
        this.hierarchy = hierarchy;
        this.runs = runs;
        this.files = files;
        this.jobs = jobs;
        this.activity = activity;
    }

    @Transactional(timeout = 10, rollbackFor = InterruptedException.class)
    public JobHandler.Result removeBatch(JobExecutionContext context, long version, int limit) throws InterruptedException {
        context.checkActive();
        ClaimedJob claim = context.claim();
        if (claim.jobType() != JobType.FOLDER_PURGE || !"FOLDER".equals(claim.aggregateType())
                || limit < 1 || limit > MAX_BATCH_SIZE) { throw new IllegalArgumentException("Invalid folder removal request"); }
        runs.boundTransaction();
        UUID owner = (UUID) query("SELECT owner_id FROM folder_purge_runs WHERE event_id = :event", claim).getSingleResult();
        // Only immutable owner identity precedes coordination. Resource rows precede every work lock.
        hierarchy.acquireExclusive(owner);
        query("SELECT id FROM folders WHERE id = :root FOR UPDATE", claim).getResultList();
        validate(claim, owner);
        String phase = (String) query("SELECT phase FROM folder_purge_runs WHERE event_id = :event", claim).getSingleResult();
        if ("FINALIZED".equals(phase)) {
            context.checkActive();
            fence(claim); // Missing root succeeds only from fully validated retained proof, with a live claim.
            return JobHandler.Result.SUCCEEDED;
        }
        if (!"DELETE_FOLDERS".equals(phase)) { throw new IllegalStateException("Folder removal phase is not ready"); }
        if (exists("""
                WITH RECURSIVE ancestors AS (
                    SELECT p.id, p.parent_folder_id, p.purge_requested_at FROM folders f
                    JOIN folders p ON p.id = f.parent_folder_id WHERE f.id = :root
                    UNION
                    SELECT p.id, p.parent_folder_id, p.purge_requested_at FROM folders p
                    JOIN ancestors a ON p.id = a.parent_folder_id
                ) SELECT EXISTS (SELECT 1 FROM ancestors WHERE purge_requested_at IS NOT NULL)
                """, claim)) { throw new IllegalStateException("Unrepresented ancestor purge barrier"); }

        @SuppressWarnings("unchecked")
        List<UUID> pendingFiles = query("""
                SELECT m.file_id FROM folder_purge_files m LEFT JOIN file_purge_work w ON w.file_id = m.file_id
                WHERE m.event_id = :event AND w.metadata_finalized_at IS NULL ORDER BY m.file_id LIMIT :limit
                """, claim).setParameter("limit", limit).getResultList();
        if (!pendingFiles.isEmpty()) {
            // Lock the whole resource page first, then the whole work page, both in UUID order.
            for (UUID file : pendingFiles) {
                context.checkActive();
                em.createNativeQuery("SELECT id FROM files WHERE id = :file FOR UPDATE").setParameter("file", file).getResultList();
                em.createNativeQuery("SELECT file_id FROM file_storage_objects WHERE file_id = :file FOR UPDATE")
                        .setParameter("file", file).getResultList();
            }
            for (UUID file : pendingFiles) {
                em.createNativeQuery("SELECT file_id FROM file_purge_work WHERE file_id = :file FOR UPDATE")
                        .setParameter("file", file).getResultList();
            }
            for (UUID file : pendingFiles) {
                context.checkActive();
                if (!files.finalizeFolderMember(claim.id(), file, owner)) {
                    throw new IllegalStateException("Folder file page made no progress");
                }
            }
            context.checkActive();
            jobs.checkpoint(claim, version); // LAST: failure rolls back every removal/receipt in this batch.
            return JobHandler.Result.CONTINUE;
        }

        @SuppressWarnings("unchecked")
        List<UUID> leaves = query("""
                SELECT m.folder_id FROM folder_purge_frontier m
                WHERE m.event_id = :event AND NOT m.is_root
                    AND NOT EXISTS (SELECT 1 FROM folder_purge_removals d
                        WHERE d.event_id = m.event_id AND d.folder_id = m.folder_id)
                    AND NOT EXISTS (SELECT 1 FROM folder_purge_frontier c WHERE c.event_id = m.event_id
                        AND c.parent_folder_id = m.folder_id AND NOT EXISTS (SELECT 1 FROM folder_purge_removals d
                            WHERE d.event_id = c.event_id AND d.folder_id = c.folder_id))
                ORDER BY m.folder_id LIMIT :limit
                """, claim).setParameter("limit", limit).getResultList();
        boolean root = leaves.isEmpty();
        if (root) {
            if (exists("""
                    SELECT EXISTS (SELECT 1 FROM folder_purge_frontier m WHERE m.event_id = :event AND NOT m.is_root
                        AND NOT EXISTS (SELECT 1 FROM folder_purge_removals d
                            WHERE d.event_id = m.event_id AND d.folder_id = m.folder_id))
                    """, claim)) { throw new IllegalStateException("Folder manifest has no removable leaf"); }
            leaves = List.of(claim.aggregateId());
        }
        for (UUID folder : leaves) {
            context.checkActive();
            em.createNativeQuery("SELECT id FROM folders WHERE id = :folder FOR UPDATE")
                    .setParameter("folder", folder).getResultList();
        }
        query("SELECT event_id FROM folder_purge_runs WHERE event_id = :event FOR UPDATE", claim).getSingleResult();
        for (UUID folder : leaves) {
            context.checkActive();
            Snapshot before = activity.snapshot(ResourceActivityService.ResourceType.FOLDER, folder);
            int removed = query("""
                    DELETE FROM folders f USING folder_purge_frontier m, folder_purge_runs r
                    WHERE m.event_id = :event AND r.event_id = m.event_id AND m.folder_id = :folder AND f.id = m.folder_id
                        AND f.owner_id = m.owner_id AND f.parent_folder_id IS NOT DISTINCT FROM m.parent_folder_id
                        AND f.deleted_at IS NOT DISTINCT FROM m.deleted_at
                        AND ((m.is_root AND f.purge_requested_at = r.requested_at)
                            OR (NOT m.is_root AND f.purge_requested_at IS NULL))
                        AND m.children_complete AND m.files_complete
                        AND NOT EXISTS (SELECT 1 FROM files WHERE folder_id = f.id)
                        AND NOT EXISTS (SELECT 1 FROM folders WHERE parent_folder_id = f.id)
                    """, claim).setParameter("folder", folder).executeUpdate();
            if (removed != 1) { throw new IllegalStateException("Folder lacks matching removable metadata"); }
            query("INSERT INTO folder_purge_removals(event_id, folder_id) VALUES (:event, :folder)", claim)
                    .setParameter("folder", folder).executeUpdate();
            activity.append(owner, PERMANENTLY_DELETED, before, null);
        }
        if (root) {
            query("UPDATE folder_purge_runs SET phase = 'FINALIZED', finalized_at = clock_timestamp() WHERE event_id = :event", claim)
                    .executeUpdate();
        }
        context.checkActive();
        jobs.checkpoint(claim, version);
        return root ? JobHandler.Result.SUCCEEDED : JobHandler.Result.CONTINUE;
    }

    /** Count/seal/snapshot/proof checks are set-based and bounded by statement/transaction deadlines. */
    private void validate(ClaimedJob claim, UUID owner) {
        boolean valid = (Boolean) query("""
                SELECT EXISTS (SELECT 1 FROM folder_purge_runs r WHERE r.event_id = :event AND r.owner_id = :owner
                    AND r.root_folder_id = :root AND r.discovery_sealed_at IS NOT NULL
                    AND r.phase IN ('DELETE_FOLDERS', 'FINALIZED')
                    AND r.manifest_folder_count = (SELECT count(*) FROM folder_purge_frontier WHERE event_id = r.event_id)
                    AND r.manifest_file_count = (SELECT count(*) FROM folder_purge_files WHERE event_id = r.event_id)
                    AND EXISTS (SELECT 1 FROM folder_purge_frontier WHERE event_id = r.event_id AND is_root
                        AND folder_id = r.root_folder_id AND owner_id = r.owner_id)
                    AND ((r.phase = 'FINALIZED' AND r.finalized_at IS NOT NULL
                        AND NOT EXISTS (SELECT 1 FROM folders WHERE id = r.root_folder_id))
                        OR (r.phase = 'DELETE_FOLDERS' AND EXISTS (SELECT 1 FROM folders f WHERE f.id = r.root_folder_id
                            AND f.owner_id = r.owner_id AND f.deleted_at IS NOT NULL AND f.purge_requested_at = r.requested_at))))
                """, claim).setParameter("owner", owner).getSingleResult();
        if (!valid || exists("""
                SELECT EXISTS (SELECT 1 FROM folder_purge_frontier m
                    LEFT JOIN folders f ON f.id = m.folder_id LEFT JOIN folder_purge_removals d
                        ON d.event_id = m.event_id AND d.folder_id = m.folder_id
                    WHERE m.event_id = :event AND (m.owner_id <> :owner OR NOT m.children_complete OR NOT m.files_complete
                        OR (f.id IS NULL AND d.removed_at IS NULL) OR (f.id IS NOT NULL AND d.removed_at IS NOT NULL)
                        OR (f.id IS NOT NULL AND (f.owner_id <> m.owner_id
                            OR f.parent_folder_id IS DISTINCT FROM m.parent_folder_id
                            OR f.deleted_at IS DISTINCT FROM m.deleted_at
                            OR (NOT m.is_root AND f.purge_requested_at IS NOT NULL)))
                        OR (NOT m.is_root AND NOT EXISTS (SELECT 1 FROM folder_purge_frontier p
                            WHERE p.event_id = m.event_id AND p.folder_id = m.parent_folder_id))
                        OR EXISTS (SELECT 1 FROM folders c WHERE c.parent_folder_id = m.folder_id
                            AND NOT EXISTS (SELECT 1 FROM folder_purge_frontier n
                                WHERE n.event_id = m.event_id AND n.folder_id = c.id AND n.parent_folder_id = m.folder_id))
                        OR EXISTS (SELECT 1 FROM files c WHERE c.folder_id = m.folder_id
                            AND NOT EXISTS (SELECT 1 FROM folder_purge_files n
                                WHERE n.event_id = m.event_id AND n.file_id = c.id AND n.folder_id = m.folder_id))))
                OR EXISTS (SELECT 1 FROM folder_purge_files m LEFT JOIN file_purge_work w ON w.file_id = m.file_id
                    LEFT JOIN folder_purge_file_progress p ON p.event_id = m.event_id AND p.file_id = m.file_id
                    LEFT JOIN files f ON f.id = m.file_id WHERE m.event_id = :event
                        AND (m.owner_id <> :owner OR p.completed_at IS NULL OR NOT folder_purge_file_verified(m.event_id, m.file_id)
                            OR (f.id IS NULL AND w.metadata_finalized_at IS NULL)
                            OR (f.id IS NOT NULL AND w.metadata_finalized_at IS NOT NULL)
                            OR (f.id IS NOT NULL AND ROW(f.owner_id, f.folder_id, f.version, f.status, f.created_at,
                                f.updated_at, f.deleted_at, f.purge_requested_at, f.storage_key)
                                IS DISTINCT FROM ROW(m.owner_id, m.folder_id, m.file_version, m.file_status,
                                    m.file_created_at, m.file_updated_at, m.deleted_at, m.purge_requested_at, m.storage_key))))
                OR EXISTS (SELECT 1 FROM folder_purge_runs r JOIN folder_purge_frontier m ON m.event_id = r.event_id
                    WHERE r.event_id = :event AND r.phase = 'FINALIZED' AND NOT EXISTS (
                        SELECT 1 FROM folder_purge_removals d WHERE d.event_id = m.event_id AND d.folder_id = m.folder_id))
                """, claim, owner)) { throw new IllegalStateException("Inconsistent folder finalization manifest"); }
    }

    private jakarta.persistence.Query query(String sql, ClaimedJob claim) {
        var query = em.createNativeQuery(sql);
        if (sql.contains(":event")) { query.setParameter("event", claim.id()); }
        if (sql.contains(":root")) { query.setParameter("root", claim.aggregateId()); }
        return query;
    }

    private boolean exists(String sql, ClaimedJob claim) { return (Boolean) query(sql, claim).getSingleResult(); }
    private boolean exists(String sql, ClaimedJob claim, UUID owner) {
        return (Boolean) query(sql, claim).setParameter("owner", owner).getSingleResult();
    }

    private void fence(ClaimedJob claim) {
        query("SELECT id FROM background_jobs WHERE id = :event FOR UPDATE", claim).getSingleResult();
        int changed = query("""
                UPDATE background_jobs SET updated_at = updated_at WHERE id = :event AND job_type = 'FOLDER_PURGE'
                    AND aggregate_type = 'FOLDER' AND aggregate_id = :root AND status = 'RUNNING'
                    AND claim_token = :token AND claim_expires_at > clock_timestamp()
                """, claim).setParameter("token", claim.claimToken()).executeUpdate();
        if (changed != 1) { throw new IllegalStateException("Folder finalization claim is stale"); }
    }
}
