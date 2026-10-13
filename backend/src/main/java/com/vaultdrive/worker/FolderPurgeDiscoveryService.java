package com.vaultdrive.worker;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobType;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** One PostgreSQL keyset page per transaction. No storage I/O or recursive Java walk. */
@Service
public class FolderPurgeDiscoveryService {
    public static final int MAX_PAGE_SIZE = 100;
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final BackgroundJobService jobs;
    private final FolderPurgeRunService runs;

    public FolderPurgeDiscoveryService(EntityManager em, HierarchyCoordinator hierarchy,
                                       BackgroundJobService jobs, FolderPurgeRunService runs) {
        this.em = em;
        this.hierarchy = hierarchy;
        this.jobs = jobs;
        this.runs = runs;
    }

    public record Batch(boolean advanced, boolean sealed) { }

    @Transactional(timeout = 10)
    public Batch discover(ClaimedJob claim, long expectedVersion, int pageSize) {
        try {
            return discoverPage(claim, expectedVersion, pageSize, null);
        } catch (InterruptedException impossible) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Discovery interrupted", impossible);
        }
    }

    @Transactional(timeout = 10, rollbackFor = InterruptedException.class)
    public Batch discover(JobExecutionContext context, long expectedVersion, int pageSize) throws InterruptedException {
        return discoverPage(context.claim(), expectedVersion, pageSize, context);
    }

    private Batch discoverPage(ClaimedJob claim, long expectedVersion, int pageSize,
                               JobExecutionContext context) throws InterruptedException {
        checkActive(context);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE || claim.jobType() != JobType.FOLDER_PURGE
                || !"FOLDER".equals(claim.aggregateType())) {
            throw new IllegalArgumentException("Invalid folder discovery request");
        }
        runs.boundTransaction();
        // Only event identity is read before coordination. Revalidate all protected state below.
        List<?> identity = em.createNativeQuery("""
                SELECT payload->>'ownerId' FROM outbox_events WHERE id = :event
                    AND aggregate_id = :root AND event_type = 'FOLDER_PURGE_REQUESTED'
                    AND aggregate_type = 'FOLDER' AND payload_version = 1
                """).setParameter("event", claim.id()).setParameter("root", claim.aggregateId()).getResultList();
        if (identity.isEmpty()) { throw inconsistent(); }
        UUID owner = UUID.fromString((String) identity.getFirst());
        // Order: exclusive hierarchy -> fresh root/frontier resource -> run/frontier -> final job fence.
        hierarchy.acquireExclusive(owner);
        checkActive(context);
        boolean valid = (Boolean) em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM folders f JOIN outbox_events e ON e.aggregate_id = f.id
                    WHERE e.id = :event AND f.id = :root AND f.owner_id = :owner
                    AND e.event_type = 'FOLDER_PURGE_REQUESTED' AND e.aggregate_type = 'FOLDER'
                    AND e.payload_version = 1 AND e.payload->>'ownerId' = CAST(f.owner_id AS text)
                    AND e.payload->>'folderId' = CAST(f.id AS text)
                    AND f.deleted_at IS NOT NULL AND f.purge_requested_at = e.created_at)
                """).setParameter("event", claim.id()).setParameter("root", claim.aggregateId())
                .setParameter("owner", owner).getSingleResult();
        if (!valid) { throw inconsistent(); }
        // Older committed events may initialize only from the matching original outbox/root intent.
        em.createNativeQuery("""
                INSERT INTO folder_purge_runs(event_id, owner_id, root_folder_id, requested_at)
                SELECT e.id, :owner, :root, e.created_at FROM outbox_events e WHERE e.id = :event
                ON CONFLICT (event_id) DO NOTHING
                """).setParameter("event", claim.id()).setParameter("owner", owner)
                .setParameter("root", claim.aggregateId()).executeUpdate();
        List<?> runRows = em.createNativeQuery("""
                SELECT r.phase FROM folder_purge_runs r JOIN folders f ON f.id = r.root_folder_id
                WHERE r.event_id = :event AND r.owner_id = :owner AND r.root_folder_id = :root
                    AND r.requested_at = f.purge_requested_at FOR UPDATE OF r
                """).setParameter("event", claim.id()).setParameter("owner", owner)
                .setParameter("root", claim.aggregateId()).getResultList();
        if (runRows.isEmpty()) { throw inconsistent(); }
        if (!"DISCOVER".equals(runRows.getFirst())) { return new Batch(false, true); }
        em.createNativeQuery("""
                INSERT INTO folder_purge_frontier(event_id, folder_id, owner_id, parent_folder_id, is_root, deleted_at)
                SELECT :event, id, owner_id, parent_folder_id, TRUE, deleted_at FROM folders WHERE id = :root
                ON CONFLICT (event_id, folder_id) DO NOTHING
                """).setParameter("event", claim.id()).setParameter("root", claim.aggregateId()).executeUpdate();
        List<?> frontiers = em.createNativeQuery("""
                SELECT folder_id, child_cursor, file_cursor, children_complete FROM folder_purge_frontier
                WHERE event_id = :event AND (NOT children_complete OR NOT files_complete)
                ORDER BY folder_id LIMIT 1 FOR UPDATE
                """).setParameter("event", claim.id()).getResultList();
        if (!frontiers.isEmpty()) {
            Object[] frontier = (Object[]) frontiers.getFirst();
            UUID folder = (UUID) frontier[0];
            validateFolder(claim.id(), folder, owner);
            if (!(Boolean) frontier[3]) {
                children(claim.id(), folder, owner, (UUID) frontier[1], pageSize, context);
            } else {
                files(claim.id(), folder, owner, (UUID) frontier[2], pageSize, context);
            }
        }
        boolean sealed = seal(claim.id());
        checkActive(context);
        jobs.checkpoint(claim, expectedVersion); // LAST; stale token/revision/lease rolls back the entire page.
        return new Batch(true, sealed);
    }

    private void validateFolder(UUID event, UUID folder, UUID owner) {
        boolean valid = (Boolean) em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM folder_purge_frontier m JOIN folders f ON f.id = m.folder_id
                    WHERE m.event_id = :event AND m.folder_id = :folder AND f.owner_id = :owner
                    AND f.parent_folder_id IS NOT DISTINCT FROM m.parent_folder_id
                    AND f.deleted_at IS NOT DISTINCT FROM m.deleted_at
                    AND (m.is_root OR f.purge_requested_at IS NULL))
                """).setParameter("event", event).setParameter("folder", folder)
                .setParameter("owner", owner).getSingleResult();
        if (!valid) { throw inconsistent(); }
    }

    private void children(UUID event, UUID folder, UUID owner, UUID cursor, int limit,
                          JobExecutionContext context) throws InterruptedException {
        var query = em.createNativeQuery("SELECT id, owner_id, purge_requested_at FROM folders "
                + "WHERE parent_folder_id = :folder " + (cursor == null ? "" : "AND id > :cursor ")
                + "ORDER BY id LIMIT :limit").setParameter("folder", folder).setParameter("limit", limit);
        if (cursor != null) { query.setParameter("cursor", cursor); }
        List<?> page = query.getResultList();
        UUID last = cursor;
        for (Object item : page) {
            checkActive(context);
            Object[] row = (Object[]) item;
            if (!owner.equals(row[1]) || row[2] != null) { throw inconsistent(); }
            last = (UUID) row[0];
            // A duplicate reached through an edge is a cycle/inconsistent parent, not a replay.
            // Transactional cursor advancement means legitimate restart never rediscovers this edge.
            em.createNativeQuery("""
                    INSERT INTO folder_purge_frontier(event_id, folder_id, owner_id, parent_folder_id, is_root, deleted_at)
                    SELECT :event, id, owner_id, parent_folder_id, FALSE, deleted_at FROM folders WHERE id = :id
                    """).setParameter("event", event).setParameter("id", last).executeUpdate();
        }
        advance(event, folder, "child_cursor", "children_complete", last, page.size() < limit);
    }

    private void files(UUID event, UUID folder, UUID owner, UUID cursor, int limit,
                       JobExecutionContext context) throws InterruptedException {
        var query = em.createNativeQuery("SELECT id, owner_id FROM files WHERE folder_id = :folder "
                + (cursor == null ? "" : "AND id > :cursor ") + "ORDER BY id LIMIT :limit")
                .setParameter("folder", folder).setParameter("limit", limit);
        if (cursor != null) { query.setParameter("cursor", cursor); }
        List<?> page = query.getResultList();
        UUID last = cursor;
        for (Object item : page) {
            checkActive(context);
            Object[] row = (Object[]) item;
            if (!owner.equals(row[1])) { throw inconsistent(); }
            last = (UUID) row[0];
            boolean allocationValid = (Boolean) em.createNativeQuery("""
                    SELECT NOT EXISTS (SELECT 1 FROM file_storage_objects a JOIN files f ON a.file_id = f.id
                        WHERE f.id = :id AND (a.owner_id <> f.owner_id OR a.storage_key <> f.storage_key))
                    """).setParameter("id", last).getSingleResult();
            if (!allocationValid) { throw inconsistent(); }
            em.createNativeQuery("""
                    INSERT INTO folder_purge_files(event_id, file_id, owner_id, folder_id, file_version, file_status,
                        file_created_at, file_updated_at, deleted_at, purge_requested_at, storage_key,
                        storage_namespace, endpoint, region, bucket, write_started, standalone_event_id)
                    SELECT :event, f.id, f.owner_id, f.folder_id, f.version, f.status, f.created_at, f.updated_at,
                        f.deleted_at, f.purge_requested_at, f.storage_key,
                        a.storage_namespace, a.endpoint, a.region, a.bucket, a.write_started, t.event_id
                    FROM files f LEFT JOIN file_storage_objects a ON a.file_id = f.id
                    LEFT JOIN file_purge_targets t ON t.file_id = f.id WHERE f.id = :id
                    """).setParameter("event", event).setParameter("id", last).executeUpdate();
        }
        advance(event, folder, "file_cursor", "files_complete", last, page.size() < limit);
    }

    private void advance(UUID event, UUID folder, String cursorColumn, String completeColumn,
                         UUID last, boolean complete) {
        // Column names are internal constants, never caller input. Avoid untyped null UUID parameters.
        var query = em.createNativeQuery("UPDATE folder_purge_frontier SET "
                + (last == null ? "" : cursorColumn + " = :cursor, ") + completeColumn + " = :complete "
                + "WHERE event_id = :event AND folder_id = :folder")
                .setParameter("complete", complete).setParameter("event", event).setParameter("folder", folder);
        if (last != null) { query.setParameter("cursor", last); }
        query.executeUpdate();
    }

    private boolean seal(UUID event) {
        boolean open = (Boolean) em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM folder_purge_frontier WHERE event_id = :event
                    AND (NOT children_complete OR NOT files_complete))
                """).setParameter("event", event).getSingleResult();
        if (open) { return false; }
        // Set-based integrity check, bounded by statement/transaction deadlines; no Java subtree materialization.
        boolean corrupt = (Boolean) em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM folder_purge_frontier m LEFT JOIN folders f ON f.id = m.folder_id
                    WHERE m.event_id = :event AND (f.id IS NULL OR f.owner_id <> m.owner_id
                        OR f.parent_folder_id IS DISTINCT FROM m.parent_folder_id
                        OR f.deleted_at IS DISTINCT FROM m.deleted_at
                        OR (NOT m.is_root AND f.purge_requested_at IS NOT NULL)))
                OR EXISTS (SELECT 1 FROM folder_purge_files m LEFT JOIN files f ON f.id = m.file_id
                    WHERE m.event_id = :event AND (f.id IS NULL OR f.owner_id <> m.owner_id
                        OR f.folder_id IS DISTINCT FROM m.folder_id OR f.storage_key <> m.storage_key))
                OR EXISTS (SELECT 1 FROM folder_purge_frontier p JOIN folders f ON f.parent_folder_id = p.folder_id
                    WHERE p.event_id = :event AND NOT EXISTS (SELECT 1 FROM folder_purge_frontier m
                        WHERE m.event_id = :event AND m.folder_id = f.id AND m.parent_folder_id = p.folder_id))
                OR EXISTS (SELECT 1 FROM folder_purge_frontier p JOIN files f ON f.folder_id = p.folder_id
                    WHERE p.event_id = :event AND NOT EXISTS (SELECT 1 FROM folder_purge_files m
                        WHERE m.event_id = :event AND m.file_id = f.id AND m.folder_id = p.folder_id))
                """).setParameter("event", event).getSingleResult();
        if (corrupt) { throw inconsistent(); }
        em.createNativeQuery("""
                UPDATE folder_purge_runs SET phase = 'DELETE_FILES', discovery_sealed_at = clock_timestamp()
                WHERE event_id = :event AND phase = 'DISCOVER'
                """).setParameter("event", event).executeUpdate();
        return true;
    }

    @Transactional(readOnly = true)
    public long checkpointVersion(ClaimedJob claim) {
        return ((Number) em.createNativeQuery("SELECT progress_version FROM background_jobs WHERE id = :id")
                .setParameter("id", claim.id()).getSingleResult()).longValue();
    }

    private IllegalStateException inconsistent() { return new IllegalStateException("Inconsistent folder purge membership"); }

    private void checkActive(JobExecutionContext context) throws InterruptedException {
        if (context != null) { context.checkActive(); }
    }
}
