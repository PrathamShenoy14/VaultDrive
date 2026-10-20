package com.vaultdrive.folder;

import com.vaultdrive.folder.dto.TrashPageResponse;
import com.vaultdrive.security.JwtService;
import com.vaultdrive.folder.dto.RestoreFolderResponse;
import com.vaultdrive.folder.exception.FolderNotFoundException;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.hamcrest.Matchers.nullValue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrashControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private FolderService folderService;

    @Test
    void shouldListTrashedFoldersSuccessfully() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(ownerId);

        TrashPageResponse.Entry folder = new TrashPageResponse.Entry(
                folderId,
                "FOLDER", "Documents",
                null,
                Instant.parse("2026-09-29T10:00:00Z"), null, "TRASHED", List.of("RESTORE", "PURGE")
        );

        when(folderService.listTrashedFolders(ownerId, 0, 50))
                .thenReturn(new TrashPageResponse(List.of(folder), 0, 50, 1, 1));

        mockMvc.perform(get("/api/v1/trash/folders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id")
                        .value(folderId.toString()))
                .andExpect(jsonPath("$.content[0].name")
                        .value("Documents"))
                .andExpect(jsonPath("$.content[0].deletedAt")
                        .value("2026-09-29T10:00:00Z"));

        verify(folderService).listTrashedFolders(ownerId, 0, 50);
    }

    @Test
    void shouldReturnEmptyTrashList() throws Exception {
        UUID ownerId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(ownerId);

        when(folderService.listTrashedFolders(ownerId, 0, 50))
                .thenReturn(new TrashPageResponse(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get("/api/v1/trash/folders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        verify(folderService).listTrashedFolders(ownerId, 0, 50);
    }

    @Test
    void shouldRejectUnauthenticatedTrashListing() throws Exception {
        mockMvc.perform(get("/api/v1/trash/folders"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(folderService);
    }

    @Test
    void shouldRestoreFolderSuccessfully() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        RestoreFolderResponse response = new RestoreFolderResponse(
                folderId,
                "Documents",
                parentId
        );
    
        when(folderService.restoreFolder(ownerId, folderId))
                .thenReturn(response);
    
        mockMvc.perform(post("/api/v1/trash/folders/{folderId}/restore", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value(folderId.toString()))
                .andExpect(jsonPath("$.restoredName").value("Documents"))
                .andExpect(jsonPath("$.parentFolderId").value(parentId.toString()));
    
        verify(folderService).restoreFolder(ownerId, folderId);
    }

    @Test
    void shouldRestoreFolderToRoot() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        RestoreFolderResponse response = new RestoreFolderResponse(
                folderId,
                "Documents",
                null
        );
    
        when(folderService.restoreFolder(ownerId, folderId))
                .thenReturn(response);
    
        mockMvc.perform(post("/api/v1/trash/folders/{folderId}/restore", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value(folderId.toString()))
                .andExpect(jsonPath("$.parentFolderId").value(nullValue()));
    
        verify(folderService).restoreFolder(ownerId, folderId);
    }

    @Test
    void shouldRejectUnauthenticatedRestoration() throws Exception {
        UUID folderId = UUID.randomUUID();
    
        mockMvc.perform(post("/api/v1/trash/folders/{folderId}/restore", folderId))
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(folderService);
    }

    @Test
    void shouldRejectRestoringAnotherUsersFolder() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        doThrow(new FolderNotFoundException("Deleted folder not found"))
                .when(folderService)
                .restoreFolder(ownerId, folderId);
    
        mockMvc.perform(post("/api/v1/trash/folders/{folderId}/restore", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Deleted folder not found"));
    
        verify(folderService).restoreFolder(ownerId, folderId);
    }

    @Test
    void shouldRejectRestoringActiveFolder() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        doThrow(new FolderNotFoundException("Deleted folder not found"))
                .when(folderService)
                .restoreFolder(ownerId, folderId);
    
        mockMvc.perform(post("/api/v1/trash/folders/{folderId}/restore", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    
        verify(folderService).restoreFolder(ownerId, folderId);
    }

    @Test
    void shouldAcceptPermanentFolderDeletionRequest() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(ownerId);

        UUID operation = UUID.randomUUID();
        when(folderService.requestPermanentDeletion(ownerId, folderId)).thenReturn(operation);

        mockMvc.perform(delete(
                        "/api/v1/trash/folders/{folderId}/permanent",
                        folderId
                ).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/deletion-operations/" + operation))
                .andExpect(jsonPath("$.operationId").value(operation.toString()))
                .andExpect(header().string("Retry-After", "2"));

        verify(folderService)
                .requestPermanentDeletion(ownerId, folderId);
    }

    @Test
    void shouldReturnNotFoundForIneligiblePermanentFolderDeletion()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(ownerId);

        doThrow(new FolderNotFoundException("Deleted folder not found"))
                .when(folderService)
                .requestPermanentDeletion(ownerId, folderId);

        mockMvc.perform(delete(
                        "/api/v1/trash/folders/{folderId}/permanent",
                        folderId
                ).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Deleted folder not found"));
    }

    @Test
    void shouldRejectUnauthenticatedPermanentFolderDeletion()
            throws Exception {
        UUID folderId = UUID.randomUUID();

        mockMvc.perform(delete(
                        "/api/v1/trash/folders/{folderId}/permanent",
                        folderId
                ))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(folderService);
    }
}
