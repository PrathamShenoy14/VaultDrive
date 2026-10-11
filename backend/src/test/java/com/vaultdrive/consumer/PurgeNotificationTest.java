package com.vaultdrive.consumer;

import com.vaultdrive.job.JobType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PurgeNotificationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID eventId = UUID.randomUUID();
    private final UUID aggregateId = UUID.randomUUID();

    @Test
    void parsesFileAndFolderAndToleratesCompatibleAdditionalFields() {
        var file = body();
        file.put("futureField", "ignored");
        assertThat(PurgeNotification.parse(message(file), mapper).jobType()).isEqualTo(JobType.FILE_PURGE);
        file.put("eventType", "FOLDER_PURGE_REQUESTED");
        file.put("aggregateType", "FOLDER");
        ((ObjectNode) file.get("payload")).remove("fileId");
        ((ObjectNode) file.get("payload")).put("folderId", aggregateId.toString());
        assertThat(PurgeNotification.parse(message(file), mapper).jobType()).isEqualTo(JobType.FOLDER_PURGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "eventType", "aggregateId", "aggregateType", "payloadVersion", "createdAt", "payload"})
    void rejectsMissingEnvelopeFields(String field) {
        var body = body();
        body.remove(field);
        rejects(message(body), InvalidNotification.Reason.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ownerId", "fileId"})
    void rejectsMissingPayloadFields(String field) {
        var body = body();
        ((ObjectNode) body.get("payload")).remove(field);
        rejects(message(body), InvalidNotification.Reason.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "aggregateId", "createdAt", "aggregateType"})
    void rejectsInvalidValues(String field) {
        var body = body();
        body.put(field, "sensitive invalid input");
        rejects(message(body), InvalidNotification.Reason.MALFORMED);
    }

    @Test
    void rejectsUnsupportedTypeVersionAndCoercedVersion() {
        var body = body();
        body.put("payloadVersion", 2);
        rejects(message(body), InvalidNotification.Reason.UNSUPPORTED_VERSION);
        body.put("payloadVersion", "1");
        rejects(message(body), InvalidNotification.Reason.MALFORMED);
        body.put("payloadVersion", 1);
        body.put("eventType", "UNKNOWN");
        rejects(message(body), InvalidNotification.Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void rejectsContradictoryMetadataPayloadAndAbbreviatedUuid() {
        var body = body();
        var message = message(body);
        message.getMessageProperties().setMessageId(UUID.randomUUID().toString());
        rejects(message, InvalidNotification.Reason.MALFORMED);
        message.getMessageProperties().setMessageId(eventId.toString());
        message.getMessageProperties().setType("FOLDER_PURGE_REQUESTED");
        rejects(message, InvalidNotification.Reason.MALFORMED);
        ((ObjectNode) body.get("payload")).put("fileId", UUID.randomUUID().toString());
        rejects(message(body), InvalidNotification.Reason.MALFORMED);
        body.put("eventId", "1-1-1-1-1");
        rejects(message(body), InvalidNotification.Reason.MALFORMED);
    }

    @Test
    void rejectsDuplicateJsonFieldsTrailingTokensOversizedAndMalformedBodies() {
        rejects(new Message("{private invalid JSON".getBytes(StandardCharsets.UTF_8), properties()),
                InvalidNotification.Reason.MALFORMED);
        String json = mapper.writeValueAsString(body());
        rejects(new Message((json + " {}").getBytes(StandardCharsets.UTF_8), properties()),
                InvalidNotification.Reason.MALFORMED);
        rejects(new Message(json.replace("\"payloadVersion\":1", "\"payloadVersion\":1,\"payloadVersion\":1")
                .getBytes(StandardCharsets.UTF_8), properties()), InvalidNotification.Reason.MALFORMED);
        rejects(new Message(new byte[PurgeNotification.MAX_BODY_BYTES + 1], properties()),
                InvalidNotification.Reason.MALFORMED);
    }

    private void rejects(Message message, InvalidNotification.Reason reason) {
        assertThatThrownBy(() -> PurgeNotification.parse(message, mapper))
                .isInstanceOf(InvalidNotification.class).hasMessage(reason.name()).hasNoCause();
    }

    private MessageProperties properties() {
        var properties = new MessageProperties();
        properties.setMessageId(eventId.toString());
        return properties;
    }

    private Message message(ObjectNode body) { return new Message(mapper.writeValueAsBytes(body), properties()); }

    private ObjectNode body() {
        var root = mapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", "FILE_PURGE_REQUESTED");
        root.put("aggregateType", "FILE");
        root.put("aggregateId", aggregateId.toString());
        root.put("payloadVersion", 1);
        root.put("createdAt", Instant.now().toString());
        var payload = mapper.createObjectNode();
        payload.put("ownerId", UUID.randomUUID().toString());
        payload.put("fileId", aggregateId.toString());
        root.set("payload", payload);
        return root;
    }
}
