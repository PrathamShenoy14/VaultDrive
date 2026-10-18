package com.vaultdrive.file;

import com.vaultdrive.common.exception.ApiError;
import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.storage.MultipartStorageException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/upload-sessions")
public class MultipartSessionController {
    private final MultipartSessionService sessions;
    private final MultipartTransferService transfers;
    private final MultipartCompletionService completions;

    public MultipartSessionController(MultipartSessionService sessions, MultipartTransferService transfers, MultipartCompletionService completions) {
        this.sessions = sessions;
        this.transfers = transfers;
        this.completions = completions;
    }

    @PostMapping
    public ResponseEntity<Session> reserve(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @RequestBody Request request) {
        Session session = sessions.reserve(owner(jwt), key, request);
        return ResponseEntity.created(URI.create("/api/v1/upload-sessions/" + session.id())).body(session);
    }

    @GetMapping("/{id}")
    public Session get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return sessions.get(owner(jwt), id);
    }

    @PostMapping("/{id}/initiate")
    public ResponseEntity<Session> initiate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Session result = transfers.initiate(owner(jwt), id);
        return ResponseEntity.status(result.state().equals("OPEN") ? 200 : 202)
                .header("Location", "/api/v1/upload-sessions/" + id).body(result);
    }

    public record PartRequest(List<PartExpectation> parts) { }

    @PostMapping("/{id}/part-urls")
    public ResponseEntity<List<MultipartTransferService.PartUrl>> parts(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @RequestBody PartRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(transfers.partUrls(owner(jwt), id, request.parts()));
    }

    @GetMapping("/{id}/parts")
    public com.vaultdrive.storage.MultipartStorage.Page list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int marker, @RequestParam(defaultValue = "100") int limit) {
        return transfers.list(owner(jwt), id, marker, limit);
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Session> complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return completionResult(completions.complete(owner(jwt), id));
    }

    @PostMapping("/{id}/recover")
    public ResponseEntity<Session> recover(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return completionResult(completions.recover(owner(jwt), id));
    }

    private static ResponseEntity<Session> completionResult(Session session) {
        return ResponseEntity.status(session.completion() != null && session.completion().ready() ? 200 : 202)
                .header("Location", "/api/v1/upload-sessions/" + session.id()).body(session);
    }

    @ExceptionHandler(MultipartConflictException.class)
    public ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(409).body(new ApiError(409, "CONFLICT", "Multipart authorization conflicts with retained state"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid() {
        return ResponseEntity.badRequest().body(new ApiError(400, "BAD_REQUEST", "Invalid multipart request"));
    }

    @ExceptionHandler(MultipartStorageException.class)
    public ResponseEntity<ApiError> unavailable() {
        return ResponseEntity.status(503).body(new ApiError(503, "SERVICE_UNAVAILABLE", "Multipart storage outcome unavailable"));
    }

    private static UUID owner(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
