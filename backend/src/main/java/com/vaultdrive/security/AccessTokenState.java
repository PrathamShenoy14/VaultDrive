package com.vaultdrive.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.UUID;

@Component
public class AccessTokenState implements OAuth2TokenValidator<Jwt> {
    private final JdbcTemplate jdbc;
    public AccessTokenState(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            UUID jti = UUID.fromString(token.getId());
            UUID user = UUID.fromString(token.getSubject());
            if (token.getExpiresAt() == null) return denied();
            String sid = token.getClaimAsString("sid");
            boolean allowed;
            if (sid == null) {
                // Existing internally issued bearer tokens retain their authorization behavior.
                allowed = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT NOT EXISTS (SELECT 1 FROM auth_access_revocations WHERE jti = ?)", Boolean.class, jti));
            } else {
                allowed = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT NOT EXISTS (SELECT 1 FROM auth_access_revocations WHERE jti = ?)
                        AND EXISTS (SELECT 1 FROM auth_sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL)
                        """, Boolean.class, jti, UUID.fromString(sid), user));
            }
            return allowed ? OAuth2TokenValidatorResult.success() : denied();
        } catch (RuntimeException ex) {
            // Database failures and malformed claims must never bypass revocation checks.
            return denied();
        }
    }

    /** Explicit operational entry point; one bounded batch, safe across instances. */
    @Transactional(timeout = 5)
    public int cleanupExpired(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Cleanup batch must be 1..1000");
        return jdbc.update("""
                WITH expired AS (
                    SELECT jti FROM auth_access_revocations WHERE expires_at <= statement_timestamp()
                    ORDER BY expires_at, jti LIMIT ? FOR UPDATE SKIP LOCKED
                ) DELETE FROM auth_access_revocations r USING expired e WHERE r.jti = e.jti
                """, limit);
    }

    private static OAuth2TokenValidatorResult denied() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token", null));
    }
}
