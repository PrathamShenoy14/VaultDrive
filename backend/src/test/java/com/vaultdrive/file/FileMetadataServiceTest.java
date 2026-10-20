package com.vaultdrive.file;

import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.user.UserRepository;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.outbox.OutboxWriter;
import com.vaultdrive.storage.FileStorageIdentityService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileMetadataServiceTest {

    @Mock
    private StoredFileRepository storedFileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FolderAccessValidator folderAccessValidator;

    @Mock
    private HierarchyCoordinator hierarchyCoordinator;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private FileStorageIdentityService storageIdentity;

    private FileMetadataService fileMetadataService;

    private UUID ownerId;

    @BeforeEach
    void setUp() {
        fileMetadataService = new FileMetadataService(
                storedFileRepository,
                userRepository,
                folderAccessValidator,
                hierarchyCoordinator,
                outboxWriter,
                storageIdentity
        );

        ownerId = UUID.randomUUID();

        lenient()
                .when(userRepository.existsById(ownerId))
                .thenReturn(true);
    }

    @Test
    void shouldRenameRootFile() {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();

        StoredFile file = new StoredFile(
                fileId,
                ownerId,
                null,
                "old.txt",
                "users/" + ownerId + "/files/" + fileId,
                "text/plain",
                100L
        );

        file.markReady();

        when(storedFileRepository
                .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
                        eq(ownerId),
                        eq("new.txt"),
                        anyCollection(),
                        eq(fileId)
                ))
                .thenReturn(false);

        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);

        StoredFile result =
                fileMetadataService.rename(
                        file,
                        "new.txt"
                );

        assertEquals("new.txt", result.getName());

        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldRenameNestedFile() {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        StoredFile file = new StoredFile(
                fileId,
                ownerId,
                folderId,
                "old.pdf",
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                500L
        );

        file.markReady();

        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
                        eq(ownerId),
                        eq(folderId),
                        eq("new.pdf"),
                        anyCollection(),
                        eq(fileId)
                ))
                .thenReturn(false);

        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);

        StoredFile result =
                fileMetadataService.rename(
                        file,
                        "new.pdf"
                );

        assertEquals("new.pdf", result.getName());

        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldRejectRenameWhenNameAlreadyExists() {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        StoredFile file = new StoredFile(
                fileId,
                ownerId,
                folderId,
                "report.pdf",
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                500L
        );

        file.markReady();

        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
                        eq(ownerId),
                        eq(folderId),
                        eq("invoice.pdf"),
                        anyCollection(),
                        eq(fileId)
                ))
                .thenReturn(true);

        DuplicateFileNameException exception =
                assertThrows(
                        DuplicateFileNameException.class,
                        () -> fileMetadataService.rename(
                                file,
                                "invoice.pdf"
                        )
                );

        assertEquals(
                "A file with this name already exists",
                exception.getMessage()
        );

        assertEquals(
                "report.pdf",
                file.getName()
        );

        verify(storedFileRepository, never())
                .saveAndFlush(any());
    }

    @Test
    void shouldTreatSameNameRenameAsNoOp() {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        StoredFile file = new StoredFile(
                fileId,
                ownerId,
                null,
                "report.pdf",
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                500L
        );
    
        file.markReady();
    
        StoredFile result =
                fileMetadataService.rename(
                        file,
                        "report.pdf"
                );
    
        assertSame(file, result);
        assertEquals("report.pdf", result.getName());
    
        verify(storedFileRepository, never())
                .saveAndFlush(any());
    
        verifyNoMoreInteractions(storedFileRepository);
    }

    @Test
    void shouldMoveFileToAnotherFolder() {
        UUID sourceFolderId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        StoredFile file = createFile(
                sourceFolderId,
                "report.pdf"
        );

        UUID ownerId = file.getOwnerId();
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq(destinationFolderId),
                        eq("report.pdf"),
                        anyCollection()
                ))
                .thenReturn(false);
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile result =
                fileMetadataService.move(
                        file,
                        destinationFolderId
                );
    
        assertSame(file, result);
        assertEquals(
                destinationFolderId,
                file.getFolderId()
        );
    
        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldMoveFileToRoot() {
        UUID sourceFolderId = UUID.randomUUID();
    
        StoredFile file = createFile(
                sourceFolderId,
                "report.pdf"
        );

        UUID ownerId = file.getOwnerId();
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq("report.pdf"),
                        anyCollection()
                ))
                .thenReturn(false);
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile result =
                fileMetadataService.move(
                        file,
                        null
                );
    
        assertSame(file, result);
        assertNull(file.getFolderId());
    
        verify(storedFileRepository)
                .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq("report.pdf"),
                        anyCollection()
                );
    
        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldRejectMoveWhenDestinationContainsDuplicateFileName() {
        UUID sourceFolderId = UUID.randomUUID();
        UUID destinationFolderId = UUID.randomUUID();
    
        StoredFile file = createFile(
                sourceFolderId,
                "report.pdf"
        );

        UUID ownerId = file.getOwnerId();
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq(destinationFolderId),
                        eq("report.pdf"),
                        anyCollection()
                ))
                .thenReturn(true);
    
        assertThrows(
                DuplicateFileNameException.class,
                () -> fileMetadataService.move(
                        file,
                        destinationFolderId
                )
        );
    
        // The entity must remain in its original folder.
        assertEquals(
                sourceFolderId,
                file.getFolderId()
        );
    
        verify(storedFileRepository, never())
                .saveAndFlush(any(StoredFile.class));
    }

    @Test
    void shouldTreatMoveToSameFolderAsNoOp() {
        UUID folderId = UUID.randomUUID();
    
        StoredFile file = createFile(
                folderId,
                "report.pdf"
        );
    
        StoredFile result =
                fileMetadataService.move(
                        file,
                        folderId
                );
    
        assertSame(file, result);
        assertEquals(folderId, file.getFolderId());
    
        verify(storedFileRepository, never())
                .saveAndFlush(any(StoredFile.class));
    
        verify(storedFileRepository, never())
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        any(),
                        any(),
                        anyString(),
                        anyCollection()
                );
    
        verify(storedFileRepository, never())
                .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                        any(),
                        anyString(),
                        anyCollection()
                );
    }

    @Test
    void shouldSoftDeleteFile() {
        UUID folderId = UUID.randomUUID();
    
        StoredFile file =
                createFile(folderId, "report.pdf");
    
        assertNull(file.getDeletedAt());
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile result =
                fileMetadataService.softDelete(file);
    
        assertNotNull(result.getDeletedAt());
        assertEquals(file, result);
    
        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldRestoreFileToOriginalFolder() {
        UUID folderId = UUID.randomUUID();

        mockAccessibleFolder(folderId);
    
        StoredFile file =
                createFile(
                        folderId,
                        "report.pdf"
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(file));
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        ownerId,
                        folderId,
                        "report.pdf",
                        Set.of(
                                FileStatus.UPLOADING,
                                FileStatus.READY
                        )
                ))
                .thenReturn(false);
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile restored =
                fileMetadataService.restore(
                    file.getOwnerId(),
                    file.getId()
                );
    
        assertEquals(
                "report.pdf",
                restored.getName()
        );
    
        assertEquals(
                folderId,
                restored.getFolderId()
        );
    
        assertNull(restored.getDeletedAt());

        InOrder lockOrder = inOrder(
                hierarchyCoordinator,
                storedFileRepository,
                folderAccessValidator
        );
        lockOrder.verify(hierarchyCoordinator).acquireShared(ownerId);
        lockOrder.verify(storedFileRepository)
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.READY
                );
        lockOrder.verify(folderAccessValidator)
                .requireAccessibleFolder(ownerId, folderId);
        lockOrder.verify(hierarchyCoordinator)
                .acquireNamespaceExclusive(ownerId, folderId);
    
        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldRestoreFileToRoot() {
        UUID originalFolderId = UUID.randomUUID();
    
        StoredFile file =
                createFile(
                        originalFolderId,
                        "report.pdf"
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(file));

        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                originalFolderId
        )).thenThrow(new FolderNotFoundException("Folder not found"));
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                        ownerId,
                        "report.pdf",
                        Set.of(
                                FileStatus.UPLOADING,
                                FileStatus.READY
                        )
                ))
                .thenReturn(false);
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile restored =
                fileMetadataService.restore(
                    file.getOwnerId(),
                    file.getId()
                );
    
        assertEquals(
                "report.pdf",
                restored.getName()
        );
    
        assertNull(restored.getFolderId());
        assertNull(restored.getDeletedAt());
    
        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldNotRestoreFileToRootFromPendingPurgeSubtree() {
        UUID originalFolderId = UUID.randomUUID();
        StoredFile file = createFile(originalFolderId, "report.pdf");
        file.softDelete();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(file));
        doThrow(new FolderNotFoundException("Folder not found"))
                .when(folderAccessValidator)
                .requireNoPendingPurgeInAncestry(
                        ownerId,
                        originalFolderId
                );

        assertThrows(
                FolderNotFoundException.class,
                () -> fileMetadataService.restore(ownerId, file.getId())
        );

        assertNotNull(file.getDeletedAt());
        assertEquals(originalFolderId, file.getFolderId());
        verify(storedFileRepository, never()).saveAndFlush(any());
        verify(folderAccessValidator, never())
                .requireAccessibleFolder(any(), any());
    }

    @Test
    void shouldAutomaticallyRenameFileWhenRestoreNameAlreadyExists() {
        UUID folderId = UUID.randomUUID();

        mockAccessibleFolder(folderId);
    
        StoredFile file =
                createFile(
                        folderId,
                        "report.pdf"
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(file));
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        ownerId,
                        folderId,
                        "report.pdf",
                        Set.of(
                                FileStatus.UPLOADING,
                                FileStatus.READY
                        )
                ))
                .thenReturn(true);
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        ownerId,
                        folderId,
                        "report (restored).pdf",
                        Set.of(
                                FileStatus.UPLOADING,
                                FileStatus.READY
                        )
                ))
                .thenReturn(false);
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile restored =
                fileMetadataService.restore(
                    file.getOwnerId(),
                    file.getId()
                );
    
        assertEquals(
                "report (restored).pdf",
                restored.getName()
        );
    
        assertEquals(
                folderId,
                restored.getFolderId()
        );
    
        assertNull(restored.getDeletedAt());
    
        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldIncrementRestoredSuffixUntilAvailableNameIsFound() {
        UUID folderId = UUID.randomUUID();

        mockAccessibleFolder(folderId);
    
        StoredFile file =
                createFile(
                        folderId,
                        "report.pdf"
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(file));
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq(folderId),
                        anyString(),
                        eq(Set.of(
                                FileStatus.UPLOADING,
                                FileStatus.READY
                        ))
                ))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(2);
    
                    return name.equals("report.pdf")
                            || name.equals("report (restored).pdf")
                            || name.equals("report (restored 2).pdf");
                });
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile restored =
                fileMetadataService.restore(
                    file.getOwnerId(),
                    file.getId()
                );
    
        assertEquals(
                "report (restored 3).pdf",
                restored.getName()
        );
    
        assertNull(restored.getDeletedAt());
    }

    @Test
    void shouldAutomaticallyRenameExtensionlessFileOnRestoreConflict() {
        UUID folderId = UUID.randomUUID();

        mockAccessibleFolder(folderId);
    
        StoredFile file =
                createFile(
                        folderId,
                        "README"
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(file));
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        ownerId,
                        folderId,
                        "README",
                        Set.of(
                                FileStatus.UPLOADING,
                                FileStatus.READY
                        )
                ))
                .thenReturn(true);
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        ownerId,
                        folderId,
                        "README (restored)",
                        Set.of(
                                FileStatus.UPLOADING,
                                FileStatus.READY
                        )
                ))
                .thenReturn(false);
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile restored =
                fileMetadataService.restore(
                    file.getOwnerId(),
                    file.getId()
                );
    
        assertEquals(
                "README (restored)",
                restored.getName()
        );
    
        assertNull(restored.getDeletedAt());
    }

    @Test
    void shouldTruncateLongFileNameWhenGeneratingRestoredName() {
        UUID folderId = UUID.randomUUID();

        mockAccessibleFolder(folderId);
    
        // 251 chars + ".pdf" = 255 chars
        String originalName =
                "a".repeat(251) + ".pdf";
    
        StoredFile file =
                createFile(
                        folderId,
                        originalName
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(file));
    
        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq(folderId),
                        anyString(),
                        eq(Set.of(
                                FileStatus.UPLOADING,
                                FileStatus.READY
                        ))
                ))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(2);
    
                    // Only the original 255-character name is occupied.
                    return name.equals(originalName);
                });
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile restored =
                fileMetadataService.restore(
                    file.getOwnerId(),
                    file.getId()
                );
    
        assertEquals(
                255,
                restored.getName().length()
        );
    
        assertTrue(
                restored.getName().endsWith(
                        " (restored).pdf"
                )
        );
    
        assertNull(restored.getDeletedAt());
    }

    @Test
    void shouldMarkTrashedFileForPermanentDeletion() {
        StoredFile file = createFile(null, "report.pdf");
        file.markReady();
        file.softDelete();

        UUID ownerId = file.getOwnerId();
        UUID fileId = file.getId();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(file));

        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);

        UUID result =
                fileMetadataService.requestPermanentDeletion(
                        ownerId,
                        fileId
                );

        assertNotNull(result);
        assertNotNull(file.getDeletedAt());
        assertNotNull(file.getPurgeRequestedAt());
        assertEquals(FileStatus.READY, file.getStatus());

        verify(storedFileRepository).saveAndFlush(file);
        var persistenceOrder = inOrder(storedFileRepository, storageIdentity, outboxWriter);
        persistenceOrder.verify(storedFileRepository).saveAndFlush(file);
        persistenceOrder.verify(storageIdentity).recordPurgeTarget(eq(file), any());
        persistenceOrder.verify(outboxWriter).append(any());
        verify(outboxWriter).append(argThat(event ->
                event.getEventType().equals("FILE_PURGE_REQUESTED")
                        && event.getAggregateId().equals(fileId)
                        && event.getPayloadVersion() == 1
                        && event.getPayload().get("ownerId").equals(ownerId.toString())
                        && event.getCreatedAt().equals(file.getPurgeRequestedAt())
        ));
    }

    @Test
    void shouldRejectFilePurgeRequestBelowPendingFolderPurge() {
        UUID folderId = UUID.randomUUID();
        StoredFile file = createFile(folderId, "report.pdf");
        file.softDelete();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.of(file));
        doThrow(new FolderNotFoundException("Folder not found"))
                .when(folderAccessValidator)
                .requireNoPendingPurgeInAncestry(ownerId, folderId);

        assertThrows(
                FolderNotFoundException.class,
                () -> fileMetadataService.requestPermanentDeletion(
                        ownerId,
                        file.getId()
                )
        );

        assertNull(file.getPurgeRequestedAt());
        verify(storedFileRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void shouldRejectRestoreWhenFileIsNotInTrashAfterHierarchyLock() {
        UUID fileId = UUID.randomUUID();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.empty());

        FileNotFoundException exception = assertThrows(
                FileNotFoundException.class,
                () -> fileMetadataService.restore(ownerId, fileId)
        );

        assertEquals("File not found", exception.getMessage());

        var inOrder = inOrder(
                hierarchyCoordinator,
                userRepository,
                storedFileRepository
        );
        inOrder.verify(hierarchyCoordinator).acquireShared(ownerId);
        inOrder.verify(userRepository).existsById(ownerId);
        inOrder.verify(storedFileRepository)
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                );

        verify(storedFileRepository, never()).saveAndFlush(any());
        verifyNoInteractions(folderAccessValidator);
    }

    @Test
    void shouldRejectPermanentDeletionWhenFileIsNotInTrashAfterHierarchyLock() {
        UUID fileId = UUID.randomUUID();

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(Optional.empty());

        FileNotFoundException exception = assertThrows(
                FileNotFoundException.class,
                () -> fileMetadataService.requestPermanentDeletion(
                        ownerId,
                        fileId
                )
        );

        assertEquals("File not found", exception.getMessage());

        var inOrder = inOrder(
                hierarchyCoordinator,
                userRepository,
                storedFileRepository
        );
        inOrder.verify(hierarchyCoordinator).acquireShared(ownerId);
        inOrder.verify(userRepository).existsById(ownerId);
        inOrder.verify(storedFileRepository)
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                );

        verify(storedFileRepository, never()).saveAndFlush(any());
        verifyNoInteractions(folderAccessValidator);
    }

    private void mockAccessibleFolder(UUID folderId) {
        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        )).thenReturn(mock(Folder.class));
    }

    private StoredFile createFile(
            UUID folderId,
            String name
    ) {
        UUID fileId = UUID.randomUUID();

        return new StoredFile(
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
    }
}
