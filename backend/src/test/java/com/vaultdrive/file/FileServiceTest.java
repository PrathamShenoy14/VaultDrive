package com.vaultdrive.file;

import com.vaultdrive.file.dto.UploadFileResponse;
import com.vaultdrive.file.exception.FileUploadException;
import com.vaultdrive.file.exception.InvalidFileNameException;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageKeyGenerator;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.file.exception.InvalidFilePaginationException;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;

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
    private StorageKeyGenerator storageKeyGenerator;

    @Mock
    private StoredFileRepository storedFileRepository;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(
                folderAccessValidator,
                fileNameValidator,
                fileMetadataService,
                objectStorageService,
                storageKeyGenerator,
                storedFileRepository
        );
    }

    @Test
    void shouldUploadFileToFolderAndMarkReady() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                " resume.pdf ",
                "application/pdf",
                "pdf-content".getBytes(StandardCharsets.UTF_8)
        );

        when(fileNameValidator.validateAndNormalize(" resume.pdf "))
                .thenReturn("resume.pdf");

        Folder folder = mock(Folder.class);

        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        )).thenReturn(folder);

        when(storageKeyGenerator.generateFileKey(
                eq(ownerId),
                any(UUID.class)
        )).thenAnswer(invocation -> {
            UUID fileId = invocation.getArgument(1);
            return "users/" + ownerId + "/files/" + fileId;
        });

        when(fileMetadataService.createUploading(any(StoredFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UploadFileResponse response =
                fileService.uploadFile(ownerId, folderId, multipartFile);

        ArgumentCaptor<StoredFile> fileCaptor =
                ArgumentCaptor.forClass(StoredFile.class);

        verify(fileMetadataService)
                .createUploading(fileCaptor.capture());

        StoredFile storedFile = fileCaptor.getValue();

        assertEquals(ownerId, storedFile.getOwnerId());
        assertEquals(folderId, storedFile.getFolderId());
        assertEquals("resume.pdf", storedFile.getName());
        assertEquals("application/pdf", storedFile.getContentType());
        assertEquals(multipartFile.getSize(), storedFile.getSizeBytes());

        // File begins as UPLOADING.
        // FileMetadataService is mocked, so markReady() does not actually
        // mutate the entity in this unit test.
        assertEquals(FileStatus.UPLOADING, storedFile.getStatus());

        assertEquals(
                "users/" + ownerId + "/files/" + storedFile.getId(),
                storedFile.getStorageKey()
        );

        verify(objectStorageService).upload(
                eq(storedFile.getStorageKey()),
                any(InputStream.class),
                eq(multipartFile.getSize()),
                eq("application/pdf")
        );

        verify(fileMetadataService).markReady(storedFile);
        verify(fileMetadataService, never()).markFailed(any());

        assertEquals(storedFile.getId(), response.fileId());
        assertEquals("resume.pdf", response.name());
        assertEquals(folderId, response.folderId());
        assertEquals("application/pdf", response.contentType());
        assertEquals(multipartFile.getSize(), response.sizeBytes());
    }

    @Test
    void shouldUploadFileToRootWithoutLookingUpFolder() {
        UUID ownerId = UUID.randomUUID();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        when(fileNameValidator.validateAndNormalize("notes.txt"))
                .thenReturn("notes.txt");

        when(storageKeyGenerator.generateFileKey(
                eq(ownerId),
                any(UUID.class)
        )).thenReturn("users/" + ownerId + "/files/test-file-id");

        when(fileMetadataService.createUploading(any(StoredFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UploadFileResponse response =
                fileService.uploadFile(ownerId, null, multipartFile);

        assertNull(response.folderId());

        verifyNoInteractions(folderAccessValidator);

        verify(objectStorageService).upload(
                eq("users/" + ownerId + "/files/test-file-id"),
                any(InputStream.class),
                eq(multipartFile.getSize()),
                eq("text/plain")
        );

        verify(fileMetadataService).markReady(any(StoredFile.class));
        verify(fileMetadataService, never()).markFailed(any());
    }

    @Test
    void shouldRejectInvalidDestinationFolder() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                new byte[]{1, 2, 3}
        );

        when(fileNameValidator.validateAndNormalize("resume.pdf"))
                .thenReturn("resume.pdf");

                when(folderAccessValidator.requireAccessibleFolder(
                        ownerId,
                        folderId
                )).thenThrow(
                        new FolderNotFoundException(
                                "Folder not found"
                        )
                );

        assertThrows(
                FolderNotFoundException.class,
                () -> fileService.uploadFile(
                        ownerId,
                        folderId,
                        multipartFile
                )
        );

        verifyNoInteractions(storageKeyGenerator);
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldStopImmediatelyWhenFileNameIsInvalid() {
        UUID ownerId = UUID.randomUUID();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "../secret.txt",
                "text/plain",
                new byte[]{1}
        );

        when(fileNameValidator.validateAndNormalize("../secret.txt"))
                .thenThrow(
                        new InvalidFileNameException(
                                "File name must not contain path separators"
                        )
                );

        InvalidFileNameException exception = assertThrows(
                InvalidFileNameException.class,
                () -> fileService.uploadFile(
                        ownerId,
                        null,
                        multipartFile
                )
        );

        assertEquals(
                "File name must not contain path separators",
                exception.getMessage()
        );

        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(storageKeyGenerator);
        verifyNoInteractions(fileMetadataService);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldMarkFileFailedWhenObjectStorageUploadFails() {
        UUID ownerId = UUID.randomUUID();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                new byte[]{1, 2, 3, 4}
        );

        when(fileNameValidator.validateAndNormalize("photo.png"))
                .thenReturn("photo.png");

        when(storageKeyGenerator.generateFileKey(
                eq(ownerId),
                any(UUID.class)
        )).thenReturn("users/" + ownerId + "/files/generated-id");

        when(fileMetadataService.createUploading(any(StoredFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RuntimeException storageFailure =
                new RuntimeException("Storage unavailable");

        doThrow(storageFailure)
                .when(objectStorageService)
                .upload(
                        anyString(),
                        any(InputStream.class),
                        anyLong(),
                        any()
                );

        FileUploadException exception = assertThrows(
                FileUploadException.class,
                () -> fileService.uploadFile(
                        ownerId,
                        null,
                        multipartFile
                )
        );

        assertEquals("Failed to upload file", exception.getMessage());
        assertSame(storageFailure, exception.getCause());

        ArgumentCaptor<StoredFile> captor =
                ArgumentCaptor.forClass(StoredFile.class);

        verify(fileMetadataService).markFailed(captor.capture());

        StoredFile failedFile = captor.getValue();

        assertEquals("photo.png", failedFile.getName());

        verify(fileMetadataService, never()).markReady(any());
    }

    @Test
    void shouldPreserveOriginalStorageFailureWhenMarkFailedAlsoFails() {
        UUID ownerId = UUID.randomUUID();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                new byte[]{1, 2, 3}
        );

        when(fileNameValidator.validateAndNormalize("photo.png"))
                .thenReturn("photo.png");

        when(storageKeyGenerator.generateFileKey(
                eq(ownerId),
                any(UUID.class)
        )).thenReturn("users/" + ownerId + "/files/generated-id");

        when(fileMetadataService.createUploading(any(StoredFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RuntimeException storageFailure =
                new RuntimeException("Garage unavailable");

        doThrow(storageFailure)
                .when(objectStorageService)
                .upload(
                        anyString(),
                        any(InputStream.class),
                        anyLong(),
                        any()
                );

        doThrow(new RuntimeException("Database unavailable"))
                .when(fileMetadataService)
                .markFailed(any(StoredFile.class));

        FileUploadException exception = assertThrows(
                FileUploadException.class,
                () -> fileService.uploadFile(
                        ownerId,
                        null,
                        multipartFile
                )
        );

        assertEquals("Failed to upload file", exception.getMessage());
        assertSame(storageFailure, exception.getCause());

        verify(fileMetadataService).markFailed(any(StoredFile.class));
        verify(fileMetadataService, never()).markReady(any());
    }

    @Test
    void shouldReportMetadataFinalizationFailureWithoutDeletingObject() {
        UUID ownerId = UUID.randomUUID();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "report.pdf",
                "application/pdf",
                new byte[]{1, 2, 3}
        );

        when(fileNameValidator.validateAndNormalize("report.pdf"))
                .thenReturn("report.pdf");

        when(storageKeyGenerator.generateFileKey(
                eq(ownerId),
                any(UUID.class)
        )).thenReturn("users/" + ownerId + "/files/generated-id");

        when(fileMetadataService.createUploading(any(StoredFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RuntimeException databaseFailure =
                new RuntimeException("Database unavailable");

        doThrow(databaseFailure)
                .when(fileMetadataService)
                .markReady(any(StoredFile.class));

        FileUploadException exception = assertThrows(
                FileUploadException.class,
                () -> fileService.uploadFile(
                        ownerId,
                        null,
                        multipartFile
                )
        );

        assertEquals(
                "File was uploaded but metadata could not be finalized",
                exception.getMessage()
        );

        assertSame(databaseFailure, exception.getCause());

        verify(objectStorageService).upload(
                anyString(),
                any(InputStream.class),
                anyLong(),
                any()
        );

        /*
         * The object has already been successfully uploaded.
         * We deliberately keep it for future reconciliation instead
         * of blindly deleting it.
         */
        verify(objectStorageService, never()).delete(anyString());

        verify(fileMetadataService, never()).markFailed(any());
    }

    @Test
    void storageKeyShouldNotContainOriginalFileName() {
        UUID ownerId = UUID.randomUUID();

        String originalFileName =
                "My Very Important Resume 2026 FINAL.pdf";

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                originalFileName,
                "application/pdf",
                new byte[]{1}
        );

        when(fileNameValidator.validateAndNormalize(originalFileName))
                .thenReturn(originalFileName);

        when(storageKeyGenerator.generateFileKey(
                eq(ownerId),
                any(UUID.class)
        )).thenAnswer(invocation -> {
            UUID fileId = invocation.getArgument(1);

            return "users/"
                    + ownerId
                    + "/files/"
                    + fileId;
        });

        when(fileMetadataService.createUploading(any(StoredFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        fileService.uploadFile(
                ownerId,
                null,
                multipartFile
        );

        ArgumentCaptor<StoredFile> captor =
                ArgumentCaptor.forClass(StoredFile.class);

        verify(fileMetadataService)
                .createUploading(captor.capture());

        StoredFile storedFile = captor.getValue();

        assertFalse(
                storedFile.getStorageKey()
                        .contains(originalFileName)
        );

        assertEquals(
                "users/"
                        + ownerId
                        + "/files/"
                        + storedFile.getId(),
                storedFile.getStorageKey()
        );
    }

    @Test
    void shouldListReadyFilesFromRoot() {
        UUID ownerId = UUID.randomUUID();

        StoredFile alpha = createReadyFile(
                ownerId,
                null,
                "alpha.pdf"
        );

        StoredFile bravo = createReadyFile(
                ownerId,
                null,
                "bravo.pdf"
        );

        Pageable expectedPageable = PageRequest.of(
                0,
                50,
                Sort.by("name").ascending()
        );

        when(storedFileRepository
                .findByOwnerIdAndFolderIdIsNullAndStatusAndDeletedAtIsNull(
                        ownerId,
                        FileStatus.READY,
                        expectedPageable
                ))
                .thenReturn(
                        new PageImpl<>(
                                List.of(alpha, bravo),
                                expectedPageable,
                                2
                        )
                );

        FilePageResponse response =
                fileService.listFiles(
                        ownerId,
                        null,
                        0,
                        50
                );

        assertEquals(2, response.content().size());
        assertEquals(0, response.page());
        assertEquals(50, response.size());
        assertEquals(2, response.totalElements());
        assertEquals(1, response.totalPages());

        FileResponse first = response.content().get(0);

        assertEquals(alpha.getId(), first.id());
        assertEquals("alpha.pdf", first.name());
        assertNull(first.folderId());
        assertEquals("application/pdf", first.contentType());
        assertEquals(100L, first.sizeBytes());
        assertEquals(alpha.getCreatedAt(), first.createdAt());
        assertEquals(alpha.getUpdatedAt(), first.updatedAt());

        assertEquals(
                "bravo.pdf",
                response.content().get(1).name()
        );

        verifyNoInteractions(folderAccessValidator);

        verify(storedFileRepository)
                .findByOwnerIdAndFolderIdIsNullAndStatusAndDeletedAtIsNull(
                        ownerId,
                        FileStatus.READY,
                        expectedPageable
                );
    }

    @Test
    void shouldListReadyFilesFromAccessibleFolder() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        Folder folder = mock(Folder.class);

        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        )).thenReturn(folder);

        StoredFile file = createReadyFile(
                ownerId,
                folderId,
                "resume.pdf"
        );

        Pageable expectedPageable = PageRequest.of(
                0,
                25,
                Sort.by("name").ascending()
        );

        when(storedFileRepository
                .findByOwnerIdAndFolderIdAndStatusAndDeletedAtIsNull(
                        ownerId,
                        folderId,
                        FileStatus.READY,
                        expectedPageable
                ))
                .thenReturn(
                        new PageImpl<>(
                                List.of(file),
                                expectedPageable,
                                30
                        )
                );

        FilePageResponse response =
                fileService.listFiles(
                        ownerId,
                        folderId,
                        0,
                        25
                );

        assertEquals(1, response.content().size());
        assertEquals(0, response.page());
        assertEquals(25, response.size());
        assertEquals(30, response.totalElements());
        assertEquals(2, response.totalPages());

        FileResponse listedFile =
                response.content().getFirst();

        assertEquals(file.getId(), listedFile.id());
        assertEquals("resume.pdf", listedFile.name());
        assertEquals(folderId, listedFile.folderId());
        assertEquals(
                "application/pdf",
                listedFile.contentType()
        );
        assertEquals(100L, listedFile.sizeBytes());

        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        folderId
                );

        verify(storedFileRepository)
                .findByOwnerIdAndFolderIdAndStatusAndDeletedAtIsNull(
                        ownerId,
                        folderId,
                        FileStatus.READY,
                        expectedPageable
                );
    }

    @Test
    void shouldRejectListingWhenFolderIsNotAccessible() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        when(folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        )).thenThrow(
                new FolderNotFoundException(
                        "Folder not found"
                )
        );

        FolderNotFoundException exception =
                assertThrows(
                        FolderNotFoundException.class,
                        () -> fileService.listFiles(
                                ownerId,
                                folderId,
                                0,
                                50
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

        verifyNoInteractions(storedFileRepository);
    }

    @Test
    void shouldRejectNegativePageNumber() {
        UUID ownerId = UUID.randomUUID();

        InvalidFilePaginationException exception =
                assertThrows(
                        InvalidFilePaginationException.class,
                        () -> fileService.listFiles(
                                ownerId,
                                null,
                                -1,
                                50
                        )
                );

        assertEquals(
                "Page must be greater than or equal to 0",
                exception.getMessage()
        );

        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(storedFileRepository);
    }

    @Test
    void shouldRejectPageSizeBelowOne() {
        UUID ownerId = UUID.randomUUID();

        InvalidFilePaginationException exception =
                assertThrows(
                        InvalidFilePaginationException.class,
                        () -> fileService.listFiles(
                                ownerId,
                                null,
                                0,
                                0
                        )
                );

        assertEquals(
                "Size must be between 1 and 100",
                exception.getMessage()
        );

        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(storedFileRepository);
    }

    @Test
    void shouldRejectPageSizeAboveMaximum() {
        UUID ownerId = UUID.randomUUID();

        InvalidFilePaginationException exception =
                assertThrows(
                        InvalidFilePaginationException.class,
                        () -> fileService.listFiles(
                                ownerId,
                                null,
                                0,
                                101
                        )
                );

        assertEquals(
                "Size must be between 1 and 100",
                exception.getMessage()
        );

        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(storedFileRepository);
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
