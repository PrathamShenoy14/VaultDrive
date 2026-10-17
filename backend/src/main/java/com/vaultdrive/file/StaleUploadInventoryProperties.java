package com.vaultdrive.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("vaultdrive.uploads.inventory")
public record StaleUploadInventoryProperties(
        @DefaultValue("1h") Duration minimumAge,
        @DefaultValue("20") int pageSize) {
    public StaleUploadInventoryProperties {
        if (minimumAge == null || minimumAge.compareTo(Duration.ofSeconds(1)) < 0
                || minimumAge.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("Inventory age must be 1s..365d");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Inventory page must be 1..100");
        }
    }
}
