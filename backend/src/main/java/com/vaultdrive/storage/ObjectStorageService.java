package com.vaultdrive.storage;

import java.io.InputStream;

public interface ObjectStorageService {

    void upload(
            String storageKey,
            InputStream inputStream,
            long contentLength,
            String contentType
    );

    void delete(String storageKey);
}