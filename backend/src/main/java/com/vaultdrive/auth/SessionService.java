package com.vaultdrive.auth;

import com.vaultdrive.security.JwtService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class SessionService {
    public static final long REFRESH_SECONDS = 7L * 24 * 60 * 60;
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final TransactionTemplate transaction;

    public SessionService(JdbcTemplate jdbc, JwtService jwt, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    // This value is internal only: never serialize it or log credential material.
    public record IssuedSession(String refreshToken, String csrfToken, String accessToken, long refreshSeconds) {
        @Override public String toString() { return "IssuedSession[REDACTED]"; }
    }

    public IssuedSession create(UUID userId) {
        return inTransaction(() -> {
            UUID session = UUID.randomUUID();
            String refresh = secret(), csrf = secret();
            jdbc.update("""
                    INSERT INTO auth_sessions(id, user_id, csrf_hash, created_at, expires_at)
                    VALUES (?, ?, ?, statement_timestamp(), statement_timestamp() + ? * interval '1 second')
                    """, session, userId, hash(csrf), REFRESH_SECONDS);
            insertToken(session, refresh);
            return new IssuedSession(refresh, csrf, jwt.generateAccessToken(userId, session), REFRESH_SECONDS);
        });
    }

    public IssuedSession refresh(String refresh, String csrf) {
        if (!validSecret(refresh)) throw unauthorized();
        IssuedSession result = inTransaction(() -> {
            Session session = lockSession(refresh);
            if (session == null) return null;
            checkCsrf(session, csrf);
            if (session.revoked() || session.remainingSeconds() <= 0) return null;
            // Protected state must be read AFTER the family lock, including when lookup waited.
            if (consumed(refresh, session.id())) {
                revoke(session.id());
                return null; // Commit reuse revocation before reporting authentication failure.
            }
            int consumed = jdbc.update("""
                    UPDATE auth_refresh_tokens SET consumed_at = clock_timestamp()
                    WHERE token_hash = ? AND session_id = ? AND consumed_at IS NULL
                    """, hash(refresh), session.id());
            if (consumed != 1) throw unauthorized();
            String next = secret();
            insertToken(session.id(), next);
            return new IssuedSession(next, csrf, jwt.generateAccessToken(session.userId(), session.id()), session.remainingSeconds());
        });
        if (result == null) throw unauthorized();
        return result;
    }

    public void logout(Jwt access, String refresh, String csrf) {
        if (!validSecret(refresh)) throw unauthorized();
        inTransaction(() -> {
            Session session = lockSession(refresh);
            if (session == null) throw unauthorized();
            checkCsrf(session, csrf);
            if (!session.userId().toString().equals(access.getSubject())
                    || !session.id().toString().equals(access.getClaimAsString("sid"))) throw unauthorized();
            // A consumed token can log out its matching family; this also closes refresh/logout races.
            revoke(session.id());
            jdbc.update("""
                    INSERT INTO auth_access_revocations(jti, expires_at) VALUES (?, ?)
                    ON CONFLICT (jti) DO NOTHING
                    """, UUID.fromString(access.getId()), Timestamp.from(access.getExpiresAt().plusSeconds(60)));
            return Boolean.TRUE;
        });
    }

    private Session lockSession(String refresh) {
        var ids = jdbc.query("SELECT session_id FROM auth_refresh_tokens WHERE token_hash = ?",
                (rs, row) -> rs.getObject(1, UUID.class), hash(refresh));
        if (ids.isEmpty()) return null;
        jdbc.queryForObject("SELECT id FROM auth_sessions WHERE id = ? FOR UPDATE", UUID.class, ids.getFirst());
        var sessions = jdbc.query("""
                SELECT id, user_id, csrf_hash, revoked_at IS NOT NULL AS revoked,
                       floor(extract(epoch FROM least(expires_at, created_at + ? * interval '1 second')
                           - clock_timestamp()))::bigint AS remaining
                FROM auth_sessions WHERE id = ?
                """, (rs, row) -> new Session(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("csrf_hash"), rs.getBoolean("revoked"), rs.getLong("remaining")), REFRESH_SECONDS, ids.getFirst());
        return sessions.isEmpty() ? null : sessions.getFirst();
    }

    private boolean consumed(String refresh, UUID session) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT consumed_at IS NOT NULL FROM auth_refresh_tokens WHERE token_hash = ? AND session_id = ?
                """, Boolean.class, hash(refresh), session));
    }

    private void insertToken(UUID session, String token) {
        jdbc.update("INSERT INTO auth_refresh_tokens(token_hash, session_id) VALUES (?, ?)", hash(token), session);
    }

    private void revoke(UUID session) {
        jdbc.update("UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, clock_timestamp()) WHERE id = ?", session);
    }

    private void checkCsrf(Session session, String csrf) {
        if (!validSecret(csrf) || !MessageDigest.isEqual(hash(csrf).getBytes(StandardCharsets.US_ASCII),
                session.csrfHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid CSRF token");
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        try {
            return transaction.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                return action.get();
            });
        } catch (DataAccessException | TransactionException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Authentication state unavailable");
        }
    }

    private String secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean validSecret(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication session");
    }

    private record Session(UUID id, UUID userId, String csrfHash, boolean revoked, long remainingSeconds) {}
}
