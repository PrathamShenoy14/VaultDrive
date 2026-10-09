package com.vaultdrive.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface StoredFileRepository
        extends JpaRepository<StoredFile, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StoredFile file
               set file.status = :newStatus,
                   file.updatedAt = :updatedAt,
                   file.version = file.version + 1
             where file.id = :fileId
               and file.ownerId = :ownerId
               and file.status = :expectedStatus
               and file.version = :expectedVersion
               and file.deletedAt is null
               and file.purgeRequestedAt is null
            """)
    int transitionUploadStatus(
            @Param("fileId") UUID fileId,
            @Param("ownerId") UUID ownerId,
            @Param("expectedStatus") FileStatus expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("newStatus") FileStatus newStatus,
            @Param("updatedAt") Instant updatedAt
    );

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

    boolean existsByOwnerIdAndFolderIdAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
            UUID ownerId,
            UUID folderId,
            String name,
            Collection<FileStatus> statuses,
            UUID excludedFileId
    );

    boolean existsByOwnerIdAndFolderIdIsNullAndNameAndDeletedAtIsNullAndStatusInAndIdNot(
            UUID ownerId,
            String name,
            Collection<FileStatus> statuses,
            UUID excludedFileId
    );

    Optional<StoredFile> findByIdAndOwnerIdAndDeletedAtIsNull(
            UUID id,
            UUID ownerId
    );

    Page<StoredFile> findByOwnerIdAndFolderIdAndStatusAndDeletedAtIsNull(
            UUID ownerId,
            UUID folderId,
            FileStatus status,
            Pageable pageable
    );

    Page<StoredFile> findByOwnerIdAndFolderIdIsNullAndStatusAndDeletedAtIsNull(
            UUID ownerId,
            FileStatus status,
            Pageable pageable
    );

    Optional<StoredFile> findByIdAndOwnerIdAndStatusAndDeletedAtIsNull(
            UUID id,
            UUID ownerId,
            FileStatus status
    );

    Page<StoredFile> findByOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
            UUID ownerId,
            FileStatus status,
            Pageable pageable
    );

    Optional<StoredFile> findByIdAndOwnerIdAndStatusAndDeletedAtIsNotNullAndPurgeRequestedAtIsNull(
            UUID id,
            UUID ownerId,
            FileStatus status
    );
}
