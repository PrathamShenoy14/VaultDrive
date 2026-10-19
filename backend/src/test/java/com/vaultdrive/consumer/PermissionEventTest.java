package com.vaultdrive.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class PermissionEventTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.randomUUID();
    private final Instant time = Instant.parse("2026-10-19T12:00:00Z");

    @Test void validAndCompatibleAdditionalFieldsParse() {
        ObjectNode root = valid(); root.put("future", "ignored");
        assertThat(PermissionEvent.parse(message(mapper.writeValueAsBytes(root)), mapper)).isEqualTo(new PermissionEvent(id, time));
    }

    @ParameterizedTest @ValueSource(strings = {"eventId", "eventType", "aggregateType", "aggregateId", "payloadVersion", "createdAt", "payload"})
    void requiredFieldsCannotBeOmitted(String field) {
        ObjectNode root = valid(); root.remove(field); rejects(mapper.writeValueAsBytes(root));
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{}", "secret", "{} {}", "{\"eventId\":\"a\",\"eventId\":\"b\"}"})
    void malformedBodiesAreSanitized(String body) { rejects(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    @Test void unsupportedVersionTypeAndMismatchedIdentitiesAreRejected() {
        ObjectNode root = valid(); root.put("payloadVersion", 2); rejects(mapper.writeValueAsBytes(root));
        root = valid(); root.put("eventType", "FILE_PURGE_REQUESTED"); rejects(mapper.writeValueAsBytes(root));
        root = valid(); root.put("aggregateId", UUID.randomUUID().toString()); rejects(mapper.writeValueAsBytes(root));
        root = valid(); ((ObjectNode) root.get("payload")).put("activityEventId", UUID.randomUUID().toString()); rejects(mapper.writeValueAsBytes(root));
        Message message = message(mapper.writeValueAsBytes(valid())); message.getMessageProperties().setMessageId(UUID.randomUUID().toString());
        assertThatThrownBy(() -> PermissionEvent.parse(message, mapper)).isInstanceOf(InvalidNotification.class).hasNoCause();
        message.getMessageProperties().setMessageId(id.toString()); message.getMessageProperties().setType("FILE_PURGE_REQUESTED");
        assertThatThrownBy(() -> PermissionEvent.parse(message, mapper)).isInstanceOf(InvalidNotification.class).hasNoCause();
        rejects(new byte[65537]);
    }

    private ObjectNode valid() {
        return (ObjectNode) mapper.valueToTree(Map.of("eventId", id, "eventType", "PERMISSION_ACTIVITY_RECORDED",
                "aggregateType", "PERMISSION_ACTIVITY", "aggregateId", id, "payloadVersion", 1,
                "createdAt", time.toString(), "payload", Map.of("activityEventId", id)));
    }
    private Message message(byte[] body) {
        var props = new MessageProperties(); props.setMessageId(id.toString());
        return new Message(body, props);
    }
    private void rejects(byte[] body) {
        assertThatThrownBy(() -> PermissionEvent.parse(message(body), mapper)).isInstanceOf(InvalidNotification.class)
                .hasNoCause().hasMessageMatching("MALFORMED|UNSUPPORTED_VERSION|UNSUPPORTED_TYPE");
    }
}
