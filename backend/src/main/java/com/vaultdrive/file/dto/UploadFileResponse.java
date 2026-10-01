package com.vaultdrive.file.dto;

import java.util.UUID;

public record UploadFileResponse(
        UUID fileId,
        String name,
        UUID folderId,
        String contentType,
        long sizeBytes
) {
}
