package com.vaultdrive.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OutboxPublisherConfigurationTest {
    private final ApplicationContextRunner bootRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(OutboxPublisherConfiguration.class)
            .withBean(OutboxClaimService.class, () -> {
                var claims = mock(OutboxClaimService.class);
                when(claims.claimNext()).thenReturn(Optional.empty());
                return claims;
            })
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("spring.rabbitmq.dynamic=false",
                    "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
                    "spring.rabbitmq.username=test-user", "spring.rabbitmq.password=test-password");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OutboxPublisherConfiguration.class)
            .withBean(OutboxClaimService.class, () -> {
                var claims = mock(OutboxClaimService.class);
                when(claims.claimNext()).thenReturn(Optional.empty());
                return claims;
            })
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ConnectionFactory.class, () -> {
                var factory = new CachingConnectionFactory();
                factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
                factory.setPublisherReturns(true);
                return factory;
            });

    @Test
    void disabledByDefaultWithoutPollingOrBrokerTransport() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OutboxPublisher.class);
            assertThat(context).doesNotHaveBean(OutboxTransport.class);
            assertThat(context).doesNotHaveBean(OutboxRabbitIo.class);
        });
    }

    @Test
    @SuppressWarnings("deprecation")
    void bootFactoryUsesBoundedNioBeforeAnyConnectionIsCreated() {
        bootRunner.withPropertyValues("vaultdrive.outbox.publisher.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            var factory = context.getBean(CachingConnectionFactory.class);
            var client = factory.getRabbitConnectionFactory();
            assertThat(ReflectionTestUtils.getField(client, "nio")).isEqualTo(true);
            assertThat(client.getNioParams().getWriteEnqueuingTimeoutInMs()).isEqualTo(1000);
            assertThat(client.getNioParams().getWriteQueueCapacity()).isEqualTo(128);
            assertThat(factory.isPublisherConfirms()).isTrue();
            assertThat(factory.isPublisherReturns()).isTrue();
            assertThat(factory.getCloseTimeout()).isEqualTo(1000);
        });
    }

    @Test
    void disabledBootConfigurationCreatesNoPollingOrIoResources() {
        bootRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OutboxRabbitIo.class);
            assertThat(context).doesNotHaveBean(OutboxPublisher.class);
            assertThat(context).doesNotHaveBean(OutboxPublisherConfiguration.Poller.class);
            verifyNoInteractions(context.getBean(OutboxClaimService.class));
        });
    }

    @Test
    void enabledBootConfigurationRejectsMissingPrivateCredentials() {
        bootRunner.withPropertyValues("vaultdrive.outbox.publisher.enabled=true",
                        "spring.rabbitmq.username=", "spring.rabbitmq.password=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void explicitEnablementCreatesPublisherAndScheduler() {
        runner.withPropertyValues("vaultdrive.outbox.publisher.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboxPublisher.class);
            assertThat(context).hasSingleBean(OutboxTransport.class);
            assertThat(context).hasSingleBean(OutboxPublisherConfiguration.Poller.class);
        });
    }

    @Test
    void rejectsLeaseShorterThanConfirmationTimeout() {
        runner.withPropertyValues("vaultdrive.outbox.publisher.lease=1s",
                "vaultdrive.outbox.publisher.confirm-timeout=2s")
                .run(context -> assertThat(context).hasFailed());
    }
}
