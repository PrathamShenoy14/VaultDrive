package com.vaultdrive.common.health;

public record HealthResponse(
        String status,
        String service
) {}