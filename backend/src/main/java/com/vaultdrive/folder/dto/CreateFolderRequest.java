package com.vaultdrive.folder.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateFolderRequest(
        @NotNull String name,
        UUID parentFolderId
) {
}