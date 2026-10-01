package com.vaultdrive.file;

import com.vaultdrive.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import com.vaultdrive.file.exception.DuplicateFileNameException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Set;

@Service
public class FileMetadataService {

    private final StoredFileRepository storedFileRepository;
    private final UserRepository userRepository;

    public FileMetadataService(
            StoredFileRepository storedFileRepository,
            UserRepository userRepository
    ) {
        this.storedFileRepository = storedFileRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public StoredFile createUploading(StoredFile file) {

        lockFileNamespace(file.getOwnerId());

        boolean duplicate;

        if (file.getFolderId() == null) {
            duplicate =
                    storedFileRepository
                            .existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
                                    file.getOwnerId(),
                                    file.getName(),
                                    NAME_RESERVING_STATUSES
                            );
        } else {
            duplicate =
                    storedFileRepository
                            .existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
                                    file.getOwnerId(),
                                    file.getFolderId(),
                                    file.getName(),
                                    NAME_RESERVING_STATUSES
                            );
        }

        if (duplicate) {
            
            throw new DuplicateFileNameException(
                    "A file with this name already exists"
            );
        }

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

    private static final Set<FileStatus> NAME_RESERVING_STATUSES =
            Set.of(
                    FileStatus.UPLOADING,
                    FileStatus.READY
            );
    
    private void lockFileNamespace(UUID ownerId) {
        userRepository.findByIdForUpdate(ownerId)
                .orElseThrow(
                        () -> new AccessDeniedException("User not found")
                );
    }
}
