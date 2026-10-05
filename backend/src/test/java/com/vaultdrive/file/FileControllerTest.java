package com.vaultdrive.file;

import com.vaultdrive.file.dto.UploadFileResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.file.dto.FileDownload;
import com.vaultdrive.file.exception.InvalidFilePaginationException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.file.exception.FileExtensionChangeException;
import com.vaultdrive.folder.exception.FolderNotFoundException;

import org.springframework.http.MediaType;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.List;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

    @Test
    void shouldUploadFileToRoot() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        var file = new org.springframework.mock.web.MockMultipartFile(
                "file",
                "notes.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "hello vaultdrive".getBytes()
        );

        when(fileService.uploadFile(
                eq(ownerId),
                eq(null),
                any()
        )).thenReturn(
                new UploadFileResponse(
                        fileId,
                        "notes.txt",
                        null,
                        "text/plain",
                        file.getSize()
                )
        );

        mockMvc.perform(
                        multipart("/api/v1/files")
                                .file(file)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId")
                        .value(fileId.toString()))
                .andExpect(jsonPath("$.name")
                        .value("notes.txt"))
                .andExpect(jsonPath("$.folderId")
                        .doesNotExist())
                .andExpect(jsonPath("$.contentType")
                        .value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes")
                        .value(file.getSize()));

        verify(fileService).uploadFile(
                eq(ownerId),
                eq(null),
                any()
        );
    }

    @Test
    void shouldUploadFileToFolder() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        var file = new org.springframework.mock.web.MockMultipartFile(
                "file",
                "resume.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                new byte[]{1, 2, 3, 4}
        );

        when(fileService.uploadFile(
                eq(ownerId),
                eq(folderId),
                any()
        )).thenReturn(
                new UploadFileResponse(
                        fileId,
                        "resume.pdf",
                        folderId,
                        "application/pdf",
                        file.getSize()
                )
        );

        mockMvc.perform(
                        multipart("/api/v1/files")
                                .file(file)
                                .param(
                                        "folderId",
                                        folderId.toString()
                                )
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId")
                        .value(fileId.toString()))
                .andExpect(jsonPath("$.name")
                        .value("resume.pdf"))
                .andExpect(jsonPath("$.folderId")
                        .value(folderId.toString()))
                .andExpect(jsonPath("$.contentType")
                        .value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes")
                        .value(file.getSize()));

        verify(fileService).uploadFile(
                eq(ownerId),
                eq(folderId),
                any()
        );
    }

    @Test
    void shouldRejectUnauthenticatedUpload() throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file",
                "notes.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "hello".getBytes()
        );

        mockMvc.perform(
                        multipart("/api/v1/files")
                                .file(file)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(fileService);
    }

    @Test
    void shouldListRootFilesWithDefaultPagination() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        Instant createdAt =
                Instant.parse("2026-10-03T01:00:00Z");

        Instant updatedAt =
                Instant.parse("2026-10-03T01:01:00Z");

        FileResponse fileResponse =
                new FileResponse(
                        fileId,
                        "notes.txt",
                        null,
                        "text/plain",
                        100L,
                        createdAt,
                        updatedAt
                );

        when(fileService.listFiles(
                ownerId,
                null,
                0,
                50
        )).thenReturn(
                new FilePageResponse(
                        List.of(fileResponse),
                        0,
                        50,
                        1,
                        1
                )
        );

        mockMvc.perform(
                        get("/api/v1/files")
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()")
                        .value(1))
                .andExpect(jsonPath("$.content[0].id")
                        .value(fileId.toString()))
                .andExpect(jsonPath("$.content[0].name")
                        .value("notes.txt"))
                .andExpect(jsonPath("$.content[0].folderId")
                        .doesNotExist())
                .andExpect(jsonPath("$.content[0].contentType")
                        .value("text/plain"))
                .andExpect(jsonPath("$.content[0].sizeBytes")
                        .value(100))
                .andExpect(jsonPath("$.content[0].createdAt")
                        .value("2026-10-03T01:00:00Z"))
                .andExpect(jsonPath("$.content[0].updatedAt")
                        .value("2026-10-03T01:01:00Z"))
                .andExpect(jsonPath("$.page")
                        .value(0))
                .andExpect(jsonPath("$.size")
                        .value(50))
                .andExpect(jsonPath("$.totalElements")
                        .value(1))
                .andExpect(jsonPath("$.totalPages")
                        .value(1));

        verify(fileService).listFiles(
                ownerId,
                null,
                0,
                50
        );
    }

    @Test
    void shouldListFilesFromFolderWithCustomPagination() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        FileResponse fileResponse =
                new FileResponse(
                        fileId,
                        "resume.pdf",
                        folderId,
                        "application/pdf",
                        250L,
                        Instant.parse(
                                "2026-10-03T02:00:00Z"
                        ),
                        Instant.parse(
                                "2026-10-03T02:01:00Z"
                        )
                );

        when(fileService.listFiles(
                ownerId,
                folderId,
                2,
                25
        )).thenReturn(
                new FilePageResponse(
                        List.of(fileResponse),
                        2,
                        25,
                        60,
                        3
                )
        );

        mockMvc.perform(
                        get("/api/v1/files")
                                .param(
                                        "folderId",
                                        folderId.toString()
                                )
                                .param("page", "2")
                                .param("size", "25")
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()")
                        .value(1))
                .andExpect(jsonPath("$.content[0].id")
                        .value(fileId.toString()))
                .andExpect(jsonPath("$.content[0].name")
                        .value("resume.pdf"))
                .andExpect(jsonPath("$.content[0].folderId")
                        .value(folderId.toString()))
                .andExpect(jsonPath("$.page")
                        .value(2))
                .andExpect(jsonPath("$.size")
                        .value(25))
                .andExpect(jsonPath("$.totalElements")
                        .value(60))
                .andExpect(jsonPath("$.totalPages")
                        .value(3));

        verify(fileService).listFiles(
                ownerId,
                folderId,
                2,
                25
        );
    }

    @Test
    void shouldReturnBadRequestForInvalidFilePagination()
            throws Exception {

        UUID ownerId = UUID.randomUUID();

        when(fileService.listFiles(
                ownerId,
                null,
                0,
                101
        )).thenThrow(
                new InvalidFilePaginationException(
                        "Size must be between 1 and 100"
                )
        );

        mockMvc.perform(
                        get("/api/v1/files")
                                .param("size", "101")
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status")
                        .value(400))
                .andExpect(jsonPath("$.error")
                        .value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value(
                                "Size must be between 1 and 100"
                        ));

        verify(fileService).listFiles(
                ownerId,
                null,
                0,
                101
        );
    }

    @Test
    void shouldReturnNotFoundWhenListingInaccessibleFolder()
            throws Exception {

        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        when(fileService.listFiles(
                ownerId,
                folderId,
                0,
                50
        )).thenThrow(
                new FolderNotFoundException(
                        "Folder not found"
                )
        );

        mockMvc.perform(
                        get("/api/v1/files")
                                .param(
                                        "folderId",
                                        folderId.toString()
                                )
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status")
                        .value(404))
                .andExpect(jsonPath("$.error")
                        .value("NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("Folder not found"));

        verify(fileService).listFiles(
                ownerId,
                folderId,
                0,
                50
        );
    }

    @Test
    void shouldRejectUnauthenticatedFileListing()
            throws Exception {

        mockMvc.perform(
                        get("/api/v1/files")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(fileService);
    }

    @Test
    void shouldDownloadFileWithCorrectContentAndHeaders()
            throws Exception {
    
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        byte[] content =
                "Hello from VaultDrive"
                        .getBytes(StandardCharsets.UTF_8);
    
        when(fileService.downloadFile(
                ownerId,
                fileId
        )).thenReturn(
                new FileDownload(
                        "notes.txt",
                        "text/plain",
                        content.length,
                        new ByteArrayInputStream(content)
                )
        );
    
        MvcResult mvcResult =
                mockMvc.perform(
                                get(
                                        "/api/v1/files/{fileId}/download",
                                        fileId
                                )
                                        .with(jwt().jwt(jwt ->
                                                jwt.subject(ownerId.toString())
                                        ))
                        )
                        .andExpect(request().asyncStarted())
                        .andReturn();
    
        mockMvc.perform(
                        asyncDispatch(mvcResult)
                )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "Content-Type",
                                "text/plain"
                        )
                )
                .andExpect(
                        header().longValue(
                                "Content-Length",
                                content.length
                        )
                )
                .andExpect(
                        header().string(
                                "Content-Disposition",
                                org.hamcrest.Matchers.containsString(
                                        "attachment"
                                )
                        )
                )
                .andExpect(
                        header().string(
                                "Content-Disposition",
                                org.hamcrest.Matchers.containsString(
                                        "notes.txt"
                                )
                        )
                )
                .andExpect(content().bytes(content));
    
        verify(fileService).downloadFile(
                ownerId,
                fileId
        );
    }
    
    @Test
    void shouldUseOctetStreamWhenDownloadContentTypeIsMissing()
            throws Exception {
    
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        byte[] content = new byte[]{1, 2, 3, 4};
    
        when(fileService.downloadFile(
                ownerId,
                fileId
        )).thenReturn(
                new FileDownload(
                        "unknown.bin",
                        null,
                        content.length,
                        new ByteArrayInputStream(content)
                )
        );
    
        MvcResult mvcResult =
                mockMvc.perform(
                                get(
                                        "/api/v1/files/{fileId}/download",
                                        fileId
                                )
                                        .with(jwt().jwt(jwt ->
                                                jwt.subject(ownerId.toString())
                                        ))
                        )
                        .andExpect(request().asyncStarted())
                        .andReturn();
    
        mockMvc.perform(
                        asyncDispatch(mvcResult)
                )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "Content-Type",
                                "application/octet-stream"
                        )
                )
                .andExpect(content().bytes(content));
    
        verify(fileService).downloadFile(
                ownerId,
                fileId
        );
    }
    
    @Test
    void shouldReturnNotFoundWhenDownloadingInvisibleFile()
            throws Exception {
    
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        when(fileService.downloadFile(
                ownerId,
                fileId
        )).thenThrow(
                new FileNotFoundException(
                        "File not found"
                )
        );
    
        mockMvc.perform(
                        get(
                                "/api/v1/files/{fileId}/download",
                                fileId
                        )
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.status")
                                .value(404)
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("NOT_FOUND")
                )
                .andExpect(
                        jsonPath("$.message")
                                .value("File not found")
                );
    
        verify(fileService).downloadFile(
                ownerId,
                fileId
        );
    }
    
    @Test
    void shouldRejectUnauthenticatedFileDownload()
            throws Exception {
    
        UUID fileId = UUID.randomUUID();
    
        mockMvc.perform(
                        get(
                                "/api/v1/files/{fileId}/download",
                                fileId
                        )
                )
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(fileService);
    }

    @Test
    void shouldRenameFile() throws Exception {
    
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        Instant createdAt =
                Instant.parse("2026-10-05T10:00:00Z");
    
        Instant updatedAt =
                Instant.parse("2026-10-05T10:05:00Z");
    
        FileResponse response =
                new FileResponse(
                        fileId,
                        "renamed.pdf",
                        folderId,
                        "application/pdf",
                        500L,
                        createdAt,
                        updatedAt
                );
    
        when(fileService.renameFile(
                ownerId,
                fileId,
                "renamed.pdf"
        )).thenReturn(response);
    
        mockMvc.perform(
                        patch(
                                "/api/v1/files/{fileId}/name",
                                fileId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "renamed.pdf"
                                        }
                                        """)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(fileId.toString()))
                .andExpect(jsonPath("$.name")
                        .value("renamed.pdf"))
                .andExpect(jsonPath("$.folderId")
                        .value(folderId.toString()))
                .andExpect(jsonPath("$.contentType")
                        .value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes")
                        .value(500))
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-10-05T10:00:00Z"))
                .andExpect(jsonPath("$.updatedAt")
                        .value("2026-10-05T10:05:00Z"));
    
        verify(fileService).renameFile(
                ownerId,
                fileId,
                "renamed.pdf"
        );
    }

    @Test
    void shouldReturnNotFoundWhenRenamingInvisibleFile()
            throws Exception {
    
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        when(fileService.renameFile(
                ownerId,
                fileId,
                "renamed.pdf"
        )).thenThrow(
                new FileNotFoundException(
                        "File not found"
                )
        );
    
        mockMvc.perform(
                        patch(
                                "/api/v1/files/{fileId}/name",
                                fileId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "renamed.pdf"
                                        }
                                        """)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status")
                        .value(404))
                .andExpect(jsonPath("$.error")
                        .value("NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("File not found"));
    
        verify(fileService).renameFile(
                ownerId,
                fileId,
                "renamed.pdf"
        );
    }

    @Test
    void shouldRejectUnauthenticatedFileRename()
            throws Exception {
    
        UUID fileId = UUID.randomUUID();
    
        mockMvc.perform(
                        patch(
                                "/api/v1/files/{fileId}/name",
                                fileId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "renamed.pdf"
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(fileService);
    }

    @Test
    void shouldReturnConflictWhenRenameNameAlreadyExists()
            throws Exception {
    
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        when(fileService.renameFile(
                ownerId,
                fileId,
                "existing.pdf"
        )).thenThrow(
                new DuplicateFileNameException(
                        "A file with this name already exists"
                )
        );
    
        mockMvc.perform(
                        patch(
                                "/api/v1/files/{fileId}/name",
                                fileId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "existing.pdf"
                                        }
                                        """)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status")
                        .value(409))
                .andExpect(jsonPath("$.error")
                        .value("Conflict"))
                .andExpect(jsonPath("$.message")
                        .value(
                                "A file with this name already exists"
                        ));
    
        verify(fileService).renameFile(
                ownerId,
                fileId,
                "existing.pdf"
        );
    }

    @Test
    void shouldReturnBadRequestWhenRenameChangesFileExtension() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        when(fileService.renameFile(
                ownerId,
                fileId,
                "report.txt"
        )).thenThrow(
                new FileExtensionChangeException(
                        "File extension cannot be changed"
                )
        );
    
        mockMvc.perform(
                        patch("/api/v1/files/{fileId}/name", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "report.txt"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("File extension cannot be changed"));
    
        verify(fileService).renameFile(
                ownerId,
                fileId,
                "report.txt"
        );
    }

    @Test
    void shouldMoveFileToFolder() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        FileResponse response = new FileResponse(
                fileId,
                "report.pdf",
                destinationFolderId,
                "application/pdf",
                100L,
                Instant.now(),
                Instant.now()
        );
    
        when(fileService.moveFile(
                ownerId,
                fileId,
                destinationFolderId
        )).thenReturn(response);
    
        mockMvc.perform(
                        patch("/api/v1/files/{fileId}/move", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "folderId": "%s"
                                        }
                                        """.formatted(destinationFolderId))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId.toString()))
                .andExpect(jsonPath("$.name").value("report.pdf"))
                .andExpect(jsonPath("$.folderId")
                        .value(destinationFolderId.toString()));
    
        verify(fileService).moveFile(
                ownerId,
                fileId,
                destinationFolderId
        );
    }

    @Test
    void shouldMoveFileToRoot() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        FileResponse response = new FileResponse(
                fileId,
                "report.pdf",
                null,
                "application/pdf",
                100L,
                Instant.now(),
                Instant.now()
        );
    
        when(fileService.moveFile(
                ownerId,
                fileId,
                null
        )).thenReturn(response);
    
        mockMvc.perform(
                        patch("/api/v1/files/{fileId}/move", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "folderId": null
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId.toString()))
                .andExpect(jsonPath("$.name").value("report.pdf"))
                .andExpect(jsonPath("$.folderId").doesNotExist());
    
        verify(fileService).moveFile(
                ownerId,
                fileId,
                null
        );
    }

    @Test
    void shouldReturnConflictWhenMoveCausesDuplicateFileName() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        when(fileService.moveFile(
                ownerId,
                fileId,
                destinationFolderId
        )).thenThrow(
                new DuplicateFileNameException(
                        "A file with this name already exists"
                )
        );
    
        mockMvc.perform(
                        patch("/api/v1/files/{fileId}/move", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "folderId": "%s"
                                        }
                                        """.formatted(destinationFolderId))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message")
                        .value("A file with this name already exists"));
    }

    @Test
    void shouldReturnNotFoundWhenMovingInvisibleFile() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        when(fileService.moveFile(
                ownerId,
                fileId,
                destinationFolderId
        )).thenThrow(
                new FileNotFoundException("File not found")
        );
    
        mockMvc.perform(
                        patch("/api/v1/files/{fileId}/move", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "folderId": "%s"
                                        }
                                        """.formatted(destinationFolderId))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("File not found"));
    }

    @Test
    void shouldReturnNotFoundWhenMoveFolderIsInaccessible() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        when(fileService.moveFile(
                ownerId,
                fileId,
                destinationFolderId
        )).thenThrow(
                new FolderNotFoundException("Folder not found")
        );
    
        mockMvc.perform(
                        patch("/api/v1/files/{fileId}/move", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "folderId": "%s"
                                        }
                                        """.formatted(destinationFolderId))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Folder not found"));
    }

    @Test
    void shouldRejectUnauthenticatedFileMove() throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        mockMvc.perform(
                        patch("/api/v1/files/{fileId}/move", fileId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "folderId": "%s"
                                        }
                                        """.formatted(destinationFolderId))
                )
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(fileService);
    }

    @Test
    void shouldTrashFile() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        mockMvc.perform(
                        delete("/api/v1/files/{fileId}", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    
        verify(fileService).trashFile(
                ownerId,
                fileId
        );
    }

    @Test
    void shouldReturnNotFoundWhenTrashingInvisibleFile() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        when(fileService.trashFile(
                ownerId,
                fileId
        )).thenThrow(
                new FileNotFoundException("File not found")
        );
    
        mockMvc.perform(
                        delete("/api/v1/files/{fileId}", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("File not found"));
    }

    @Test
    void shouldReturnNotFoundWhenTrashingFileInInaccessibleFolder() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        when(fileService.trashFile(
                ownerId,
                fileId
        )).thenThrow(
                new FolderNotFoundException("Folder not found")
        );
    
        mockMvc.perform(
                        delete("/api/v1/files/{fileId}", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Folder not found"));
    }

    @Test
    void shouldRejectUnauthenticatedFileTrash() throws Exception {
        UUID fileId = UUID.randomUUID();
    
        mockMvc.perform(
                        delete("/api/v1/files/{fileId}", fileId)
                )
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(fileService);
    }

    @Test
    void shouldListTrashedFilesWithDefaultPagination() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        FileResponse file = new FileResponse(
                fileId,
                "report.pdf",
                null,
                "application/pdf",
                100L,
                Instant.now(),
                Instant.now()
        );
    
        FilePageResponse response = new FilePageResponse(
                List.of(file),
                0,
                50,
                1,
                1
        );
    
        when(fileService.listTrash(
                ownerId,
                0,
                50
        )).thenReturn(response);
    
        mockMvc.perform(
                        get("/api/v1/files/trash")
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id")
                        .value(fileId.toString()))
                .andExpect(jsonPath("$.content[0].name")
                        .value("report.pdf"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    
        verify(fileService).listTrash(
                ownerId,
                0,
                50
        );
    }

    @Test
    void shouldListTrashedFilesWithCustomPagination() throws Exception {
        UUID ownerId = UUID.randomUUID();
    
        FilePageResponse response = new FilePageResponse(
                List.of(),
                2,
                20,
                0,
                0
        );
    
        when(fileService.listTrash(
                ownerId,
                2,
                20
        )).thenReturn(response);
    
        mockMvc.perform(
                        get("/api/v1/files/trash")
                                .param("page", "2")
                                .param("size", "20")
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(20));
    
        verify(fileService).listTrash(
                ownerId,
                2,
                20
        );
    }

    @Test
    void shouldReturnBadRequestForInvalidTrashPagination() throws Exception {
        UUID ownerId = UUID.randomUUID();
    
        when(fileService.listTrash(
                ownerId,
                0,
                101
        )).thenThrow(
                new InvalidFilePaginationException(
                        "Size must be between 1 and 100"
                )
        );
    
        mockMvc.perform(
                        get("/api/v1/files/trash")
                                .param("size", "101")
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("Size must be between 1 and 100"));
    }

    @Test
    void shouldRejectUnauthenticatedTrashListing() throws Exception {
        mockMvc.perform(
                        get("/api/v1/files/trash")
                )
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(fileService);
    }

    @Test
    void shouldRestoreFile() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        FileResponse response =
                new FileResponse(
                        fileId,
                        "report.pdf",
                        folderId,
                        "application/pdf",
                        1024L,
                        Instant.parse("2026-10-06T00:00:00Z"),
                        Instant.parse("2026-10-06T00:10:00Z")
                );
    
        when(fileService.restoreFile(
                ownerId,
                fileId
        )).thenReturn(response);
    
        mockMvc.perform(
                        post("/api/v1/files/{fileId}/restore", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId.toString()))
                .andExpect(jsonPath("$.name").value("report.pdf"))
                .andExpect(jsonPath("$.folderId").value(folderId.toString()))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(1024));
    
        verify(fileService)
                .restoreFile(
                        ownerId,
                        fileId
                );
    }

    @Test
    void shouldReturnAutomaticallyRenamedFileAfterRestore() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        FileResponse response =
                new FileResponse(
                        fileId,
                        "report (restored).pdf",
                        folderId,
                        "application/pdf",
                        1024L,
                        Instant.parse("2026-10-06T00:00:00Z"),
                        Instant.parse("2026-10-06T00:10:00Z")
                );
    
        when(fileService.restoreFile(
                ownerId,
                fileId
        )).thenReturn(response);
    
        mockMvc.perform(
                        post("/api/v1/files/{fileId}/restore", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId.toString()))
                .andExpect(jsonPath("$.name")
                        .value("report (restored).pdf"))
                .andExpect(jsonPath("$.folderId")
                        .value(folderId.toString()));
    
        verify(fileService)
                .restoreFile(
                        ownerId,
                        fileId
                );
    }

    @Test
    void shouldReturnNotFoundWhenRestoringInvisibleFile() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        when(fileService.restoreFile(
                ownerId,
                fileId
        )).thenThrow(
                new FileNotFoundException("File not found")
        );
    
        mockMvc.perform(
                        post("/api/v1/files/{fileId}/restore", fileId)
                                .with(jwt().jwt(jwt ->
                                        jwt.subject(ownerId.toString())
                                ))
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("File not found"));
    
        verify(fileService)
                .restoreFile(
                        ownerId,
                        fileId
                );
    }

    @Test
    void shouldRejectUnauthenticatedFileRestore() throws Exception {
        UUID fileId = UUID.randomUUID();
    
        mockMvc.perform(
                        post("/api/v1/files/{fileId}/restore", fileId)
                )
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(fileService);
    }
}
