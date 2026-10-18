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
    public record ManifestPart(int partNumber, long length, String sha256, java.util.UUID registrationId, String etag) { }
    public record CompletionAck(String etag, String sha256, String checksumType) { }
    public record CompletedObject(long size, String sha256, String etag, String contentType, Map<String, String> metadata) { }
}
