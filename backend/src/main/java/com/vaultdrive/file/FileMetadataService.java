package com.vaultdrive.file;

import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.file.exception.UploadFinalizationRejectedException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.user.UserRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.folder.exception.FolderNotFoundException;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    private final HierarchyCoordinator hierarchyCoordinator;

    public FileMetadataService(
            StoredFileRepository storedFileRepository,
            UserRepository userRepository,
            FolderAccessValidator folderAccessValidator,
            HierarchyCoordinator hierarchyCoordinator
    ) {
        this.storedFileRepository = storedFileRepository;
        this.userRepository = userRepository;
        this.folderAccessValidator = folderAccessValidator;
        this.hierarchyCoordinator = hierarchyCoordinator;
    }

    @Transactional
    public StoredFile createUploading(StoredFile file) {
        hierarchyCoordinator.acquireShared(file.getOwnerId());

        requireOwner(file.getOwnerId());
        requireAccessibleDestination(
                file.getOwnerId(),
                file.getFolderId()
        );

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

        folderAccessValidator.requireNoPendingPurgeInAncestry(
                ownerId,
                file.getFolderId()
        );

        UUID destinationFolderId =
                resolveRestoreDestination(
                        ownerId,
                        file.getFolderId()
                );

        hierarchyCoordinator.acquireNamespaceExclusive(
                ownerId,
                destinationFolderId
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

        folderAccessValidator.requireNoPendingPurgeInAncestry(
                ownerId,
                file.getFolderId()
        );

        file.requestPermanentDeletion();
    
        return storedFileRepository.saveAndFlush(file);
    }

    @Transactional(
            noRollbackFor = UploadFinalizationRejectedException.class
    )
    public void markReady(StoredFile file) {
        hierarchyCoordinator.acquireShared(file.getOwnerId());

        StoredFile current = requireUploadingVersion(file);

        try {
            requireAccessibleDestination(
                    current.getOwnerId(),
                    current.getFolderId()
            );
        } catch (FolderNotFoundException exception) {
            transitionUploadStatus(
                    current,
                    FileStatus.FAILED
            );

            throw new UploadFinalizationRejectedException(
                    "Upload destination is no longer accessible",
                    exception
            );
        }

        transitionUploadStatus(current, FileStatus.READY);
    }

    @Transactional
    public void markFailed(StoredFile file) {
        hierarchyCoordinator.acquireShared(file.getOwnerId());

        StoredFile current = requireUploadingVersion(file);
        transitionUploadStatus(current, FileStatus.FAILED);
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
        hierarchyCoordinator.acquireShared(ownerId);
        requireOwner(ownerId);
    }

    private void requireOwner(UUID ownerId) {
        if (!userRepository.existsById(ownerId)) {
            throw new AccessDeniedException("User not found");
        }
    }

    private void requireAccessibleDestination(
            UUID ownerId,
            UUID folderId
    ) {
        if (folderId != null) {
            folderAccessValidator.requireAccessibleFolder(
                    ownerId,
                    folderId
            );
        }
    }

    private StoredFile requireUploadingVersion(StoredFile expected) {
        StoredFile current = storedFileRepository
                .findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
                        expected.getId(),
                        expected.getOwnerId(),
                        FileStatus.UPLOADING
                )
                .orElseThrow(this::uploadTransitionConflict);

        if (current.getVersion() != expected.getVersion()) {
            throw uploadTransitionConflict();
        }

        return current;
    }

    private void transitionUploadStatus(
            StoredFile current,
            FileStatus newStatus
    ) {
        int updated = storedFileRepository.transitionUploadStatus(
                current.getId(),
                current.getOwnerId(),
                FileStatus.UPLOADING,
                current.getVersion(),
                newStatus,
                Instant.now()
        );

        if (updated != 1) {
            throw uploadTransitionConflict();
        }
    }

    private OptimisticLockingFailureException uploadTransitionConflict() {
        return new OptimisticLockingFailureException(
                "Upload metadata is no longer eligible for transition"
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
