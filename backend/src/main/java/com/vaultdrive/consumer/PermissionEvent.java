package com.vaultdrive.consumer;

import org.springframework.amqp.core.Message;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

record PermissionEvent(UUID id, Instant createdAt) {
    static PermissionEvent parse(Message message, ObjectMapper mapper) {
        return parseEnvelope(message, mapper, "PERMISSION_ACTIVITY_RECORDED", "PERMISSION_ACTIVITY", "activityEventId");
    }

    static PermissionEvent parseEnvelope(Message message, ObjectMapper mapper, String type, String aggregate, String pointer) {
        UUID id = null;
        try {
            if (message.getBody().length > 65536) throw new IllegalArgumentException();
            JsonNode root = mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(message.getBody());
            id = uuid(string(root, "eventId"));
            if (!id.equals(uuid(message.getMessageProperties().getMessageId()))) throw new IllegalArgumentException();
            JsonNode version = root.get("payloadVersion");
            if (version == null || !version.isInt()) throw new IllegalArgumentException();
            if (version.asInt() != 1) throw new InvalidNotification(InvalidNotification.Reason.UNSUPPORTED_VERSION, id);
            if (!type.equals(string(root, "eventType"))) {
                throw new InvalidNotification(InvalidNotification.Reason.UNSUPPORTED_TYPE, id);
            }
            String amqpType = message.getMessageProperties().getType();
            if ((amqpType != null && !amqpType.equals(type))
                    || !aggregate.equals(string(root, "aggregateType"))
                    || !id.equals(uuid(string(root, "aggregateId")))
                    || !id.equals(uuid(string(root.get("payload"), pointer)))) throw new IllegalArgumentException();
            return new PermissionEvent(id, Instant.parse(string(root, "createdAt")));
        } catch (InvalidNotification invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new InvalidNotification(InvalidNotification.Reason.MALFORMED, id);
        }
    }

    private static String string(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        if (value == null || !value.isString()) throw new IllegalArgumentException();
        return value.asString();
    }

    private static UUID uuid(String value) {
        if (value == null) throw new IllegalArgumentException();
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return id;
    }
}
