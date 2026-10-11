package com.vaultdrive.storage;

/** Sanitized failure classification; no storage URLs, keys, credentials or raw provider messages. */
public final class StorageDeletionException extends RuntimeException {
    public enum Kind { TRANSIENT, ACCESS_DENIED, BINDING_MISMATCH, STORAGE_FAILURE }
    private final Kind kind;

    public StorageDeletionException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public Kind kind() { return kind; }
    public boolean retryable() { return kind == Kind.TRANSIENT; }
}
