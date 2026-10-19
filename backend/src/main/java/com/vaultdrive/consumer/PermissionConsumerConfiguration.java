package com.vaultdrive.consumer;

import com.vaultdrive.outbox.OutboxPublisherProperties;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.listener.FatalListenerExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "vaultdrive.sharing.notifications.consumer.enabled", havingValue = "true")
public class PermissionConsumerConfiguration {
    @Bean
    SimpleMessageListenerContainer permissionNotificationContainer(ConnectionFactory factory,
            PermissionEventIngestor ingestor, ObjectMapper mapper, OutboxPublisherProperties properties) {
        if (!properties.isRoutingDistinct()) throw new IllegalArgumentException("Permission routing must be isolated");
        return container(factory, properties.permissionQueue(), new PermissionEventListener(ingestor, mapper, 1000));
    }

    static SimpleMessageListenerContainer container(ConnectionFactory factory, String queue, PermissionEventListener listener) {
        var container = new SimpleMessageListenerContainer(factory);
        container.setQueueNames(queue);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setChannelTransacted(false);
        container.setPrefetchCount(1);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(true);
        container.setForceStop(true);
        container.setShutdownTimeout(3000);
        container.setMessageListener(listener);
        container.setErrorHandler(error -> { throw new FatalListenerExecutionException("PERMISSION_CONSUMER_STOPPED"); });
        return container;
    }
}
