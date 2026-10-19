package com.vaultdrive.sharing;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.file.exception.InvalidFilePaginationException;
import com.vaultdrive.folder.dto.FolderResponse;
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

    /** M1 mutations stay owner-only; visible collaborators get 403, others get 404. */
    public void requireOwner(UUID actor, boolean folder, UUID id) {
        UUID owner = access.owner(folder, id);
        if (!owner.equals(actor)) {
            hierarchy.acquireShared(owner);
            access.require(actor, folder, id, false);
            throw new AccessDeniedException("Owner permission required");
        }
        // The mutation still locks and freshly reads its owned row after this immutable identity check.
    }

    public FolderResponse folder(UUID actor, UUID id) {
        return locked(actor, true, id).folderResponse();
    }

    private ResourceAccess locked(UUID actor, boolean folder, UUID id) {
        hierarchy.acquireShared(access.owner(folder, id));
        return access.require(actor, folder, id, false);
    }

    public List<FolderResponse> folders(UUID actor, UUID parent) {
        UUID owner = parent == null ? actor : locked(actor, true, parent).ownerId();
        if (parent == null) hierarchy.acquireShared(owner);
        List<?> rows = access.query("""
                SELECT a.* FROM folders f
                CROSS JOIN LATERAL resolve_resource_access(:actor, NULL, f.id) a
                WHERE f.owner_id = :owner AND f.parent_folder_id IS NOT DISTINCT FROM CAST(:parent AS uuid)
                """).setParameter("actor", actor).setParameter("owner", owner)
                .setParameter("parent", parent).getResultList();
        // Preserve the existing Java case-insensitive ordering, including Unicode case pairs.
        return rows.stream().map(r -> ResourceAccessRepository.map((Object[]) r).folderResponse())
                .sorted(java.util.Comparator.comparing(FolderResponse::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(FolderResponse::name).thenComparing(FolderResponse::id)).toList();
    }

    public FilePageResponse files(UUID actor, UUID folder, int page, int size) {
        validatePage(page, size);
        UUID owner = folder == null ? actor : locked(actor, true, folder).ownerId();
        if (folder == null) hierarchy.acquireShared(owner);
        List<?> rows = access.query(pageQuery("""
                SELECT a.* FROM files f
                CROSS JOIN LATERAL resolve_resource_access(:actor, f.id, NULL) a
                WHERE f.owner_id = :owner AND a.parent_id IS NOT DISTINCT FROM CAST(:parent AS uuid)
                """, "name, id"))
                .setParameter("actor", actor).setParameter("owner", owner).setParameter("parent", folder)
                .setParameter("limit", size).setParameter("offset", (long) page * size).getResultList();
        long total = ((Number) ((Object[]) rows.getFirst())[13]).longValue();
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
                SELECT a.*, CASE WHEN g.file_id IS NULL THEN 'FOLDER' ELSE 'FILE' END resource_type
                FROM resource_grants g
                CROSS JOIN LATERAL resolve_resource_access(:actor, g.file_id, g.folder_id) a
                WHERE g.grantee_user_id = :actor
                """, "name, id"))
                .setParameter("actor", actor).setParameter("limit", size)
                .setParameter("offset", (long) page * size).getResultList();
        long total = ((Number) ((Object[]) rows.getFirst())[14]).longValue();
        List<SharedEntry> content = new ArrayList<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            if (r[0] != null) {
                ResourceAccess a = ResourceAccessRepository.map(r);
                String type = (String) r[13];
                content.add(new SharedEntry(type, type.equals("FILE") ? a.fileResponse() : a.folderResponse()));
            }
        }
        return new SharedPage(content, page, size, total, pages(total, size));
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
