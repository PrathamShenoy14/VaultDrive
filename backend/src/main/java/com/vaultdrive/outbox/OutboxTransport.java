package com.vaultdrive.outbox;

public interface OutboxTransport {
    PublishResult publish(ClaimedOutboxEvent event);
}
