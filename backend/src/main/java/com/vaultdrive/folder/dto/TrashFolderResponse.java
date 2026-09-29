package com.vaultdrive.folder.dto;

import java.time.Instant;
import java.util.UUID;

public record TrashFolderResponse(
        UUID id,
        String name,
        UUID originalParentFolderId,
        Instant deletedAt
) {
}