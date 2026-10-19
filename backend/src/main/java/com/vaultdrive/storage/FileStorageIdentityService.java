package com.vaultdrive.storage;

import com.vaultdrive.file.StoredFile;
import com.vaultdrive.outbox.OutboxEvent;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileStorageIdentityService {
    private final EntityManager entityManager;
    private final StorageBinding binding;

    public FileStorageIdentityService(EntityManager entityManager, StorageBinding binding) {
        this.entityManager = entityManager;
        this.binding = binding;
    }

    /** New allocations support direct multipart only. Historical identities remain immutable. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveMultipart(StoredFile file) {
        StorageTarget target = binding.target(file.getStorageKey());
        entityManager.createNativeQuery("""
                INSERT INTO file_storage_objects(storage_key, file_id, owner_id,
                    storage_namespace, endpoint, region, bucket, upload_protocol)
                VALUES (:key, :file, :owner, :namespace, :endpoint, :region, :bucket, 'DIRECT_MULTIPART')
                """).setParameter("key", target.key()).setParameter("file", file.getId())
                .setParameter("owner", file.getOwnerId()).setParameter("namespace", target.namespace())
                .setParameter("endpoint", target.endpoint()).setParameter("region", target.region())
                .setParameter("bucket", target.bucket()).executeUpdate();
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
