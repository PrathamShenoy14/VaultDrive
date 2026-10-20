package com.vaultdrive.sharing;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.file.exception.InvalidFilePaginationException;
import com.vaultdrive.folder.dto.FolderResponse;
import com.vaultdrive.folder.dto.FolderPageResponse;
import com.vaultdrive.folder.dto.TrashPageResponse;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ResourceReadService {
    private final ResourceAccessRepository access;
    private final HierarchyCoordinator hierarchy;

    public ResourceReadService(ResourceAccessRepository access, HierarchyCoordinator hierarchy) {
        this.access = access;
        this.hierarchy = hierarchy;
    }

    public ResourceAccess file(UUID actor, UUID id) {
        return locked(actor, false, id);
    }

    /** Owner-only operations: visible collaborators get 403, others get 404. */
    public void requireOwner(UUID actor, boolean folder, UUID id) {
        UUID owner = access.owner(folder, id);
        if (!owner.equals(actor)) {
            hierarchy.acquireShared(owner);
            access.require(actor, folder, id, false);
            throw new AccessDeniedException("Owner permission required");
        }
        // The mutation still locks and freshly reads its owned row after this immutable identity check.
    }

    public UUID owner(boolean folder, UUID id) {
        return access.owner(folder, id);
    }

    /** Called after the owner hierarchy lock. File callers refresh before the authorization
     * snapshot and retain this version for their optimistic write. */
    public void refreshForMutation(Object entity) {
        access.refreshForMutation(entity);
    }

    /** Caller holds exclusive folder/shared file owner coordination in its mutation transaction. */
    public ResourceAccess requireEditor(UUID actor, boolean folder, UUID id, boolean trash) {
        ResourceAccess resource = access.require(actor, folder, id, false);
        if (resource.role() == ResourceRole.OWNER) return resource;
        if (resource.role() != ResourceRole.EDITOR) {
            throw new AccessDeniedException("Editor permission required");
        }
        if (trash) {
            // Authorize the target first. A private parent must produce 403 without exposing it.
            if (resource.parentId() == null || !resource.parentVisible()
                    || access.require(actor, true, resource.parentId(), false).role() == ResourceRole.VIEWER) {
                throw new AccessDeniedException("Editor permission on parent required");
            }
        }
        return resource;
    }

    public FolderResponse folder(UUID actor, UUID id) {
        return locked(actor, true, id).folderResponse();
    }

    private ResourceAccess locked(UUID actor, boolean folder, UUID id) {
        hierarchy.acquireShared(access.owner(folder, id));
        return access.require(actor, folder, id, false);
    }

    public FolderPageResponse folders(UUID actor, UUID parent, int page, int size) {
        validatePage(page, size);
        UUID owner = parent == null ? actor : locked(actor, true, parent).ownerId();
        if (parent == null) hierarchy.acquireShared(owner);
        List<?> rows = access.query(pageQuery("SELECT " + ResourceAccessRepository.projection(true) + """
                 FROM folders f
                CROSS JOIN LATERAL resolve_resource_access(:actor, NULL, f.id) a
                WHERE f.owner_id = :owner AND f.parent_folder_id IS NOT DISTINCT FROM CAST(:parent AS uuid)
                """, "lower(name) COLLATE \"C\", name COLLATE \"C\", id"))
                .setParameter("actor", actor).setParameter("owner", owner).setParameter("parent", parent)
                .setParameter("limit", size).setParameter("offset", (long) page * size).getResultList();
        long total = ((Number) ((Object[]) rows.getFirst())[14]).longValue();
        List<FolderResponse> content = new ArrayList<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            if (r[0] != null) content.add(ResourceAccessRepository.map(r).folderResponse());
        }
        return new FolderPageResponse(content, page, size, total, pages(total, size));
    }

    public FilePageResponse files(UUID actor, UUID folder, int page, int size) {
        validatePage(page, size);
        UUID owner = folder == null ? actor : locked(actor, true, folder).ownerId();
        if (folder == null) hierarchy.acquireShared(owner);
        List<?> rows = access.query(pageQuery("SELECT " + ResourceAccessRepository.projection(false) + """
                 FROM files f
                CROSS JOIN LATERAL resolve_resource_access(:actor, f.id, NULL) a
                WHERE f.owner_id = :owner AND a.parent_id IS NOT DISTINCT FROM CAST(:parent AS uuid)
                """, "name, id"))
                .setParameter("actor", actor).setParameter("owner", owner).setParameter("parent", folder)
                .setParameter("limit", size).setParameter("offset", (long) page * size).getResultList();
        long total = ((Number) ((Object[]) rows.getFirst())[14]).longValue();
        List<FileResponse> content = new ArrayList<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            if (r[0] != null) content.add(ResourceAccessRepository.map(r).fileResponse());
        }
        return new FilePageResponse(content, page, size, total, pages(total, size));
    }

    public record SharedEntry(String resourceType, Object resource) {}
    public record SharedPage(List<SharedEntry> content, int page, int size, long totalElements, int totalPages) {}

    public SharedPage sharedWithMe(UUID actor, int page, int size) {
        validatePage(page, size);
        // A single statement snapshot across owners. Filter access before ORDER/LIMIT and count.
        List<?> rows = access.query(pageQuery("""
                SELECT a.*, (a.effective_role = 'OWNER' OR EXISTS (SELECT 1 FROM resource_grants pg
                    WHERE pg.grantee_user_id = :actor AND pg.owner_id = a.owner_id AND pg.role = 'EDITOR'
                    AND pg.folder_id = ANY(CASE WHEN g.file_id IS NULL THEN a.folder_path[2:]
                                              ELSE a.folder_path END))) parent_editable,
                    CASE WHEN g.file_id IS NULL THEN 'FOLDER' ELSE 'FILE' END resource_type
                FROM resource_grants g
                CROSS JOIN LATERAL resolve_resource_access(:actor, g.file_id, g.folder_id) a
                WHERE g.grantee_user_id = :actor
                """, "name, id"))
                .setParameter("actor", actor).setParameter("limit", size)
                .setParameter("offset", (long) page * size).getResultList();
        long total = ((Number) ((Object[]) rows.getFirst())[15]).longValue();
        List<SharedEntry> content = new ArrayList<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            if (r[0] != null) {
                ResourceAccess a = ResourceAccessRepository.map(r);
                String type = (String) r[14];
                content.add(new SharedEntry(type, type.equals("FILE") ? a.fileResponse() : a.folderResponse()));
            }
        }
        return new SharedPage(content, page, size, total, pages(total, size));
    }

    /** Owner Trash, including its own pending roots but excluding every pending ancestor.
     * Count, eligibility and page are evaluated in one statement after hierarchy coordination. */
    @Transactional(readOnly = true, timeout = 5)
    public TrashPageResponse trash(UUID actor, boolean folder, int page, int size) {
        validatePage(page, size);
        access.query("SELECT set_config('statement_timeout', '3000', true)").getSingleResult();
        access.query("SELECT set_config('lock_timeout', '2000', true)").getSingleResult();
        hierarchy.acquireShared(actor);
        String table = folder ? "folders" : "files";
        String parent = folder ? "parent_folder_id" : "folder_id";
        String selection = """
                SELECT t.id, t.name, t.%s AS parent_id, t.deleted_at, t.purge_requested_at,
                %s AS purge_eligible
                FROM %s t
                CROSS JOIN LATERAL (
                    WITH RECURSIVE ancestry AS (
                        SELECT f.id, f.parent_folder_id, f.purge_requested_at, ARRAY[f.id] path, 1 depth
                        FROM folders f WHERE f.id = t.%s AND f.owner_id = :actor
                        UNION ALL
                        SELECT f.id, f.parent_folder_id, f.purge_requested_at, a.path || f.id, a.depth + 1
                        FROM ancestry a JOIN folders f ON f.id = a.parent_folder_id AND f.owner_id = :actor
                        WHERE a.depth < 256 AND NOT f.id = ANY(a.path)
                    )
                    SELECT NOT EXISTS (SELECT 1 FROM ancestry WHERE purge_requested_at IS NOT NULL)
                        AND (t.%s IS NULL OR EXISTS (SELECT 1 FROM ancestry WHERE parent_folder_id IS NULL)) ok
                ) barrier
                WHERE t.owner_id = :actor AND t.deleted_at IS NOT NULL AND barrier.ok %s
                """.formatted(parent, folder ? "NOT EXISTS (WITH RECURSIVE subtree AS ("
                        + "SELECT f.id, f.purge_requested_at FROM folders f WHERE f.id = t.id "
                        + "UNION SELECT f.id, f.purge_requested_at FROM folders f JOIN subtree s "
                        + "ON f.parent_folder_id = s.id WHERE f.owner_id = :actor) "
                        + "SELECT 1 FROM subtree WHERE purge_requested_at IS NOT NULL)" : "true",
                        table, parent, parent, folder ? "" : "AND t.status = 'READY'");
        List<?> rows = access.query(pageQuery(selection, "deleted_at DESC, id"))
                .setParameter("actor", actor).setParameter("limit", size)
                .setParameter("offset", (long) page * size).getResultList();
        long total = ((Number) ((Object[]) rows.getFirst())[6]).longValue();
        List<TrashPageResponse.Entry> content = new ArrayList<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            if (r[0] == null) continue;
            boolean pending = r[4] != null;
            content.add(new TrashPageResponse.Entry((UUID) r[0], folder ? "FOLDER" : "FILE", (String) r[1],
                    (UUID) r[2], ResourceAccessRepository.instant(r[3]), ResourceAccessRepository.instant(r[4]),
                    pending ? "PENDING_PURGE" : "TRASHED",
                    pending ? List.of() : Boolean.TRUE.equals(r[5]) ? List.of("RESTORE", "PURGE") : List.of("RESTORE")));
        }
        return new TrashPageResponse(content, page, size, total, pages(total, size));
    }

    private static String pageQuery(String selection, String order) {
        return "WITH accessible AS MATERIALIZED (" + selection + ") "
                + "SELECT a.*, totals.total FROM (SELECT count(*) total FROM accessible) totals "
                + "LEFT JOIN (SELECT * FROM accessible ORDER BY " + order
                + " LIMIT :limit OFFSET :offset) a ON true ORDER BY " + order;
    }

    private static int pages(long total, int size) {
        return (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidFilePaginationException("Page must be nonnegative and size between 1 and 100");
        }
    }
}
