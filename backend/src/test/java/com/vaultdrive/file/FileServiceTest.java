package com.vaultdrive.file;

import com.vaultdrive.file.dto.UploadFileResponse;
import com.vaultdrive.file.exception.FileUploadException;
import com.vaultdrive.file.exception.InvalidFileNameException;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageKeyGenerator;

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

    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(
                folderAccessValidator,
                fileNameValidator,
                fileMetadataService,
                objectStorageService,
                storageKeyGenerator
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
}
