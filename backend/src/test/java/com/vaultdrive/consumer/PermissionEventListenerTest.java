package com.vaultdrive.consumer;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.listener.FatalListenerExecutionException;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PermissionEventListenerTest {
    private final PermissionEventIngestor ingestor = mock(PermissionEventIngestor.class);
    private final Channel channel = mock(Channel.class);
    private final PermissionEventListener listener = new PermissionEventListener(ingestor, new ObjectMapper(), 1);

    @Test
    void failedQuarantineGetsThreeAttemptsAndLeavesDeliveryUnacked() {
        doThrow(new DataAccessResourceFailureException("private connection details"))
                .when(ingestor).quarantine(anyString(), any());
        assertThatThrownBy(() -> listener.onMessage(invalid(), channel))
                .isInstanceOf(FatalListenerExecutionException.class).hasMessage("DATABASE_UNAVAILABLE").hasNoCause();
        verify(ingestor, times(3)).quarantine(anyString(), any());
        verifyNoInteractions(channel);
    }

    @Test
    void ackFailureDoesNotRepeatDatabaseWorkOrExposeCause() throws Exception {
        doThrow(new IOException("private socket detail")).when(channel).basicAck(7, false);
        assertThatThrownBy(() -> listener.onMessage(invalid(), channel))
                .isInstanceOf(FatalListenerExecutionException.class).hasMessage("ACK_UNCERTAIN").hasNoCause();
        verify(ingestor).quarantine(anyString(), any());
        verify(channel).basicAck(7, false);
    }

    @Test
    void interruptedBackoffLeavesDeliveryUnackedAndPreservesInterrupt() {
        doThrow(new DataAccessResourceFailureException("private detail")).when(ingestor).quarantine(anyString(), any());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> listener.onMessage(invalid(), channel)).hasMessage("INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(channel);
        } finally {
            Thread.interrupted();
        }
    }

    private Message invalid() {
        var properties = new MessageProperties();
        properties.setDeliveryTag(7);
        return new Message(new byte[]{0}, properties);
    }

    @Test void ambientTransactionRejectsBeforeDatabaseOrNetworkWork() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> listener.onMessage(invalid(), channel)).hasMessage("OUTER_TRANSACTION");
            verifyNoInteractions(ingestor, channel);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void unexpectedFailureStopsUnackedWithSanitizedCode() {
        doThrow(new IllegalStateException("private payload")).when(ingestor).quarantine(anyString(), any());
        assertThatThrownBy(() -> listener.onMessage(invalid(), channel)).hasMessage("UNEXPECTED_FAILURE").hasNoCause();
        verifyNoInteractions(channel);
    }
}
