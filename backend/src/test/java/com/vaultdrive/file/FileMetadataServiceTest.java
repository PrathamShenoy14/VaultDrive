package com.vaultdrive.file;

import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
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

    private FileMetadataService fileMetadataService;

    private UUID ownerId;

    @BeforeEach
    void setUp() {
        fileMetadataService = new FileMetadataService(
                storedFileRepository,
                userRepository
        );

        ownerId = UUID.randomUUID();

        User user = mock(User.class);

        lenient()
                .when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(user));
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

        verify(userRepository)
                .findByIdForUpdate(ownerId);

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

        verify(storedFileRepository)
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

        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);

        fileMetadataService.markReady(file);

        assertEquals(
                FileStatus.READY,
                file.getStatus()
        );

        verify(storedFileRepository)
                .saveAndFlush(file);
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

        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);

        fileMetadataService.markFailed(file);

        assertEquals(
                FileStatus.FAILED,
                file.getStatus()
        );

        verify(storedFileRepository)
                .saveAndFlush(file);
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

        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));

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

        verify(userRepository)
                .findByIdForUpdate(ownerId);

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

        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));

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

        verify(userRepository)
                .findByIdForUpdate(ownerId);

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

        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));

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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
        StoredFile result =
                fileMetadataService.rename(
                        file,
                        "report.pdf"
                );
    
        assertSame(file, result);
        assertEquals("report.pdf", result.getName());
    
        verify(userRepository)
                .findByIdForUpdate(ownerId);
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
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
    
        verify(userRepository)
                .findByIdForUpdate(ownerId);
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
        StoredFile result =
                fileMetadataService.move(
                        file,
                        folderId
                );
    
        assertSame(file, result);
        assertEquals(folderId, file.getFolderId());
    
        verify(userRepository)
                .findByIdForUpdate(ownerId);
    
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
    
        when(userRepository.findByIdForUpdate(ownerId))
                .thenReturn(Optional.of(mock(User.class)));
    
        when(storedFileRepository.saveAndFlush(file))
                .thenReturn(file);
    
        StoredFile result =
                fileMetadataService.softDelete(file);
    
        assertNotNull(result.getDeletedAt());
        assertEquals(file, result);
    
        verify(userRepository)
                .findByIdForUpdate(ownerId);
    
        verify(storedFileRepository)
                .saveAndFlush(file);
    }

    @Test
    void shouldRestoreFileToOriginalFolder() {
        UUID folderId = UUID.randomUUID();
    
        StoredFile file =
                createFile(
                        folderId,
                        "report.pdf"
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();
    
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
                        file,
                        folderId
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
                        file,
                        null
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
    
        StoredFile file =
                createFile(
                        folderId,
                        "report.pdf"
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();
    
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
                        file,
                        folderId
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
    
        StoredFile file =
                createFile(
                        folderId,
                        "report.pdf"
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();
    
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
                        file,
                        folderId
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
    
        StoredFile file =
                createFile(
                        folderId,
                        "README"
                );
    
        UUID ownerId = file.getOwnerId();
    
        file.softDelete();
    
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
                        file,
                        folderId
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
                        file,
                        folderId
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
