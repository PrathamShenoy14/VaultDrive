package com.vaultdrive.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.amqp.autoconfigure.ConnectionFactoryCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisherConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "vaultdrive.outbox.publisher.enabled", havingValue = "true")
    @EnableScheduling
    static class RuntimeConfiguration {
        @Bean
        OutboxRabbitIo outboxRabbitIo() {
            return new OutboxRabbitIo();
        }

        @Bean
        ConnectionFactoryCustomizer outboxConnectionFactoryCustomizer(OutboxRabbitIo io) {
            // Runs before Boot creates the caching factory and its publisher factory.
            return io::configure;
        }

        @Bean
        ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler() {
                @Override
                protected void initiateEarlyShutdown() {
                    // Context close normally starts a graceful lifecycle stop.
                    // Interrupt now, before it waits for an in-flight broker call.
                    shutdown();
                }
            };
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("outbox-poll-");
            scheduler.setDaemon(true);
            scheduler.setWaitForTasksToCompleteOnShutdown(false);
            scheduler.setAwaitTerminationSeconds(2);
            return scheduler;
        }

        @Bean
        RabbitTemplate outboxRabbitTemplate(ConnectionFactory factory) {
            if (factory.getUsername() == null || factory.getUsername().isBlank()
                    || factory instanceof org.springframework.amqp.rabbit.connection.CachingConnectionFactory caching
                    && (caching.getRabbitConnectionFactory().getPassword() == null
                        || caching.getRabbitConnectionFactory().getPassword().isBlank())) {
                throw new IllegalArgumentException("Configure private RabbitMQ credentials before enabling the publisher");
            }
            if (factory instanceof org.springframework.amqp.rabbit.connection.CachingConnectionFactory caching) {
                caching.setCloseTimeout(OutboxRabbitIo.CLOSE_TIMEOUT_MS);
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
        Poller outboxPoller(OutboxPublisher publisher, OutboxPublisherProperties properties,
                org.springframework.beans.factory.ObjectProvider<com.vaultdrive.operations.ProcessingSignals> signals) {
            return new Poller(publisher, properties, signals.getIfAvailable(com.vaultdrive.operations.ProcessingSignals::new));
        }
    }

    static class Poller {
        private static final Logger log = LoggerFactory.getLogger(Poller.class);
        private final OutboxPublisher publisher;
        private final OutboxPublisherProperties properties;
        private final com.vaultdrive.operations.ProcessingSignals signals;

        Poller(OutboxPublisher publisher, OutboxPublisherProperties properties) {
            this(publisher, properties, new com.vaultdrive.operations.ProcessingSignals());
        }
        Poller(OutboxPublisher publisher, OutboxPublisherProperties properties, com.vaultdrive.operations.ProcessingSignals signals) {
            this.publisher = publisher;
            this.properties = properties;
            this.signals = signals;
        }

        @Scheduled(fixedDelayString = "${vaultdrive.outbox.publisher.poll-delay:5s}")
        public void poll() {
            try {
                for (int i = 0; i < properties.maxEventsPerPoll(); i++) {
                    if (Thread.currentThread().isInterrupted() || !publisher.publishNext()) {
                        break;
                    }
                }
                signals.success("publisher");
            } catch (RuntimeException exception) {
                // Neither connection URLs nor exception messages/stack traces
                // are logged; a committed claim remains recoverable.
                log.warn("Outbox polling interrupted by {}", exception.getClass().getSimpleName());
            }
        }
    }
}
