package com.vaultdrive.folder.dto;

import jakarta.validation.constraints.NotNull;

public record RenameFolderRequest(
        @NotNull String name
) {
}