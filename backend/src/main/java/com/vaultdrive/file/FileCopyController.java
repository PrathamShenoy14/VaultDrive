package com.vaultdrive.file;

import com.vaultdrive.file.FileCopyTransactions.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class FileCopyController {
    private final FileCopyTransactions tx;
    private final FileCopyService copies;
    public FileCopyController(FileCopyTransactions tx, FileCopyService copies) { this.tx=tx; this.copies=copies; }

    @PostMapping("/files/{sourceId}/copy")
    public ResponseEntity<Status> copy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sourceId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Request request) {
        return result(copies.copy(actor(jwt),sourceId,key,request));
    }
    @GetMapping("/copy-operations/{id}")
    public ResponseEntity<Status> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Status s = tx.get(actor(jwt),id);
        var response = ResponseEntity.ok().header("Cache-Control","no-store");
        if (!s.terminal()) { response.header("Retry-After","2"); }
        return response.body(s);
    }
    @PostMapping("/copy-operations/{id}/execute")
    public ResponseEntity<Status> execute(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return result(copies.execute(actor(jwt),id));
    }
    @PostMapping("/copy-operations/{id}/recover")
    public ResponseEntity<Status> recover(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return result(copies.recover(actor(jwt),id));
    }
    @PostMapping("/copy-operations/{id}/cancel")
    public ResponseEntity<Status> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return result(tx.cancel(actor(jwt),id));
    }
    private static ResponseEntity<Status> result(Status s) {
        var response = ResponseEntity.status(s.terminal()?200:202).header("Location","/api/v1/copy-operations/"+s.id())
                .header("Cache-Control","no-store");
        if (!s.terminal()) { response.header("Retry-After","2"); }
        return response.body(s);
    }
    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
