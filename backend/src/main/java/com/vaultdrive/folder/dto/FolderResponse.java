package com.vaultdrive.folder.dto;

import java.time.Instant;
import java.util.UUID;

public record FolderResponse(
        UUID id,
        String name,
        UUID parentFolderId,
        Instant createdAt,
        Instant updatedAt
) {
}