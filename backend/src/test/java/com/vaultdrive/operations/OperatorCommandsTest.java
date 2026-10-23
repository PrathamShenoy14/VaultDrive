package com.vaultdrive.operations;

import com.vaultdrive.consumer.RetainedEventReplay;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperatorCommandsTest {
    OperatorTransactions tx=mock(OperatorTransactions.class);RetainedEventReplay replay=mock(RetainedEventReplay.class);
    OperationalState state=mock(OperationalState.class);ConfigurableApplicationContext context=mock(ConfigurableApplicationContext.class);
    OperatorCommands commands=new OperatorCommands(tx,replay,state,context);
    @Test void rejectsInvalidBatchBeforeAnyMutationAndAlwaysClosesProcessContext() {
        var id=UUID.randomUUID();
        for(String targets:new String[]{id+",invalid",id+","+id,java.util.stream.IntStream.range(0,21).mapToObj(i->UUID.randomUUID().toString()).collect(java.util.stream.Collectors.joining(","))}){
            assertThatThrownBy(()->commands.run(new DefaultApplicationArguments("--operator.command=replay-event","--operator.incident=INC-1","--operator.ids="+targets)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(tx,replay);verify(context,times(3)).close();
    }
    @Test void replayFailureRecordsUnknownAndDoesNotProceedToOtherTargets() {
        UUID id=UUID.randomUUID(),other=UUID.randomUUID();doThrow(new IllegalStateException()).when(replay).replay(id);
        assertThatThrownBy(()->commands.run(new DefaultApplicationArguments("--operator.command=replay-event","--operator.incident=INC-1","--operator.ids="+id+","+other)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Replay uncertain");
        verify(tx).finishReplay(any(),eq(false));verify(replay,never()).replay(other);verify(context).close();
    }
}
