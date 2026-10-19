package com.vaultdrive.support;

import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.file.UploadFinalizationTransactions;
import com.vaultdrive.storage.StorageBinding;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

/** Disposable bytes for historical recovery/purge tests; never used by production. */
@Import(HistoricalUploadFixtures.class)
public class HistoricalObjectFixtures {
    private final HistoricalUploadFixtures history;
    private final StorageBinding binding;
    private final S3Client s3;
    private final UploadFinalizationTransactions recovery;
    private final StoredFileRepository files;

    public HistoricalObjectFixtures(HistoricalUploadFixtures history, StorageBinding binding, S3Client s3,
            UploadFinalizationTransactions recovery, StoredFileRepository files) {
        this.history = history;
        this.binding = binding;
        this.s3 = s3;
        this.recovery = recovery;
        this.files = files;
    }

    public void write(StoredFile file, byte[] bytes) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Fixture storage I/O requires no database transaction");
        }
        var target = binding.target(file.getStorageKey());
        s3.putObject(PutObjectRequest.builder().bucket(target.bucket()).key(target.key())
                .contentType(file.getContentType()).build(), RequestBody.fromBytes(bytes));
    }

    public StoredFile completed(UUID owner, UUID folder, String name, String type, byte[] bytes) {
        UUID id = UUID.randomUUID();
        var file = history.reservation(new StoredFile(id, owner, folder, name,
                "users/" + owner + "/files/" + id, type, bytes.length));
        history.admission(file.getStorageKey());
        write(file, bytes);
        history.receipt(file);
        if (recovery.recover(id) != UploadFinalizationTransactions.Outcome.RECOVERED) {
            throw new IllegalStateException("Historical fixture recovery failed");
        }
        return files.findById(id).orElseThrow();
    }
}
