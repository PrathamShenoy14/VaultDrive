package com.vaultdrive.sharing;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SharingController {
    private final GrantService grants;
    private final ResourceReadService reads;

    public SharingController(GrantService grants, ResourceReadService reads) {
        this.grants = grants;
        this.reads = reads;
    }

    public record CreateGrant(String email, ResourceRole role) {}
    public record UpdateGrant(ResourceRole role) {}

    @PostMapping("/{type:files|folders}/{id}/grants")
    public ResponseEntity<GrantService.Grant> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @PathVariable UUID id, @RequestBody CreateGrant request) {
        GrantService.Grant grant = grants.create(actor(jwt), folder(type), id, request.email(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Long.toString(grant.version())).body(grant);
    }

    @GetMapping("/{type:files|folders}/{id}/grants")
    public GrantService.GrantList list(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @PathVariable UUID id) {
        return grants.list(actor(jwt), folder(type), id);
    }

    @PatchMapping("/{type:files|folders}/{id}/grants/{grantId}")
    public ResponseEntity<GrantService.Grant> update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @PathVariable UUID id, @PathVariable UUID grantId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody UpdateGrant request) {
        GrantService.Grant grant = grants.update(actor(jwt), folder(type), id, grantId, request.role(), version(ifMatch));
        return ResponseEntity.ok().eTag(Long.toString(grant.version())).body(grant);
    }

    @DeleteMapping("/{type:files|folders}/{id}/grants/{grantId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @PathVariable UUID id, @PathVariable UUID grantId) {
        grants.revoke(actor(jwt), folder(type), id, grantId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/shared-with-me")
    public ResourceReadService.SharedPage shared(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return reads.sharedWithMe(actor(jwt), page, size);
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static boolean folder(String type) { return type.equals("folders"); }

    private static Long version(String value) {
        if (value == null) return null;
        if (!value.matches("\"[0-9]+\"")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected quoted grant version");
        try {
            return Long.parseLong(value.substring(1, value.length() - 1));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid grant version");
        }
    }
}
