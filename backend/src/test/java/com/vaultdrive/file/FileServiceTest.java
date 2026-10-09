package com.vaultdrive.file;

import com.vaultdrive.file.dto.UploadFileResponse;
import com.vaultdrive.file.dto.FileDownload;
import com.vaultdrive.file.exception.FileUploadException;
import com.vaultdrive.file.exception.InvalidFileNameException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.file.exception.FileExtensionChangeException;
import com.vaultdrive.file.exception.UploadFinalizationRejectedException;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageKeyGenerator;
import com.vaultdrive.storage.StorageObject;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.file.exception.InvalidFilePaginationException;

import org.springframework.data.domain.Page;
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
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
    private StorageKeyGenerator storageKeyGenerator;

    @Mock
    private StoredFileRepository storedFileRepository;

    @Mock
    private HierarchyCoordinator hierarchyCoordinator;

    private FileService fileService;
    private FileNameExtensionResolver fileNameExtensionResolver;

    @BeforeEach
    void setUp() {
        fileNameExtensionResolver =
                    new FileNameExtensionResolver();
        
        fileService = new FileService(
                folderAccessValidator,
                fileNameValidator,
                fileMetadataService,
                objectStorageService,
                storageKeyGenerator,
                storedFileRepository,
                fileNameExtensionResolver,
                hierarchyCoordinator
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

        when(storageKeyGenerator.generateFileKey(
                eq(ownerId),
                any(UUID.class)
        )).thenReturn("users/" + ownerId + "/files/generated-id");

        when(fileMetadataService.createUploading(any(StoredFile.class)))
                .thenThrow(new FolderNotFoundException("Folder not found"));

        assertThrows(
                FolderNotFoundException.class,
                () -> fileService.uploadFile(
                        ownerId,
                        folderId,
                        multipartFile
                )
        );

        verify(storageKeyGenerator).generateFileKey(
                eq(ownerId),
                any(UUID.class)
        );
        verify(fileMetadataService).createUploading(any(StoredFile.class));
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
    void shouldDeleteObjectWhenDestinationWasRejectedDuringFinalization() {
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

        UploadFinalizationRejectedException rejection =
                new UploadFinalizationRejectedException(
                        "Upload destination is no longer accessible",
                        new FolderNotFoundException("Folder not found")
                );
        doThrow(rejection)
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

        assertSame(rejection, exception.getCause());
        verify(objectStorageService)
                .delete("users/" + ownerId + "/files/generated-id");
        verify(fileMetadataService, never()).markFailed(any());
    }

    @Test
    void shouldKeepFailedMetadataTrackingWhenRejectedObjectCleanupFails() {
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

        UploadFinalizationRejectedException rejection =
                new UploadFinalizationRejectedException(
                        "Upload destination is no longer accessible",
                        new FolderNotFoundException("Folder not found")
                );
        RuntimeException cleanupFailure =
                new RuntimeException("Garage delete failed");

        doThrow(rejection)
                .when(fileMetadataService)
                .markReady(any(StoredFile.class));
        doThrow(cleanupFailure)
                .when(objectStorageService)
                .delete(anyString());

        assertThrows(
                FileUploadException.class,
                () -> fileService.uploadFile(
                        ownerId,
                        null,
                        multipartFile
                )
        );

        assertArrayEquals(
                new Throwable[]{cleanupFailure},
                rejection.getSuppressed()
        );
        verify(objectStorageService).delete(anyString());
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

    @Test
    void shouldDownloadReadyRootFile() throws Exception {
        UUID ownerId = UUID.randomUUID();
    
        StoredFile storedFile = createReadyFile(
                ownerId,
                null,
                "report.pdf"
        );
    
        byte[] content = "pdf-content"
                .getBytes(StandardCharsets.UTF_8);
    
        InputStream inputStream =
                new ByteArrayInputStream(content);
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(java.util.Optional.of(storedFile));
    
        when(objectStorageService.download(
                storedFile.getStorageKey()
        )).thenReturn(
                new StorageObject(inputStream)
        );
    
        FileDownload download =
                fileService.downloadFile(
                        ownerId,
                        storedFile.getId()
                );
    
        assertEquals("report.pdf", download.name());
        assertEquals(
                "application/pdf",
                download.contentType()
        );
        assertEquals(100L, download.sizeBytes());
        assertSame(inputStream, download.inputStream());
    
        verifyNoInteractions(folderAccessValidator);
    
        verify(objectStorageService).download(
                storedFile.getStorageKey()
        );
    }
    
    @Test
    void shouldDownloadReadyFileFromAccessibleFolder() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        StoredFile storedFile = createReadyFile(
                ownerId,
                folderId,
                "resume.pdf"
        );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(java.util.Optional.of(storedFile));
    
        when(objectStorageService.download(
                storedFile.getStorageKey()
        )).thenReturn(
                new StorageObject(
                        new ByteArrayInputStream(
                                new byte[]{1, 2, 3}
                        )
                )
        );
    
        FileDownload download =
                fileService.downloadFile(
                        ownerId,
                        storedFile.getId()
                );
    
        assertEquals("resume.pdf", download.name());
    
        verify(folderAccessValidator)
                .requireAccessibleFolder(
                        ownerId,
                        folderId
                );
    
        verify(objectStorageService).download(
                storedFile.getStorageKey()
        );
    }
    
    @Test
    void shouldRejectDownloadWhenFileIsNotVisible() {
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        fileId,
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(java.util.Optional.empty());
    
        FileNotFoundException exception =
                assertThrows(
                        FileNotFoundException.class,
                        () -> fileService.downloadFile(
                                ownerId,
                                fileId
                        )
                );
    
        assertEquals(
                "File not found",
                exception.getMessage()
        );
    
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(objectStorageService);
    }
    
    @Test
    void shouldNotAccessStorageWhenDownloadFolderIsInaccessible() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        StoredFile storedFile = createReadyFile(
                ownerId,
                folderId,
                "secret.pdf"
        );
    
        when(storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        storedFile.getId(),
                        ownerId,
                        FileStatus.READY
                ))
                .thenReturn(java.util.Optional.of(storedFile));
    
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
                () -> fileService.downloadFile(
                        ownerId,
                        storedFile.getId()
                )
        );
    
        verify(objectStorageService, never())
                .download(anyString());
    }

    @Test
    void shouldRenameReadyRootFile() {
        UUID ownerId = UUID.randomUUID();
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
                .upload(
                        anyString(),
                        any(),
                        anyLong(),
                        any()
                );
    
        verify(objectStorageService, never())
                .delete(anyString());
    }

    @Test
    void shouldRenameReadyFileInsideAccessibleFolder() {
        UUID ownerId = UUID.randomUUID();
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
    void shouldListTrashedFilesForOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
    
        StoredFile first =
                createReadyFile(
                        ownerId,
                        folderId,
                        "alpha.pdf"
                );
    
        StoredFile second =
                createReadyFile(
                        ownerId,
                        null,
                        "bravo.pdf"
                );
    
        first.softDelete();
        second.softDelete();
    
        Page<StoredFile> page =
                new PageImpl<>(
                        List.of(first, second),
                        PageRequest.of(
                                0,
                                50,
                                Sort.by("name").ascending()
                        ),
                        2
                );
    
        when(storedFileRepository
                .findByOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                        eq(ownerId),
                        eq(FileStatus.READY),
                        any(Pageable.class)
                ))
                .thenReturn(page);
    
        FilePageResponse response =
                fileService.listTrash(
                        ownerId,
                        0,
                        50
                );
    
        assertEquals(2, response.content().size());
        assertEquals(2, response.totalElements());
        assertEquals(1, response.totalPages());
    
        assertEquals(
                "alpha.pdf",
                response.content().get(0).name()
        );
    
        assertEquals(
                "bravo.pdf",
                response.content().get(1).name()
        );
    
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void shouldRejectInvalidTrashPageSize() {
        UUID ownerId = UUID.randomUUID();
    
        assertThrows(
                InvalidFilePaginationException.class,
                () -> fileService.listTrash(
                        ownerId,
                        0,
                        101
                )
        );
    
        verifyNoInteractions(storedFileRepository);
        verifyNoInteractions(folderAccessValidator);
        verifyNoInteractions(objectStorageService);
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
