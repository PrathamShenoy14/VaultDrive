package com.vaultdrive.operations;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperationsProperties.class)
public class OperationsConfiguration {
    // Runs before any worker initMethod or listener lifecycle can consume work.
    @Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor operationalStartupGuard(org.springframework.core.env.Environment env) {
        return factory -> new DeploymentSafeguards(env, org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("vaultdrive.operations", org.springframework.boot.context.properties.bind.Bindable.of(OperationsProperties.class))
                .orElseGet(() -> new OperationsProperties(false,false,java.time.Duration.ofSeconds(15),java.time.Duration.ofMinutes(2),
                        java.time.Duration.ofMinutes(5),java.time.Duration.ofMinutes(30)))).validate();
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = "vaultdrive.operations.probes-enabled", havingValue = "true")
    ProbeLoop operationalProbeLoop(Readiness readiness, OperationsProperties p, RequestAdmission admission) {
        return new ProbeLoop(readiness, p, admission);
    }
    static final class ProbeLoop implements AutoCloseable, org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProbeLoop.class);
        private final Readiness readiness;
        private final OperationsProperties p;
        private final RequestAdmission admission;
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var t = new Thread(task, "readiness-probes"); t.setDaemon(true); return t;
        });
        ProbeLoop(Readiness readiness, OperationsProperties p, RequestAdmission admission) { this.readiness=readiness; this.p=p; this.admission=admission; }
        void start() { executor.scheduleWithFixedDelay(this::poll, p.probeDelay().toMillis(), p.probeDelay().toMillis(), TimeUnit.MILLISECONDS); }
        void poll() {
            try {
                readiness.probe();
                log.info("Operational readiness ready={} components={} work={} admission={}", readiness.ready(),
                        readiness.snapshot().components(), readiness.snapshot().work(), admission.counts());
            } catch (RuntimeException failure) { log.warn("Operational probe failed"); }
        }
        public void close() { executor.shutdownNow(); }
        public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event) { close(); }
    }
}
