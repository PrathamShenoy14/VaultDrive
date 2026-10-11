package com.vaultdrive.worker;

import com.vaultdrive.job.BackgroundJobService;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "vaultdrive.jobs.worker")
public record JobWorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2") @Min(1) @Max(32) int concurrency,
        @DefaultValue("5s") @NotNull Duration pollDelay,
        @DefaultValue("10s") @NotNull Duration renewalInterval,
        @DefaultValue("5m") @NotNull Duration maxRuntime,
        @DefaultValue("5s") @NotNull Duration shutdownGrace
) {
    @AssertTrue(message = "Worker durations must be positive; renewal at most one third of the lease, runtime at least renewal interval, and shutdown grace at most 60s")
    public boolean isTimingValid() {
        return valid(pollDelay) && valid(renewalInterval) && valid(maxRuntime) && valid(shutdownGrace)
                && renewalInterval.compareTo(BackgroundJobService.LEASE.dividedBy(3)) <= 0
                && maxRuntime.compareTo(renewalInterval) >= 0
                && shutdownGrace.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    private static boolean valid(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(Duration.ofDays(1)) <= 0;
    }
}
