package com.vaultdrive.operations;

import com.rabbitmq.client.*;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.worker.JobWorker;
import com.vaultdrive.outbox.OutboxPublisherProperties;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadinessTest {
    OperationalState state=mock(OperationalState.class);ObjectStorageService storage=mock(ObjectStorageService.class);
    ConnectionFactory rabbit=mock(ConnectionFactory.class);Connection connection=mock(Connection.class);Channel channel=mock(Channel.class);
    ApplicationContext context=mock(ApplicationContext.class);JobWorker worker=mock(JobWorker.class);
    @SuppressWarnings("unchecked") ObjectProvider<JobWorker> workers=mock(ObjectProvider.class);
    ProcessingSignals signals=new ProcessingSignals();Readiness readiness;
    @BeforeEach void setup() throws Exception {
        when(state.inspect()).thenReturn(Map.of("exhaustedJobs",0L));when(rabbit.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
        var ok=mock(AMQP.Queue.DeclareOk.class);when(ok.getConsumerCount()).thenReturn(1);when(channel.queueDeclarePassive(anyString())).thenReturn(ok);
        for(String name:new String[]{"purgeNotificationContainer","permissionNotificationContainer"}){
            var container=mock(SimpleMessageListenerContainer.class);when(container.isRunning()).thenReturn(true);when(container.getActiveConsumerCount()).thenReturn(1);
            when(context.containsBean(name)).thenReturn(true);when(context.getBean(name)).thenReturn(container);
        }
        when(workers.getIfAvailable()).thenReturn(worker);when(worker.operational(any())).thenReturn(true);
        for(String name:new String[]{"publisher","reconciliation","expiry","finalization"})signals.success(name);
        var p=new OperationsProperties(false,true,Duration.ofSeconds(15),Duration.ofMinutes(2),Duration.ofMinutes(5),Duration.ofMinutes(30));
        var routes=new OutboxPublisherProperties(false,10,20,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(1),Duration.ofSeconds(60),"ex","purge","purge","permission","permission");
        readiness=new Readiness(p,state,storage,rabbit,routes,signals,context,workers);
    }
    @Test void startsUnreadyAndCachedReadsDoNoDependencyIo() {
        assertThat(readiness.ready()).isFalse();readiness.probe();assertThat(readiness.ready()).isTrue();
        for(int i=0;i<100;i++)assertThat(readiness.ready()).isTrue();verify(state,times(1)).inspect();verify(storage,times(1)).checkAvailability();
    }
    @Test void contextClosureImmediatelyFailsReadinessWithoutWaitingForAProbe() {
        readiness.probe();assertThat(readiness.ready()).isTrue();
        readiness.onApplicationEvent(new org.springframework.context.event.ContextClosedEvent(context));assertThat(readiness.ready()).isFalse();
        verify(state,times(1)).inspect();
    }
    @Test void databaseStorageAndBrokerFailuresEachFailReadiness() {
        when(state.inspect()).thenThrow(new IllegalStateException());readiness.probe();assertThat(readiness.ready()).isFalse();
        doReturn(Map.of()).when(state).inspect();doThrow(new IllegalStateException()).when(storage).checkAvailability();
        readiness.probe();assertThat(readiness.ready()).isFalse();doNothing().when(storage).checkAvailability();
        when(rabbit.createConnection()).thenThrow(new IllegalStateException());readiness.probe();assertThat(readiness.ready()).isFalse();
    }
    @Test void stoppedConsumerAndStalledWorkerFailEvenWithHealthyDependencies() {
        var container=(SimpleMessageListenerContainer)context.getBean("permissionNotificationContainer");when(container.isRunning()).thenReturn(false);
        readiness.probe();assertThat(readiness.ready()).isFalse();when(container.isRunning()).thenReturn(true);
        when(worker.operational(any())).thenReturn(false);readiness.probe();assertThat(readiness.ready()).isFalse();
    }
    @Test void exhaustedWorkFailsWhileRetainedBusinessRecoveryRemainsVisibleWithoutRemovingTraffic() {
        when(state.inspect()).thenReturn(Map.of("exhaustedJobs",1L));readiness.probe();assertThat(readiness.ready()).isFalse();
        when(state.inspect()).thenReturn(Map.of("pendingRecovery",1L));readiness.probe();assertThat(readiness.ready()).isTrue();
        assertThat(readiness.snapshot().work()).containsEntry("pendingRecovery",1L);
        when(state.inspect()).thenReturn(Map.of("unresolvedExpiry",1L));readiness.probe();assertThat(readiness.ready()).isTrue();
    }
    @Test void staleSnapshotFailsClosedAndConsumersNeedBrokerPresence() throws Exception {
        readiness.probe();var s=readiness.snapshot();
        org.springframework.test.util.ReflectionTestUtils.setField(readiness,"snapshot",new Readiness.Snapshot(true,s.components(),s.work(),System.nanoTime()-Duration.ofMinutes(3).toNanos()));
        assertThat(readiness.ready()).isFalse();
        when(channel.queueDeclarePassive(anyString())).thenReturn(mock(AMQP.Queue.DeclareOk.class));readiness.probe();assertThat(readiness.ready()).isFalse();
    }
}
