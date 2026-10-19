package com.vaultdrive.consumer;

import com.vaultdrive.outbox.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PermissionEventIngestor {
    private final OutboxEventRepository outbox;
    private final EntityManager entityManager;
    public PermissionEventIngestor(OutboxEventRepository outbox, EntityManager entityManager) {
        this.outbox = outbox;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accept(PermissionEvent event) {
        var source = outbox.findById(event.id()).orElseThrow(() ->
                new InvalidNotification(InvalidNotification.Reason.UNKNOWN_EVENT, event.id()));
        if (!source.getEventType().equals("PERMISSION_ACTIVITY_RECORDED")
                || !source.getAggregateType().equals("PERMISSION_ACTIVITY")
                || source.getPayloadVersion() != 1 || !source.getAggregateId().equals(event.id())
                || !source.getCreatedAt().equals(event.createdAt())
                || !event.id().toString().equals(source.getPayload().get("activityEventId"))) {
            throw new InvalidNotification(InvalidNotification.Reason.EVENT_MISMATCH, event.id());
        }
        var rows = entityManager.createNativeQuery("SELECT event_type FROM permission_activity_events WHERE id = :id")
                .setParameter("id", event.id()).getResultList();
        if (rows.isEmpty()) throw new InvalidNotification(InvalidNotification.Reason.UNKNOWN_EVENT, event.id());
        // Links have no registered recipient. Owners and inherited descendants receive no fan-out.
        entityManager.createNativeQuery("""
                INSERT INTO sharing_notifications(id, source_event_id, recipient_user_id, event_type)
                SELECT :notification, id, recipient_id, event_type FROM permission_activity_events
                WHERE id = :id AND recipient_id IS NOT NULL AND recipient_id <> owner_id
                    AND event_type IN ('GRANT_CREATED', 'GRANT_ROLE_CHANGED', 'GRANT_REVOKED')
                ON CONFLICT (source_event_id, recipient_user_id) DO NOTHING
                """).setParameter("notification", UUID.randomUUID()).setParameter("id", event.id()).executeUpdate();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void quarantine(String digest, InvalidNotification invalid) {
        entityManager.createNativeQuery("""
                INSERT INTO notification_quarantine(body_sha256, event_id, reason_code)
                VALUES (:digest, :eventId, :reason) ON CONFLICT (body_sha256) DO NOTHING
                """).setParameter("digest", digest).setParameter("eventId", invalid.eventId())
                .setParameter("reason", invalid.reason().name()).executeUpdate();
    }
}
