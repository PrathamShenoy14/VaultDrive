package com.vaultdrive.consumer;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("vaultdrive.jobs.reconciliation")
public record MissingJobReconciliationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5m") Duration eligibilityDelay,
        @DefaultValue("1m") Duration pollDelay,
        @DefaultValue("20") @Min(1) @Max(100) int pageSize) {
    @AssertTrue(message = "Reconciliation delay must be 1m..7d and poll delay 1s..1d")
    public boolean isTimingValid() {
        return eligibilityDelay != null && eligibilityDelay.compareTo(Duration.ofMinutes(1)) >= 0
                && eligibilityDelay.compareTo(Duration.ofDays(7)) <= 0
                && pollDelay != null && pollDelay.compareTo(Duration.ofSeconds(1)) >= 0
                && pollDelay.compareTo(Duration.ofDays(1)) <= 0;
    }
}
