package com.vaultdrive.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 32, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "payload_version", nullable = false, updatable = false)
    private int payloadVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, String> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 32)
    private OutboxDeliveryStatus deliveryStatus;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "last_failure_code", length = 48)
    private String lastFailureCode;

    protected OutboxEvent() {
    }

    private OutboxEvent(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Map<String, String> payload,
            Instant requestedAt
    ) {
        this.id = UUID.randomUUID();
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = Objects.requireNonNull(aggregateId);
        this.payloadVersion = 1;
        this.payload = Map.copyOf(payload);
        this.createdAt = Objects.requireNonNull(requestedAt);
        this.deliveryStatus = OutboxDeliveryStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = requestedAt;
    }

    public static OutboxEvent filePurgeRequested(
            UUID ownerId,
            UUID fileId,
            Instant requestedAt
    ) {
        return new OutboxEvent(
                "FILE_PURGE_REQUESTED", "FILE", fileId,
                Map.of("ownerId", ownerId.toString(), "fileId", fileId.toString()),
                requestedAt
        );
    }

    public static OutboxEvent folderPurgeRequested(
            UUID ownerId,
            UUID folderId,
            Instant requestedAt
    ) {
        return new OutboxEvent(
                "FOLDER_PURGE_REQUESTED", "FOLDER", folderId,
                Map.of("ownerId", ownerId.toString(), "folderId", folderId.toString()),
                requestedAt
        );
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public int getPayloadVersion() {
        return payloadVersion;
    }

    public Map<String, String> getPayload() {
        return Map.copyOf(payload);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public OutboxDeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public String getLastFailureCode() {
        return lastFailureCode;
    }

    void claim(Instant databaseNow, Duration lease, Duration recoveryBackoff) {
        if (deliveryStatus == OutboxDeliveryStatus.PUBLISHING) {
            lastFailureCode = "LEASE_EXPIRED";
        }
        deliveryStatus = OutboxDeliveryStatus.PUBLISHING;
        claimToken = UUID.randomUUID();
        claimExpiresAt = databaseNow.plus(lease);
        attemptCount++;
        lastAttemptAt = databaseNow;
        nextAttemptAt = claimExpiresAt.plus(recoveryBackoff);
    }

    void exhaustExpiredClaim() {
        if (deliveryStatus == OutboxDeliveryStatus.PUBLISHING) {
            lastFailureCode = "LEASE_EXPIRED";
        } else if (lastFailureCode == null) {
            lastFailureCode = "ATTEMPT_LIMIT";
        }
        deliveryStatus = OutboxDeliveryStatus.FAILED;
        claimToken = null;
        claimExpiresAt = null;
    }
}
