package com.vaultdrive.storage;

import java.util.Map;
import java.util.UUID;

/** CopyObject has one dispatcher. An absent/failed HEAD is never a negative receipt. */
public final class CopyStorage {
    private CopyStorage() { }
    public record ObjectInfo(long size, String etag, String contentType, Map<String, String> metadata) { }
    public record Command(StorageTarget source, StorageTarget destination, UUID operationId,
                          String sourceEtag, String contentType) { }
    public static class Uncertain extends RuntimeException {
        public Uncertain() { super("Copy storage outcome unavailable"); }
    }
}
