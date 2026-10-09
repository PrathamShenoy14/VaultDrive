package com.vaultdrive.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitOutboxTransportTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final RabbitAdmin admin = mock(RabbitAdmin.class);
    private final CachingConnectionFactory factory = new CachingConnectionFactory();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OutboxPublisherProperties properties = new OutboxPublisherProperties(
            false, 10, 20, Duration.ofSeconds(30), Duration.ofMillis(50),
            Duration.ofSeconds(1), Duration.ofSeconds(60), "test.exchange", "test.queue", "test.route"
    );
    private RabbitOutboxTransport transport;
    private ClaimedOutboxEvent event;

    @BeforeEach
    void setUp() {
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        when(template.getConnectionFactory()).thenReturn(factory);
        transport = new RabbitOutboxTransport(template, admin, mapper, properties);
        event = new ClaimedOutboxEvent(UUID.randomUUID(), "FILE_PURGE_REQUESTED", "FILE",
                UUID.randomUUID(), 1, Map.of("ownerId", UUID.randomUUID().toString()),
                Instant.now(), UUID.randomUUID(), 1);
    }

    @AfterEach
    void cleanup() {
        factory.destroy();
    }

    @Test
    void positiveConfirmUsesPersistentMessageStableIdAndDurableQuorumTopology() {
        complete(true, false);
        assertThat(transport.publish(event)).isEqualTo(PublishResult.CONFIRMED);
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(properties.exchange()), eq(properties.routingKey()), sent.capture(), any());
        assertThat(sent.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(sent.getValue().getMessageProperties().getMessageId()).isEqualTo(event.id().toString());
        var envelope = mapper.readTree(sent.getValue().getBody());
        assertThat(envelope.get("eventId").asString()).isEqualTo(event.id().toString());
        assertThat(envelope.get("payloadVersion").asInt()).isEqualTo(1);
        ArgumentCaptor<Queue> queue = ArgumentCaptor.forClass(Queue.class);
        verify(admin).declareQueue(queue.capture());
        assertThat(queue.getValue().isDurable()).isTrue();
        assertThat(queue.getValue().isExclusive()).isFalse();
        assertThat(queue.getValue().isAutoDelete()).isFalse();
        assertThat(queue.getValue().getArguments()).containsEntry("x-queue-type", "quorum");
        verify(admin).declareExchange(argThat(exchange -> exchange.isDurable() && !exchange.isAutoDelete()));
        verify(admin).declareBinding(any(Binding.class));
        verify(template).setMandatory(true);
    }

    @Test
    void negativeConfirmIsFailure() {
        complete(false, false);
        assertThat(transport.publish(event)).isEqualTo(PublishResult.NACK);
    }

    @Test
    void returnedMessageIsFailureEvenWhenBrokerAcks() {
        complete(true, true);
        assertThat(transport.publish(event)).isEqualTo(PublishResult.RETURNED);
    }

    @Test
    void missingConfirmTimesOut() {
        assertThat(transport.publish(event)).isEqualTo(PublishResult.TIMEOUT);
    }

    @Test
    void brokerDeclarationFailureIsFailure() {
        doThrow(new IllegalStateException("sensitive broker detail"))
                .when(admin).declareExchange(any(DirectExchange.class));
        assertThat(transport.publish(event)).isEqualTo(PublishResult.BROKER_ERROR);
        verify(template, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void refusesIoInsideDatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> transport.publish(event)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(admin);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void refusesUnreliableConfirmConfiguration() {
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        assertThatThrownBy(() -> new RabbitOutboxTransport(template, admin, mapper, properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void complete(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312,
                        "NO_ROUTE", properties.exchange(), properties.routingKey()));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
