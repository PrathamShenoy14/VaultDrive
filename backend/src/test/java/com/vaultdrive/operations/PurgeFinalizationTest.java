package com.vaultdrive.operations;

import com.vaultdrive.worker.FilePurgeRecoveryService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PurgeFinalizationTest {
    @Test void failedScanRetainsPositionAndRestartRevisitsOnlyRetainedSuccesses() {
        var service=mock(FilePurgeRecoveryService.class);var id=UUID.randomUUID();
        when(service.recoverPage(null,2)).thenReturn(new FilePurgeRecoveryService.Page(id,2,1,1));
        when(service.recoverPage(id,2)).thenThrow(new IllegalStateException()).thenReturn(new FilePurgeRecoveryService.Page(null,0,0,0));
        var p=new PurgeFinalizationConfiguration.Properties(true,2,Duration.ofSeconds(1));var signals=new ProcessingSignals();
        try(var poller=new PurgeFinalizationConfiguration.Poller(service,p,signals)){poller.poll();poller.poll();poller.poll();}
        try(var restart=new PurgeFinalizationConfiguration.Poller(service,p,signals)){restart.poll();}
        verify(service,times(2)).recoverPage(null,2);verify(service,times(2)).recoverPage(id,2);
        assertThat(signals.fresh("finalization",Duration.ofSeconds(5))).isTrue();
    }
}
