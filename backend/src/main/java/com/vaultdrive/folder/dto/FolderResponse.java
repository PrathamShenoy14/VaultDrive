package com.vaultdrive.folder.dto;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.vaultdrive.sharing.ResourceRole;

public record FolderResponse(
        UUID id,
        String name,
        UUID parentFolderId,
        Instant createdAt,
        Instant updatedAt,
        ResourceRole effectiveRole,
        List<String> allowedCapabilities
) {
    public FolderResponse(UUID id, String name, UUID parentFolderId, Instant createdAt, Instant updatedAt) {
        this(id, name, parentFolderId, createdAt, updatedAt,
                ResourceRole.OWNER, ResourceRole.OWNER.capabilities(true));
    }
}
