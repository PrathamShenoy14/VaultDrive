package com.vaultdrive.operations;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated @ConfigurationProperties("vaultdrive.operations")
public record OperationsProperties(@DefaultValue("false") boolean deployment,
        @DefaultValue("false") boolean probesEnabled,
        @DefaultValue("15s") Duration probeDelay,
        @DefaultValue("2m") Duration staleAfter,
        @DefaultValue("5m") Duration backlogAge,
        @DefaultValue("30m") Duration recoveryAge) {
    @AssertTrue(message = "Operational timing must be bounded and stale-after exceed probe delay")
    public boolean isTimingValid() {
        return valid(probeDelay, Duration.ofSeconds(5), Duration.ofMinutes(1))
                && valid(staleAfter, Duration.ofSeconds(15), Duration.ofMinutes(10))
                && staleAfter.compareTo(probeDelay.multipliedBy(2)) >= 0
                && valid(backlogAge, Duration.ofMinutes(1), Duration.ofDays(1))
                && valid(recoveryAge, Duration.ofMinutes(1), Duration.ofDays(1));
    }
    private static boolean valid(Duration d, Duration min, Duration max) {
        return d != null && d.compareTo(min) >= 0 && d.compareTo(max) <= 0;
    }
}
