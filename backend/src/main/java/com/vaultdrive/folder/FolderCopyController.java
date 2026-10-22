package com.vaultdrive.folder;

import com.vaultdrive.folder.FolderCopyTransactions.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class FolderCopyController {
    private final FolderCopyTransactions tx;
    private final FolderCopyService copies;
    public FolderCopyController(FolderCopyTransactions tx, FolderCopyService copies) { this.tx=tx; this.copies=copies; }
    @PostMapping("/folders/{sourceId}/copy")
    public ResponseEntity<Status> copy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sourceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Request request) {
        return result(tx.admit(actor(jwt),sourceId,key,request),false);
    }
    @GetMapping("/folder-copy-operations/{id}")
    public ResponseEntity<Status> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return result(tx.get(actor(jwt),id),true);
    }
    @PostMapping("/folder-copy-operations/{id}/execute")
    public ResponseEntity<Status> execute(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return result(copies.execute(actor(jwt),id),false);
    }
    @PostMapping("/folder-copy-operations/{id}/recover")
    public ResponseEntity<Status> recover(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return result(copies.recover(actor(jwt),id),false);
    }
    @PostMapping("/folder-copy-operations/{id}/cancel")
    public ResponseEntity<Status> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return result(tx.cancel(actor(jwt),id),false);
    }
    @PostMapping("/folder-copy-operations/{id}/discard")
    public ResponseEntity<Status> discard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return result(tx.discard(actor(jwt),id),false);
    }
    private static ResponseEntity<Status> result(Status s, boolean get) {
        var response=ResponseEntity.status(get || s.terminal()?200:202)
                .header("Location","/api/v1/folder-copy-operations/"+s.id()).header("Cache-Control","no-store");
        if (!s.terminal()) response.header("Retry-After","2");
        return response.body(s);
    }
    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
