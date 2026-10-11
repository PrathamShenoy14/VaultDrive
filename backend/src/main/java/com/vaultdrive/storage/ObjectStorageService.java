package com.vaultdrive.storage;

import java.io.InputStream;

public interface ObjectStorageService {

    void upload(
            String storageKey,
            InputStream inputStream,
            long contentLength,
            String contentType
    );

    StorageObject download(String storageKey);

    void delete(String storageKey);

    /** Purge uses the durable target; adapters must reject incompatible storage bindings. */
    void delete(StorageTarget target);
}
