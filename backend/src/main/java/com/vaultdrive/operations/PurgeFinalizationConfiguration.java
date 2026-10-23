package com.vaultdrive.operations;

import com.vaultdrive.worker.FilePurgeRecoveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.*;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PurgeFinalizationConfiguration.Properties.class)
public class PurgeFinalizationConfiguration {
    @Validated @ConfigurationProperties("vaultdrive.jobs.finalization")
    public record Properties(@DefaultValue("false") boolean enabled,@DefaultValue("20") @Min(1) @Max(100) int pageSize,
                             @DefaultValue("1m") Duration pollDelay) {
        @AssertTrue(message="Finalization delay must be 1s..1h")
        public boolean isTimingValid(){return pollDelay!=null && pollDelay.compareTo(Duration.ofSeconds(1))>=0 && pollDelay.compareTo(Duration.ofHours(1))<=0;}
    }
    @Bean(initMethod="start",destroyMethod="close")
    @ConditionalOnProperty(name="vaultdrive.jobs.finalization.enabled",havingValue="true")
    Poller purgeFinalizationPoller(FilePurgeRecoveryService service,Properties p,ProcessingSignals signals){return new Poller(service,p,signals);}
    static final class Poller implements AutoCloseable, org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
        private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(Poller.class);
        private final FilePurgeRecoveryService service;private final Properties p;private final ProcessingSignals signals;
        private UUID cursor;
        private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"purge-finalization");t.setDaemon(true);return t;});
        Poller(FilePurgeRecoveryService service,Properties p,ProcessingSignals signals){this.service=service;this.p=p;this.signals=signals;}
        void start(){long delay=p.pollDelay().toMillis();executor.scheduleWithFixedDelay(this::poll,delay,delay,TimeUnit.MILLISECONDS);}
        synchronized void poll(){
            try{
                var page=service.recoverPage(cursor,p.pageSize());cursor=page.nextCursor();signals.success("finalization");
                log.info("Purge finalization examined={} finalized={} blocked={}",page.examined(),page.finalized(),page.blocked());
            }catch(RuntimeException failure){log.warn("Purge finalization scan failed");}
        }
        public void close(){executor.shutdownNow();}
        public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event){close();}
    }
}
