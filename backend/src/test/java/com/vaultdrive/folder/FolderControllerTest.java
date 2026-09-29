package com.vaultdrive.folder;

import com.vaultdrive.folder.exception.DuplicateFolderNameException;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.folder.exception.InvalidFolderNameException;
import com.vaultdrive.folder.exception.InvalidFolderMoveException;

import com.vaultdrive.security.JwtService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vaultdrive.folder.dto.FolderResponse;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FolderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private FolderService folderService;

    // TEST 1: Successfully create a root folder

    @Test
    void shouldCreateRootFolderSuccessfully() throws Exception {

        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(ownerId);

        when(folderService.createFolder(
                ownerId,
                null,
                "Documents"
        )).thenReturn(folderId);

        mockMvc.perform(
                post("/api/v1/folders")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Documents",
                                    "parentFolderId": null
                                }
                                """)
        )
        .andExpect(status().isCreated())
        .andExpect(
                jsonPath("$.folderId")
                        .value(folderId.toString())
        );

        verify(folderService).createFolder(
                ownerId,
                null,
                "Documents"
        );
    }

    // TEST 2: Reject unauthenticated requests

    @Test
    void shouldRejectRequestWithoutJwt() throws Exception {

        mockMvc.perform(
                post("/api/v1/folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Documents"
                                }
                                """)
        )
        .andExpect(status().isUnauthorized());

        verifyNoInteractions(folderService);
    }

    // TEST 3: Reject invalid folder names

    @Test
    void shouldRejectInvalidFolderName() throws Exception {

        UUID ownerId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(ownerId);

        when(folderService.createFolder(
                ownerId,
                null,
                "Invalid/Name"
        )).thenThrow(
                new InvalidFolderNameException(
                        "Folder name cannot contain / or \\"
                )
        );

        mockMvc.perform(
                post("/api/v1/folders")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Invalid/Name"
                                }
                                """)
        )
        .andExpect(status().isBadRequest())
        .andExpect(
                jsonPath("$.message")
                        .value("Folder name cannot contain / or \\")
        );
    }

    // TEST 4: Reject inaccessible parent folders

    @Test
    void shouldRejectInaccessibleParentFolder() throws Exception {

        UUID ownerId = UUID.randomUUID();
        UUID parentFolderId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(ownerId);

        when(folderService.createFolder(
                ownerId,
                parentFolderId,
                "Projects"
        )).thenThrow(
                new FolderNotFoundException(
                        "Parent folder not found"
                )
        );

        mockMvc.perform(
                post("/api/v1/folders")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Projects",
                                    "parentFolderId": "%s"
                                }
                                """.formatted(parentFolderId))
        )
        .andExpect(status().isNotFound())
        .andExpect(
                jsonPath("$.message")
                        .value("Parent folder not found")
        );
    }

    // TEST 5: Reject duplicate folder names

    @Test
    void shouldRejectDuplicateFolderName() throws Exception {

        UUID ownerId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(ownerId);

        when(folderService.createFolder(
                ownerId,
                null,
                "Documents"
        )).thenThrow(
                new DuplicateFolderNameException(
                        "A folder with this name already exists"
                )
        );

        mockMvc.perform(
                post("/api/v1/folders")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Documents"
                                }
                                """)
        )
        .andExpect(status().isConflict())
        .andExpect(
                jsonPath("$.message")
                        .value("A folder with this name already exists")
        );
    }

    @Test
    void shouldRetrieveFolderSuccessfully() throws Exception {
    
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        FolderResponse response = new FolderResponse(
                folderId,
                "Documents",
                null,
                Instant.parse("2026-09-29T10:00:00Z"),
                Instant.parse("2026-09-29T10:00:00Z")
        );
    
        when(folderService.getFolder(ownerId, folderId))
                .thenReturn(response);
    
        mockMvc.perform(get("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(folderId.toString()))
                .andExpect(jsonPath("$.name").value("Documents"))
                .andExpect(jsonPath("$.parentFolderId").isEmpty())
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-09-29T10:00:00Z"));
    
        verify(folderService).getFolder(ownerId, folderId);
    }
    
    
    @Test
    void shouldListRootFoldersSuccessfully() throws Exception {
    
        UUID ownerId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        FolderResponse documents = new FolderResponse(
                UUID.randomUUID(),
                "Documents",
                null,
                Instant.parse("2026-09-29T10:00:00Z"),
                Instant.parse("2026-09-29T10:00:00Z")
        );
    
        FolderResponse pictures = new FolderResponse(
                UUID.randomUUID(),
                "Pictures",
                null,
                Instant.parse("2026-09-29T11:00:00Z"),
                Instant.parse("2026-09-29T11:00:00Z")
        );
    
        when(folderService.listRootFolders(ownerId))
                .thenReturn(List.of(documents, pictures));
    
        mockMvc.perform(get("/api/v1/folders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Documents"))
                .andExpect(jsonPath("$[1].name").value("Pictures"));
    
        verify(folderService).listRootFolders(ownerId);
    }
    
    
    @Test
    void shouldReturnEmptyRootFolderList() throws Exception {
    
        UUID ownerId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        when(folderService.listRootFolders(ownerId))
                .thenReturn(List.of());
    
        mockMvc.perform(get("/api/v1/folders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    
        verify(folderService).listRootFolders(ownerId);
    }
    
    
    @Test
    void shouldReturnNotFoundForInaccessibleFolder() throws Exception {
    
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        when(folderService.getFolder(ownerId, folderId))
                .thenThrow(
                        new FolderNotFoundException("Folder not found")
                );
    
        mockMvc.perform(get("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Folder not found"));
    
        verify(folderService).getFolder(ownerId, folderId);
    }
    
    
    @Test
    void shouldRejectUnauthenticatedFolderRetrieval() throws Exception {
    
        mockMvc.perform(get("/api/v1/folders"))
                .andExpect(status().isUnauthorized());
    
        mockMvc.perform(get(
                        "/api/v1/folders/{folderId}",
                        UUID.randomUUID()
                ))
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(folderService);
    }

    @Test
    void shouldListChildFoldersSuccessfully() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
        Instant now = Instant.parse("2026-09-29T10:00:00Z");
    
        FolderResponse projects = new FolderResponse(
                UUID.randomUUID(), "Projects", parentId, now, now
        );
    
        FolderResponse resume = new FolderResponse(
                UUID.randomUUID(), "Resume", parentId, now, now
        );
    
        when(folderService.listChildFolders(ownerId, parentId))
                .thenReturn(List.of(projects, resume));
    
        mockMvc.perform(get("/api/v1/folders/{folderId}/children", parentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Projects"))
                .andExpect(jsonPath("$[1].name").value("Resume"))
                .andExpect(jsonPath("$[0].parentFolderId")
                        .value(parentId.toString()));
    
        verify(folderService).listChildFolders(ownerId, parentId);
    }

    @Test
    void shouldReturnEmptyChildFolderList() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        when(folderService.listChildFolders(ownerId, parentId))
                .thenReturn(List.of());
    
        mockMvc.perform(get("/api/v1/folders/{folderId}/children", parentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    
        verify(folderService).listChildFolders(ownerId, parentId);
    }

    @Test
    void shouldRejectBrowsingInaccessibleFolder() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        when(folderService.listChildFolders(ownerId, parentId))
                .thenThrow(new FolderNotFoundException("Folder not found"));
    
        mockMvc.perform(get("/api/v1/folders/{folderId}/children", parentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Folder not found"));
    
        verify(folderService).listChildFolders(ownerId, parentId);
    }

    @Test
    void shouldRejectUnauthenticatedChildFolderListing() throws Exception {
        UUID parentId = UUID.randomUUID();
    
        mockMvc.perform(get("/api/v1/folders/{folderId}/children", parentId))
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(folderService);
    }

    @Test
    void shouldRenameFolderSuccessfully() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
        Instant now = Instant.parse("2026-09-29T10:00:00Z");
    
        FolderResponse response = new FolderResponse(
                folderId, "My Documents", null, now, now
        );
    
        when(folderService.renameFolder(
                ownerId, folderId, "My Documents"
        )).thenReturn(response);
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "My Documents"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(folderId.toString()))
                .andExpect(jsonPath("$.name").value("My Documents"))
                .andExpect(jsonPath("$.parentFolderId").isEmpty());
    
        verify(folderService).renameFolder(
                ownerId, folderId, "My Documents"
        );
    }

    @Test
    void shouldRejectUnauthenticatedRename() throws Exception {
        UUID folderId = UUID.randomUUID();
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}", folderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Renamed"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(folderService);
    }

    @Test
    void shouldRejectRenamingInaccessibleFolder() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        when(folderService.renameFolder(
                ownerId, folderId, "Renamed"
        )).thenThrow(
                new FolderNotFoundException("Folder not found")
        );
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Renamed"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Folder not found"));
    
        verify(folderService).renameFolder(
                ownerId, folderId, "Renamed"
        );
    }

    @Test
    void shouldRejectDuplicateNameDuringRename() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        when(folderService.renameFolder(
                ownerId, folderId, "Pictures"
        )).thenThrow(
                new DuplicateFolderNameException(
                        "A folder with this name already exists"
                )
        );
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Pictures"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("A folder with this name already exists"));
    
        verify(folderService).renameFolder(
                ownerId, folderId, "Pictures"
        );
    }

    @Test
    void shouldRejectRenameWithMissingName() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    
        verifyNoInteractions(folderService);
    }

    @Test
    void shouldMoveFolderSuccessfully() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
        Instant now = Instant.parse("2026-09-29T10:00:00Z");
    
        FolderResponse response = new FolderResponse(
                folderId, "Projects", destinationId, now, now
        );
    
        when(folderService.moveFolder(ownerId, folderId, destinationId))
                .thenReturn(response);
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}/move", folderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "destinationFolderId": "%s"
                                }
                                """.formatted(destinationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(folderId.toString()))
                .andExpect(jsonPath("$.name").value("Projects"))
                .andExpect(jsonPath("$.parentFolderId")
                        .value(destinationId.toString()));
    
        verify(folderService).moveFolder(ownerId, folderId, destinationId);
    }

    @Test
    void shouldMoveFolderToRoot() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
        Instant now = Instant.parse("2026-09-29T10:00:00Z");
    
        FolderResponse response = new FolderResponse(
                folderId, "Projects", null, now, now
        );
    
        when(folderService.moveFolder(ownerId, folderId, null))
                .thenReturn(response);
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}/move", folderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "destinationFolderId": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentFolderId").isEmpty());
    
        verify(folderService).moveFolder(ownerId, folderId, null);
    }

    @Test
    void shouldRejectMovingFolderIntoItself() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        when(folderService.moveFolder(ownerId, folderId, folderId))
                .thenThrow(new InvalidFolderMoveException(
                        "A folder cannot be moved into itself"
                ));
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}/move", folderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "destinationFolderId": "%s"
                                }
                                """.formatted(folderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("A folder cannot be moved into itself"));
    
        verify(folderService).moveFolder(ownerId, folderId, folderId);
    }

    @Test
    void shouldRejectMovingIntoInaccessibleDestination() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        when(folderService.moveFolder(ownerId, folderId, destinationId))
                .thenThrow(new FolderNotFoundException(
                        "Destination folder not found"
                ));
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}/move", folderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "destinationFolderId": "%s"
                                }
                                """.formatted(destinationId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Destination folder not found"));
    
        verify(folderService).moveFolder(ownerId, folderId, destinationId);
    }

    @Test
    void shouldRejectUnauthenticatedFolderMove() throws Exception {
        UUID folderId = UUID.randomUUID();
    
        mockMvc.perform(patch("/api/v1/folders/{folderId}/move", folderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "destinationFolderId": null
                                }
                                """))
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(folderService);
    }

    @Test
    void shouldDeleteFolderSuccessfully() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        mockMvc.perform(delete("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    
        verify(folderService).deleteFolder(ownerId, folderId);
    }

    @Test
    void shouldRejectUnauthenticatedFolderDeletion() throws Exception {
        UUID folderId = UUID.randomUUID();
    
        mockMvc.perform(delete("/api/v1/folders/{folderId}", folderId))
                .andExpect(status().isUnauthorized());
    
        verifyNoInteractions(folderService);
    }

    @Test
    void shouldRejectDeletingInaccessibleFolder() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        doThrow(new FolderNotFoundException("Folder not found"))
                .when(folderService)
                .deleteFolder(ownerId, folderId);
    
        mockMvc.perform(delete("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("Folder not found"));
    
        verify(folderService).deleteFolder(ownerId, folderId);
    }

    @Test
    void shouldRejectDeletingAlreadyDeletedFolder() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        String token = jwtService.generateAccessToken(ownerId);
    
        doThrow(new FolderNotFoundException("Folder not found"))
                .when(folderService)
                .deleteFolder(ownerId, folderId);
    
        mockMvc.perform(delete("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    
        verify(folderService).deleteFolder(ownerId, folderId);
    }
}