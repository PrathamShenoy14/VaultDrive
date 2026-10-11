package com.vaultdrive.consumer;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.listener.FatalListenerExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "vaultdrive.purge.consumer.enabled", havingValue = "true")
public class PurgeConsumerConfiguration {
    @Bean
    SimpleMessageListenerContainer purgeNotificationContainer(ConnectionFactory factory,
            NotificationIngestor ingestor, ObjectMapper mapper,
            @Value("${vaultdrive.outbox.publisher.queue:vaultdrive.purge.requests}") String queue) {
        return container(factory, queue, new PurgeNotificationListener(ingestor, mapper, 1000));
    }

    // Shared by runtime and live tests, which supply UUID-suffixed queues in the isolated vhost.
    static SimpleMessageListenerContainer container(ConnectionFactory factory, String queue,
                                                   PurgeNotificationListener listener) {
        var container = new SimpleMessageListenerContainer(factory);
        container.setQueueNames(queue);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setChannelTransacted(false);
        container.setPrefetchCount(1);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        container.setAutoDeclare(false); // Existing topology, no new declarations or DLX arguments.
        container.setMissingQueuesFatal(true);
        container.setForceStop(true);
        container.setShutdownTimeout(3000);
        container.setMessageListener(listener);
        // Stop on exhausted DB retries, uncertain ACK, or unexpected errors. Never
        // delegate a poison message to an automatic reject/requeue loop.
        container.setErrorHandler(error -> { throw new FatalListenerExecutionException("CONSUMER_STOPPED"); });
        return container;
    }
}
