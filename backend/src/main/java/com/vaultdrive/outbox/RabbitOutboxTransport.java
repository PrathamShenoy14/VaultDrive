package com.vaultdrive.outbox;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RabbitOutboxTransport implements OutboxTransport {

    private final RabbitTemplate template;
    private final RabbitAdmin admin;
    private final ObjectMapper mapper;
    private final OutboxPublisherProperties properties;

    public RabbitOutboxTransport(RabbitTemplate template, RabbitAdmin admin,
                                ObjectMapper mapper, OutboxPublisherProperties properties) {
        if (!(template.getConnectionFactory() instanceof CachingConnectionFactory factory)
                || !factory.isPublisherConfirms() || factory.isSimplePublisherConfirms()
                || !factory.isPublisherReturns()) {
            throw new IllegalArgumentException("Outbox requires correlated confirms and publisher returns");
        }
        template.setMandatory(true);
        this.template = template;
        this.admin = admin;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public PublishResult publish(ClaimedOutboxEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("RabbitMQ I/O must run outside a database transaction");
        }
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(Map.of(
                    "eventId", event.id().toString(), "eventType", event.eventType(),
                    "aggregateType", event.aggregateType(), "aggregateId", event.aggregateId().toString(),
                    "payloadVersion", event.payloadVersion(), "createdAt", event.createdAt().toString(),
                    "payload", event.payload()
            ));
        } catch (RuntimeException exception) {
            return PublishResult.SERIALIZATION_ERROR;
        }
        try {
            // Idempotent declarations also repair a removed topology. They
            // happen after the claim commits, never inside a DB transaction.
            admin.declareExchange(new DirectExchange(properties.exchange(), true, false));
            admin.declareQueue(QueueBuilder.durable(properties.queue()).quorum().build());
            admin.declareBinding(new Binding(properties.queue(), Binding.DestinationType.QUEUE,
                    properties.exchange(), properties.routingKey(), null));
            MessageProperties messageProperties = new MessageProperties();
            messageProperties.setMessageId(event.id().toString());
            messageProperties.setType(event.eventType());
            messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            messageProperties.setContentEncoding("UTF-8");
            messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            CorrelationData correlation = new CorrelationData(event.claimToken().toString());
            template.send(properties.exchange(), properties.routingKey(),
                    new Message(body, messageProperties), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                return PublishResult.NACK;
            }
            // Spring populates returned-message information before completing
            // the ack future. An ack for an unroutable message is not success.
            return correlation.getReturned() == null ? PublishResult.CONFIRMED : PublishResult.RETURNED;
        } catch (TimeoutException exception) {
            return PublishResult.TIMEOUT;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PublishResult.INTERRUPTED;
        } catch (ExecutionException | RuntimeException exception) {
            return PublishResult.BROKER_ERROR;
        }
    }
}
