package com.vaultdrive.folder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    Optional<Folder> findByIdAndOwnerIdAndDeletedAtIsNull(
            UUID id,
            UUID ownerId
    );

    List<Folder> findByOwnerIdAndParentFolderIdAndDeletedAtIsNull(
            UUID ownerId,
            UUID parentFolderId
    );
    
    List<Folder> findByOwnerIdAndParentFolderIdIsNullAndDeletedAtIsNull(
            UUID ownerId
    );
    
    boolean existsByOwnerIdAndParentFolderIdAndNameAndDeletedAtIsNull(
            UUID ownerId,
            UUID parentFolderId,
            String name
    );

    boolean existsByOwnerIdAndParentFolderIdIsNullAndNameAndDeletedAtIsNull(
            UUID ownerId,
            String name
    );

    List<Folder> findByOwnerIdAndDeletedAtIsNotNull(UUID ownerId);

    Optional<Folder> findByIdAndOwnerIdAndDeletedAtIsNotNull(
            UUID id,
            UUID ownerId
    );
}