package com.vaultdrive.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("vaultdrive.admission")
public record AdmissionProperties(
        @DefaultValue @Valid Policy authentication,
        @DefaultValue @Valid Policy multipart,
        @DefaultValue @Valid Policy downloads,
        @DefaultValue @Valid Policy metadata) {
    public record Policy(@DefaultValue("8") @Min(1) @Max(128) int concurrency,
                         @DefaultValue("20") @Min(1) @Max(10000) int burst,
                         @DefaultValue("10") @Min(1) @Max(10000) int perSecond) { }
}
