package com.vaultdrive.file.dto;

import java.util.UUID;

public record MoveFileRequest(
        UUID folderId
) {
}
