package com.vaultdrive.auth;

import com.vaultdrive.auth.dto.LoginRequest;
import com.vaultdrive.auth.dto.LoginResponse;
import com.vaultdrive.auth.dto.RegisterRequest;
import com.vaultdrive.auth.dto.RegisterResponse;

import com.vaultdrive.security.JwtService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthenticationService authenticationService;
    private final JwtService jwtService;

    public AuthController(
            RegistrationService registrationService,
            AuthenticationService authenticationService,
            JwtService jwtService
    ) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        UUID userId = registrationService.register(request);

        RegisterResponse response = new RegisterResponse(
                userId,
                "User registered successfully"
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        UUID userId = authenticationService.authenticate(request);

        String accessToken = jwtService.generateAccessToken(userId);

        LoginResponse response = new LoginResponse(
                accessToken,
                "Bearer",
                jwtService.getAccessTokenExpiresInSeconds()
        );

        return ResponseEntity.ok(response);
    }
}