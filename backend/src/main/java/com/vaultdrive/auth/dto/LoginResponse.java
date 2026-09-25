package com.vaultdrive.auth.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {}