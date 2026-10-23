package com.vaultdrive.file;

import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.folder.dto.TrashPageResponse;
import com.vaultdrive.deletion.DeletionOperationController;
import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.RenameFileRequest;
import com.vaultdrive.file.dto.MoveFileRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vaultdrive.file.dto.FileDownload;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping
    public ResponseEntity<FilePageResponse> listFiles(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        FilePageResponse response =
                fileService.listFiles(
                        ownerId,
                        folderId,
                        page,
                        size
                );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{fileId}")
    public FileResponse getFile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID fileId) {
        return fileService.getFile(UUID.fromString(jwt.getSubject()), fileId);
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID fileId, jakarta.servlet.http.HttpServletRequest request
    ) {
        UUID ownerId =
                UUID.fromString(jwt.getSubject());

        FileDownload download =
                fileService.downloadFile(
                        ownerId,
                        fileId
                );

        StreamingResponseBody responseBody = com.vaultdrive.operations.AdmissionLease.streaming(request, download.inputStream());

        ContentDisposition contentDisposition =
                ContentDisposition
                        .attachment()
                        .filename(
                                download.name(),
                                StandardCharsets.UTF_8
                        )
                        .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition.toString()
                )
                .contentType(
                        resolveContentType(
                                download.contentType()
                        )
                )
                .contentLength(download.sizeBytes())
                .body(responseBody);
    }

    @PatchMapping("/{fileId}/name")
    public ResponseEntity<FileResponse> renameFile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID fileId,
            @RequestBody RenameFileRequest request
    ) {
        UUID actorId =
                UUID.fromString(jwt.getSubject());

        FileResponse response =
                fileService.renameFile(
                        actorId,
                        fileId,
                        request.name()
                );

        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{fileId}/move")
    public ResponseEntity<FileResponse> moveFile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID fileId,
            @RequestBody MoveFileRequest request
    ) {
        UUID ownerId =
                UUID.fromString(jwt.getSubject());
    
        FileResponse response =
                fileService.moveFile(
                        ownerId,
                        fileId,
                        request.folderId()
                );
    
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> trashFile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID fileId
    ) {
        UUID actorId =
                UUID.fromString(jwt.getSubject());
    
        fileService.trashFile(
                actorId,
                fileId
        );
    
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trash")
    public ResponseEntity<TrashPageResponse> listTrash(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUID ownerId =
                UUID.fromString(jwt.getSubject());
    
        TrashPageResponse response =
                fileService.listTrash(
                        ownerId,
                        page,
                        size
                );
    
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{fileId}/restore")
    public ResponseEntity<FileResponse> restoreFile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID fileId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
    
        FileResponse response =
                fileService.restoreFile(
                        ownerId,
                        fileId
                );
    
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{fileId}/permanent")
    public ResponseEntity<DeletionOperationController.Accepted> permanentlyDeleteFile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID fileId
    ) {
        UUID ownerId =
                UUID.fromString(jwt.getSubject());

        UUID operation = fileService.requestPermanentDeletion(ownerId, fileId);
        return DeletionOperationController.accepted(operation);
    }
    
    private MediaType resolveContentType(
            String contentType
    ) {
        if (contentType == null ||
                contentType.isBlank()) {

            return MediaType.APPLICATION_OCTET_STREAM;
        }

        try {
            return MediaType.parseMediaType(
                    contentType
            );
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
