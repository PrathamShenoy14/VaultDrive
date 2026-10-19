package com.vaultdrive.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final long accessTokenMinutes;

    public JwtService(
            JwtEncoder jwtEncoder,
            @Value("${vaultdrive.jwt.issuer}") String issuer,
            @Value("${vaultdrive.jwt.access-token-minutes}") long accessTokenMinutes
    ) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.accessTokenMinutes = accessTokenMinutes;

        if (accessTokenMinutes <= 0) {
            throw new IllegalArgumentException(
                    "JWT access token lifetime must be positive"
            );
        }
    }

    public String generateAccessToken(UUID userId) {
        return generateAccessToken(userId, null);
    }

    public String generateAccessToken(UUID userId, UUID sessionId) {

        Instant now = Instant.now();

        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(
                        now.plus(
                                accessTokenMinutes,
                                ChronoUnit.MINUTES
                        )
                );
        if (sessionId != null) builder.claim("sid", sessionId.toString());
        JwtClaimsSet claims = builder.build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }

    public long getAccessTokenExpiresInSeconds() {
        return accessTokenMinutes * 60;
    }
}
