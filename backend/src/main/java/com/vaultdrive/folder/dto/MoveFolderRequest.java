package com.vaultdrive.folder.dto;

import java.util.UUID;

public record MoveFolderRequest(
        UUID destinationFolderId
) {
}