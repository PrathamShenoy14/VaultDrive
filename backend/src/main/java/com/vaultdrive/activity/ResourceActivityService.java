package com.vaultdrive.activity;

import com.vaultdrive.file.StoredFile;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.sharing.ResourceAccessRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local PostgreSQL history only; no outbox, broker, or storage dependency. */
@Service
public class ResourceActivityService {
    public enum ResourceType { FILE, FOLDER }
    public enum ActorAttribution { CALLER, UPLOAD_INITIATOR, DELETION_REQUESTER }
    public enum EventType { UPLOAD_COMPLETED, COPIED, FOLDER_CREATED, RENAMED, MOVED, TRASHED, RESTORED, PERMANENTLY_DELETED }
    public record Snapshot(UUID id, UUID ownerId, ResourceType type, String name, UUID parentId,
                           boolean trashed, String status) {
        public static Snapshot of(StoredFile f) {
            return new Snapshot(f.getId(), f.getOwnerId(), ResourceType.FILE, f.getName(), f.getFolderId(),
                    f.getDeletedAt() != null, f.getStatus().name());
        }
        public static Snapshot of(Folder f) {
            return new Snapshot(f.getId(), f.getOwnerId(), ResourceType.FOLDER, f.getName(), f.getParentFolderId(),
                    f.getDeletedAt() != null, null);
        }
    }
    public record Activity(UUID id, UUID actorId, String actorDisplayName, ActorAttribution actorAttribution, UUID ownerId,
                           ResourceType resourceType, UUID resourceId, String resourceName, EventType eventType,
                           String beforeName, String afterName, UUID beforeParentId, UUID afterParentId,
                           Boolean beforeTrashed, Boolean afterTrashed, String beforeStatus, String afterStatus,
                           Instant occurredAt) {}
    public record ActivityPage(List<Activity> content, int page, int size, long totalElements, long totalPages) {}
    private final EntityManager em;
    public ResourceActivityService(EntityManager em) { this.em = em; }

    // Caller holds the existing coordination and has successfully flushed its
    // transition. Rollback (including any later job fence) rolls back history too.
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID actor, EventType event, Snapshot before, Snapshot after) {
        append(actor, null, event == EventType.PERMANENTLY_DELETED ? ActorAttribution.DELETION_REQUESTER : ActorAttribution.CALLER,
                event, before, after);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void copied(UUID owner, String actorName, UUID file) {
        append(owner, actorName, ActorAttribution.CALLER, EventType.COPIED, null, snapshot(ResourceType.FILE, file));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void copiedFolder(UUID owner, String actorName, UUID folder) {
        append(owner, actorName, ActorAttribution.CALLER, EventType.COPIED, null, snapshot(ResourceType.FOLDER, folder));
    }

    private void append(UUID actor, String actorName, ActorAttribution attribution, EventType event, Snapshot before, Snapshot after) {
        Snapshot resource = after == null ? before : after;
        em.createNativeQuery("""
                INSERT INTO resource_activity_events(id, actor_id, actor_display_name, actor_attribution, owner_id,
                    resource_type, resource_id, resource_name, event_type, before_name, after_name,
                    before_parent_id, after_parent_id, before_trashed, after_trashed, before_status, after_status)
                VALUES (:id, :actor, coalesce(cast(:actorName AS varchar),
                    (SELECT display_name FROM users WHERE id = :actor), '[deleted account]'), :attribution, :owner,
                    :type, :resource, :name, :event, :beforeName, :afterName,
                    :beforeParent, :afterParent, :beforeTrashed, :afterTrashed, :beforeStatus, :afterStatus)
                """).setParameter("id", UUID.randomUUID()).setParameter("actor", actor).setParameter("actorName", actorName)
                .setParameter("attribution", attribution.name()).setParameter("owner", resource.ownerId()).setParameter("type", resource.type().name())
                .setParameter("resource", resource.id()).setParameter("name", resource.name()).setParameter("event", event.name())
                .setParameter("beforeName", before == null ? null : before.name()).setParameter("afterName", after == null ? null : after.name())
                .setParameter("beforeParent", before == null ? null : before.parentId()).setParameter("afterParent", after == null ? null : after.parentId())
                .setParameter("beforeTrashed", before == null ? null : before.trashed()).setParameter("afterTrashed", after == null ? null : after.trashed())
                .setParameter("beforeStatus", before == null ? null : before.status()).setParameter("afterStatus", after == null ? null : after.status())
                .executeUpdate();
    }

    /** Fresh scalar snapshot under the caller's existing resource lock, avoiding JPA cache state. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot snapshot(ResourceType type, UUID id) {
        Object[] r = (Object[]) em.createNativeQuery(type == ResourceType.FILE
                ? "SELECT owner_id, name, folder_id, deleted_at IS NOT NULL, status FROM files WHERE id = :id"
                : "SELECT owner_id, name, parent_folder_id, deleted_at IS NOT NULL, cast(NULL AS varchar) FROM folders WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        return new Snapshot(id, (UUID) r[0], type, (String) r[1], (UUID) r[2], (Boolean) r[3], (String) r[4]);
    }

    /** Only the winning UPLOADING -> READY CAS calls this, never READY replay. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void uploadCompleted(UUID file) {
        Snapshot after = snapshot(ResourceType.FILE, file);
        List<?> actors = em.createNativeQuery("""
                SELECT coalesce(c.activity_actor_id, s.initiator_user_id), c.activity_actor_display_name
                FROM upload_sessions s JOIN multipart_completions c ON c.session_id = s.id WHERE s.file_id = :file
                """).setParameter("file", file).getResultList();
        UUID actor = after.ownerId(); String actorName = null; // Historical SERVER_PUT uploader was the owner.
        if (actors.isEmpty()) {
            actors = em.createNativeQuery("SELECT initiator_id,actor_display_name FROM empty_file_creations WHERE file_id=:file")
                    .setParameter("file",file).getResultList();
        }
        if (!actors.isEmpty()) { Object[] a = (Object[]) actors.getFirst(); actor = (UUID) a[0]; actorName = (String) a[1]; }
        append(actor, actorName, actorName == null ? ActorAttribution.UPLOAD_INITIATOR : ActorAttribution.CALLER, EventType.UPLOAD_COMPLETED,
                new Snapshot(file, after.ownerId(), after.type(), after.name(), after.parentId(), false, "UPLOADING"), after);
    }

    @Transactional(readOnly = true, timeout = 5)
    public ActivityPage list(UUID actor, ResourceType resourceType, UUID resourceId, EventType eventType,
                             UUID actorId, Instant from, Instant to, int page, int size) {
        Instant earliest = Instant.parse("0001-01-01T00:00:00Z"), latest = Instant.parse("9999-12-31T23:59:59.999999Z");
        if (page < 0 || size < 1 || size > 100 || (resourceId != null && resourceType == null)
                || (from != null && (from.isBefore(earliest) || from.isAfter(latest)))
                || (to != null && (to.isBefore(earliest) || to.isAfter(latest)))
                || (from != null && to != null && from.isAfter(to))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid activity parameters");
        }
        em.createNativeQuery("SELECT set_config('statement_timeout', '3000', true)").getSingleResult();
        em.createNativeQuery("SELECT set_config('lock_timeout', '2000', true)").getSingleResult();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("owner", actor);
        StringBuilder where = new StringBuilder("owner_id = :owner");
        filter(where, params, "resource_type = :type", "type", resourceType == null ? null : resourceType.name());
        filter(where, params, "resource_id = :resource", "resource", resourceId);
        filter(where, params, "event_type = :event", "event", eventType == null ? null : eventType.name());
        filter(where, params, "actor_id = :actor", "actor", actorId);
        filter(where, params, "occurred_at >= :from", "from", from);
        filter(where, params, "occurred_at <= :to", "to", to);
        var q = em.createNativeQuery("""
                WITH visible AS MATERIALIZED (SELECT * FROM resource_activity_events WHERE
                """ + where + """
                ) SELECT p.id, p.actor_id, p.actor_display_name, p.owner_id, p.resource_type,
                    p.resource_id, p.resource_name, p.event_type, p.before_name, p.after_name,
                    p.before_parent_id, p.after_parent_id, p.before_trashed, p.after_trashed,
                    p.before_status, p.after_status, p.occurred_at, p.actor_attribution, t.total
                FROM (SELECT count(*) total FROM visible) t LEFT JOIN LATERAL
                    (SELECT * FROM visible ORDER BY occurred_at DESC, id DESC LIMIT :size OFFSET :offset) p ON true
                ORDER BY p.occurred_at DESC, p.id DESC
                """).setParameter("size", size).setParameter("offset", (long) page * size);
        params.forEach(q::setParameter);
        List<?> rows = q.getResultList();
        long total = ((Number) ((Object[]) rows.getFirst())[18]).longValue();
        List<Activity> content = new ArrayList<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            if (r[0] == null) continue;
            content.add(new Activity((UUID) r[0], (UUID) r[1], (String) r[2], ActorAttribution.valueOf((String) r[17]), (UUID) r[3],
                    ResourceType.valueOf((String) r[4]), (UUID) r[5], (String) r[6], EventType.valueOf((String) r[7]),
                    (String) r[8], (String) r[9], (UUID) r[10], (UUID) r[11], (Boolean) r[12], (Boolean) r[13],
                    (String) r[14], (String) r[15], ResourceAccessRepository.instant(r[16])));
        }
        return new ActivityPage(List.copyOf(content), page, size, total, (total + size - 1) / size);
    }

    private static void filter(StringBuilder sql, Map<String, Object> params, String clause, String name, Object value) {
        if (value != null) { sql.append(" AND ").append(clause); params.put(name, value); }
    }
}
