package com.vaultdrive.support;

import com.vaultdrive.file.FileStatus;
import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.storage.StorageBinding;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synthetic pre-consolidation PostgreSQL history. Import explicitly in tests;
 * this is neither application admission nor an executable upload protocol.
 * Database triggers still validate every fixture transition.
 */
public class HistoricalUploadFixtures {
    private final EntityManager em;
    private final StoredFileRepository files;
    private final StorageBinding binding;

    public HistoricalUploadFixtures(EntityManager em, StoredFileRepository files, StorageBinding binding) {
        this.em = em;
        this.files = files;
        this.binding = binding;
    }

    @Transactional
    public StoredFile reservation(StoredFile file) {
        StoredFile saved = files.saveAndFlush(file);
        allocation(saved);
        return saved;
    }

    @Transactional
    public void allocation(StoredFile file) {
        var target = binding.target(file.getStorageKey());
        em.createNativeQuery("""
                INSERT INTO file_storage_objects(storage_key, file_id, owner_id,
                    storage_namespace, endpoint, region, bucket, upload_protocol)
                VALUES (:key, :file, :owner, :namespace, :endpoint, :region, :bucket, 'SERVER_PUT')
                """).setParameter("key", target.key()).setParameter("file", file.getId())
                .setParameter("owner", file.getOwnerId()).setParameter("namespace", target.namespace())
                .setParameter("endpoint", target.endpoint()).setParameter("region", target.region())
                .setParameter("bucket", target.bucket()).executeUpdate();
    }

    @Transactional
    public void admission(String key) {
        int changed = em.createNativeQuery("""
                UPDATE file_storage_objects a SET write_started = true,
                    admitted_file_version = f.version, admitted_folder_id = f.folder_id
                FROM files f WHERE a.storage_key = :key AND f.id = a.file_id AND NOT a.write_started
                    AND a.write_precluded_at IS NULL AND a.upload_protocol = 'SERVER_PUT'
                """).setParameter("key", key).executeUpdate();
        if (changed != 1) throw new IllegalStateException("Invalid historical admission fixture");
    }

    @Transactional
    public void receipt(StoredFile file) {
        em.createNativeQuery("UPDATE file_storage_objects SET put_succeeded_at = clock_timestamp() "
                + "WHERE file_id = :id AND put_succeeded_at IS NULL")
                .setParameter("id", file.getId()).executeUpdate();
    }

    @Transactional
    public void status(StoredFile file, FileStatus status) {
        if (files.transitionUploadStatus(file.getId(), file.getOwnerId(), FileStatus.UPLOADING,
                file.getVersion(), status, java.time.Instant.now()) != 1) {
            throw new IllegalStateException("Invalid historical status fixture");
        }
    }
}
