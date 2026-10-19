package com.vaultdrive.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {

        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);

        SecretKey secretKey =
                new SecretKeySpec(keyBytes, "HmacSHA256");

        JwtConfig jwtConfig = new JwtConfig();

        JwtEncoder jwtEncoder =
                jwtConfig.jwtEncoder(secretKey);

        jwtDecoder = jwtConfig.jwtDecoder(
                secretKey,
                "https://vaultdrive.local", allowedState()
        );

        jwtService = new JwtService(
                jwtEncoder,
                "https://vaultdrive.local",
                30
        );
    }

    @Test
    void shouldGenerateValidAccessToken() {

        UUID userId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(userId);

        Jwt decodedToken = jwtDecoder.decode(token);

        assertThat(decodedToken.getSubject())
                .isEqualTo(userId.toString());
        assertThat(decodedToken.getId()).isNotBlank();
        assertThat(jwtDecoder.decode(jwtService.generateAccessToken(userId)).getId()).isNotEqualTo(decodedToken.getId());

        assertThat(decodedToken.getIssuer().toString())
                .isEqualTo("https://vaultdrive.local");

        assertThat(decodedToken.getHeaders().get("alg"))
                .isEqualTo("HS256");

        Instant issuedAt = decodedToken.getIssuedAt();
        Instant expiresAt = decodedToken.getExpiresAt();

        assertThat(issuedAt).isNotNull();
        assertThat(expiresAt).isNotNull();

        assertThat(Duration.between(issuedAt, expiresAt))
                .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void shouldRejectTokenSignedWithDifferentSecret() {

        UUID userId = UUID.randomUUID();

        byte[] otherKeyBytes = new byte[32];
        new SecureRandom().nextBytes(otherKeyBytes);

        SecretKey otherSecretKey =
                new SecretKeySpec(otherKeyBytes, "HmacSHA256");

        JwtConfig jwtConfig = new JwtConfig();

        JwtEncoder otherEncoder =
                jwtConfig.jwtEncoder(otherSecretKey);

        JwtService otherJwtService = new JwtService(
                otherEncoder,
                "https://vaultdrive.local",
                15
        );

        String token =
                otherJwtService.generateAccessToken(userId);

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectExpiredToken() {

        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);

        SecretKey secretKey =
                new SecretKeySpec(keyBytes, "HmacSHA256");

        JwtConfig jwtConfig = new JwtConfig();

        JwtEncoder encoder =
                jwtConfig.jwtEncoder(secretKey);

        JwtDecoder decoder =
                jwtConfig.jwtDecoder(secretKey, "https://vaultdrive.local", allowedState());

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://vaultdrive.local")
                .subject(UUID.randomUUID().toString())
                .issuedAt(now.minusSeconds(3600))
                .expiresAt(now.minusSeconds(1800))
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        String expiredToken = encoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(expiredToken))
                .isInstanceOf(JwtException.class);
    }

    private static AccessTokenState allowedState() {
        AccessTokenState state = mock(AccessTokenState.class);
        when(state.validate(any())).thenReturn(OAuth2TokenValidatorResult.success());
        return state;
    }
}
