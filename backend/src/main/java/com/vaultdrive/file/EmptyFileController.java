package com.vaultdrive.file;

import com.vaultdrive.file.EmptyFileTransactions.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class EmptyFileController {
    private final EmptyFileService service;
    public EmptyFileController(EmptyFileService service) { this.service=service; }
    @PostMapping("/files/empty")
    public ResponseEntity<Status> create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @RequestBody Request request) {
        Status s=service.create(UUID.fromString(jwt.getSubject()),key,request);
        return ResponseEntity.status(s.state().equals("READY") ? 200 : 202).header("Cache-Control","no-store")
                .location(URI.create("/api/v1/empty-file-creations/"+s.id())).body(s);
    }
    @GetMapping("/empty-file-creations/{id}")
    public ResponseEntity<Status> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.get(UUID.fromString(jwt.getSubject()),id));
    }
    @PostMapping("/empty-file-creations/{id}/recover")
    public ResponseEntity<Status> recover(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.recover(UUID.fromString(jwt.getSubject()),id));
    }
}
