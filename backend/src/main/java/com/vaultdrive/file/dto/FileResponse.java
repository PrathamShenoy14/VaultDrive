package com.vaultdrive.file.dto;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.vaultdrive.sharing.ResourceRole;

public record FileResponse(
        UUID id,
        String name,
        UUID folderId,
        String contentType,
        long sizeBytes,
        Instant createdAt,
        Instant updatedAt,
        ResourceRole effectiveRole,
        List<String> allowedCapabilities
) {
    public FileResponse(UUID id, String name, UUID folderId, String contentType, long sizeBytes,
                        Instant createdAt, Instant updatedAt) {
        this(id, name, folderId, contentType, sizeBytes, createdAt, updatedAt,
                ResourceRole.OWNER, ResourceRole.OWNER.capabilities(false));
    }
}
