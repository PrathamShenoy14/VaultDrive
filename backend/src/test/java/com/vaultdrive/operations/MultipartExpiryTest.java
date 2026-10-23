package com.vaultdrive.operations;

import com.vaultdrive.file.MultipartCancellationService;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MultipartExpiryTest {
    @Test void boundedPagesAdvanceWrapAndRestartWithoutStorageOrRetryLoops() {
        var service=mock(MultipartCancellationService.class); var cursor=UUID.randomUUID();
        when(service.expire(null,2)).thenReturn(new MultipartCancellationService.ExpiryPage(List.of(),cursor));
        when(service.expire(cursor,2)).thenReturn(new MultipartCancellationService.ExpiryPage(List.of(),null));
        var properties=new MultipartExpiryConfiguration.Properties(true,2,Duration.ofSeconds(1));
        var signals=new ProcessingSignals();
        try(var poller=new MultipartExpiryConfiguration.Poller(service,properties,signals)){
            poller.poll();poller.poll();poller.poll();
        }
        try(var restarted=new MultipartExpiryConfiguration.Poller(service,properties,signals)){restarted.poll();}
        verify(service,times(3)).expire(null,2);verify(service).expire(cursor,2);
        assertThat(signals.fresh("expiry",Duration.ofSeconds(5))).isTrue();
    }
    @Test void failedScanRetainsCursorForNextTick() {
        var service=mock(MultipartCancellationService.class);
        when(service.expire(null,2)).thenThrow(new IllegalStateException()).thenReturn(new MultipartCancellationService.ExpiryPage(List.of(),null));
        var signals=new ProcessingSignals();
        try(var poller=new MultipartExpiryConfiguration.Poller(service,new MultipartExpiryConfiguration.Properties(true,2,Duration.ofSeconds(1)),signals)){
            poller.poll(); assertThat(signals.fresh("expiry",Duration.ofSeconds(5))).isFalse();poller.poll();
        }
        verify(service,times(2)).expire(null,2);
    }
}
