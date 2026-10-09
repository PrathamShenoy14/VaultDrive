package com.vaultdrive.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OutboxPublisherConfigurationTest {
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
        });
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
