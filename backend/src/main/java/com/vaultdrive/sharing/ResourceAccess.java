package com.vaultdrive.sharing;

import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.folder.dto.FolderResponse;
import java.time.Instant;
import java.util.UUID;

/** Internal snapshot. Storage identity and private ancestry never enter public DTOs. */
public record ResourceAccess(UUID id, UUID ownerId, UUID parentId, String name, String contentType,
                             long sizeBytes, String storageKey, Instant createdAt, Instant updatedAt,
                             ResourceRole role, boolean parentVisible, boolean active) {
    public FileResponse fileResponse() {
        return new FileResponse(id, name, parentVisible ? parentId : null, contentType, sizeBytes,
                createdAt, updatedAt, role, role.capabilities(false));
    }

    public FolderResponse folderResponse() {
        return new FolderResponse(id, name, parentVisible ? parentId : null, createdAt, updatedAt,
                role, role.capabilities(true));
    }
}
