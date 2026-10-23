package com.vaultdrive.operations;

import com.vaultdrive.file.MultipartCancellationService;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MultipartExpiryConfiguration.Properties.class)
public class MultipartExpiryConfiguration {
    @Validated @ConfigurationProperties("vaultdrive.multipart.expiry")
    public record Properties(@DefaultValue("false") boolean enabled,
            @DefaultValue("20") @Min(1) @Max(100) int pageSize,
            @DefaultValue("1m") Duration pollDelay) {
        @AssertTrue(message = "Expiry delay must be between 1s and 1h")
        public boolean isTimingValid() { return pollDelay != null && pollDelay.compareTo(Duration.ofSeconds(1)) >= 0
                && pollDelay.compareTo(Duration.ofHours(1)) <= 0; }
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = "vaultdrive.multipart.expiry.enabled", havingValue = "true")
    Poller multipartExpiryPoller(MultipartCancellationService service, Properties properties, ProcessingSignals signals) {
        return new Poller(service, properties, signals);
    }
    public static final class Poller implements AutoCloseable, org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Poller.class);
        private final MultipartCancellationService service;
        private final Properties properties;
        private final ProcessingSignals signals;
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "multipart-expiry"); thread.setDaemon(true); return thread;
        });
        private UUID cursor;
        Poller(MultipartCancellationService service, Properties properties, ProcessingSignals signals) {
            this.service = service; this.properties = properties; this.signals = signals;
        }
        public void start() {
            long delay = properties.pollDelay().toMillis();
            executor.scheduleWithFixedDelay(this::poll, delay, delay, TimeUnit.MILLISECONDS);
        }
        synchronized void poll() {
            try {
                var page = service.expire(cursor, properties.pageSize());
                cursor = page.nextCursor(); // Failed rows are revisited on wrap or restart, never hot-looped.
                long blocked = page.results().stream().filter(e -> e.disposition().equals("BLOCKED")).count();
                log.info("Multipart expiry scanned={} blocked={}", page.results().size(), blocked);
                if (page.results().stream().noneMatch(e -> "DATABASE_UNAVAILABLE".equals(e.reason()))) signals.success("expiry");
            } catch (RuntimeException failure) { log.warn("Multipart expiry scan failed: DATABASE_UNAVAILABLE"); }
        }
        public void close() { executor.shutdownNow(); }
        public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event) { close(); }
    }
}
