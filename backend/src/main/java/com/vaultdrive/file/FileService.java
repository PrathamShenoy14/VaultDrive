package com.vaultdrive.file;

import com.vaultdrive.file.dto.UploadFileResponse;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.file.exception.FileUploadException;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageKeyGenerator;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Service
public class FileService {

    private final FolderAccessValidator folderAccessValidator;
    private final FileNameValidator fileNameValidator;
    private final FileMetadataService fileMetadataService;
    private final ObjectStorageService objectStorageService;
    private final StorageKeyGenerator storageKeyGenerator;

    public FileService(
            FolderAccessValidator folderAccessValidator,
            FileNameValidator fileNameValidator,
            FileMetadataService fileMetadataService,
            ObjectStorageService objectStorageService,
            StorageKeyGenerator storageKeyGenerator
    ) {
        this.folderAccessValidator = folderAccessValidator;
        this.fileNameValidator = fileNameValidator;
        this.fileMetadataService = fileMetadataService;
        this.objectStorageService = objectStorageService;
        this.storageKeyGenerator = storageKeyGenerator;
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
                storageKeyGenerator.generateFileKey(ownerId, fileId);

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

        try (InputStream inputStream = multipartFile.getInputStream()) {

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

    private void markFailedSafely(StoredFile storedFile) {
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
}
