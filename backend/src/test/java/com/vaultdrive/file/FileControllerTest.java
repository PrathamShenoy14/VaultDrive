package com.vaultdrive.file;

import com.vaultdrive.file.dto.UploadFileResponse;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.MediaType;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
}
