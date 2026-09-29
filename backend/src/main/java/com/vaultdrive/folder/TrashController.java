package com.vaultdrive.folder;

import com.vaultdrive.folder.dto.TrashFolderResponse;
import com.vaultdrive.folder.dto.RestoreFolderResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trash")
public class TrashController {

    private final FolderService folderService;

    public TrashController(FolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping("/folders")
    public ResponseEntity<List<TrashFolderResponse>> listTrashedFolders(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        List<TrashFolderResponse> folders =
                folderService.listTrashedFolders(ownerId);

        return ResponseEntity.ok(folders);
    }

    @PostMapping("/folders/{folderId}/restore")
    public ResponseEntity<RestoreFolderResponse> restoreFolder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID folderId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        RestoreFolderResponse response =
                folderService.restoreFolder(ownerId, folderId);

        return ResponseEntity.ok(response);
    }
}
