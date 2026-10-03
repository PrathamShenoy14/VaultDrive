package com.vaultdrive.file.dto;

import java.time.Instant;
import java.util.UUID;

public record FileResponse(
        UUID id,
        String name,
        UUID folderId,
        String contentType,
        long sizeBytes,
        Instant createdAt,
        Instant updatedAt
) {
}
