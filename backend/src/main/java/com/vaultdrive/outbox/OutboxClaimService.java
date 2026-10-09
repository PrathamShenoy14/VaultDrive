package com.vaultdrive.outbox;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class OutboxClaimService {

    private final EntityManager entityManager;
    private final OutboxPublisherProperties properties;

    public OutboxClaimService(EntityManager entityManager, OutboxPublisherProperties properties) {
        this.entityManager = entityManager;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedOutboxEvent> claimNext() {
        // Only outbox rows are locked, in due-time/id order. No hierarchy or
        // resource locks and no broker calls enter this transaction.
        for (int i = 0; i < properties.maxEventsPerPoll(); i++) {
            List<?> rows = entityManager.createNativeQuery("""
                    SELECT * FROM outbox_events
                    WHERE (delivery_status = 'PENDING' AND next_attempt_at <= clock_timestamp())
                       OR (delivery_status = 'PUBLISHING' AND claim_expires_at <= clock_timestamp()
                           AND next_attempt_at <= clock_timestamp())
                    ORDER BY CASE WHEN delivery_status = 'PUBLISHING'
                                  THEN claim_expires_at ELSE next_attempt_at END, created_at, id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, OutboxEvent.class).getResultList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            OutboxEvent event = (OutboxEvent) rows.getFirst();
            if (event.getAttemptCount() >= properties.maxAttempts()) {
                event.exhaustExpiredClaim();
                entityManager.flush();
                continue;
            }
            event.claim(databaseNow(), properties.lease(), properties.backoff(event.getAttemptCount() + 1));
            entityManager.flush();
            return Optional.of(ClaimedOutboxEvent.from(event));
        }
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean owns(ClaimedOutboxEvent claim) {
        Number count = (Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM outbox_events
                WHERE id = :id AND delivery_status = 'PUBLISHING'
                  AND claim_token = :token AND claim_expires_at > clock_timestamp()
                """).setParameter("id", claim.id()).setParameter("token", claim.claimToken())
                .getSingleResult();
        return count.longValue() == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean confirmed(ClaimedOutboxEvent claim) {
        return entityManager.createNativeQuery("""
                UPDATE outbox_events
                SET delivery_status = 'PUBLISHED', published_at = clock_timestamp(),
                    claim_token = NULL, claim_expires_at = NULL, last_failure_code = NULL
                WHERE id = :id AND delivery_status = 'PUBLISHING'
                  AND claim_token = :token AND claim_expires_at > clock_timestamp()
                """).setParameter("id", claim.id()).setParameter("token", claim.claimToken())
                .executeUpdate() == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failed(ClaimedOutboxEvent claim, PublishResult result) {
        if (result == PublishResult.CONFIRMED) {
            throw new IllegalArgumentException("Confirmed publication is not a failure");
        }
        Instant next = databaseNow().plus(properties.backoff(claim.attemptCount()));
        return entityManager.createNativeQuery("""
                UPDATE outbox_events
                SET delivery_status = CASE WHEN attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = :next, claim_token = NULL, claim_expires_at = NULL,
                    last_failure_code = :failure
                WHERE id = :id AND delivery_status = 'PUBLISHING'
                  AND claim_token = :token AND claim_expires_at > clock_timestamp()
                """).setParameter("id", claim.id()).setParameter("token", claim.claimToken())
                .setParameter("maxAttempts", properties.maxAttempts()).setParameter("next", next)
                .setParameter("failure", result.name()).executeUpdate() == 1;
    }

    private Instant databaseNow() {
        return (Instant) entityManager.createNativeQuery("SELECT clock_timestamp()", Instant.class)
                .getSingleResult();
    }
}
