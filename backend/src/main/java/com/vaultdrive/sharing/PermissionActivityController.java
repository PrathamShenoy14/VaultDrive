package com.vaultdrive.sharing;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PermissionActivityController {
    private final PermissionActivityService activity;

    public PermissionActivityController(PermissionActivityService activity) { this.activity = activity; }

    @GetMapping("/api/v1/permission-activity")
    public ResponseEntity<PermissionActivityService.ActivityPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) PermissionActivityService.ResourceType resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) PermissionActivityType eventType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(activity.list(
                UUID.fromString(jwt.getSubject()), resourceType, resourceId, eventType, page, size));
    }
}
