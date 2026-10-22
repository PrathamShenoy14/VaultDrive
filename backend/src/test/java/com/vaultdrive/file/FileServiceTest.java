package com.vaultdrive.file;

import com.vaultdrive.file.exception.InvalidFileNameException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.file.exception.FileExtensionChangeException;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageObject;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.folder.dto.TrashPageResponse;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock
    private FolderAccessValidator folderAccessValidator;

    @Mock
    private FileNameValidator fileNameValidator;

    @Mock
    private FileMetadataService fileMetadataService;

    @Mock
    private ObjectStorageService objectStorageService;

@Mock
    private StoredFileRepository storedFileRepository;

    @Mock
    private HierarchyCoordinator hierarchyCoordinator;

@Mock private com.vaultdrive.sharing.ResourceReadService resourceReads;

    private FileService fileService;
    private FileNameExtensionResolver fileNameExtensionResolver;

    @BeforeEach
    void setUp() {
        lenient().when(resourceReads.requireEditor(any(), anyBoolean(), any(), anyBoolean()))
                .thenAnswer(i -> new com.vaultdrive.sharing.ResourceAccess(i.getArgument(2), i.getArgument(0),
                        null, "", null, 0, null, java.time.Instant.now(), java.time.Instant.now(),
                        com.vaultdrive.sharing.ResourceRole.OWNER, true, true, true));
        fileNameExtensionResolver =
                    new FileNameExtensionResolver();
        
        fileService = new FileService(
                folderAccessValidator,
                fileNameValidator,
                fileMetadataService,
                objectStorageService,
                storedFileRepository,
                fileNameExtensionResolver,
                hierarchyCoordinator,
                resourceReads,
                org.mockito.Mockito.mock(com.vaultdrive.activity.ResourceActivityService.class)
        );
    }

    @Test
    void readsDelegateToCurrentPermissionSnapshot() {
        UUID actor = UUID.randomUUID(), folder = UUID.randomUUID();
        FilePageResponse page = new FilePageResponse(List.of(), 0, 10, 0, 0);
        when(resourceReads.files(actor, folder, 0, 10)).thenReturn(page);
        assertSame(page, fileService.listFiles(actor, folder, 0, 10));
    }

    @Test
    void downloadUsesAuthorizedStorageIdentity() {
        UUID actor = UUID.randomUUID(), fileId = UUID.randomUUID();
        var snapshot = new com.vaultdrive.sharing.ResourceAccess(fileId, UUID.randomUUID(), null,
                "shared.txt", "text/plain", 1, "opaque-key", java.time.Instant.now(), java.time.Instant.now(),
                com.vaultdrive.sharing.ResourceRole.VIEWER, false, true, false);
        when(resourceReads.file(actor, fileId)).thenReturn(snapshot);
        InputStream bytes = new ByteArrayInputStream(new byte[]{1});
        when(objectStorageService.download("opaque-key")).thenReturn(new StorageObject(bytes));
        assertSame(bytes, fileService.downloadFile(actor, fileId).inputStream());
    }

    @Test
    void deniedDownloadNeverCallsStorage() {
        UUID actor = UUID.randomUUID(), fileId = UUID.randomUUID();
        when(resourceReads.file(actor, fileId)).thenThrow(new FileNotFoundException("File not found"));
        assertThrows(FileNotFoundException.class, () -> fileService.downloadFile(actor, fileId));
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRenameReadyRootFile() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID fileId = UUID.randomUUID();
    
        StoredFile storedFile = new StoredFile(
                fileId,
                ownerId,
                null,
                "old-name.txt",
                "users/" + ownerId + "/files/" + fileId,
                "text/plain",
                100L
        );
    
        storedFile.markReady();
    
        // Tell the mocked FileNameValidator what to return
        when(fileNameValidator
                .validateAndNormalize("new-name.txt"))
                .thenReturn("new-name.txt");
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        when(fileMetadataService.rename(
                storedFile,
                "new-name.txt"
        ))
                .thenAnswer(invocation -> {
                    storedFile.rename("new-name.txt");
                    return storedFile;
                });
    
        FileResponse response =
                fileService.renameFile(
                        ownerId,
                        fileId,
                        "new-name.txt"
                );
    
        assertEquals("new-name.txt", response.name());
        assertEquals(fileId, response.id());
        assertNull(response.folderId());

        var lockOrder = inOrder(
                hierarchyCoordinator,
                storedFileRepository
        );
        lockOrder.verify(hierarchyCoordinator).acquireShared(ownerId);
        lockOrder.verify(storedFileRepository)
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                );
    
        verify(fileNameValidator, times(2))
                .validateAndNormalize("new-name.txt");
    
        verify(fileMetadataService)
                .rename(
                        storedFile,
                        "new-name.txt"
                );
    
        verifyNoInteractions(folderAccessValidator);
    
        verify(objectStorageService, never())
                .download(anyString());
    
        verify(objectStorageService, never())
                .delete(any(com.vaultdrive.storage.StorageTarget.class));
    }

    @Test
    void shouldRenameReadyFileInsideAccessibleFolder() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        StoredFile storedFile = new StoredFile(
                fileId,
                ownerId,
                folderId,
                "old.pdf",
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                500L
        );
    
        storedFile.markReady();
    
        when(fileNameValidator
                .validateAndNormalize("renamed.pdf"))
                .thenReturn("renamed.pdf");
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        when(fileMetadataService.rename(
                storedFile,
                "renamed.pdf"
        ))
                .thenAnswer(invocation -> {
                    storedFile.rename("renamed.pdf");
                    return storedFile;
                });
    
        FileResponse response =
                fileService.renameFile(
                        ownerId,
                        fileId,
                        "renamed.pdf"
                );
    
        assertEquals("renamed.pdf", response.name());
        assertEquals(folderId, response.folderId());
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        folderId
                );
    
        verify(fileMetadataService)
                .rename(
                        storedFile,
                        "renamed.pdf"
                );
    
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectRenameWhenFileIsNotVisible() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID fileId = UUID.randomUUID();
    
        when(fileNameValidator
                .validateAndNormalize("new-name.txt"))
                .thenReturn("new-name.txt");
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.empty());
    
        FileNotFoundException exception =
                assertThrows(
                        FileNotFoundException.class,
                        () -> fileService.renameFile(
                                ownerId,
                                fileId,
                                "new-name.txt"
                        )
                );
    
        assertEquals(
                "File not found",
                exception.getMessage()
        );
    
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldNotRenameWhenFolderIsInaccessible() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        StoredFile storedFile = new StoredFile(
                fileId,
                ownerId,
                folderId,
                "report.pdf",
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                500L
        );
    
        storedFile.markReady();
    
        when(fileNameValidator
                .validateAndNormalize("final.pdf"))
                .thenReturn("final.pdf");
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        doThrow(new FolderNotFoundException(
                "Parent folder not found"
        ))
                .when(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        folderId
                );
    
        assertThrows(
                FolderNotFoundException.class,
                () -> fileService.renameFile(
                        ownerId,
                        fileId,
                        "final.pdf"
                )
        );
    
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldPreserveExistingExtensionWhenRenamingWithoutExtension() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID fileId = UUID.randomUUID();
    
        StoredFile storedFile = new StoredFile(
                fileId,
                ownerId,
                null,
                "Platform FDE - JD.pdf",
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                500L
        );
    
        storedFile.markReady();
    
        when(fileNameValidator
                .validateAndNormalize("Job Description"))
                .thenReturn("Job Description");
    
        when(fileNameValidator
                .validateAndNormalize("Job Description.pdf"))
                .thenReturn("Job Description.pdf");
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        when(fileMetadataService.rename(
                storedFile,
                "Job Description.pdf"
        )).thenAnswer(invocation -> {
            storedFile.rename("Job Description.pdf");
            return storedFile;
        });
    
        FileResponse response =
                fileService.renameFile(
                        ownerId,
                        fileId,
                        "Job Description"
                );
    
        assertEquals(
                "Job Description.pdf",
                response.name()
        );
    
        verify(fileNameValidator)
                .validateAndNormalize("Job Description");
    
        verify(fileNameValidator)
                .validateAndNormalize("Job Description.pdf");
    
        verify(fileMetadataService)
                .rename(
                        storedFile,
                        "Job Description.pdf"
                );
    
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectRenameWhenExtensionIsChanged() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID fileId = UUID.randomUUID();
    
        StoredFile storedFile = new StoredFile(
                fileId,
                ownerId,
                null,
                "report.pdf",
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                500L
        );
    
        storedFile.markReady();
    
        when(fileNameValidator
                .validateAndNormalize("report.txt"))
                .thenReturn("report.txt");
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        FileExtensionChangeException exception =
                assertThrows(
                        FileExtensionChangeException.class,
                        () -> fileService.renameFile(
                                ownerId,
                                fileId,
                                "report.txt"
                        )
                );
    
        assertEquals(
                "File extension cannot be changed",
                exception.getMessage()
        );
    
        verify(fileNameValidator)
                .validateAndNormalize("report.txt");
    
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldAllowSameExtensionWithDifferentCase() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID fileId = UUID.randomUUID();
    
        StoredFile storedFile = new StoredFile(
                fileId,
                ownerId,
                null,
                "report.pdf",
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                500L
        );
    
        storedFile.markReady();
    
        when(fileNameValidator
                .validateAndNormalize("Final Report.PDF"))
                .thenReturn("Final Report.PDF");
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        when(fileMetadataService.rename(
                storedFile,
                "Final Report.PDF"
        )).thenAnswer(invocation -> {
            storedFile.rename("Final Report.PDF");
            return storedFile;
        });
    
        FileResponse response =
                fileService.renameFile(
                        ownerId,
                        fileId,
                        "Final Report.PDF"
                );
    
        assertEquals(
                "Final Report.PDF",
                response.name()
        );
    
        verify(fileNameValidator, times(2))
                .validateAndNormalize("Final Report.PDF");
    
        verify(fileMetadataService)
                .rename(
                        storedFile,
                        "Final Report.PDF"
                );
    
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectRenameWhenPreservedExtensionMakesFinalNameInvalid() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID fileId = UUID.randomUUID();
    
        String requestedName = "a".repeat(254);
        String resolvedName = requestedName + ".pdf";
    
        StoredFile storedFile = new StoredFile(
                fileId,
                ownerId,
                null,
                "report.pdf",
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                500L
        );
    
        storedFile.markReady();
    
        when(fileNameValidator
                .validateAndNormalize(requestedName))
                .thenReturn(requestedName);
    
        when(fileNameValidator
                .validateAndNormalize(resolvedName))
                .thenThrow(
                        new InvalidFileNameException(
                                "File name must not exceed 255 characters"
                        )
                );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        InvalidFileNameException exception =
                assertThrows(
                        InvalidFileNameException.class,
                        () -> fileService.renameFile(
                                ownerId,
                                fileId,
                                requestedName
                        )
                );
    
        assertEquals(
                "File name must not exceed 255 characters",
                exception.getMessage()
        );
    
        verify(fileNameValidator)
                .validateAndNormalize(requestedName);
    
        verify(fileNameValidator)
                .validateAndNormalize(resolvedName);
    
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldMoveReadyFileToAccessibleFolder() {
        UUID ownerId = UUID.randomUUID();
        UUID sourceFolderId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        StoredFile storedFile = createReadyFile(
                ownerId,
                sourceFolderId,
                "report.pdf"
        );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        when(fileMetadataService.move(
                storedFile,
                destinationFolderId
        )).thenAnswer(invocation -> {
            storedFile.move(destinationFolderId);
            return storedFile;
        });
    
        FileResponse response =
                fileService.moveFile(
                        ownerId,
                        storedFile.getId(),
                        destinationFolderId
                );
    
        assertEquals(
                destinationFolderId,
                response.folderId()
        );
    
        assertEquals(
                "report.pdf",
                response.name()
        );

        var lockOrder = inOrder(
                hierarchyCoordinator,
                storedFileRepository
        );
        lockOrder.verify(hierarchyCoordinator).acquireShared(ownerId);
        lockOrder.verify(storedFileRepository)
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                );
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        sourceFolderId
                );
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        destinationFolderId
                );
    
        verify(fileMetadataService)
                .move(
                        storedFile,
                        destinationFolderId
                );
    
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldMoveReadyFileToRoot() {
        UUID ownerId = UUID.randomUUID();
        UUID sourceFolderId = UUID.randomUUID();
    
        StoredFile storedFile = createReadyFile(
                ownerId,
                sourceFolderId,
                "report.pdf"
        );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        when(fileMetadataService.move(
                storedFile,
                null
        )).thenAnswer(invocation -> {
            storedFile.move(null);
            return storedFile;
        });
    
        FileResponse response =
                fileService.moveFile(
                        ownerId,
                        storedFile.getId(),
                        null
                );
    
        assertNull(response.folderId());
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        sourceFolderId
                );
    
        /*
         * Destination is root, so validateFolderAccess()
         * returns immediately and does not call the validator.
         */
        verify(folderAccessValidator, times(1))
                .requireAccessibleFolder(
                        ownerId,
                        sourceFolderId
                );
    
        verify(fileMetadataService)
                .move(
                        storedFile,
                        null
                );
    
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectMoveWhenSourceFolderIsInaccessible() {
        UUID ownerId = UUID.randomUUID();
        UUID sourceFolderId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        StoredFile storedFile = createReadyFile(
                ownerId,
                sourceFolderId,
                "secret.pdf"
        );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                sourceFolderId
        )).thenThrow(
                new FolderNotFoundException(
                        "Parent folder not found"
                )
        );
    
        assertThrows(
                FolderNotFoundException.class,
                () -> fileService.moveFile(
                        ownerId,
                        storedFile.getId(),
                        destinationFolderId
                )
        );
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        sourceFolderId
                );
    
        verify(folderAccessValidator, never())
                .requireAccessibleFolder(
                        ownerId,
                        destinationFolderId
                );
    
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectMoveWhenDestinationFolderIsInaccessible() {
        UUID ownerId = UUID.randomUUID();
        UUID sourceFolderId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        StoredFile storedFile = createReadyFile(
                ownerId,
                sourceFolderId,
                "report.pdf"
        );
        
        Folder sourceFolder = mock(Folder.class);
        
        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                sourceFolderId
        )).thenReturn(sourceFolder);
        
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                destinationFolderId
        )).thenThrow(
                new FolderNotFoundException(
                        "Folder not found"
                )
        );
    
        assertThrows(
                FolderNotFoundException.class,
                () -> fileService.moveFile(
                        ownerId,
                        storedFile.getId(),
                        destinationFolderId
                )
        );
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        sourceFolderId
                );
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        destinationFolderId
                );
    
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectMoveWhenFileIsNotVisible() {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.empty());
    
        FileNotFoundException exception =
                assertThrows(
                        FileNotFoundException.class,
                        () -> fileService.moveFile(
                                ownerId,
                                fileId,
                                destinationFolderId
                        )
                );
    
        assertEquals(
                "File not found",
                exception.getMessage()
        );
    
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldAllowMoveToSameFolderAsNoOp() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        StoredFile storedFile = createReadyFile(
                ownerId,
                folderId,
                "report.pdf"
        );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        Folder folder = mock(Folder.class);
    
        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        )).thenReturn(folder);
    
        when(fileMetadataService.move(
                storedFile,
                folderId
        )).thenReturn(storedFile);
    
        FileResponse response =
                fileService.moveFile(
                        ownerId,
                        storedFile.getId(),
                        folderId
                );
    
        assertEquals(folderId, response.folderId());
        assertEquals("report.pdf", response.name());
    
        verify(fileMetadataService)
                .move(
                        storedFile,
                        folderId
                );
    
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldTrashReadyRootFile() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
    
        StoredFile storedFile =
                createReadyFile(
                        ownerId,
                        null,
                        "report.pdf"
                );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        storedFile.softDelete();
    
        when(fileMetadataService.softDelete(storedFile))
                .thenReturn(storedFile);
    
        FileResponse response =
                fileService.trashFile(
                        ownerId,
                        storedFile.getId()
                );
    
        assertEquals(storedFile.getId(), response.id());
        assertEquals("report.pdf", response.name());
        assertNull(response.folderId());

        var lockOrder = inOrder(
                hierarchyCoordinator,
                storedFileRepository
        );
        lockOrder.verify(hierarchyCoordinator).acquireShared(ownerId);
        lockOrder.verify(storedFileRepository)
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                );
    
        verify(fileMetadataService)
                .softDelete(storedFile);
    
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldTrashReadyFileInsideAccessibleFolder() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID folderId = UUID.randomUUID();
    
        StoredFile storedFile =
                createReadyFile(
                        ownerId,
                        folderId,
                        "report.pdf"
                );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        Folder folder = mock(Folder.class);
    
        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        )).thenReturn(folder);
    
        storedFile.softDelete();
    
        when(fileMetadataService.softDelete(storedFile))
                .thenReturn(storedFile);
    
        FileResponse response =
                fileService.trashFile(
                        ownerId,
                        storedFile.getId()
                );
    
        assertEquals(storedFile.getId(), response.id());
        assertEquals(folderId, response.folderId());
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        folderId
                );
    
        verify(fileMetadataService)
                .softDelete(storedFile);
    
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectTrashWhenFileIsNotVisible() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID fileId = UUID.randomUUID();
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.empty());
    
        FileNotFoundException exception =
                assertThrows(
                        FileNotFoundException.class,
                        () -> fileService.trashFile(
                                ownerId,
                                fileId
                        )
                );
    
        assertEquals(
                "File not found",
                exception.getMessage()
        );
    
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectTrashWhenParentFolderIsInaccessible() {
        UUID ownerId = UUID.randomUUID();
        lenient().when(resourceReads.owner(anyBoolean(), any())).thenReturn(ownerId);
        UUID folderId = UUID.randomUUID();
    
        StoredFile storedFile =
                createReadyFile(
                        ownerId,
                        folderId,
                        "report.pdf"
                );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(storedFile));
    
        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        )).thenThrow(
                new FolderNotFoundException("Folder not found")
        );
    
        FolderNotFoundException exception =
                assertThrows(
                        FolderNotFoundException.class,
                        () -> fileService.trashFile(
                                ownerId,
                                storedFile.getId()
                        )
                );
    
        assertEquals(
                "Folder not found",
                exception.getMessage()
        );
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        folderId
                );
    
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldDelegateBoundedTrashSnapshot() {
        UUID owner = UUID.randomUUID();
        var page = new TrashPageResponse(List.of(), 2, 10, 0, 0);
        when(resourceReads.trash(owner, false, 2, 10)).thenReturn(page);
        assertSame(page, fileService.listTrash(owner, 2, 10));
    }

    @Test
    void shouldRestoreFileToRootWhenOriginalFolderIsInaccessible() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        StoredFile storedFile =
                createReadyFile(
                        ownerId,
                        folderId,
                        "report.pdf"
                );
    
        storedFile.softDelete();
    
        when(fileMetadataService.restore(
            ownerId,
            storedFile.getId()
        )).thenAnswer(invocation -> {
            storedFile.restore(
                    null,
                    storedFile.getName()
            );
            return storedFile;
        });
    
        FileResponse response =
                fileService.restoreFile(
                        ownerId,
                        storedFile.getId()
                );
    
        assertNull(response.folderId());
        assertEquals("report.pdf", response.name());
    
        verify(fileMetadataService)
                .restore(
                    ownerId,
                    storedFile.getId()
                );

        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(storedFileRepository);
        verifyNoInteractions(objectStorageService);
    }
    
    @Test
    void shouldRestoreRootFileToRoot() {
        UUID ownerId = UUID.randomUUID();
    
        StoredFile storedFile =
                createReadyFile(
                        ownerId,
                        null,
                        "report.pdf"
                );
    
        storedFile.softDelete();
    
        when(fileMetadataService.restore(
            ownerId,
            storedFile.getId()
        )).thenAnswer(invocation -> {
            storedFile.restore(
                    null,
                    storedFile.getName()
            );
            return storedFile;
        });
    
        FileResponse response =
                fileService.restoreFile(
                        ownerId,
                        storedFile.getId()
                );
    
        assertNull(response.folderId());
        assertEquals("report.pdf", response.name());
    
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(storedFileRepository);

        verify(fileMetadataService)
                .restore(
                    ownerId,
                    storedFile.getId()
                );
    
        verifyNoInteractions(objectStorageService);
    }
    
    @Test
    void shouldRestoreFileToAccessibleOriginalFolder() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        StoredFile storedFile =
                createReadyFile(
                        ownerId,
                        folderId,
                        "report.pdf"
                );
    
        storedFile.softDelete();
    
        when(fileMetadataService.restore(
            ownerId,
            storedFile.getId()
        )).thenAnswer(invocation -> {
            storedFile.restore(
                    folderId,
                    storedFile.getName()
            );
            return storedFile;
        });
    
        FileResponse response =
                fileService.restoreFile(
                        ownerId,
                        storedFile.getId()
                );
    
        assertEquals(
                folderId,
                response.folderId()
        );
    
        assertEquals(
                "report.pdf",
                response.name()
        );
    
        verify(fileMetadataService)
                .restore(
                    ownerId,
                    storedFile.getId()
                );

        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(storedFileRepository);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectRestoreWhenFileIsNotInTrash() {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        when(fileMetadataService.restore(ownerId, fileId))
                .thenThrow(new FileNotFoundException("File not found"));
    
        FileNotFoundException exception =
                assertThrows(
                        FileNotFoundException.class,
                        () -> fileService.restoreFile(
                                ownerId,
                                fileId
                        )
                );
    
        assertEquals(
                "File not found",
                exception.getMessage()
        );
    
        verifyNoInteractions(folderAccessValidator);
        verify(fileMetadataService).restore(ownerId, fileId);
        verifyNoInteractions(storedFileRepository);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRequestPermanentDeletionForTrashedFile() {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        fileService.requestPermanentDeletion(ownerId, fileId);

        verify(fileMetadataService)
                .requestPermanentDeletion(
                    ownerId,
                    fileId
                );

        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(storedFileRepository);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectPermanentDeletionForActiveFile() {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        when(fileMetadataService.requestPermanentDeletion(ownerId, fileId))
                .thenThrow(new FileNotFoundException("File not found"));

        assertThrows(
                FileNotFoundException.class,
                () -> fileService.requestPermanentDeletion(ownerId, fileId)
        );

        verify(fileMetadataService)
                .requestPermanentDeletion(ownerId, fileId);
        verifyNoInteractions(storedFileRepository);
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(objectStorageService);
    }

    private StoredFile createReadyFile(
            UUID ownerId,
            UUID folderId,
            String name
    ) {
        UUID fileId = UUID.randomUUID();

        StoredFile storedFile = new StoredFile(
                fileId,
                ownerId,
                folderId,
                name,
                "users/"
                        + ownerId
                        + "/files/"
                        + fileId,
                "application/pdf",
                100L
        );

        storedFile.markReady();

        return storedFile;
    }
}
