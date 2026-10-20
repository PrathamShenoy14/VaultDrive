package com.vaultdrive.folder.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TrashPageResponse(List<Entry> content, int page, int size, long totalElements, int totalPages) {
    public record Entry(UUID id, String resourceType, String name, UUID originalParentId,
                        Instant deletedAt, Instant purgeRequestedAt, String lifecycleState,
                        List<String> allowedCapabilities) { }
}
