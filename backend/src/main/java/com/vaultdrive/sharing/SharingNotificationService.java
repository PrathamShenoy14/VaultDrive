package com.vaultdrive.sharing;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SharingNotificationService {
    public enum Type { GRANT_CREATED, GRANT_ROLE_CHANGED, GRANT_REVOKED, STORAGE_QUOTA_THRESHOLD_CROSSED }
    public record QuotaMetadata(int threshold, long logicalUsageBytes, long limitBytes, Instant occurredAt) {}
    public record Notification(UUID id, UUID sourceEventId, Type eventType, Instant createdAt,
            Instant readAt, boolean actionable, PermissionActivityService.ResourceType resourceType,
            UUID resourceId, String resourceName, ResourceRole newRole, QuotaMetadata quota) {}
    public record Page(List<Notification> content, int page, int size, long totalElements, long totalPages) {}
    public record UnreadCount(long unreadCount) {}

    private final ResourceAccessRepository access;
    public SharingNotificationService(ResourceAccessRepository access) { this.access = access; }

    @Transactional(readOnly = true)
    public Page list(UUID recipient, Boolean unread, Type type, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be nonnegative and size 1..100");
        }
        String where = "recipient_user_id = :recipient"
                + (unread == null ? "" : unread ? " AND read_at IS NULL" : " AND read_at IS NOT NULL")
                + (type == null ? "" : " AND event_type = :type");
        // One statement snapshot across owners. Scope precedes filters/count/page.
        // Current grant identity+revision suppress stale, reordered and regrant events.
        // The resolver gates display only; notifications never authorize an operation.
        var query = access.query("""
                WITH visible AS MATERIALIZED (SELECT * FROM sharing_notifications WHERE
                """ + where + """
                ), totals AS (SELECT count(*) total FROM visible), page AS (
                    SELECT * FROM visible ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset)
                SELECT n.id, n.source_event_id, n.event_type, n.created_at, n.read_at,
                    (a.id IS NOT NULL) actionable,
                    CASE WHEN a.id IS NOT NULL THEN e.resource_type END,
                    CASE WHEN a.id IS NOT NULL THEN e.resource_id END,
                    CASE WHEN a.id IS NOT NULL THEN e.resource_name END,
                    CASE WHEN a.id IS NOT NULL THEN e.new_role END, t.total,
                    q.threshold, q.logical_usage_bytes, q.limit_bytes, q.occurred_at
                FROM totals t LEFT JOIN page n ON true
                LEFT JOIN permission_activity_events e ON e.id = n.source_event_id
                    AND n.event_type <> 'STORAGE_QUOTA_THRESHOLD_CROSSED'
                LEFT JOIN storage_quota_events q ON q.id = n.source_event_id
                    AND n.event_type = 'STORAGE_QUOTA_THRESHOLD_CROSSED' AND q.owner_id = :recipient
                LEFT JOIN resource_grants g ON g.id = e.subject_id AND g.version = e.subject_version
                    AND g.grantee_user_id = :recipient AND g.role = e.new_role
                    AND e.event_type <> 'GRANT_REVOKED'
                LEFT JOIN LATERAL resolve_resource_access(:recipient, g.file_id, g.folder_id) a ON true
                ORDER BY n.created_at DESC, n.id DESC
                """).setParameter("recipient", recipient).setParameter("size", size)
                .setParameter("offset", (long) page * size);
        if (type != null) query.setParameter("type", type.name());
        List<?> rows = query.getResultList();
        long total = ((Number) ((Object[]) rows.getFirst())[10]).longValue();
        List<Notification> content = new ArrayList<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            if (r[0] == null) continue;
            content.add(new Notification((UUID) r[0], (UUID) r[1], Type.valueOf((String) r[2]),
                    ResourceAccessRepository.instant(r[3]), r[4] == null ? null : ResourceAccessRepository.instant(r[4]),
                    (Boolean) r[5], r[6] == null ? null : PermissionActivityService.ResourceType.valueOf((String) r[6]),
                    (UUID) r[7], (String) r[8], r[9] == null ? null : ResourceRole.valueOf((String) r[9]),
                    r[11] == null ? null : new QuotaMetadata(((Number) r[11]).intValue(),
                            ((Number) r[12]).longValue(), ((Number) r[13]).longValue(), ResourceAccessRepository.instant(r[14]))));
        }
        return new Page(List.copyOf(content), page, size, total, (total + size - 1) / size);
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(UUID recipient) {
        return new UnreadCount(((Number) access.query("""
                SELECT count(*) FROM sharing_notifications WHERE recipient_user_id = :recipient AND read_at IS NULL
                """).setParameter("recipient", recipient).getSingleResult()).longValue());
    }

    @Transactional
    public void markRead(UUID recipient, UUID id) {
        // Atomic and monotonic even for concurrent identical requests; no read/modify race.
        int found = access.query("""
                UPDATE sharing_notifications SET read_at = coalesce(read_at, greatest(clock_timestamp(), created_at))
                WHERE id = :id AND recipient_user_id = :recipient
                """).setParameter("id", id).setParameter("recipient", recipient).executeUpdate();
        if (found == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
    }
}
