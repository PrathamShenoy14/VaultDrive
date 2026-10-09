package com.vaultdrive.folder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

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

    List<Folder> findByOwnerIdAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
            UUID ownerId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Folder> findByIdAndOwnerIdAndDeletedAtIsNotNull(
            UUID id,
            UUID ownerId
    );

    @Query(value = """
            WITH RECURSIVE ancestors AS (
                SELECT id, parent_folder_id, purge_requested_at
                  FROM folders
                 WHERE id = :folderId
                   AND owner_id = :ownerId
                UNION
                SELECT parent.id,
                       parent.parent_folder_id,
                       parent.purge_requested_at
                  FROM folders parent
                  JOIN ancestors child
                    ON parent.id = child.parent_folder_id
                   AND parent.owner_id = :ownerId
            )
            SELECT EXISTS (
                SELECT 1
                  FROM ancestors
                 WHERE purge_requested_at IS NOT NULL
            )
            """, nativeQuery = true)
    boolean hasPendingPurgeInAncestry(
            @Param("ownerId") UUID ownerId,
            @Param("folderId") UUID folderId
    );

    @Query(value = """
            WITH RECURSIVE subtree AS (
                SELECT id
                  FROM folders
                 WHERE id = :folderId
                   AND owner_id = :ownerId
                UNION
                SELECT child.id
                  FROM folders child
                  JOIN subtree parent
                    ON child.parent_folder_id = parent.id
                 WHERE child.owner_id = :ownerId
            )
            SELECT EXISTS (
                SELECT 1
                  FROM folders folder
                  JOIN subtree
                    ON subtree.id = folder.id
                 WHERE folder.purge_requested_at IS NOT NULL
            )
            """, nativeQuery = true)
    boolean hasPendingPurgeInSubtree(
            @Param("ownerId") UUID ownerId,
            @Param("folderId") UUID folderId
    );
}
