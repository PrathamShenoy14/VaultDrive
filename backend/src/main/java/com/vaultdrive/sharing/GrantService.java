package com.vaultdrive.sharing;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class GrantService {
    private final ResourceAccessRepository access;
    private final HierarchyCoordinator hierarchy;
    private final UserRepository users;

    public GrantService(ResourceAccessRepository access, HierarchyCoordinator hierarchy, UserRepository users) {
        this.access = access;
        this.hierarchy = hierarchy;
        this.users = users;
    }

    public record Grant(UUID id, UUID fileId, UUID folderId, UUID granteeUserId, String email,
                        ResourceRole role, long version, Instant createdAt, Instant updatedAt) {}
    public record GrantList(List<Grant> direct, List<Grant> inherited) {}

    public Grant create(UUID actor, boolean folder, UUID target, String email, ResourceRole role) {
        ResourceAccess resource = owner(actor, folder, target, true);
        requireActive(resource);
        requireGrantRole(role);
        if (email == null || email.isBlank()) throw badRequest("Registered recipient email is required");
        // The target is authorized before exact recipient lookup; no directory API.
        User recipient = users.findByEmailIgnoreCase(email.strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> badRequest("Registered recipient not found"));
        if (recipient.getId().equals(actor)) throw badRequest("Cannot grant access to the owner");
        UUID id = UUID.randomUUID();
        int inserted = access.query("""
                INSERT INTO resource_grants(id, owner_id, file_id, folder_id, grantee_user_id, role)
                VALUES (:id, :owner, :file, :folder, :recipient, :role) ON CONFLICT DO NOTHING
                """).setParameter("id", id).setParameter("owner", actor)
                .setParameter("file", folder ? null : target).setParameter("folder", folder ? target : null)
                .setParameter("recipient", recipient.getId()).setParameter("role", role.name()).executeUpdate();
        if (inserted == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Direct grant already exists");
        return direct(folder, target, id);
    }

    public GrantList list(UUID actor, boolean folder, UUID target) {
        owner(actor, folder, target, false);
        List<Grant> rows = grants(access.query("""
                SELECT g.id, g.file_id, g.folder_id, g.grantee_user_id, u.email, g.role, g.version,
                       g.created_at, g.updated_at
                FROM resolve_resource_access(:actor, :file, :folder, true) a
                JOIN resource_grants g ON g.owner_id = a.owner_id AND
                    (g.file_id = :file OR g.folder_id = ANY(a.folder_path))
                JOIN users u ON u.id = g.grantee_user_id ORDER BY g.created_at, g.id
                """).setParameter("actor", actor).setParameter("file", folder ? null : target)
                .setParameter("folder", folder ? target : null).getResultList());
        return new GrantList(rows.stream().filter(g -> isDirect(g, folder, target)).toList(),
                rows.stream().filter(g -> !isDirect(g, folder, target)).toList());
    }

    public Grant update(UUID actor, boolean folder, UUID target, UUID grantId, ResourceRole role, Long version) {
        ResourceAccess resource = owner(actor, folder, target, true);
        requireGrantRole(role);
        if (version == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
        Grant previous = direct(folder, target, grantId);
        if (previous.version() != version) throw stale();
        if (role.ordinal() > previous.role().ordinal()) requireActive(resource);
        if (role == previous.role()) return previous;
        int updated = access.query("""
                UPDATE resource_grants SET role = :role, version = version + 1, updated_at = statement_timestamp()
                WHERE id = :id AND version = :version
                """).setParameter("role", role.name()).setParameter("id", grantId)
                .setParameter("version", version).executeUpdate();
        if (updated != 1) throw stale();
        return direct(folder, target, grantId);
    }

    public void revoke(UUID actor, boolean folder, UUID target, UUID grantId) {
        owner(actor, folder, target, true);
        access.query("DELETE FROM resource_grants WHERE id = :id AND "
                + (folder ? "folder_id" : "file_id") + " = :target")
                .setParameter("id", grantId).setParameter("target", target).executeUpdate();
    }

    private ResourceAccess owner(UUID actor, boolean folder, UUID target, boolean mutation) {
        UUID owner = access.owner(folder, target);
        if (mutation) hierarchy.acquireExclusive(owner);
        else hierarchy.acquireShared(owner);
        ResourceAccess resource = access.require(actor, folder, target, true);
        if (resource.role() != ResourceRole.OWNER) throw new AccessDeniedException("Owner permission required");
        return resource;
    }

    private Grant direct(boolean folder, UUID target, UUID grantId) {
        List<Grant> rows = grants(access.query("""
                SELECT g.id, g.file_id, g.folder_id, g.grantee_user_id, u.email, g.role, g.version,
                       g.created_at, g.updated_at FROM resource_grants g JOIN users u ON u.id = g.grantee_user_id
                WHERE g.id = :id AND
                """ + (folder ? "g.folder_id" : "g.file_id") + " = :target")
                .setParameter("id", grantId).setParameter("target", target).getResultList());
        if (rows.isEmpty()) throw ResourceAccessRepository.notFound(folder);
        return rows.getFirst();
    }

    private static boolean isDirect(Grant grant, boolean folder, UUID target) {
        return target.equals(folder ? grant.folderId() : grant.fileId());
    }

    private static List<Grant> grants(List<?> rows) {
        return rows.stream().map(row -> {
            Object[] r = (Object[]) row;
            return new Grant((UUID) r[0], (UUID) r[1], (UUID) r[2], (UUID) r[3], (String) r[4],
                    ResourceRole.valueOf((String) r[5]), ((Number) r[6]).longValue(),
                    ResourceAccessRepository.instant(r[7]), ResourceAccessRepository.instant(r[8]));
        }).toList();
    }

    private static void requireActive(ResourceAccess resource) {
        if (!resource.active()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Resource is in Trash");
    }

    private static void requireGrantRole(ResourceRole role) {
        if (role == null || role == ResourceRole.OWNER) throw badRequest("Role must be VIEWER or EDITOR");
    }

    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Grant was modified");
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
