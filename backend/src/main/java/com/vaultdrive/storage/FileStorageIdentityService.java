package com.vaultdrive.storage;

import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.outbox.OutboxEvent;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class FileStorageIdentityService {
    private final EntityManager entityManager;
    private final StorageBinding binding;
    private final HierarchyCoordinator hierarchy;
    private final StoredFileRepository files;
    private final FolderAccessValidator folders;

    public FileStorageIdentityService(EntityManager entityManager, StorageBinding binding,
                                     HierarchyCoordinator hierarchy, StoredFileRepository files,
                                     FolderAccessValidator folders) {
        this.entityManager = entityManager;
        this.binding = binding;
        this.hierarchy = hierarchy;
        this.files = files;
        this.folders = folders;
    }

    /** Called after the new file flush, under the existing owner hierarchy lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(StoredFile file) {
        reserve(file, "SERVER_PUT");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveMultipart(StoredFile file) {
        reserve(file, "DIRECT_MULTIPART");
    }

    private void reserve(StoredFile file, String protocol) {
        StorageTarget target = binding.target(file.getStorageKey());
        entityManager.createNativeQuery("""
                INSERT INTO file_storage_objects(storage_key, file_id, owner_id,
                    storage_namespace, endpoint, region, bucket, upload_protocol)
                VALUES (:key, :file, :owner, :namespace, :endpoint, :region, :bucket, :protocol)
                """).setParameter("key", target.key()).setParameter("file", file.getId())
                .setParameter("owner", file.getOwnerId()).setParameter("namespace", target.namespace())
                .setParameter("endpoint", target.endpoint()).setParameter("region", target.region())
                .setParameter("bucket", target.bucket()).setParameter("protocol", protocol).executeUpdate();
    }

    /** One application write invocation per allocation, even after uncertain failure or file removal. */
    @Transactional
    public StorageTarget beginWrite(String key) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT file_id, owner_id FROM file_storage_objects WHERE storage_key = :key
                """).setParameter("key", key).getResultList();
        if (rows.isEmpty()) { throw new IllegalStateException("Storage key is not allocated"); }
        Object[] identity = (Object[]) rows.getFirst(); // Immutable identity, not lifecycle state.
        UUID owner = (UUID) identity[1];
        // Lock order: shared owner hierarchy, fresh eligible file row, allocation row.
        hierarchy.acquireShared(owner);
        StoredFile file = files.findUploadingForStorageWrite((UUID) identity[0], owner)
                .orElseThrow(() -> new IllegalStateException("Storage write is not eligible"));
        if (file.getFolderId() != null) { folders.requireAccessibleFolder(owner, file.getFolderId()); }
        StorageTarget target = binding.target(key);
        int changed = entityManager.createNativeQuery("""
                UPDATE file_storage_objects SET write_started = TRUE,
                    admitted_file_version = :version, admitted_folder_id = :folder
                WHERE storage_key = :key AND upload_protocol = 'SERVER_PUT'
                    AND NOT write_started AND write_precluded_at IS NULL
                    AND file_id = :file AND owner_id = :owner
                    AND storage_namespace = :namespace AND endpoint = :endpoint
                    AND region = :region AND bucket = :bucket
                """).setParameter("key", key).setParameter("file", file.getId()).setParameter("owner", owner)
                .setParameter("version", file.getVersion()).setParameter("folder", file.getFolderId())
                .setParameter("namespace", target.namespace())
                .setParameter("endpoint", target.endpoint()).setParameter("region", target.region())
                .setParameter("bucket", target.bucket()).executeUpdate();
        if (changed != 1) { throw new IllegalStateException("Storage key cannot be written again"); }
        return target;
    }

    /** Legacy/unknown bindings deliberately produce no executable target. v1 event stays unchanged. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPurgeTarget(StoredFile file, OutboxEvent event) {
        entityManager.createNativeQuery("""
                INSERT INTO file_purge_targets(event_id, file_id, owner_id, file_version, requested_at,
                    storage_namespace, endpoint, region, bucket, storage_key)
                SELECT :event, file_id, owner_id, :version, :requested,
                    storage_namespace, endpoint, region, bucket, storage_key
                FROM file_storage_objects
                WHERE file_id = :file AND owner_id = :owner AND storage_key = :key
                    AND write_started AND storage_namespace IS NOT NULL
                """).setParameter("event", event.getId()).setParameter("file", file.getId())
                .setParameter("owner", file.getOwnerId()).setParameter("key", file.getStorageKey())
                .setParameter("version", file.getVersion()).setParameter("requested", file.getPurgeRequestedAt())
                .executeUpdate();
    }

}
