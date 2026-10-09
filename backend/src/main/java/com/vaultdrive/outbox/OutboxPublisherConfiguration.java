package com.vaultdrive.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisherConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "vaultdrive.outbox.publisher.enabled", havingValue = "true")
    @EnableScheduling
    static class RuntimeConfiguration {
        @Bean
        RabbitTemplate outboxRabbitTemplate(ConnectionFactory factory) {
            if (factory.getUsername() == null || factory.getUsername().isBlank()
                    || factory instanceof org.springframework.amqp.rabbit.connection.CachingConnectionFactory caching
                    && (caching.getRabbitConnectionFactory().getPassword() == null
                        || caching.getRabbitConnectionFactory().getPassword().isBlank())) {
                throw new IllegalArgumentException("Configure private RabbitMQ credentials before enabling the publisher");
            }
            return new RabbitTemplate(factory);
        }

        @Bean
        RabbitAdmin outboxRabbitAdmin(ConnectionFactory factory) {
            RabbitAdmin admin = new RabbitAdmin(factory);
            admin.setAutoStartup(false);
            return admin;
        }

        @Bean
        OutboxTransport outboxTransport(RabbitTemplate template, RabbitAdmin admin,
                                       ObjectMapper mapper, OutboxPublisherProperties properties) {
            return new RabbitOutboxTransport(template, admin, mapper, properties);
        }

        @Bean
        OutboxPublisher outboxPublisher(OutboxClaimService claims, OutboxTransport transport) {
            return new OutboxPublisher(claims, transport);
        }

        @Bean
        Poller outboxPoller(OutboxPublisher publisher, OutboxPublisherProperties properties) {
            return new Poller(publisher, properties);
        }
    }

    static class Poller {
        private static final Logger log = LoggerFactory.getLogger(Poller.class);
        private final OutboxPublisher publisher;
        private final OutboxPublisherProperties properties;

        Poller(OutboxPublisher publisher, OutboxPublisherProperties properties) {
            this.publisher = publisher;
            this.properties = properties;
        }

        @Scheduled(fixedDelayString = "${vaultdrive.outbox.publisher.poll-delay:5s}")
        public void poll() {
            try {
                for (int i = 0; i < properties.maxEventsPerPoll(); i++) {
                    if (Thread.currentThread().isInterrupted() || !publisher.publishNext()) {
                        break;
                    }
                }
            } catch (RuntimeException exception) {
                // Neither connection URLs nor exception messages/stack traces
                // are logged; a committed claim remains recoverable.
                log.warn("Outbox polling interrupted by {}", exception.getClass().getSimpleName());
            }
        }
    }
}
