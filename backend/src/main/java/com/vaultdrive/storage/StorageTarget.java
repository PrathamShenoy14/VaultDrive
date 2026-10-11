package com.vaultdrive.storage;

/** A storage binding contains no credentials and is never an instruction to create a client. */
public record StorageTarget(String namespace, String endpoint, String region, String bucket, String key) {
    public StorageTarget {
        if (blank(namespace) || blank(endpoint) || blank(region) || blank(bucket) || blank(key)) {
            throw new IllegalArgumentException("Incomplete storage identity");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
