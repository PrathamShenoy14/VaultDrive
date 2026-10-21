package com.vaultdrive.storage;

import com.vaultdrive.file.*;
import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.file.MultipartCompletionTransactions.*;
import com.vaultdrive.storage.MultipartStorage.*;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.security.JwtService;
import com.vaultdrive.sharing.GrantService;
import com.vaultdrive.sharing.ResourceRole;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(45)
class StorageUsageIntegrationTest {
    static final String SCHEMA = "storage_quota_" + UUID.randomUUID().toString().replace("-", "");
    static final String SHA = Base64.getEncoder().encodeToString(new byte[32]);
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired StorageUsageService usage;
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartCompletionTransactions completion;
    @Autowired MultipartCancellationTransactions cancellation;
    @Autowired FileService files;
    @Autowired FolderService folders;
    @Autowired GrantService grants;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean ObjectStorageService storage;
    UUID owner;
    TransactionTemplate tx;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = user("owner");
    }

    @AfterAll static void dispose(@Autowired DataSource source) throws Exception {
        assertThat(SCHEMA).matches("storage_quota_[0-9a-f]{32}");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test");
            s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void quotaErrorReplayAndAuthenticatedOwnerOnlyUsage() throws Exception {
        limit(owner, 10);
        var first = reserve(owner, null, "same", 6, "a");
        assertThat(reserve(owner, null, "same", 6, "a").id()).isEqualTo(first.id());
        assertThat(usage.get(owner).reservedBytes()).isEqualTo(6);
        String token = "Bearer " + jwt.generateAccessToken(owner);
        mvc.perform(post("/api/v1/upload-sessions").header("Authorization", token)
                .header("Idempotency-Key", "b").contentType("application/json")
                .content("{\"folderId\":null,\"name\":\"b\",\"size\":5,\"contentType\":\"text/plain\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Storage quota exceeded"));
        mvc.perform(get("/api/v1/users/me/storage").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.limitBytes").value(10)).andExpect(jsonPath("$.usedBytes").value(0))
                .andExpect(jsonPath("$.reservedBytes").value(6)).andExpect(jsonPath("$.availableBytes").value(4));
        mvc.perform(get("/api/v1/users/me/storage")).andExpect(status().isUnauthorized());
        UUID other = user("other");
        mvc.perform(get("/api/v1/users/me/storage").header("Authorization", "Bearer " + jwt.generateAccessToken(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservedBytes").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE owner_id=?", Integer.class, owner)).isEqualTo(1);
    }

    @Test void concurrentDifferentKeysCannotOvercommit() throws Exception {
        limit(owner, 10);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var pid = new AtomicInteger(); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tx.execute(t -> {
                var result = reserve(owner, null, "a", 6, "a"); held.countDown(); await(release); return result;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> {
                try { return tx.execute(t -> {
                    pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); entered.countDown();
                    return reserve(owner, null, "b", 6, "b");
                }); } catch (RuntimeException e) { return e; }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid.get()));
                if (blocked) break;
                Thread.yield();
            }
            assertThat(blocked).isTrue(); assertThat(second.isDone()).isFalse();
            release.countDown();
            assertThat(first.get(8, TimeUnit.SECONDS)).isInstanceOf(Session.class);
            assertThat(second.get(8, TimeUnit.SECONDS)).isInstanceOf(RuntimeException.class);
            assertThat(usage.get(owner).reservedBytes()).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE owner_id=?", Integer.class, owner)).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void editorChargesOwnerAndCancellationRetainsUncertainAllocation() {
        UUID editor = user("editor");
        UUID folder = folders.createFolder(owner, null, "shared");
        grants.create(owner, true, folder, email(editor), ResourceRole.EDITOR);
        limit(owner, 5);
        var s = reserve(editor, folder, "delegated", 4, "editor-key");
        assertThat(s.ownerId()).isEqualTo(owner);
        assertThat(usage.get(owner).reservedBytes()).isEqualTo(4);
        assertThat(usage.get(editor).reservedBytes()).isZero();
        assertThatThrownBy(() -> reserve(owner, null, "extra", 2, "owner-key")).isInstanceOf(RuntimeException.class);
        var dispatch = sessions.claimInitiation(editor, s.id());
        assertThat(dispatch.permitted()).isTrue();
        cancellation.cancel(editor, s.id());
        var ownerUsage = usage.get(owner);
        assertThat(ownerUsage.reservedBytes()).isZero();
        assertThat(ownerUsage.uncertainStorageBytes()).isEqualTo(4);
        assertThat(ownerUsage.uncertainStorageAllocations()).isEqualTo(1);
        assertThat(usage.get(editor).uncertainStorageAllocations()).isZero();
    }

    @Test void completionConvertsOnceTrashRestoreAndRepairPreserveUsage() throws Exception {
        limit(owner, 2);
        var s = reserve(owner, null, "ready", 1, "ready-key");
        var d = sessions.claimInitiation(owner, s.id());
        sessions.recordUploadId(owner, s.id(), 1, d.transfer().session().initiationId(), "fixture-" + s.id());
        sessions.authorizeParts(owner, s.id(), List.of(new PartExpectation(1, 1, SHA)));
        var authorized = completion.authorize(owner, s.id());
        var frozen = completion.freeze(owner, authorized, List.of(new ManifestPart(1, 1, SHA,
                authorized.registered().getFirst().registrationId(), "opaque")));
        var permit = completion.claim(owner, s.id());
        completion.recordReceipt(owner, permit.snapshot(), new CompletedObject(1, frozen.expectedSha256(), "final", "text/plain",
                Map.of("vaultdrive-session", s.id().toString(), "vaultdrive-initiation", d.transfer().session().initiationId().toString())), Evidence.COMPLETE_HEAD);
        completion.finalizeReady(owner, s.id());
        completion.finalizeReady(owner, s.id());
        assertThat(usage.get(owner).usedBytes()).isEqualTo(1);
        assertThat(usage.get(owner).reservedBytes()).isZero();
        files.trashFile(owner, s.fileId());
        assertThat(usage.get(owner).usedBytes()).isEqualTo(1);
        files.restoreFile(owner, s.fileId());
        assertThat(usage.get(owner).usedBytes()).isEqualTo(1);
        assertThat(usage.reconcile(owner, false).repaired()).isFalse();
        jdbc.update("UPDATE storage_quotas SET used_bytes=0 WHERE owner_id=?", owner);
        var repaired = usage.reconcile(owner, true);
        assertThat(repaired.repaired()).isTrue();
        assertThat(repaired.derivedUsedBytes()).isEqualTo(1);
        assertThat(usage.get(owner).usedBytes()).isEqualTo(1);
        assertThat(usage.candidates(null, 1)).hasSize(1);
        assertThatThrownBy(() -> usage.candidates(null, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unknownHistoricalAllocationSurvivesCounterRepair() {
        UUID oldFile = UUID.randomUUID();
        jdbc.update("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,write_started) VALUES (?,?,?,true)",
                "historical/" + oldFile, oldFile, owner);
        var before = usage.get(owner);
        assertThat(before.uncertainStorageAllocations()).isEqualTo(1);
        assertThat(before.unknownSizeAllocations()).isEqualTo(1);
        assertThat(before.uncertainStorageBytes()).isZero();
        assertThat(usage.reconcile(owner, true).repaired()).isFalse();
        assertThat(usage.get(owner)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT quota_size_bytes FROM file_storage_objects WHERE file_id=?",
                Long.class, oldFile)).isNull();
    }

    private Session reserve(UUID actor, UUID folder, String name, long size, String key) {
        return sessions.reserve(actor, key, new Request(folder, name, size, "text/plain"));
    }
    private void limit(UUID id, long amount) { jdbc.update("UPDATE storage_quotas SET limit_bytes=? WHERE owner_id=?", amount, id); }
    private UUID user(String label) { return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", label)).getId(); }
    private String email(UUID id) { return users.findById(id).orElseThrow().getEmail(); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
