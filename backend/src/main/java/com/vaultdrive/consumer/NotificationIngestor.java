package com.vaultdrive.consumer;

import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.JobType;
import com.vaultdrive.outbox.OutboxEvent;
import com.vaultdrive.outbox.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationIngestor {
    private final OutboxEventRepository outbox;
    private final BackgroundJobService jobs;
    private final EntityManager entityManager;

    public NotificationIngestor(OutboxEventRepository outbox, BackgroundJobService jobs, EntityManager entityManager) {
        this.outbox = outbox;
        this.jobs = jobs;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accept(PurgeNotification notification) {
        validate(notification);
        try {
            jobs.create(notification.eventId(), notification.jobType(), notification.aggregateId());
        } catch (IllegalArgumentException exception) {
            throw new InvalidNotification(InvalidNotification.Reason.IDENTITY_CONFLICT, notification.eventId());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean acceptReconciled(PurgeNotification notification) {
        validate(notification);
        return jobs.createIfAbsent(notification.eventId(), notification.jobType(), notification.aggregateId());
    }

    private void validate(PurgeNotification notification) {
        // This shared validation only reads immutable source identity. Ordinary
        // consumer ingestion takes no resource locks; reconciliation coordinates
        // its additional intent checks before entering here. No external I/O.
        OutboxEvent source = outbox.findById(notification.eventId()).orElseThrow(() ->
                new InvalidNotification(InvalidNotification.Reason.UNKNOWN_EVENT, notification.eventId()));
        String resourceField = notification.jobType() == JobType.FILE_PURGE ? "fileId" : "folderId";
        if (source.getPayloadVersion() != 1 || !source.getEventType().equals(notification.eventType())
                || !source.getAggregateType().equals(notification.jobType().aggregateType())
                || !source.getAggregateId().equals(notification.aggregateId())
                || !source.getCreatedAt().equals(notification.createdAt())
                || !notification.ownerId().toString().equals(source.getPayload().get("ownerId"))
                || !notification.aggregateId().toString().equals(source.getPayload().get(resourceField))) {
            throw new InvalidNotification(InvalidNotification.Reason.EVENT_MISMATCH, notification.eventId());
        }
        // Do not require PUBLISHED: delivery can precede publisher outcome commit.
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void quarantine(String digest, InvalidNotification invalid) {
        entityManager.createNativeQuery("""
                INSERT INTO notification_quarantine(body_sha256, event_id, reason_code)
                VALUES (:digest, :eventId, :reason)
                ON CONFLICT (body_sha256) DO NOTHING
                """).setParameter("digest", digest).setParameter("eventId", invalid.eventId())
                .setParameter("reason", invalid.reason().name()).executeUpdate();
    }
}
