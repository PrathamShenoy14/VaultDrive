package com.vaultdrive.consumer;

import jakarta.persistence.EntityManager;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Local operator only: strict normal ingestion of one authoritative source; no broker/storage calls. */
@Service
public class RetainedEventReplay {
    private final EntityManager em;
    private final NotificationIngestor purge;
    private final PermissionEventIngestor notifications;
    private final ObjectMapper mapper;
    public RetainedEventReplay(EntityManager em, NotificationIngestor purge, PermissionEventIngestor notifications, ObjectMapper mapper) {
        this.em=em; this.purge=purge; this.notifications=notifications; this.mapper=mapper;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void replay(UUID id) {
        em.createNativeQuery("SET LOCAL statement_timeout = '3s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '1s'").executeUpdate();
        var rows = em.createNativeQuery("""
                SELECT e.event_type, CASE WHEN octet_length(e.payload::text)<=60000 THEN
                  jsonb_build_object('eventId',e.id,'eventType',e.event_type,'aggregateType',e.aggregate_type,
                  'aggregateId',e.aggregate_id,'payloadVersion',e.payload_version,'createdAt',e.created_at,'payload',e.payload)::text END
                FROM outbox_events e WHERE e.id=:id AND e.delivery_status IN ('PUBLISHED','FAILED')
                """).setParameter("id",id).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("Replay requires a retained published or failed event");
        Object[] row = (Object[]) rows.getFirst();
        if (row[1] == null) throw new IllegalArgumentException("Source exceeds replay bounds");
        var headers = new MessageProperties(); headers.setMessageId(id.toString()); headers.setType((String) row[0]);
        var message = new Message(((String) row[1]).getBytes(StandardCharsets.UTF_8), headers);
        // Ingestors keep their normal independent commit and identity/source guards.
        switch ((String) row[0]) {
            case "FILE_PURGE_REQUESTED", "FOLDER_PURGE_REQUESTED" -> purge.accept(PurgeNotification.parse(message,mapper));
            case "PERMISSION_ACTIVITY_RECORDED" -> notifications.accept(PermissionEvent.parse(message,mapper));
            case "STORAGE_QUOTA_THRESHOLD_CROSSED" -> notifications.acceptQuota(QuotaEvent.parse(message,mapper));
            default -> throw new IllegalArgumentException("Unsupported retained event");
        }
    }
}
