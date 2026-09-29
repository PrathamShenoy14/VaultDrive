package com.vaultdrive.folder.dto;

import java.util.UUID;

public record RestoreFolderResponse(
        UUID folderId,
        String restoredName,
        UUID parentFolderId
) {
}