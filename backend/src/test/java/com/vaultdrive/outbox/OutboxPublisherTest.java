package com.vaultdrive.outbox;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    @Test
    void databaseFailureAfterConfirmLeavesLeaseForRecovery() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        OutboxTransport transport = mock(OutboxTransport.class);
        ClaimedOutboxEvent claim = new ClaimedOutboxEvent(UUID.randomUUID(), "FILE_PURGE_REQUESTED",
                "FILE", UUID.randomUUID(), 1, Map.of(), Instant.now(), UUID.randomUUID(), 1);
        when(claims.claimNext()).thenReturn(Optional.of(claim));
        when(claims.owns(claim)).thenReturn(true);
        when(transport.publish(claim)).thenReturn(PublishResult.CONFIRMED);
        when(claims.confirmed(claim)).thenThrow(new IllegalStateException("Database unavailable"));
        assertThatThrownBy(() -> new OutboxPublisher(claims, transport).publishNext())
                .isInstanceOf(IllegalStateException.class);
        verify(claims, never()).failed(any(), any());
    }

    @Test
    void alreadyExpiredOrReplacedClaimIsNotSent() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        OutboxTransport transport = mock(OutboxTransport.class);
        ClaimedOutboxEvent claim = new ClaimedOutboxEvent(UUID.randomUUID(), "FILE_PURGE_REQUESTED",
                "FILE", UUID.randomUUID(), 1, Map.of(), Instant.now(), UUID.randomUUID(), 1);
        when(claims.claimNext()).thenReturn(Optional.of(claim));
        when(claims.owns(claim)).thenReturn(false);
        assertThat(new OutboxPublisher(claims, transport).publishNext()).isTrue();
        verifyNoInteractions(transport);
    }

    @Test
    void exponentialBackoffCapsWithoutOverflow() {
        var properties = new OutboxPublisherProperties(false, 10, 20, Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(60), "e", "q", "r");
        assertThat(properties.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.backoff(1000)).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.isTimingValid()).isTrue();
    }
}
