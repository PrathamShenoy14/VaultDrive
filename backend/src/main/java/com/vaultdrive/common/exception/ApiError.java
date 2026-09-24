package com.vaultdrive.common.exception;

public record ApiError(
        int status,
        String error,
        String message
) {
}