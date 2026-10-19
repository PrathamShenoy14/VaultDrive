package com.vaultdrive.user;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;

@Service
public class ProfileService {
    private final UserRepository users;
    public ProfileService(UserRepository users) { this.users = users; }

    public record Profile(UUID userId, String email, String displayName, Instant createdAt, Instant updatedAt) {
        static Profile from(User user) {
            return new Profile(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt(), user.getUpdatedAt());
        }
    }

    @Transactional(readOnly = true)
    public Profile get(UUID userId) {
        return Profile.from(users.findById(userId).orElseThrow(ProfileService::unauthorized));
    }

    @Transactional
    public Profile update(UUID userId, String displayName) {
        User user = users.findByIdForUpdate(userId).orElseThrow(ProfileService::unauthorized);
        user.updateDisplayName(displayName.trim());
        return Profile.from(user);
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication");
    }
}
