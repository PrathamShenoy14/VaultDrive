package com.vaultdrive.folder.dto;

import java.util.List;

public record FolderPageResponse(List<FolderResponse> content, int page, int size,
                                 long totalElements, int totalPages) { }
