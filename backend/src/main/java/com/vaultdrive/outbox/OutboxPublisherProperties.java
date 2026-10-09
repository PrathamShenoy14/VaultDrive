package com.vaultdrive.outbox;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "vaultdrive.outbox.publisher")
public record OutboxPublisherProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("10") @Min(1) @Max(1000) int maxAttempts,
        @DefaultValue("20") @Min(1) @Max(1000) int maxEventsPerPoll,
        @DefaultValue("30s") @NotNull Duration lease,
        @DefaultValue("10s") @NotNull Duration confirmTimeout,
        @DefaultValue("1s") @NotNull Duration initialBackoff,
        @DefaultValue("60s") @NotNull Duration maxBackoff,
        @DefaultValue("vaultdrive.outbox.events") @NotBlank String exchange,
        @DefaultValue("vaultdrive.purge.requests") @NotBlank String queue,
        @DefaultValue("purge.requested") @NotBlank String routingKey
) {
    @AssertTrue(message = "Publisher durations must be positive, at most one day, lease greater than confirm timeout, and max backoff at least initial backoff")
    public boolean isTimingValid() {
        return valid(lease) && valid(confirmTimeout) && valid(initialBackoff) && valid(maxBackoff)
                && lease.compareTo(confirmTimeout) > 0
                && maxBackoff.compareTo(initialBackoff) >= 0;
    }

    private boolean valid(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(Duration.ofDays(1)) <= 0;
    }

    public Duration backoff(int attempt) {
        long delay = initialBackoff.toMillis();
        long cap = maxBackoff.toMillis();
        for (int i = 1; i < attempt && delay < cap; i++) {
            delay = Math.min(cap, delay * 2);
        }
        return Duration.ofMillis(delay);
    }
}
