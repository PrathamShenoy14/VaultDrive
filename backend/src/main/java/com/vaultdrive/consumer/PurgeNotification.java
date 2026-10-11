package com.vaultdrive.consumer;

import com.vaultdrive.job.JobType;
import org.springframework.amqp.core.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

record PurgeNotification(UUID eventId, String eventType, JobType jobType, UUID aggregateId,
                         UUID ownerId, Instant createdAt) {
    static final int MAX_BODY_BYTES = 65536;

    static PurgeNotification parse(Message message, ObjectMapper mapper) {
        UUID id = null;
        try {
            if (message.getBody().length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException();
            }
            ObjectReader reader = mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root = reader.readTree(message.getBody());
            if (root == null || !root.isObject()) { throw new IllegalArgumentException(); }
            id = uuid(string(root, "eventId"));
            String messageId = message.getMessageProperties().getMessageId();
            if (messageId == null || !uuid(messageId).equals(id)) { throw new IllegalArgumentException(); }
            JsonNode version = root.get("payloadVersion");
            if (version == null || !version.isInt()) { throw new IllegalArgumentException(); }
            if (version.asInt() != 1) {
                throw new InvalidNotification(InvalidNotification.Reason.UNSUPPORTED_VERSION, id);
            }
            String eventType = string(root, "eventType");
            JobType type = switch (eventType) {
                case "FILE_PURGE_REQUESTED" -> JobType.FILE_PURGE;
                case "FOLDER_PURGE_REQUESTED" -> JobType.FOLDER_PURGE;
                default -> throw new InvalidNotification(InvalidNotification.Reason.UNSUPPORTED_TYPE, id);
            };
            String amqpType = message.getMessageProperties().getType();
            if (amqpType != null && !amqpType.equals(eventType)) { throw new IllegalArgumentException(); }
            if (!type.aggregateType().equals(string(root, "aggregateType"))) { throw new IllegalArgumentException(); }
            UUID aggregateId = uuid(string(root, "aggregateId"));
            Instant createdAt = Instant.parse(string(root, "createdAt"));
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) { throw new IllegalArgumentException(); }
            UUID ownerId = uuid(string(payload, "ownerId"));
            String resourceField = type == JobType.FILE_PURGE ? "fileId" : "folderId";
            if (!aggregateId.equals(uuid(string(payload, resourceField)))) { throw new IllegalArgumentException(); }
            return new PurgeNotification(id, eventType, type, aggregateId, ownerId, createdAt);
        } catch (JacksonException | IllegalArgumentException | DateTimeParseException exception) {
            throw new InvalidNotification(InvalidNotification.Reason.MALFORMED, id);
        }
    }

    private static String string(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isString()) { throw new IllegalArgumentException(); }
        return value.asString();
    }

    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        // UUID.fromString accepts abbreviated groups; require a canonical identity.
        if (!id.toString().equalsIgnoreCase(value)) { throw new IllegalArgumentException(); }
        return id;
    }
}
