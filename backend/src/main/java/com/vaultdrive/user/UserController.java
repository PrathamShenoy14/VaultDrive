package com.vaultdrive.user;

import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt
    ) {

        UUID userId = UUID.fromString(
                jwt.getSubject()
        );

        return ResponseEntity.ok(
                Map.of("userId", userId.toString())
        );
    }
}