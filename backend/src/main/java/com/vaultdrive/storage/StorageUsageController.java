package com.vaultdrive.storage;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class StorageUsageController {
    private final StorageUsageService usage;
    public StorageUsageController(StorageUsageService usage) { this.usage = usage; }

    @GetMapping("/api/v1/users/me/storage")
    public ResponseEntity<StorageUsageService.Usage> get(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(usage.get(UUID.fromString(jwt.getSubject())));
    }
}
