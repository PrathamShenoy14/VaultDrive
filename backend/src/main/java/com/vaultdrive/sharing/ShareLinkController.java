package com.vaultdrive.sharing;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files/{fileId}/share-links")
public class ShareLinkController {
    private final ShareLinkService links;
    public ShareLinkController(ShareLinkService links) { this.links = links; }
    public record CreateLink(Instant expiresAt) {}

    @PostMapping
    public ResponseEntity<ShareLinkService.CreatedLink> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID fileId, @RequestBody CreateLink request) {
        return ResponseEntity.status(201).body(links.create(UUID.fromString(jwt.getSubject()), fileId, request.expiresAt()));
    }

    @GetMapping
    public List<ShareLinkService.Link> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID fileId) {
        return links.list(UUID.fromString(jwt.getSubject()), fileId);
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID fileId, @PathVariable UUID linkId) {
        links.revoke(UUID.fromString(jwt.getSubject()), fileId, linkId);
        return ResponseEntity.noContent().build();
    }
}
