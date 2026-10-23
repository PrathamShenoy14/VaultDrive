package com.vaultdrive.operations;

import com.vaultdrive.outbox.OutboxPublisherProperties;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.worker.JobWorker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real dependencies with owned empty queues. An unreachable local port simulates broker loss without stopping a shared broker. */
@SpringBootTest @ActiveProfiles("test") @DirtiesContext @Timeout(45)
@EnabledIfEnvironmentVariable(named="RABBITMQ_TEST_VHOST",matches="vaultdrive_outbox_test")
class ReadinessInfrastructureIntegrationTest {
    static final String SCHEMA="readiness_infrastructure_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema="+SCHEMA);
        r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @Autowired OperationalState state;@Autowired ObjectStorageService storage;
    @Test void realDependenciesReportReadyAndConsumerStopOrConnectionLossFailsClosed() throws Exception {
        String purge="vaultdrive.purge.requests.test.ready."+UUID.randomUUID();
        String permission="vaultdrive.permission.activity.test.ready."+UUID.randomUUID();
        var factory=factory(5672);var admin=new RabbitAdmin(factory);var containers=new ArrayList<SimpleMessageListenerContainer>();
        var owned=new ArrayList<String>();
        try {
            for(String name:List.of(purge,permission)){
                assertThat(admin.getQueueProperties(name)).isNull();admin.declareQueue(QueueBuilder.durable(name).quorum().build());owned.add(name);
                var c=new SimpleMessageListenerContainer(factory);c.setQueueNames(name);c.setAutoDeclare(false);c.setAcknowledgeMode(AcknowledgeMode.MANUAL);
                c.setPrefetchCount(1);c.setShutdownTimeout(1000);c.setForceStop(true);
                c.setMessageListener((ChannelAwareMessageListener)(m,ch)->ch.basicAck(m.getMessageProperties().getDeliveryTag(),false));
                containers.add(c);c.start();
            }
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(containers.stream().anyMatch(c->c.getActiveConsumerCount()==0) && System.nanoTime()<deadline)Thread.sleep(10);
            var context=mock(ApplicationContext.class);
            for(int i=0;i<2;i++){
                String name=i==0?"purgeNotificationContainer":"permissionNotificationContainer";
                when(context.containsBean(name)).thenReturn(true);when(context.getBean(name)).thenReturn(containers.get(i));
            }
            var signals=new ProcessingSignals();for(String name:List.of("publisher","reconciliation","expiry","finalization"))signals.success(name);
            var worker=mock(JobWorker.class);when(worker.operational(any())).thenReturn(true);
            @SuppressWarnings("unchecked") ObjectProvider<JobWorker> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(worker);
            var routes=new OutboxPublisherProperties(false,10,20,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(1),Duration.ofSeconds(60),"unused",purge,"purge",permission,"permission");
            var p=new OperationsProperties(false,true,Duration.ofSeconds(15),Duration.ofMinutes(2),Duration.ofMinutes(5),Duration.ofMinutes(30));
            var readiness=new Readiness(p,state,storage,factory,routes,signals,context,provider);
            readiness.probe();assertThat(readiness.snapshot().components()).allSatisfy((name,ok)->assertThat(ok).as(name).isTrue());assertThat(readiness.ready()).isTrue();
            containers.get(1).stop();readiness.probe();assertThat(readiness.ready()).isFalse();
            assertThat(readiness.snapshot().components()).containsEntry("permissionNotificationContainer",false);
            var unreachable=factory(1);
            try{
                var disconnected=new Readiness(p,state,storage,unreachable,routes,signals,context,provider);
                disconnected.probe();assertThat(disconnected.ready()).isFalse();assertThat(disconnected.snapshot().components()).containsEntry("rabbitmq",false);
            }finally{unreachable.destroy();}
        }finally{
            containers.forEach(SimpleMessageListenerContainer::stop);
            for(String name:owned)admin.deleteQueue(name);factory.destroy();
        }
    }
    CachingConnectionFactory factory(int port){
        var factory=new CachingConnectionFactory("127.0.0.1",port);factory.setVirtualHost("vaultdrive_outbox_test");
        factory.setUsername(System.getenv("RABBITMQ_USERNAME"));factory.setPassword(System.getenv("RABBITMQ_PASSWORD"));factory.setConnectionTimeout(1000);
        factory.getRabbitConnectionFactory().setChannelRpcTimeout(2000);return factory;
    }
}
