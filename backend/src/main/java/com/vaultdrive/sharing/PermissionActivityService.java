package com.vaultdrive.sharing;

import com.vaultdrive.outbox.OutboxEvent;
import com.vaultdrive.outbox.OutboxWriter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PermissionActivityService {
    private final ResourceAccessRepository access;
    private final OutboxWriter outbox;

    public PermissionActivityService(ResourceAccessRepository access, OutboxWriter outbox) {
        this.access = access;
        this.outbox = outbox;
    }

    public enum ResourceType { FILE, FOLDER }
    public record Activity(UUID id, UUID actorId, String actorDisplayName, UUID ownerId,
                           ResourceType resourceType, UUID resourceId, String resourceName,
                           PermissionActivityType eventType, UUID subjectId, long subjectVersion,
                           UUID recipientId, String recipientEmail, ResourceRole oldRole,
                           ResourceRole newRole, Instant linkExpiresAt, Instant occurredAt) {}
    public record ActivityPage(List<Activity> content, int page, int size, long totalElements, long totalPages) {}

    // Called only after the existing exclusive owner hierarchy lock, fresh
    // authorization and a successful mutation. No independent commit or I/O.
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID actor, boolean folder, ResourceAccess resource, PermissionActivityType type,
                       UUID subject, long version, UUID recipient, String email,
                       ResourceRole oldRole, ResourceRole newRole, Instant expiry) {
        UUID id = UUID.randomUUID();
        Instant occurredAt = ResourceAccessRepository.instant(access.query("""
                INSERT INTO permission_activity_events(id, actor_id, actor_display_name, owner_id,
                    resource_type, resource_id, resource_name, event_type, subject_id, subject_version,
                    recipient_id, recipient_email, old_role, new_role, link_expires_at)
                SELECT :id, :actor, u.display_name, :owner, :resourceType, :resource, :name,
                    :type, :subject, :version, :recipient, :email, :oldRole, :newRole, :expiry
                FROM users u WHERE u.id = :actor
                RETURNING occurred_at
                """).setParameter("id", id).setParameter("actor", actor).setParameter("owner", resource.ownerId())
                .setParameter("resourceType", folder ? "FOLDER" : "FILE").setParameter("resource", resource.id())
                .setParameter("name", resource.name()).setParameter("type", type.name())
                .setParameter("subject", subject).setParameter("version", version)
                .setParameter("recipient", recipient).setParameter("email", email)
                .setParameter("oldRole", oldRole == null ? null : oldRole.name())
                .setParameter("newRole", newRole == null ? null : newRole.name())
                .setParameter("expiry", expiry).getSingleResult());
        outbox.append(OutboxEvent.permissionActivityRecorded(id, occurredAt));
    }

    @Transactional(readOnly = true)
    public ActivityPage list(UUID actor, ResourceType resourceType, UUID resourceId,
                             PermissionActivityType eventType, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (resourceId != null && resourceType == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Page must be nonnegative, size 1..100, and resourceId requires resourceType");
        }
        // Owner scope is the authorization predicate, applied before count/offset.
        // No current/historical grants, live resource names or ancestry are joined.
        String where = "owner_id = :actor"
                + (resourceType == null ? "" : " AND resource_type = :resourceType")
                + (resourceId == null ? "" : " AND resource_id = :resourceId")
                + (eventType == null ? "" : " AND event_type = :eventType");
        var query = access.query("""
                WITH visible AS MATERIALIZED (SELECT * FROM permission_activity_events WHERE
                """ + where + """
                ), totals AS (SELECT count(*) total FROM visible)
                SELECT p.id, p.actor_id, p.actor_display_name, p.owner_id, p.resource_type,
                    p.resource_id, p.resource_name, p.event_type, p.subject_id, p.subject_version,
                    p.recipient_id, p.recipient_email, p.old_role, p.new_role, p.link_expires_at,
                    p.occurred_at, t.total
                FROM totals t LEFT JOIN LATERAL (SELECT * FROM visible
                    ORDER BY occurred_at DESC, id DESC LIMIT :size OFFSET :offset) p ON true
                ORDER BY p.occurred_at DESC, p.id DESC
                """).setParameter("actor", actor).setParameter("size", size).setParameter("offset", (long) page * size);
        if (resourceType != null) query.setParameter("resourceType", resourceType.name());
        if (resourceId != null) query.setParameter("resourceId", resourceId);
        if (eventType != null) query.setParameter("eventType", eventType.name());
        List<?> rows = query.getResultList();
        List<Activity> content = new ArrayList<>();
        long total = ((Number) ((Object[]) rows.getFirst())[16]).longValue();
        for (Object value : rows) {
            Object[] r = (Object[]) value;
            if (r[0] == null) continue;
            content.add(new Activity((UUID) r[0], (UUID) r[1], (String) r[2], (UUID) r[3],
                    ResourceType.valueOf((String) r[4]), (UUID) r[5], (String) r[6],
                    PermissionActivityType.valueOf((String) r[7]), (UUID) r[8], ((Number) r[9]).longValue(),
                    (UUID) r[10], (String) r[11], r[12] == null ? null : ResourceRole.valueOf((String) r[12]),
                    r[13] == null ? null : ResourceRole.valueOf((String) r[13]),
                    r[14] == null ? null : ResourceAccessRepository.instant(r[14]), ResourceAccessRepository.instant(r[15])));
        }
        return new ActivityPage(List.copyOf(content), page, size, total, (total + size - 1) / size);
    }
}
