package com.vaultdrive.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vaultdrive.uploads.finalization-recovery")
public record UploadFinalizationRecoveryProperties(@DefaultValue("20") int pageSize) {
    public UploadFinalizationRecoveryProperties {
        if (pageSize < 1 || pageSize > 100) { throw new IllegalArgumentException("Recovery page must be 1..100"); }
    }
}
