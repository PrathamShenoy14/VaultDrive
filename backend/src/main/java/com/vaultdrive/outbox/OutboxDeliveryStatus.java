package com.vaultdrive.outbox;

public enum OutboxDeliveryStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED
}
