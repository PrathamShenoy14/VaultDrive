package com.vaultdrive.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "files")
public class StoredFile {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "storage_key", nullable = false, length = 1024, unique = true)
    private String storageKey;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private FileStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "purge_requested_at")
    private Instant purgeRequestedAt;

    protected StoredFile() {
    }

    public StoredFile(
            UUID id,
            UUID ownerId,
            UUID folderId,
            String name,
            String storageKey,
            String contentType,
            long sizeBytes
    ) {
        Instant now = Instant.now();

        this.id = id;
        this.ownerId = ownerId;
        this.folderId = folderId;
        this.name = name;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.status = FileStatus.UPLOADING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public String getName() {
        return name;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public FileStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getPurgeRequestedAt() {
        return purgeRequestedAt;
    }

    public void markReady() {
        this.status = FileStatus.READY;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = FileStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    public void move(UUID folderId) {
        this.folderId = folderId;
        this.updatedAt = Instant.now();
    }

    public void softDelete() {
        Instant now = Instant.now();
    
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public void restore(
            UUID destinationFolderId,
            String restoredName
    ) {
        Instant now = Instant.now();
    
        this.folderId = destinationFolderId;
        this.name = restoredName;
        this.deletedAt = null;
        this.updatedAt = now;
    }

    public void requestPermanentDeletion() {
        this.purgeRequestedAt = Instant.now();
        this.updatedAt = this.purgeRequestedAt;
    }
}
