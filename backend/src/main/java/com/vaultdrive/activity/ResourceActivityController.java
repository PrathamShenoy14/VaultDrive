package com.vaultdrive.activity;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
public class ResourceActivityController {
    private final ResourceActivityService activity;
    public ResourceActivityController(ResourceActivityService activity) { this.activity = activity; }

    @GetMapping("/api/v1/activity")
    public ResponseEntity<ResourceActivityService.ActivityPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) ResourceActivityService.ResourceType resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) ResourceActivityService.EventType eventType,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(activity.list(
                UUID.fromString(jwt.getSubject()), resourceType, resourceId, eventType, actorId, from, to, page, size));
    }
}
