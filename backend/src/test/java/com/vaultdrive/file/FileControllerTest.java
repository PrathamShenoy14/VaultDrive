package com.vaultdrive.file;

import com.vaultdrive.file.dto.UploadFileResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.file.exception.InvalidFilePaginationException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


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
}
