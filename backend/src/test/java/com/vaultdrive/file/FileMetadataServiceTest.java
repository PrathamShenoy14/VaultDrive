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
