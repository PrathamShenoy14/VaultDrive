package com.vaultdrive.auth;

import com.vaultdrive.auth.dto.LoginRequest;
import com.vaultdrive.auth.dto.LoginResponse;
import com.vaultdrive.auth.dto.RegisterRequest;
import com.vaultdrive.auth.dto.RegisterResponse;

import com.vaultdrive.security.JwtService;
import com.vaultdrive.security.AuthTransportFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

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
    private final SessionService sessions;
    public static final String REFRESH_COOKIE = "__Host-vaultdrive-refresh";

    public AuthController(
            RegistrationService registrationService,
            AuthenticationService authenticationService,
            JwtService jwtService,
            SessionService sessions
    ) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
        this.jwtService = jwtService;
        this.sessions = sessions;
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

        return issued(sessions.create(userId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(HttpServletRequest request,
            @RequestHeader(AuthTransportFilter.CSRF_HEADER) String csrf) {
        return issued(sessions.refresh(refreshCookie(request), csrf));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
            @RequestHeader(AuthTransportFilter.CSRF_HEADER) String csrf) {
        sessions.logout(jwt, refreshCookie(request), csrf);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0)).build();
    }

    private ResponseEntity<LoginResponse> issued(SessionService.IssuedSession session) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), session.refreshSeconds()))
                .header(AuthTransportFilter.CSRF_HEADER, session.csrfToken())
                .body(new LoginResponse(session.accessToken(), "Bearer", jwtService.getAccessTokenExpiresInSeconds()));
    }

    private static String cookie(String value, long seconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value).secure(true).httpOnly(true)
                .sameSite("Strict").path("/").maxAge(seconds).build().toString();
    }

    private static String refreshCookie(HttpServletRequest request) {
        String found = null;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (REFRESH_COOKIE.equals(cookie.getName())) {
                    if (found != null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication session");
                    found = cookie.getValue();
                }
            }
        }
        return found;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<com.vaultdrive.common.exception.ApiError> sessionFailure(ResponseStatusException failure) {
        // Write the response directly: servlet error redispatch must not replace a 403/503 with a 401.
        int status = failure.getStatusCode().value();
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new com.vaultdrive.common.exception.ApiError(status,
                        HttpStatus.valueOf(status).name(), failure.getReason()));
    }
}
