package com.vaultdrive.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MissingJobReconciliationProperties.class)
public class MissingJobReconciliationConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "vaultdrive.jobs.reconciliation.enabled", havingValue = "true")
    static class RuntimeConfiguration {
        @Bean(initMethod = "start", destroyMethod = "close")
        Poller missingJobPoller(MissingJobReconciler reconciler, MissingJobReconciliationProperties properties,
                org.springframework.beans.factory.ObjectProvider<com.vaultdrive.operations.ProcessingSignals> signals) {
            return new Poller(reconciler, properties, signals.getIfAvailable(com.vaultdrive.operations.ProcessingSignals::new));
        }
    }

    static class Poller implements AutoCloseable {
        private static final Logger log = LoggerFactory.getLogger(Poller.class);
        private final MissingJobReconciler reconciler;
        private final MissingJobReconciliationProperties properties;
        private final com.vaultdrive.operations.ProcessingSignals signals;
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "missing-job-reconciliation");
            thread.setDaemon(true);
            return thread;
        });
        private MissingJobReconciliationTransactions.Cursor cursor;

        Poller(MissingJobReconciler reconciler, MissingJobReconciliationProperties properties) {
            this(reconciler, properties, new com.vaultdrive.operations.ProcessingSignals());
        }
        Poller(MissingJobReconciler reconciler, MissingJobReconciliationProperties properties,
                com.vaultdrive.operations.ProcessingSignals signals) {
            this.reconciler = reconciler;
            this.properties = properties;
            this.signals = signals;
        }

        public void start() {
            long delay = properties.pollDelay().toMillis();
            executor.scheduleWithFixedDelay(this::poll, delay, delay, TimeUnit.MILLISECONDS);
        }

        void poll() {
            try {
                var page = reconciler.reconcilePage(cursor);
                cursor = page.nextCursor();
                if (!page.scanFailed() && page.failed() == 0) signals.success("reconciliation");
                log.info("Missing-job reconciliation scanned={} created={} alreadyPresent={} skipped={} failed={} scanFailed={}",
                        page.scanned(), page.created(), page.alreadyPresent(), page.skipped(), page.failed(), page.scanFailed());
            } catch (RuntimeException failure) {
                log.warn("Missing-job reconciliation poll failed");
            }
        }

        @Override public void close() { executor.shutdownNow(); }
    }
}
