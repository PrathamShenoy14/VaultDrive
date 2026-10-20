package com.vaultdrive.deletion;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deletion-operations")
public class DeletionOperationController {
    private final DeletionOperationService operations;
    public DeletionOperationController(DeletionOperationService operations) { this.operations = operations; }

    public record Accepted(UUID operationId) { }

    public static ResponseEntity<Accepted> accepted(UUID operation) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/deletion-operations/" + operation))
                .header("Cache-Control", "no-store").header("Retry-After", "2")
                .body(new Accepted(operation));
    }

    @GetMapping("/{operationId}")
    public ResponseEntity<DeletionOperationService.Status> get(@AuthenticationPrincipal Jwt jwt,
                                                              @PathVariable UUID operationId) {
        var status = operations.get(UUID.fromString(jwt.getSubject()), operationId);
        var response = ResponseEntity.ok().header("Cache-Control", "no-store");
        if (status.state() != DeletionOperationService.State.LOGICALLY_COMPLETED
                && status.state() != DeletionOperationService.State.FAILED) response.header("Retry-After", "2");
        return response.body(status);
    }
}
