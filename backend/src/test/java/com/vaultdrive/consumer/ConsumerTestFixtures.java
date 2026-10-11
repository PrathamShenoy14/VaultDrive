package com.vaultdrive.consumer;

import com.vaultdrive.outbox.OutboxEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

final class ConsumerTestFixtures {
    static Message message(OutboxEvent event, ObjectMapper mapper) {
        var properties = new MessageProperties();
        properties.setMessageId(event.getId().toString());
        properties.setType(event.getEventType());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setDeliveryTag(7);
        return new Message(mapper.writeValueAsBytes(Map.of(
                "eventId", event.getId().toString(), "eventType", event.getEventType(),
                "aggregateType", event.getAggregateType(), "aggregateId", event.getAggregateId().toString(),
                "payloadVersion", event.getPayloadVersion(), "createdAt", event.getCreatedAt().toString(),
                "payload", event.getPayload())), properties);
    }
}
