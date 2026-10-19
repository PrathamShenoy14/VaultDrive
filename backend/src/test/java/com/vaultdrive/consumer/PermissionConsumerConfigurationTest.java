package com.vaultdrive.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PermissionConsumerConfigurationTest {
    @Test
    void disabledByDefaultCreatesNoContainerAndContactsNoBroker() {
        var factory = mock(ConnectionFactory.class);
        new ApplicationContextRunner().withUserConfiguration(PermissionConsumerConfiguration.class)
                .withBean(ConnectionFactory.class, () -> factory)
                .withBean(PermissionEventIngestor.class, () -> mock(PermissionEventIngestor.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SimpleMessageListenerContainer.class);
                    verifyNoInteractions(factory);
                });
    }
}
