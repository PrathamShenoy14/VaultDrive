package com.vaultdrive.file;

import com.vaultdrive.file.dto.FilePageResponse;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.file.dto.UploadFileResponse;
import com.vaultdrive.file.dto.FileDownload;
import com.vaultdrive.file.exception.FileUploadException;
import com.vaultdrive.file.exception.InvalidFilePaginationException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageKeyGenerator;
import com.vaultdrive.storage.StorageObject;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
public class FileService {

    private static final int MAX_PAGE_SIZE = 100;

    private final FolderAccessValidator folderAccessValidator;
    private final FileNameValidator fileNameValidator;
    private final FileMetadataService fileMetadataService;
    private final ObjectStorageService objectStorageService;
    private final StorageKeyGenerator storageKeyGenerator;
    private final StoredFileRepository storedFileRepository;

    public FileService(
            FolderAccessValidator folderAccessValidator,
            FileNameValidator fileNameValidator,
            FileMetadataService fileMetadataService,
            ObjectStorageService objectStorageService,
            StorageKeyGenerator storageKeyGenerator,
            StoredFileRepository storedFileRepository
    ) {
        this.folderAccessValidator = folderAccessValidator;
        this.fileNameValidator = fileNameValidator;
        this.fileMetadataService = fileMetadataService;
        this.objectStorageService = objectStorageService;
        this.storageKeyGenerator = storageKeyGenerator;
        this.storedFileRepository = storedFileRepository;
    }

    public UploadFileResponse uploadFile(
            UUID ownerId,
            UUID folderId,
            MultipartFile multipartFile
    ) {
        String name = fileNameValidator.validateAndNormalize(
                multipartFile.getOriginalFilename()
        );

        validateDestinationFolder(ownerId, folderId);

        UUID fileId = UUID.randomUUID();

        String storageKey =
                storageKeyGenerator.generateFileKey(
                        ownerId,
                        fileId
                );

        StoredFile storedFile = new StoredFile(
                fileId,
                ownerId,
                folderId,
                name,
                storageKey,
                multipartFile.getContentType(),
                multipartFile.getSize()
        );

        StoredFile savedFile =
                fileMetadataService.createUploading(storedFile);

        try (InputStream inputStream =
                     multipartFile.getInputStream()) {

            objectStorageService.upload(
                    savedFile.getStorageKey(),
                    inputStream,
                    savedFile.getSizeBytes(),
                    savedFile.getContentType()
            );

        } catch (IOException | RuntimeException exception) {

            markFailedSafely(savedFile);

            throw new FileUploadException(
                    "Failed to upload file",
                    exception
            );
        }

        try {
            fileMetadataService.markReady(savedFile);

        } catch (RuntimeException exception) {

            /*
             * The object already exists in storage, but PostgreSQL could not
             * transition the metadata to READY.
             *
             * We deliberately do not blindly delete the object here because
             * the database outcome may be uncertain. A reconciliation process
             * can handle stale UPLOADING records later.
             */
            throw new FileUploadException(
                    "File was uploaded but metadata could not be finalized",
                    exception
            );
        }

        return new UploadFileResponse(
                savedFile.getId(),
                savedFile.getName(),
                savedFile.getFolderId(),
                savedFile.getContentType(),
                savedFile.getSizeBytes()
        );
    }

    public FilePageResponse listFiles(
            UUID ownerId,
            UUID folderId,
            int page,
            int size
    ) {
        validatePagination(page, size);

        validateDestinationFolder(
                ownerId,
                folderId
        );

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by("name").ascending()
        );

        Page<StoredFile> storedFiles;

        if (folderId == null) {

            storedFiles =
                    storedFileRepository
                            .findByOwnerIdAndFolderIdIsNullAndStatusAndDeletedAtIsNull(
                                    ownerId,
                                    FileStatus.READY,
                                    pageable
                            );

        } else {

            storedFiles =
                    storedFileRepository
                            .findByOwnerIdAndFolderIdAndStatusAndDeletedAtIsNull(
                                    ownerId,
                                    folderId,
                                    FileStatus.READY,
                                    pageable
                            );
        }

        List<FileResponse> content =
                storedFiles.getContent()
                        .stream()
                        .map(this::toFileResponse)
                        .toList();

        return new FilePageResponse(
                content,
                storedFiles.getNumber(),
                storedFiles.getSize(),
                storedFiles.getTotalElements(),
                storedFiles.getTotalPages()
        );
    }

    private FileResponse toFileResponse(
            StoredFile storedFile
    ) {
        return new FileResponse(
                storedFile.getId(),
                storedFile.getName(),
                storedFile.getFolderId(),
                storedFile.getContentType(),
                storedFile.getSizeBytes(),
                storedFile.getCreatedAt(),
                storedFile.getUpdatedAt()
        );
    }

    private void validatePagination(
            int page,
            int size
    ) {
        if (page < 0) {
            throw new InvalidFilePaginationException(
                    "Page must be greater than or equal to 0"
            );
        }

        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidFilePaginationException(
                    "Size must be between 1 and "
                            + MAX_PAGE_SIZE
            );
        }
    }

    private void validateDestinationFolder(
            UUID ownerId,
            UUID folderId
    ) {
        if (folderId == null) {
            return;
        }

        folderAccessValidator.requireAccessibleFolder(
                ownerId,
                folderId
        );
    }

    private void markFailedSafely(
            StoredFile storedFile
    ) {
        try {
            fileMetadataService.markFailed(storedFile);

        } catch (RuntimeException ignored) {

            /*
             * The original storage failure is more important.
             *
             * If updating FAILED also fails, the row may remain UPLOADING.
             * A future reconciliation process can detect that state.
             */
        }
    }

    public FileDownload downloadFile(
            UUID ownerId,
            UUID fileId
    ) {
        StoredFile storedFile =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                                fileId,
                                ownerId,
                                FileStatus.READY
                        )
                        .orElseThrow(() ->
                                new FileNotFoundException(
                                        "File not found"
                                )
                        );
    
        validateDestinationFolder(
                ownerId,
                storedFile.getFolderId()
        );
    
        StorageObject storageObject =
                objectStorageService.download(
                        storedFile.getStorageKey()
                );
    
        return new FileDownload(
                storedFile.getName(),
                storedFile.getContentType(),
                storedFile.getSizeBytes(),
                storageObject.inputStream()
        );
    }
}