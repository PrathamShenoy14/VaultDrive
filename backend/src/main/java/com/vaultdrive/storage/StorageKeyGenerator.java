package com.vaultdrive.storage;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StorageKeyGenerator {

    public String generateFileKey(UUID ownerId, UUID fileId) {
        return "users/" + ownerId + "/files/" + fileId;
    }
}
