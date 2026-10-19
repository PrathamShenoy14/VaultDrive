package com.vaultdrive.sharing;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class SharingNotificationController {
    private final SharingNotificationService notifications;
    public SharingNotificationController(SharingNotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    public ResponseEntity<SharingNotificationService.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) SharingNotificationService.Type eventType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.list(
                UUID.fromString(jwt.getSubject()), unread, eventType, page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<SharingNotificationService.UnreadCount> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                notifications.unreadCount(UUID.fromString(jwt.getSubject())));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        notifications.markRead(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
