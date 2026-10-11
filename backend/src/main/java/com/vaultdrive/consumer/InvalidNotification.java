package com.vaultdrive.consumer;

import java.util.UUID;

final class InvalidNotification extends RuntimeException {
    enum Reason { MALFORMED, UNSUPPORTED_VERSION, UNSUPPORTED_TYPE, UNKNOWN_EVENT, EVENT_MISMATCH, IDENTITY_CONFLICT }

    private final Reason reason;
    private final UUID eventId;

    InvalidNotification(Reason reason, UUID eventId) {
        super(reason.name()); // Never propagate parser, payload or database exception text.
        this.reason = reason;
        this.eventId = eventId;
    }

    Reason reason() { return reason; }
    UUID eventId() { return eventId; }
}
