package com.vaultdrive.auth;

import com.vaultdrive.security.AccessTokenState;
import com.vaultdrive.security.JwtService;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP, independent PostgreSQL transactions and a new private schema per run. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(40)
class AuthenticationSessionIntegrationTest {
    static final String SCHEMA = "auth_sessions_" + UUID.randomUUID().toString().replace("-", "");
    static final String PASSWORD = "SessionPassword123!";
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired SessionService sessions;
    @Autowired AccessTokenState state;
    @Autowired PlatformTransactionManager manager;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    UUID user;
    String email;

    @BeforeEach void fixture() {
        email = UUID.randomUUID() + "@example.test";
        user = users.saveAndFlush(new User(email, passwords.encode(PASSWORD), "Session user")).getId();
    }

    @Test void loginPreservesCredentialsAndIssuesOnlyHashedCookieCredentials() throws Exception {
        var response = loginResponse();
        assertThat(response.statusCode()).isEqualTo(200);
        var login = credentials(response);
        var claims = decoder.decode(login.access());
        assertThat(claims.getSubject()).isEqualTo(user.toString());
        assertThat(UUID.fromString(claims.getId())).isNotNull();
        assertThat(Duration.between(claims.getIssuedAt(), claims.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(mapper.readTree(response.body()).get("expiresIn").asLong()).isEqualTo(1800);
        assertThat(response.body()).doesNotContain(login.refresh(), login.csrf(), "refreshToken", "password");
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow())
                .contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/", "Max-Age=2592000").doesNotContain("Domain=");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(SessionService.validSecret(login.refresh())).isTrue();
        assertThat(SessionService.validSecret(login.csrf())).isTrue();
        UUID sid = sid(login);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM auth_refresh_tokens WHERE session_id = ?", String.class, sid))
                .isEqualTo(SessionService.hash(login.refresh())).isNotEqualTo(login.refresh());
        assertThat(jdbc.queryForObject("SELECT csrf_hash FROM auth_sessions WHERE id = ?", String.class, sid))
                .isEqualTo(SessionService.hash(login.csrf()));
        assertThat(jdbc.queryForObject("SELECT expires_at - created_at <= interval '30 days 1 second' FROM auth_sessions WHERE id = ?",
                Boolean.class, sid)).isTrue();
        assertThat(sessions.create(user).toString()).isEqualTo("IssuedSession[REDACTED]");
    }

    @Test void invalidCredentialsHaveSameResponseForUnknownEmailAndWrongPassword() throws Exception {
        var wrong = call("POST", "/auth/login", Map.of("X-VaultDrive-Client", "1"),
                mapper.writeValueAsString(Map.of("email", email, "password", "wrong")));
        var absent = call("POST", "/auth/login", Map.of("X-VaultDrive-Client", "1"),
                mapper.writeValueAsString(Map.of("email", "absent@example.test", "password", "wrong")));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(absent.statusCode()).isEqualTo(401);
        assertThat(mapper.readTree(wrong.body()).get("message").asString())
                .isEqualTo(mapper.readTree(absent.body()).get("message").asString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ?", Integer.class, user)).isZero();
    }

    @Test void rotationIsSingleUseAndReplayCommitsOnlyAffectedFamilyRevocation() throws Exception {
        var first = login();
        var unrelated = login();
        UUID session = sid(first);
        var next = credentials(refresh(first));
        assertThat(next.refresh()).isNotEqualTo(first.refresh());
        assertThat(decoder.decode(next.access()).getId()).isNotEqualTo(decoder.decode(first.access()).getId());
        assertThat(sid(next)).isEqualTo(sid(first));
        assertThat(profile(first.access()).statusCode()).isEqualTo(200);
        assertThat(refresh(first).statusCode()).isEqualTo(401);
        assertThat(refresh(next).statusCode()).isEqualTo(401);
        assertThat(profile(next.access()).statusCode()).isEqualTo(401);
        assertThat(profile(first.access()).statusCode()).isEqualTo(401);
        assertThat(profile(unrelated.access()).statusCode()).isEqualTo(200);
        assertThat(refresh(unrelated).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_tokens WHERE session_id = ?", Integer.class,
                session)).isEqualTo(2);
    }

    @Test void concurrentHttpRefreshesWaitOnDatabaseLockThenRevokeReusedFamily() throws Exception {
        var first = login();
        UUID session = sid(first);
        var executor = Executors.newFixedThreadPool(2);
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM auth_sessions WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, session); lock.executeQuery().close();
            }
            Future<HttpResponse<String>> a = executor.submit(() -> refresh(first));
            Future<HttpResponse<String>> b = executor.submit(() -> refresh(first));
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE query LIKE 'SELECT id FROM auth_sessions WHERE id = %' AND wait_event_type = 'Lock'
                        """, Integer.class) < 2) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(10);
                }
            } finally { connection.commit(); }
            var responses = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 401);
            var winner = credentials(responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow());
            assertThat(profile(winner.access()).statusCode()).isEqualTo(401);
            assertThat(refresh(winner).statusCode()).isEqualTo(401);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_tokens WHERE session_id = ?", Integer.class, session)).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }

    @Test void independentServiceInstancesUseTheSamePostgresSingleUseState() {
        var first = sessions.create(user);
        var otherInstance = new SessionService(jdbc, jwt, manager);
        var rotated = otherInstance.refresh(first.refreshToken(), first.csrfToken());
        assertThatThrownBy(() -> sessions.refresh(first.refreshToken(), first.csrfToken()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> otherInstance.refresh(rotated.refreshToken(), rotated.csrfToken()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void replayRevocationSurvivesCallerTransactionRollback() {
        var first = sessions.create(user);
        var next = sessions.refresh(first.refreshToken(), first.csrfToken());
        new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
            assertThatThrownBy(() -> sessions.refresh(first.refreshToken(), first.csrfToken()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            status.setRollbackOnly();
            return null;
        });
        assertThatThrownBy(() -> sessions.refresh(next.refreshToken(), next.csrfToken()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void concurrentLogoutAndRefreshLeaveNoUsableFamilyCredentials() throws Exception {
        var first = login();
        UUID session = sid(first);
        var executor = Executors.newFixedThreadPool(2);
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM auth_sessions WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, session); lock.executeQuery().close();
            }
            var loggingOut = executor.submit(() -> logout(first, first));
            var rotating = executor.submit(() -> refresh(first));
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE query LIKE 'SELECT id FROM auth_sessions WHERE id = %' AND wait_event_type = 'Lock'
                        """, Integer.class) < 2) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(10);
                }
            } finally { connection.commit(); }
            assertThat(loggingOut.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            var rotated = rotating.get(10, TimeUnit.SECONDS);
            assertThat(rotated.statusCode()).isIn(200, 401);
            if (rotated.statusCode() == 200) {
                var next = credentials(rotated);
                assertThat(profile(next.access()).statusCode()).isEqualTo(401);
                assertThat(refresh(next).statusCode()).isEqualTo(401);
            }
            assertThat(profile(first.access()).statusCode()).isEqualTo(401);
            assertThat(refresh(first).statusCode()).isEqualTo(401);
        } finally { executor.shutdownNow(); }
    }

    @Test void refreshChecksExpiryAfterAcquiringTheFamilyLock() throws Exception {
        var first = login();
        UUID session = sid(first);
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM auth_sessions WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, session); lock.executeQuery().close();
            }
            var refreshing = executor.submit(() -> refresh(first));
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE query LIKE 'SELECT id FROM auth_sessions WHERE id = %' AND wait_event_type = 'Lock'
                        """, Integer.class) < 1) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(10);
                }
                // The token was eligible before waiting; change eligibility under the held lock.
                try (var expire = connection.prepareStatement("UPDATE auth_sessions SET created_at = clock_timestamp() - interval '31 days', expires_at = clock_timestamp() - interval '1 day' WHERE id = ?")) {
                    expire.setObject(1, session); expire.executeUpdate();
                }
            } finally { connection.commit(); }
            assertThat(refreshing.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_tokens WHERE session_id = ?", Integer.class, session)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void logoutRevokesPresentedJtiAndFamilyImmediatelyAndClearsCookieAfterCommit() throws Exception {
        var first = login(); var unrelated = login();
        var claims = decoder.decode(first.access());
        var response = logout(first, first);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow())
                .contains("Max-Age=0", "Secure", "HttpOnly", "SameSite=Strict", "Path=/");
        assertThat(jdbc.queryForObject("SELECT expires_at FROM auth_access_revocations WHERE jti = ?",
                Timestamp.class, UUID.fromString(claims.getId())).toInstant()).isEqualTo(claims.getExpiresAt().plusSeconds(60));
        assertThat(profile(first.access()).statusCode()).isEqualTo(401);
        assertThat(refresh(first).statusCode()).isEqualTo(401);
        assertThat(logout(first, first).statusCode()).isEqualTo(401);
        assertThat(profile(unrelated.access()).statusCode()).isEqualTo(200);
        assertThat(refresh(unrelated).statusCode()).isEqualTo(200);
    }

    @Test void logoutRejectsDifferentFamilyEvenForSameUser() throws Exception {
        var first = login(); var other = login();
        assertThat(logout(first, other).statusCode()).isEqualTo(401);
        assertThat(profile(first.access()).statusCode()).isEqualTo(200);
        assertThat(refresh(other).statusCode()).isEqualTo(200);
    }

    @Test void logoutWithConsumedCookieRevokesRotatedFamily() throws Exception {
        var first = login(); var next = credentials(refresh(first));
        assertThat(logout(first, first).statusCode()).isEqualTo(204);
        assertThat(refresh(next).statusCode()).isEqualTo(401);
        assertThat(profile(next.access()).statusCode()).isEqualTo(401);
    }

    @Test void expiredUnknownMalformedAndRevokedRefreshCredentialsAreUnauthorized() throws Exception {
        var first = login(); UUID session = sid(first);
        jdbc.update("UPDATE auth_sessions SET created_at = clock_timestamp() - interval '31 days', expires_at = clock_timestamp() - interval '1 day' WHERE id = ?", session);
        assertThat(refresh(first).statusCode()).isEqualTo(401);
        assertThat(refresh(new Credentials(first.access(), "a".repeat(43), first.csrf())).statusCode()).isEqualTo(401);
        assertThat(refresh(new Credentials(first.access(), "bad", first.csrf())).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/refresh", Map.of("X-VaultDrive-CSRF", first.csrf()), null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_refresh_tokens WHERE session_id = ?", Integer.class, session)).isEqualTo(1);
    }

    @Test void logoutCanRevokeExpiredRefreshFamilyWhileAccessTokenIsStillValid() throws Exception {
        var first = login(); UUID session = sid(first);
        jdbc.update("UPDATE auth_sessions SET created_at = clock_timestamp() - interval '31 days', expires_at = clock_timestamp() - interval '1 day' WHERE id = ?", session);
        assertThat(profile(first.access()).statusCode()).isEqualTo(200);
        assertThat(logout(first, first).statusCode()).isEqualTo(204);
        assertThat(profile(first.access()).statusCode()).isEqualTo(401);
    }

    @Test void cookiesNeverAuthenticateOrdinaryApiAndLogoutRequiresBothCredentialTypes() throws Exception {
        var first = login();
        assertThat(call("GET", "/users/me", Map.of("Cookie", cookie(first)), null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/logout", Map.of("Cookie", cookie(first), "X-VaultDrive-CSRF", first.csrf()), null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/logout", Map.of("Authorization", "Bearer " + first.access(), "X-VaultDrive-CSRF", first.csrf()), null).statusCode()).isEqualTo(401);
        assertThat(profile(first.access()).statusCode()).isEqualTo(200);
    }

    @Test void csrfFailuresAndDuplicateCookiesDoNotConsumeOrRevokeSessions() throws Exception {
        var first = login();
        assertThat(call("POST", "/auth/refresh", Map.of("Cookie", cookie(first)), null).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/auth/logout", Map.of("Cookie", cookie(first), "Authorization", "Bearer " + first.access()), null).statusCode()).isEqualTo(403);
        assertThat(refresh(new Credentials(first.access(), first.refresh(), "a".repeat(43))).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/auth/refresh", Map.of("Cookie", cookie(first) + "; " + cookie(first), "X-VaultDrive-CSRF", first.csrf()), null).statusCode()).isEqualTo(401);
        assertThat(refresh(first).statusCode()).isEqualTo(200);
        assertThat(profile(first.access()).statusCode()).isEqualTo(200);
    }

    @Test void replayWithoutCorrectCsrfCannotRevokeFamily() throws Exception {
        var first = login(); var next = credentials(refresh(first));
        assertThat(refresh(new Credentials(first.access(), first.refresh(), "b".repeat(43))).statusCode()).isEqualTo(403);
        assertThat(refresh(next).statusCode()).isEqualTo(200);
    }

    @Test void crossOriginSimpleLoginAndCredentialQueriesAreRejected() throws Exception {
        String body = mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD));
        assertThat(call("POST", "/auth/login", Map.of("Origin", "https://attacker.test"), body).statusCode()).isEqualTo(403);
        var preflight = call("OPTIONS", "/auth/login", Map.of("Origin", "https://attacker.test",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "X-VaultDrive-Client"), null);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        var first = login();
        for (String path : List.of("/auth/login", "/auth/refresh", "/auth/logout")) {
            assertThat(call("POST", path + "?refresh_token=forbidden", Map.of(), null).statusCode()).isEqualTo(400);
        }
        assertThat(refresh(first).statusCode()).isEqualTo(200);
    }

    @Test void profileIsPrivateValidatedAndCannotUpdateAnotherUserOrSecrets() throws Exception {
        var first = login(); var before = users.findById(user).orElseThrow();
        String passwordHash = before.getPasswordHash();
        UUID other = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "other-hash", "Other")).getId();
        assertThat(profile(null).statusCode()).isEqualTo(401);
        assertThat(call("PATCH", "/users/me", Map.of(), "{\"displayName\":\"Changed\"}").statusCode()).isEqualTo(401);
        var updated = call("PATCH", "/users/me", bearer(first.access()), mapper.writeValueAsString(Map.of(
                "displayName", "  New name  ", "userId", other, "email", "overwrite@example.test", "passwordHash", "overwrite")));
        assertThat(updated.statusCode()).isEqualTo(200);
        var json = mapper.readTree(updated.body());
        assertThat(json.get("userId").asString()).isEqualTo(user.toString());
        assertThat(json.get("email").asString()).isEqualTo(email);
        assertThat(json.get("displayName").asString()).isEqualTo("New name");
        assertThat(json.size()).isEqualTo(5);
        assertThat(json.has("createdAt")).isTrue(); assertThat(json.has("updatedAt")).isTrue();
        assertThat(updated.body()).doesNotContain(passwordHash, first.refresh(), first.csrf(), "passwordHash");
        assertThat(profile(first.access()).body()).contains("New name");
        assertThat(users.findById(other).orElseThrow().getDisplayName()).isEqualTo("Other");
        assertThat(users.findById(user).orElseThrow().getPasswordHash()).isEqualTo(passwordHash);
        for (String invalid : List.of("{}", "{\"displayName\":null}", "{\"displayName\":\"   \"}",
                "{\"displayName\":\"bad\\u0000name\"}", "{\"displayName\":\"bad\\nname\"}",
                mapper.writeValueAsString(Map.of("displayName", "x".repeat(101))))) {
            assertThat(call("PATCH", "/users/me", bearer(first.access()), invalid).statusCode()).isEqualTo(400);
        }
        assertThat(profile(jwt.generateAccessToken(UUID.randomUUID())).statusCode()).isEqualTo(401);
    }

    @Test void jtiRevocationIndependentlyRejectsOtherwiseValidJwtAndCleanupIsBounded() throws Exception {
        String access = jwt.generateAccessToken(user);
        var claims = decoder.decode(access);
        jdbc.update("INSERT INTO auth_access_revocations(jti, expires_at) VALUES (?, ?)", UUID.fromString(claims.getId()),
                Timestamp.from(claims.getExpiresAt().plusSeconds(60)));
        assertThat(profile(access).statusCode()).isEqualTo(401);
        Set<UUID> expired = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID(); expired.add(id);
            jdbc.update("INSERT INTO auth_access_revocations(jti, expires_at) VALUES (?, clock_timestamp() - interval '1 day')", id);
        }
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT jti FROM auth_access_revocations WHERE jti = ? FOR UPDATE")) {
                lock.setObject(1, expired.iterator().next()); lock.executeQuery().close();
            }
            try { assertThat(state.cleanupExpired(2)).isEqualTo(2); }
            finally { connection.rollback(); }
        }
        assertThat(state.cleanupExpired(2)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_access_revocations WHERE jti = ?", Integer.class, UUID.fromString(claims.getId()))).isEqualTo(1);
        assertThat(profile(access).statusCode()).isEqualTo(401);
        assertThatThrownBy(() -> state.cleanupExpired(1001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void jwtRequiresJtiAndValidSubjectAndRejectsExpiredTokens() throws Exception {
        assertThat(profile(signed(null, user.toString(), Instant.now().plusSeconds(1800))).statusCode()).isEqualTo(401);
        assertThat(profile(signed(UUID.randomUUID().toString(), "invalid", Instant.now().plusSeconds(1800))).statusCode()).isEqualTo(401);
        assertThat(profile(signed(UUID.randomUUID().toString(), user.toString(), Instant.now().minusSeconds(120))).statusCode()).isEqualTo(401);
    }

    @Test void unavailableRevocationStateFailsClosedOverHttpAndRecovers() throws Exception {
        var first = login();
        jdbc.execute("ALTER TABLE auth_access_revocations RENAME TO auth_access_revocations_unavailable");
        try { assertThat(profile(first.access()).statusCode()).isEqualTo(401); }
        finally { jdbc.execute("ALTER TABLE auth_access_revocations_unavailable RENAME TO auth_access_revocations"); }
        assertThat(profile(first.access()).statusCode()).isEqualTo(200);
    }

    @Test void failedLogoutRollsBackSessionRevocationAndDoesNotClearCookie() throws Exception {
        var first = login();
        failInserts("auth_access_revocations");
        try {
            var response = logout(first, first);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        } finally { restoreInserts("auth_access_revocations"); }
        assertThat(profile(first.access()).statusCode()).isEqualTo(200);
        assertThat(logout(first, first).statusCode()).isEqualTo(204);
    }

    @Test void failedRotationRollsBackConsumptionAndReturnsNoReplacementCookie() throws Exception {
        var first = login();
        failInserts("auth_refresh_tokens");
        try {
            var response = refresh(first);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        } finally { restoreInserts("auth_refresh_tokens"); }
        assertThat(refresh(first).statusCode()).isEqualTo(200);
    }

    @Test void failedSessionCreationReturnsNoCookieAndRollsBackFamily() throws Exception {
        failInserts("auth_refresh_tokens");
        try {
            var response = loginResponse();
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ?", Integer.class, user)).isZero();
        } finally { restoreInserts("auth_refresh_tokens"); }
        assertThat(loginResponse().statusCode()).isEqualTo(200);
    }

    @Test void databaseUniquenessGuardsOneUnconsumedTokenPerFamily() throws Exception {
        var first = login();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO auth_refresh_tokens(token_hash, session_id) VALUES (?, ?)",
                "a".repeat(64), sid(first))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private HttpResponse<String> loginResponse() throws Exception {
        return call("POST", "/auth/login", Map.of("X-VaultDrive-Client", "1"),
                mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD)));
    }
    private Credentials login() throws Exception { return credentials(loginResponse()); }
    private Credentials credentials(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        String raw = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new Credentials(mapper.readTree(response.body()).get("accessToken").asString(),
                raw.substring(raw.indexOf('=') + 1), response.headers().firstValue("X-VaultDrive-CSRF").orElseThrow());
    }
    private UUID sid(Credentials credentials) { return UUID.fromString(decoder.decode(credentials.access()).getClaimAsString("sid")); }
    private HttpResponse<String> refresh(Credentials credentials) throws Exception {
        return call("POST", "/auth/refresh", Map.of("Cookie", cookie(credentials), "X-VaultDrive-CSRF", credentials.csrf()), null);
    }
    private HttpResponse<String> logout(Credentials access, Credentials cookie) throws Exception {
        return call("POST", "/auth/logout", Map.of("Authorization", "Bearer " + access.access(), "Cookie", cookie(cookie), "X-VaultDrive-CSRF", cookie.csrf()), null);
    }
    private HttpResponse<String> profile(String token) throws Exception { return call("GET", "/users/me", token == null ? Map.of() : bearer(token), null); }
    private Map<String, String> bearer(String token) { return Map.of("Authorization", "Bearer " + token); }
    private String cookie(Credentials credentials) { return AuthController.REFRESH_COOKIE + "=" + credentials.refresh(); }
    private HttpResponse<String> call(String method, String path, Map<String, String> headers, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(15));
        headers.forEach(request::header);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String signed(String jti, String subject, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer("https://vaultdrive.local").subject(subject).issuedAt(expiry.minusSeconds(1800)).expiresAt(expiry);
        if (jti != null) claims.id(jti);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
    private void failInserts(String table) {
        jdbc.execute("CREATE FUNCTION auth_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test database failure'; END $$");
        jdbc.execute("CREATE TRIGGER auth_test_reject BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION auth_test_reject()");
    }
    private void restoreInserts(String table) {
        jdbc.execute("DROP TRIGGER auth_test_reject ON " + table);
        jdbc.execute("DROP FUNCTION auth_test_reject()");
    }
    private record Credentials(String access, String refresh, String csrf) {
        @Override public String toString() { return "Credentials[REDACTED]"; }
    }
}
