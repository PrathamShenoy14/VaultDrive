package com.vaultdrive.file;

import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.file.exception.UploadFinalizationRejectedException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;

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

    private FileMetadataService fileMetadataService;

    private UUID ownerId;

    @BeforeEach
    void setUp() {
        fileMetadataService = new FileMetadataService(
                storedFileRepository,
                userRepository,
                folderAccessValidator,
                hierarchyCoordinator
        );

        ownerId = UUID.randomUUID();

        User user = mock(User.class);

        lenient()
                .when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(user));

        lenient()
                .when(userRepository.existsById(ownerId))
                .thenReturn(true);
    }

    @Test
    void shouldCreateUploadingFileInRootWhenNameIsAvailable() {
        StoredFile file = createFile(
                null,
                "report.pdf"
        );

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
                fileMetadataService.createUploading(file);

        assertSame(file, result);
        assertEquals(FileStatus.UPLOADING, result.getStatus());

        InOrder order = inOrder(
                hierarchyCoordinator,
                userRepository,
                storedFileRepository
        );

        order.verify(hierarchyCoordinator).acquireShared(ownerId);
        order.verify(userRepository).existsById(ownerId);
        order.verify(storedFileRepository)
                .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq("report.pdf"),
                        anyCollection()
                );

        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldRejectDuplicateRootFileWhenNameIsReserved() {
        StoredFile file = createFile(
                null,
                "report.pdf"
        );

        when(storedFileRepository
                .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq("report.pdf"),
                        anyCollection()
                ))
                .thenReturn(true);

        DuplicateFileNameException exception =
                assertThrows(
                        DuplicateFileNameException.class,
                        () -> fileMetadataService
                                .createUploading(file)
                );

        assertEquals(
                "A file with this name already exists",
                exception.getMessage()
        );

        verify(storedFileRepository, never())
                .saveAndFlush(any());
    }

    @Test
    void shouldCheckDuplicateWithinSpecificFolder() {
        UUID folderId = UUID.randomUUID();

        StoredFile file = createFile(
                folderId,
                "report.pdf"
        );

        when(storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq(folderId),
                        eq("report.pdf"),
                        anyCollection()
                ))
                .thenReturn(false);

        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);

        fileMetadataService.createUploading(file);

        InOrder order = inOrder(
                hierarchyCoordinator,
                folderAccessValidator,
                storedFileRepository
        );
        order.verify(hierarchyCoordinator).acquireShared(ownerId);
        order.verify(folderAccessValidator)
                .requireAccessibleFolder(ownerId, folderId);
        order.verify(storedFileRepository)
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq(folderId),
                        eq("report.pdf"),
                        argThat(statuses ->
                                statuses.contains(FileStatus.UPLOADING)
                                        && statuses.contains(FileStatus.READY)
                                        && !statuses.contains(FileStatus.FAILED)
                        )
                );
    }

    @Test
    void shouldOnlyTreatUploadingAndReadyAsNameReservingStatuses() {
        StoredFile file = createFile(
                null,
                "retry.pdf"
        );

        when(storedFileRepository
                .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq("retry.pdf"),
                        anyCollection()
                ))
                .thenReturn(false);

        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);

        fileMetadataService.createUploading(file);

        verify(storedFileRepository)
                .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                        eq(ownerId),
                        eq("retry.pdf"),
                        argThat(statuses ->
                                statuses.size() == 2
                                        && statuses.contains(FileStatus.UPLOADING)
                                        && statuses.contains(FileStatus.READY)
                                        && !statuses.contains(FileStatus.FAILED)
                        )
                );
    }

    @Test
    void shouldMarkUploadingFileReady() {
        StoredFile file = createFile(
                null,
                "report.pdf"
        );

        assertEquals(
                FileStatus.UPLOADING,
                file.getStatus()
        );

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.UPLOADING
                ))
                .thenReturn(Optional.of(file));

        when(storedFileRepository.transitionUploadStatus(
                eq(file.getId()),
                eq(ownerId),
                eq(FileStatus.UPLOADING),
                eq(file.getVersion()),
                eq(FileStatus.READY),
                any()
        )).thenReturn(1);

        fileMetadataService.markReady(file);

        verify(hierarchyCoordinator).acquireShared(ownerId);
        verify(storedFileRepository).transitionUploadStatus(
                eq(file.getId()),
                eq(ownerId),
                eq(FileStatus.UPLOADING),
                eq(0L),
                eq(FileStatus.READY),
                any()
        );
    }

    @Test
    void shouldMarkUploadingFileFailed() {
        StoredFile file = createFile(
                null,
                "report.pdf"
        );

        assertEquals(
                FileStatus.UPLOADING,
                file.getStatus()
        );

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.UPLOADING
                ))
                .thenReturn(Optional.of(file));

        when(storedFileRepository.transitionUploadStatus(
                eq(file.getId()),
                eq(ownerId),
                eq(FileStatus.UPLOADING),
                eq(file.getVersion()),
                eq(FileStatus.FAILED),
                any()
        )).thenReturn(1);

        fileMetadataService.markFailed(file);

        verify(hierarchyCoordinator).acquireShared(ownerId);
        verify(storedFileRepository).transitionUploadStatus(
                eq(file.getId()),
                eq(ownerId),
                eq(FileStatus.UPLOADING),
                eq(0L),
                eq(FileStatus.FAILED),
                any()
        );
    }

    @Test
    void shouldFailFinalizationWhenDestinationIsNoLongerAccessible() {
        UUID folderId = UUID.randomUUID();
        StoredFile file = createFile(folderId, "report.pdf");

        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.UPLOADING
                ))
                .thenReturn(Optional.of(file));

        FolderNotFoundException inaccessible =
                new FolderNotFoundException("Folder not found");

        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        )).thenThrow(inaccessible);

        when(storedFileRepository.transitionUploadStatus(
                eq(file.getId()),
                eq(ownerId),
                eq(FileStatus.UPLOADING),
                eq(0L),
                eq(FileStatus.FAILED),
                any()
        )).thenReturn(1);

        UploadFinalizationRejectedException exception = assertThrows(
                UploadFinalizationRejectedException.class,
                () -> fileMetadataService.markReady(file)
        );

        assertSame(inaccessible, exception.getCause());

        InOrder order = inOrder(
                hierarchyCoordinator,
                storedFileRepository,
                folderAccessValidator
        );
        order.verify(hierarchyCoordinator).acquireShared(ownerId);
        order.verify(storedFileRepository)
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        file.getId(),
                        ownerId,
                        FileStatus.UPLOADING
                );
        order.verify(folderAccessValidator)
                .requireAccessibleFolder(ownerId, folderId);
        order.verify(storedFileRepository).transitionUploadStatus(
                eq(file.getId()),
                eq(ownerId),
                eq(FileStatus.UPLOADING),
                eq(0L),
                eq(FileStatus.FAILED),
                any()
        );
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

        UUID ownerId = file.getOwnerId();
    
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
    
        UUID ownerId = file.getOwnerId();
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
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

        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));

        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);

        StoredFile result =
                fileMetadataService.requestPermanentDeletion(
                        ownerId,
                        fileId
                );

        assertNotNull(result.getDeletedAt());
        assertNotNull(result.getPurgeRequestedAt());
        assertEquals(FileStatus.READY, result.getStatus());

        verify(storedFileRepository).saveAndFlush(file);
    }

    @Test
    void shouldRejectRestoreWhenFileIsNotInTrashAfterLockingOwner() {
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

        var inOrder = inOrder(userRepository, storedFileRepository);
        inOrder.verify(userRepository).findByIdForUpdate(ownerId);
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
    void shouldRejectPermanentDeletionWhenFileIsNotInTrashAfterLockingOwner() {
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

        var inOrder = inOrder(userRepository, storedFileRepository);
        inOrder.verify(userRepository).findByIdForUpdate(ownerId);
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
