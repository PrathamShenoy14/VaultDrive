package com.vaultdrive.folder;

import com.vaultdrive.folder.dto.TrashPageResponse;
import com.vaultdrive.deletion.DeletionOperationController;
import com.vaultdrive.folder.dto.RestoreFolderResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.bind.annotation.RequestParam;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trash")
public class TrashController {

    private final FolderService folderService;

    public TrashController(FolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping("/folders")
    public ResponseEntity<TrashPageResponse> listTrashedFolders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        TrashPageResponse folders =
                folderService.listTrashedFolders(ownerId, page, size);

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

    @DeleteMapping("/folders/{folderId}/permanent")
    public ResponseEntity<DeletionOperationController.Accepted> permanentlyDeleteFolder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID folderId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        UUID operation = folderService.requestPermanentDeletion(ownerId, folderId);
        return DeletionOperationController.accepted(operation);
    }
}
