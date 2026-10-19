package com.vaultdrive.sharing;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ShareLinkService {
    private final ResourceAccessRepository access;
    private final HierarchyCoordinator hierarchy;
    private final PermissionActivityService activity;

    public ShareLinkService(ResourceAccessRepository access, HierarchyCoordinator hierarchy, PermissionActivityService activity) {
        this.access = access;
        this.hierarchy = hierarchy;
        this.activity = activity;
    }

    public record Link(UUID id, Instant createdAt, Instant expiresAt, Instant revokedAt, long version) {}
    public record CreatedLink(Link link, String secret) {
        @Override public String toString() { return "CreatedLink[link=" + link + ", secret=REDACTED]"; }
    }
    public record PublicFile(String name, String contentType, long sizeBytes) {}
    // Internal authorization result, never serialized by a controller.
    public record AuthorizedFile(PublicFile metadata, String storageKey) {}

    public CreatedLink create(UUID actor, UUID file, Instant requestedExpiry) {
        ResourceAccess resource = owner(actor, file, true);
        if (!resource.active()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Resource is in Trash");
        Instant now = ResourceAccessRepository.instant(access.query("SELECT statement_timestamp()").getSingleResult());
        Instant expiry = requestedExpiry == null ? now.plus(24, ChronoUnit.HOURS)
                : requestedExpiry.truncatedTo(ChronoUnit.MICROS);
        if (!expiry.isAfter(now) || expiry.isAfter(now.plus(7, ChronoUnit.DAYS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expiry must be in the future and within seven days");
        }
        UUID id = UUID.randomUUID();
        String secret = ShareLinkTokens.generate();
        access.query("""
                INSERT INTO share_links(id, owner_id, file_id, token_hash, created_at, expires_at)
                VALUES (:id, :owner, :file, :hash, :created, :expiry)
                """).setParameter("id", id).setParameter("owner", actor).setParameter("file", file)
                .setParameter("hash", ShareLinkTokens.hash(secret)).setParameter("created", now)
                .setParameter("expiry", expiry).executeUpdate();
        activity.append(actor, false, resource, PermissionActivityType.LINK_CREATED, id, 0,
                null, null, null, null, expiry);
        return new CreatedLink(new Link(id, now, expiry, null, 0), secret);
    }

    public List<Link> list(UUID actor, UUID file) {
        owner(actor, file, false);
        List<?> rows = access.query("""
                SELECT id, created_at, expires_at, revoked_at, version FROM share_links
                WHERE file_id = :file ORDER BY created_at, id
                """).setParameter("file", file).getResultList();
        return rows.stream().map(row -> {
                    Object[] r = (Object[]) row;
                    return new Link((UUID) r[0], ResourceAccessRepository.instant(r[1]),
                            ResourceAccessRepository.instant(r[2]), r[3] == null ? null : ResourceAccessRepository.instant(r[3]),
                            ((Number) r[4]).longValue());
                }).toList();
    }

    public void revoke(UUID actor, UUID file, UUID link) {
        ResourceAccess resource = owner(actor, file, true);
        List<?> rows = access.query("""
                UPDATE share_links SET revoked_at = statement_timestamp(), version = version + 1
                WHERE id = :id AND file_id = :file AND revoked_at IS NULL
                RETURNING version, expires_at
                """).setParameter("id", link).setParameter("file", file).getResultList();
        if (!rows.isEmpty()) {
            Object[] r = (Object[]) rows.getFirst();
            activity.append(actor, false, resource, PermissionActivityType.LINK_REVOKED, link,
                    ((Number) r[0]).longValue(), null, null, null, null, ResourceAccessRepository.instant(r[1]));
        }
    }

    @Transactional(readOnly = true)
    public AuthorizedFile authorize(UUID link, String secret) {
        byte[] hash = ShareLinkTokens.hash(secret);
        List<?> owners = access.query("SELECT owner_id FROM share_links WHERE id = :id")
                .setParameter("id", link).getResultList();
        if (owners.isEmpty()) throw notFound();
        // Immutable identity only before the shared owner lock. All authority comes from the next snapshot.
        hierarchy.acquireShared((UUID) owners.getFirst());
        List<?> rows = access.query("""
                SELECT a.name, a.content_type, a.size_bytes, a.storage_key
                FROM share_links l
                CROSS JOIN LATERAL resolve_resource_access(l.owner_id, l.file_id, NULL) a
                WHERE l.id = :id AND l.token_hash = :hash AND l.revoked_at IS NULL
                  AND l.expires_at > statement_timestamp()
                """).setParameter("id", link).setParameter("hash", hash).getResultList();
        if (rows.isEmpty()) throw notFound();
        Object[] r = (Object[]) rows.getFirst();
        return new AuthorizedFile(new PublicFile((String) r[0], (String) r[1], ((Number) r[2]).longValue()), (String) r[3]);
    }

    private ResourceAccess owner(UUID actor, UUID file, boolean mutation) {
        UUID owner = access.owner(false, file);
        if (mutation) hierarchy.acquireExclusive(owner);
        else hierarchy.acquireShared(owner);
        ResourceAccess resource = access.require(actor, false, file, true);
        if (resource.role() != ResourceRole.OWNER) throw new AccessDeniedException("Owner permission required");
        return resource;
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found");
    }
}
