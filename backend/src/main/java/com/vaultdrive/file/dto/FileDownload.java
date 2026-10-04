package com.vaultdrive.file.dto;

import java.io.InputStream;

public record FileDownload(
        String name,
        String contentType,
        long sizeBytes,
        InputStream inputStream
) {
}