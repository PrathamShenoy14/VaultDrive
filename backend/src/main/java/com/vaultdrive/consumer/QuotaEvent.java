package com.vaultdrive.consumer;

import org.springframework.amqp.core.Message;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

record QuotaEvent(UUID id, Instant createdAt) {
    static QuotaEvent parse(Message message, ObjectMapper mapper) {
        var event = PermissionEvent.parseEnvelope(message, mapper,
                "STORAGE_QUOTA_THRESHOLD_CROSSED", "STORAGE_QUOTA_EVENT", "quotaEventId");
        return new QuotaEvent(event.id(), event.createdAt());
    }
}
