package com.vaultdrive.file;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface StoredFileRepository
        extends JpaRepository<StoredFile, UUID> {

    boolean existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusIn(
            UUID ownerId,
            UUID folderId,
            String name,
            Collection<FileStatus> statuses
    );

    boolean existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusIn(
            UUID ownerId,
            String name,
            Collection<FileStatus> statuses
    );

    Optional<StoredFile> findByIdAndOwnerIdAndDeletedAtIsNull(
            UUID id,
            UUID ownerId
    );
}