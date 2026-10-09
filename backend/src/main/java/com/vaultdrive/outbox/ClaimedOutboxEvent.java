package com.vaultdrive.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ClaimedOutboxEvent(
        UUID id, String eventType, String aggregateType, UUID aggregateId,
        int payloadVersion, Map<String, String> payload, Instant createdAt,
        UUID claimToken, int attemptCount
) {
    public ClaimedOutboxEvent {
        payload = Map.copyOf(payload);
    }

    static ClaimedOutboxEvent from(OutboxEvent event) {
        return new ClaimedOutboxEvent(
                event.getId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getPayloadVersion(), event.getPayload(),
                event.getCreatedAt(), event.getClaimToken(), event.getAttemptCount()
        );
    }
}
