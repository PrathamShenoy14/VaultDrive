package com.vaultdrive.outbox;

public enum PublishResult {
    CONFIRMED,
    NACK,
    RETURNED,
    TIMEOUT,
    BROKER_ERROR,
    INTERRUPTED,
    SERIALIZATION_ERROR
}
