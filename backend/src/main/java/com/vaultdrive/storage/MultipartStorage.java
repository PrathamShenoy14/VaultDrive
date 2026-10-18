package com.vaultdrive.storage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Storage DTOs contain no SDK types. Capabilities must never be logged or persisted. */
public final class MultipartStorage {
    private MultipartStorage() { }
    public record SignedPart(String url, Map<String, List<String>> requiredHeaders, Instant expiresAt) {
        @Override public String toString() { return "SignedPart[REDACTED]"; }
    }
    public record Part(int partNumber, long size, String etag, String sha256) { }
    public record Page(List<Part> parts, Integer nextMarker) { }
}
