package com.vaultdrive.folder;

import com.vaultdrive.folder.dto.CreateFolderRequest;
import com.vaultdrive.folder.dto.CreateFolderResponse;
import com.vaultdrive.folder.dto.FolderResponse;
import com.vaultdrive.folder.dto.RenameFolderRequest;
import com.vaultdrive.folder.dto.MoveFolderRequest;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    // Create a folder.

    @PostMapping
    public ResponseEntity<CreateFolderResponse> createFolder(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateFolderRequest request
    ) {

        UUID ownerId = UUID.fromString(jwt.getSubject());

        UUID folderId = folderService.createFolder(
                ownerId,
                request.parentFolderId(),
                request.name()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new CreateFolderResponse(folderId));
    }

    // List the authenticated user's root folders.

    @GetMapping
    public ResponseEntity<List<FolderResponse>> listRootFolders(
            @AuthenticationPrincipal Jwt jwt
    ) {

        UUID ownerId = UUID.fromString(jwt.getSubject());

        List<FolderResponse> folders =
                folderService.listRootFolders(ownerId);

        return ResponseEntity.ok(folders);
    }

    // Retrieve a specific folder.

    @GetMapping("/{folderId}")
    public ResponseEntity<FolderResponse> getFolder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID folderId
    ) {

        UUID ownerId = UUID.fromString(jwt.getSubject());

        FolderResponse folder =
                folderService.getFolder(ownerId, folderId);

        return ResponseEntity.ok(folder);
    }

    @GetMapping("/{folderId}/children")
    public ResponseEntity<List<FolderResponse>> listChildFolders(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID folderId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
    
        List<FolderResponse> children =
                folderService.listChildFolders(ownerId, folderId);
    
        return ResponseEntity.ok(children);
    }

    @PatchMapping("/{folderId}")
    public ResponseEntity<FolderResponse> renameFolder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID folderId,
            @Valid @RequestBody RenameFolderRequest request
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
    
        FolderResponse response = folderService.renameFolder(
                ownerId,
                folderId,
                request.name()
        );
    
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{folderId}/move")
    public ResponseEntity<FolderResponse> moveFolder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID folderId,
            @RequestBody MoveFolderRequest request
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
    
        FolderResponse response = folderService.moveFolder(
                ownerId,
                folderId,
                request.destinationFolderId()
        );
    
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{folderId}")
    public ResponseEntity<Void> deleteFolder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID folderId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
    
        folderService.deleteFolder(ownerId, folderId);
    
        return ResponseEntity.noContent().build();
    }
}