package com.vaultdrive.file;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.UploadFileResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.vaultdrive.file.dto.FileDownload;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadFileResponse> uploadFile(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID folderId
    ) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        UploadFileResponse response =
                fileService.uploadFile(
                        ownerId,
                        folderId,
                        file
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
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

    @GetMapping("/{fileId}/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID fileId
    ) {
        UUID ownerId =
                UUID.fromString(jwt.getSubject());
    
        FileDownload download =
                fileService.downloadFile(
                        ownerId,
                        fileId
                );
    
        StreamingResponseBody responseBody =
                outputStream -> {
                    try (InputStream inputStream =
                                 download.inputStream()) {
    
                        inputStream.transferTo(outputStream);
                    }
                };
    
        ContentDisposition contentDisposition =
                ContentDisposition
                        .attachment()
                        .filename(
                                download.name(),
                                StandardCharsets.UTF_8
                        )
                        .build();
    
        return ResponseEntity.ok()
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