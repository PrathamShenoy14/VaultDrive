package com.vaultdrive.user;

import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final ProfileService profiles;
    public UserController(ProfileService profiles) { this.profiles = profiles; }

    public record UpdateProfile(
            @NotBlank @Size(max = 100)
            @Pattern(regexp = "[^\\p{Cc}]*", message = "must not contain control characters") String displayName) {}

    @GetMapping("/me")
    public ResponseEntity<ProfileService.Profile> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt
    ) {

        UUID userId = UUID.fromString(
                jwt.getSubject()
        );

        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(profiles.get(userId));
    }

    @PatchMapping("/me")
    public ResponseEntity<ProfileService.Profile> updateCurrentUser(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfile request) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(profiles.update(UUID.fromString(jwt.getSubject()), request.displayName()));
    }
}
