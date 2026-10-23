package com.vaultdrive.consumer;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.JobType;
import jakarta.persistence.EntityManager;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MissingJobReconciliationTransactions {
    private final EntityManager em;
    private final NotificationIngestor ingestor;
    private final HierarchyCoordinator hierarchy;
    private final ObjectMapper mapper;
    private final MissingJobReconciliationProperties properties;

    public MissingJobReconciliationTransactions(EntityManager em, NotificationIngestor ingestor,
            HierarchyCoordinator hierarchy, ObjectMapper mapper, MissingJobReconciliationProperties properties) {
        this.em = em;
        this.ingestor = ingestor;
        this.hierarchy = hierarchy;
        this.mapper = mapper;
        this.properties = properties;
    }

    public record Cursor(Instant publishedAt, UUID eventId) { }
    public enum Outcome { CREATED, ALREADY_PRESENT, SKIPPED }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public List<Cursor> scan(Cursor after, int limit) {
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("Page must be 1..100"); }
        bounds();
        var query = em.createNativeQuery("""
                SELECT e.published_at, e.id FROM outbox_events e
                WHERE e.delivery_status = 'PUBLISHED'
                  AND e.event_type NOT IN ('PERMISSION_ACTIVITY_RECORDED','STORAGE_QUOTA_THRESHOLD_CROSSED')
                  AND e.published_at <= clock_timestamp() - :delay * interval '1 millisecond'
                  AND NOT EXISTS (SELECT 1 FROM background_jobs j WHERE j.id = e.id)
                """ + (after == null ? "" : " AND (e.published_at, e.id) > (:published, :id)")
                + " ORDER BY e.published_at, e.id LIMIT :limit")
                .setParameter("delay", properties.eligibilityDelay().toMillis()).setParameter("limit", limit);
        if (after != null) {
            query.setParameter("published", after.publishedAt()).setParameter("id", after.eventId());
        }
        return query.getResultList().stream().map(value -> {
            Object[] row = (Object[]) value;
            return new Cursor((Instant) row[0], (UUID) row[1]);
        }).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Outcome ingest(UUID id) {
        bounds();
        // Bound payload materialization, preserve JSON field types, and use the consumer's
        // strict parser. An oversized/malformed historical source remains blocked in place.
        List<?> rows = em.createNativeQuery("""
                SELECT CASE WHEN octet_length(e.payload::text) <= 60000 THEN
                    jsonb_build_object('eventId', e.id, 'eventType', e.event_type,
                    'aggregateType', e.aggregate_type, 'aggregateId', e.aggregate_id,
                    'payloadVersion', e.payload_version, 'createdAt', e.created_at,
                    'payload', e.payload)::text END
                FROM outbox_events e WHERE e.id = :id AND e.delivery_status = 'PUBLISHED'
                  AND e.event_type NOT IN ('PERMISSION_ACTIVITY_RECORDED','STORAGE_QUOTA_THRESHOLD_CROSSED')
                  AND e.published_at <= clock_timestamp() - :delay * interval '1 millisecond'
                  AND NOT EXISTS (SELECT 1 FROM notification_quarantine q WHERE q.event_id = e.id)
                """).setParameter("id", id).setParameter("delay", properties.eligibilityDelay().toMillis())
                .getResultList();
        if (rows.isEmpty() || rows.getFirst() == null) { return Outcome.SKIPPED; }
        var messageProperties = new MessageProperties();
        messageProperties.setMessageId(id.toString());
        PurgeNotification notification;
        try {
            notification = PurgeNotification.parse(new Message(
                    ((String) rows.getFirst()).getBytes(StandardCharsets.UTF_8), messageProperties), mapper);
        } catch (InvalidNotification invalid) { return Outcome.SKIPPED; }

        // No namespace mutation. Lock order: shared owner hierarchy -> fresh resource
        // row/read -> job identity insert. Never acquire resources after job insertion.
        hierarchy.acquireShared(notification.ownerId());
        boolean file = notification.jobType() == JobType.FILE_PURGE;
        em.createNativeQuery("SELECT id FROM " + (file ? "files" : "folders")
                + " WHERE id = :id FOR SHARE").setParameter("id", notification.aggregateId()).getResultList();
        if (!intentMatches(notification, file)) { return Outcome.SKIPPED; }
        return ingestor.acceptReconciled(notification) ? Outcome.CREATED : Outcome.ALREADY_PRESENT;
    }

    private boolean intentMatches(PurgeNotification n, boolean file) {
        String proof = file ? """
                SELECT 1 FROM file_purge_targets t
                JOIN file_storage_objects a ON a.file_id = t.file_id AND a.owner_id = t.owner_id
                    AND a.storage_key = t.storage_key AND a.storage_namespace = t.storage_namespace
                    AND a.endpoint = t.endpoint AND a.region = t.region AND a.bucket = t.bucket
                JOIN files f ON f.id = t.file_id AND f.owner_id = t.owner_id
                    AND f.storage_key = t.storage_key AND f.version = t.file_version
                    AND f.status = 'READY' AND f.deleted_at IS NOT NULL
                    AND f.purge_requested_at = t.requested_at
                WHERE t.event_id = :event AND t.file_id = :aggregate AND t.owner_id = :owner
                    AND t.requested_at = :created AND a.write_started
                """ : """
                SELECT 1 FROM folder_purge_runs r JOIN folders f ON f.id = r.root_folder_id
                    AND f.owner_id = r.owner_id AND f.deleted_at IS NOT NULL
                    AND f.purge_requested_at = r.requested_at
                WHERE r.event_id = :event AND r.root_folder_id = :aggregate AND r.owner_id = :owner
                    AND r.requested_at = :created
                """;
        return (Boolean) em.createNativeQuery("SELECT EXISTS (" + proof + ")")
                .setParameter("event", n.eventId()).setParameter("aggregate", n.aggregateId())
                .setParameter("owner", n.ownerId()).setParameter("created", n.createdAt()).getSingleResult();
    }

    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
    }
}
