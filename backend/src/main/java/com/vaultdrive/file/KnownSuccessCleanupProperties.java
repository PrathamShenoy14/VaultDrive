package com.vaultdrive.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vaultdrive.uploads.known-success-cleanup")
public record KnownSuccessCleanupProperties(@DefaultValue("20") int pageSize) {
    public KnownSuccessCleanupProperties {
        if (pageSize < 1 || pageSize > 100) { throw new IllegalArgumentException("Cleanup page must be 1..100"); }
    }
}
