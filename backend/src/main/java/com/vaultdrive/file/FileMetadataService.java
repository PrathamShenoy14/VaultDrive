package com.vaultdrive.file;

import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.folder.exception.FolderNotFoundException;

import java.util.Set;
import java.util.UUID;
import java.util.Objects;

@Service
public class FileMetadataService {

    private static final Set<FileStatus> NAME_RESERVING_STATUSES =
            Set.of(
                    FileStatus.UPLOADING,
                    FileStatus.READY
            );

    private final StoredFileRepository storedFileRepository;
    private final UserRepository userRepository;
    private final FolderAccessValidator folderAccessValidator;

    public FileMetadataService(
            StoredFileRepository storedFileRepository,
            UserRepository userRepository,
            FolderAccessValidator folderAccessValidator
    ) {
        this.storedFileRepository = storedFileRepository;
        this.userRepository = userRepository;
        this.folderAccessValidator = folderAccessValidator;
    }

    @Transactional
    public StoredFile createUploading(StoredFile file) {

        lockFileNamespace(file.getOwnerId());

        boolean duplicate =
                fileNameExists(
                        file.getOwnerId(),
                        file.getFolderId(),
                        file.getName()
                );

        if (duplicate) {
            throw new DuplicateFileNameException(
                    "A file with this name already exists"
            );
        }

        return storedFileRepository.saveAndFlush(file);
    }

    @Transactional
    public StoredFile rename(
            StoredFile file,
            String newName
    ) {
        if (file.getName().equals(newName)) {
            return file;
        }

        boolean duplicate =
                fileNameExistsExcluding(
                        file.getOwnerId(),
                        file.getFolderId(),
                        newName,
                        file.getId()
                );

        if (duplicate) {
            throw new DuplicateFileNameException(
                    "A file with this name already exists"
            );
        }

        file.rename(newName);

        return storedFileRepository.saveAndFlush(file);
    }

    @Transactional
    public StoredFile move(
            StoredFile file,
            UUID destinationFolderId
    ) {
        if (Objects.equals(
                file.getFolderId(),
                destinationFolderId
        )) {
            return file;
        }
    
        boolean duplicate =
                fileNameExists(
                        file.getOwnerId(),
                        destinationFolderId,
                        file.getName()
                );
    
        if (duplicate) {
            throw new DuplicateFileNameException(
                    "A file with this name already exists"
            );
        }
    
        file.move(destinationFolderId);
    
        return storedFileRepository.saveAndFlush(file);
    }

    @Transactional
    public StoredFile softDelete(StoredFile file) {
        file.softDelete();
    
        return storedFileRepository.saveAndFlush(file);
    }

    @Transactional
    public StoredFile restore(
            UUID ownerId,
            UUID fileId
    ) {
        lockFileNamespace(ownerId);

        StoredFile file =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                                fileId,
                                ownerId,
                                FileStatus.READY
                        )
                        .orElseThrow(() ->
                                new FileNotFoundException("File not found")
                        );

        UUID destinationFolderId =
                resolveRestoreDestination(
                        ownerId,
                        file.getFolderId()
                );
    
        String restoredName =
                generateRestoredName(
                        ownerId,
                        destinationFolderId,
                        file.getName()
                );
    
        file.restore(destinationFolderId, restoredName);

        return storedFileRepository.saveAndFlush(file);
    }

    @Transactional
    public StoredFile requestPermanentDeletion(
            UUID ownerId,
            UUID fileId
    ) {
        lockFileNamespace(ownerId);

        StoredFile file =
                storedFileRepository
                        .findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
                                fileId,
                                ownerId,
                                FileStatus.READY
                        )
                        .orElseThrow(() ->
                                new FileNotFoundException("File not found")
                        );

        file.requestPermanentDeletion();
    
        return storedFileRepository.saveAndFlush(file);
    }

    @Transactional
    public void markReady(StoredFile file) {
        file.markReady();
        storedFileRepository.saveAndFlush(file);
    }

    @Transactional
    public void markFailed(StoredFile file) {
        file.markFailed();
        storedFileRepository.saveAndFlush(file);
    }

    private boolean fileNameExists(
            UUID ownerId,
            UUID folderId,
            String name
    ) {
        if (folderId == null) {
            return storedFileRepository
                    .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                            ownerId,
                            name,
                            NAME_RESERVING_STATUSES
                    );
        }

        return storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                        ownerId,
                        folderId,
                        name,
                        NAME_RESERVING_STATUSES
                );
    }

    private boolean fileNameExistsExcluding(
            UUID ownerId,
            UUID folderId,
            String name,
            UUID excludedFileId
    ) {
        if (folderId == null) {
            return storedFileRepository
                    .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
                            ownerId,
                            name,
                            NAME_RESERVING_STATUSES,
                            excludedFileId
                    );
        }

        return storedFileRepository
                .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
                        ownerId,
                        folderId,
                        name,
                        NAME_RESERVING_STATUSES,
                        excludedFileId
                );
    }

    private void lockFileNamespace(UUID ownerId) {
        userRepository.findByIdForUpdate(ownerId)
                .orElseThrow(
                        () -> new AccessDeniedException(
                                "User not found"
                        )
                );
    }

    private UUID resolveRestoreDestination(
            UUID ownerId,
            UUID originalFolderId
    ) {
        if (originalFolderId == null) {
            return null;
        }

        try {
            folderAccessValidator.requireAccessibleFolder(
                    ownerId,
                    originalFolderId
            );

            return originalFolderId;

        } catch (FolderNotFoundException exception) {
            return null;
        }
    }

    private String generateRestoredName(
            UUID ownerId,
            UUID folderId,
            String originalName
    ) {
        if (!fileNameExists(
                ownerId,
                folderId,
                originalName
        )) {
            return originalName;
        }
    
        String baseName = originalName;
        String extension = "";
    
        int lastDot = originalName.lastIndexOf('.');
    
        // ".env" is treated as extensionless.
        // "file." is also treated as extensionless.
        if (lastDot > 0 && lastDot < originalName.length() - 1) {
            baseName = originalName.substring(0, lastDot);
            extension = originalName.substring(lastDot);
        }
    
        int suffix = 1;
    
        while (true) {
            String suffixText =
                    suffix == 1
                            ? " (restored)"
                            : " (restored " + suffix + ")";
    
            // name column is VARCHAR(255)
            int maximumBaseLength =
                    255
                            - suffixText.length()
                            - extension.length();
    
            String truncatedBase =
                    baseName.substring(
                            0,
                            Math.min(
                                    baseName.length(),
                                    maximumBaseLength
                            )
                    );
    
            String candidate =
                    truncatedBase
                            + suffixText
                            + extension;
    
            if (!fileNameExists(
                    ownerId,
                    folderId,
                    candidate
            )) {
                return candidate;
            }
    
            suffix++;
        }
    }
}
