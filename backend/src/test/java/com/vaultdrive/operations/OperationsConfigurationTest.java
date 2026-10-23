package com.vaultdrive.operations;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsConfigurationTest {
    OperationsProperties p(boolean deployment,boolean probes){return new OperationsProperties(deployment,probes,Duration.ofSeconds(15),Duration.ofMinutes(2),Duration.ofMinutes(5),Duration.ofMinutes(30));}
    @Test void localModeDoesNotRequireOrStartAutonomousProcessing() {
        new DeploymentSafeguards(new MockEnvironment(),p(false,false)).validate();
        new ApplicationContextRunner().withUserConfiguration(OperationsConfiguration.class)
                .run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(OperationsConfiguration.ProbeLoop.class);});
    }
    @Test void deploymentFailsBeforeAnyRuntimeBeanInitialization() {
        var initialized=new java.util.concurrent.atomic.AtomicBoolean();
        new ApplicationContextRunner().withUserConfiguration(OperationsConfiguration.class)
                .withPropertyValues("vaultdrive.operations.deployment=true","vaultdrive.operations.probes-enabled=true")
                .withBean("unsafeRuntime",Object.class,()->{initialized.set(true);return new Object();})
                .run(c->{assertThat(c).hasFailed();assertThat(initialized).isFalse();});
        new ApplicationContextRunner().withUserConfiguration(OperationsConfiguration.class)
                .withPropertyValues("vaultdrive.operations.recovery-age=0s")
                .withBean("unsafeRuntime",Object.class,()->{initialized.set(true);return new Object();})
                .run(c->{assertThat(c).hasFailed();assertThat(initialized).isFalse();});
    }
    MockEnvironment deployment(){
        var env=new MockEnvironment();
        for(String key:new String[]{"vaultdrive.outbox.publisher.enabled","vaultdrive.purge.consumer.enabled",
                "vaultdrive.sharing.notifications.consumer.enabled","vaultdrive.jobs.worker.enabled",
                "vaultdrive.jobs.reconciliation.enabled","vaultdrive.multipart.expiry.enabled","vaultdrive.jobs.finalization.enabled","spring.rabbitmq.publisher-returns"})env.setProperty(key,"true");
        for(String key:new String[]{"spring.rabbitmq.username","spring.rabbitmq.password","spring.rabbitmq.virtual-host",
                "storage.s3.namespace","storage.s3.bucket","storage.s3.region","storage.s3.access-key","storage.s3.secret-key"})env.setProperty(key,"fixture");
        return env.withProperty("spring.jpa.hibernate.ddl-auto","validate").withProperty("spring.rabbitmq.publisher-confirm-type","correlated")
                .withProperty("spring.jpa.open-in-view","false").withProperty("spring.rabbitmq.dynamic","false")
                .withProperty("storage.s3.endpoint","https://garage.example.test");
    }
    @Test void validatesEveryRequiredProcessorAndStorageAndTiming() {
        new DeploymentSafeguards(deployment(),p(true,true)).validate();
        for(String key:new String[]{"vaultdrive.outbox.publisher.enabled","vaultdrive.purge.consumer.enabled",
                "vaultdrive.sharing.notifications.consumer.enabled","vaultdrive.jobs.worker.enabled",
                "vaultdrive.jobs.reconciliation.enabled","vaultdrive.multipart.expiry.enabled","vaultdrive.jobs.finalization.enabled"}){
            assertThatThrownBy(()->new DeploymentSafeguards(deployment().withProperty(key,"false"),p(true,true)).validate()).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(()->new DeploymentSafeguards(deployment().withProperty("storage.s3.endpoint","http://garage"),p(true,true)).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new DeploymentSafeguards(deployment().withProperty("spring.jpa.open-in-view","true"),p(true,true)).validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new DeploymentSafeguards(deployment().withProperty("vaultdrive.multipart.expiry.poll-delay","3m"),p(true,true)).validate()).isInstanceOf(IllegalStateException.class);
    }
    @Test void operatorRejectsAnyBackgroundExecutionOrHttp() {
        var env=new MockEnvironment().withProperty("vaultdrive.operator.enabled","true").withProperty("spring.main.web-application-type","none")
                .withProperty("spring.jpa.hibernate.ddl-auto","validate").withProperty("spring.flyway.enabled","false").withProperty("spring.rabbitmq.dynamic","false");
        new DeploymentSafeguards(env,p(false,false)).validate();
        env.setProperty("vaultdrive.jobs.worker.enabled","true");
        assertThatThrownBy(()->new DeploymentSafeguards(env,p(false,false)).validate()).isInstanceOf(IllegalStateException.class);
        env.setProperty("vaultdrive.jobs.worker.enabled","false");env.setProperty("spring.flyway.enabled","true");
        assertThatThrownBy(()->new DeploymentSafeguards(env,p(false,false)).validate()).isInstanceOf(IllegalStateException.class);
        env.setProperty("spring.flyway.enabled","false");env.setProperty("spring.jpa.hibernate.ddl-auto","create");
        assertThatThrownBy(()->new DeploymentSafeguards(env,p(false,false)).validate()).isInstanceOf(IllegalStateException.class);
    }
    @Test void propertyValidationRejectsZeroCapacityAndInvalidExpiryTiming() {
        new ApplicationContextRunner().withUserConfiguration(AdmissionConfiguration.class)
                .withPropertyValues("vaultdrive.admission.downloads.concurrency=0").run(c->assertThat(c).hasFailed());
        new ApplicationContextRunner().withUserConfiguration(MultipartExpiryConfiguration.class)
                .withPropertyValues("vaultdrive.multipart.expiry.poll-delay=0s").run(c->assertThat(c).hasFailed());
        new ApplicationContextRunner().withUserConfiguration(OperationsConfiguration.class)
                .withPropertyValues("vaultdrive.operations.stale-after=15s","vaultdrive.operations.probe-delay=15s").run(c->assertThat(c).hasFailed());
    }
}
