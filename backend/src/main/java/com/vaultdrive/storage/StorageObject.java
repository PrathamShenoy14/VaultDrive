package com.vaultdrive.storage;

import java.io.InputStream;

public record StorageObject(
        InputStream inputStream
) {
}
