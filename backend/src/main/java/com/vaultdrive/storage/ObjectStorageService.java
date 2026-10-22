package com.vaultdrive.storage;

import java.time.Instant;
import java.util.UUID;

public interface ObjectStorageService {

    CopyStorage.ObjectInfo inspectCopyObject(StorageTarget target);

    /** Exactly one SDK attempt; caller must hold a newly committed durable dispatch permit. */
    void copyObject(CopyStorage.Command command);

    StorageObject download(String storageKey);

    /** Purge uses the durable target; adapters must reject incompatible storage bindings. */
    void delete(StorageTarget target);

    String initiateMultipart(StorageTarget target, UUID sessionId, UUID initiationId, String contentType);

    MultipartStorage.SignedPart signPart(StorageTarget target, String uploadId, int partNumber,
            long length, String sha256, Instant deadline);

    MultipartStorage.Page listParts(StorageTarget target, String uploadId, int marker, int limit);

    MultipartStorage.CompletionAck completeMultipart(StorageTarget target, String uploadId,
            java.util.List<MultipartStorage.ManifestPart> manifest, String compositeSha256);

    MultipartStorage.CompletedObject inspectMultipartObject(StorageTarget target);

    /** Backend-only, one dispatch. Acknowledgement is not writer-termination or removal proof. */
    void abortMultipart(StorageTarget target, String uploadId);
}
