package com.vaultdrive.file.dto;

import java.util.List;

public record FilePageResponse(
        List<FileResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
