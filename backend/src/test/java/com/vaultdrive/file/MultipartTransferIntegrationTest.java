package com.vaultdrive.file;

import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.security.JwtService;
import com.vaultdrive.storage.*;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(30)
class MultipartTransferIntegrationTest {
    static final String SCHEMA = "multipart_m2a_" + UUID.randomUUID().toString().replace("-", "");
    static final String SHA = Base64.getEncoder().encodeToString(new byte[32]);
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartTransferService transfers;
    @Autowired FolderService folders;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired HierarchyCoordinator hierarchy;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean ObjectStorageService storage;
    UUID owner;
    TransactionTemplate tx;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "M2A")).getId();
        reset(storage);
        when(storage.initiateMultipart(any(), any(), any(), any())).thenAnswer(call -> {
            assertNoTransaction();
            UUID sessionId = call.getArgument(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM multipart_initiation_dispatches WHERE session_id = ?",
                    Integer.class, sessionId)).isEqualTo(1);
            return "test-garage-id";
        });
        when(storage.signPart(any(), any(), anyInt(), anyLong(), any(), any())).thenAnswer(call -> {
            assertNoTransaction();
            return new MultipartStorage.SignedPart("https://fixture.invalid/?secret=redacted", Map.of(), call.getArgument(5));
        });
    }
    @AfterEach void noTerminalDispatch() {
        // M2A operations and rejected incomplete completion never authorize byte deletion.
        verify(storage, never()).delete(any(StorageTarget.class));
        verify(storage, never()).delete(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE owner_id = ? AND status = 'READY'", Integer.class, owner)).isZero();
    }
    @AfterAll static void dispose(@Autowired DataSource source) throws Exception {
        assertThat(SCHEMA).matches("multipart_m2a_[0-9a-f]{32}");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test");
            s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void initiationCommitsPermissionThenExactIdAndDuplicatesNeverDispatchAgain() {
        var r = reserve(null);
        var result = transfers.initiate(owner, r.id());
        assertThat(result.state()).isEqualTo("OPEN");
        assertThat(result.uploadId()).isEqualTo("test-garage-id");
        assertThat(transfers.initiate(owner, r.id())).isEqualTo(result);
        verify(storage, times(1)).initiateMultipart(any(), eq(r.id()), eq(result.initiationId()), any());
        assertThatThrownBy(() -> sessions.recordUploadId(owner, r.id(), result.revision(), result.initiationId(), "other-id"))
                .isInstanceOf(MultipartConflictException.class);
    }

    @Test void uncertainCreateIsRetainedAndNeverRetried() {
        var r = reserve(null);
        doThrow(new MultipartStorageException()).when(storage).initiateMultipart(any(), any(), any(), any());
        var result = transfers.initiate(owner, r.id());
        assertThat(result.state()).isEqualTo("INITIATING");
        assertThat(transfers.initiate(owner, r.id())).isEqualTo(result);
        assertThatThrownBy(() -> transfers.partUrls(owner, r.id(), List.of(part(1, 8388608))))
                .isInstanceOf(MultipartConflictException.class);
        verify(storage, times(1)).initiateMultipart(any(), any(), any(), any());
        verify(storage, never()).signPart(any(), any(), anyInt(), anyLong(), any(), any());
    }

    @Test void crashAfterPermissionButBeforeGarageLeavesConservativeInitiating() {
        var r = reserve(null);
        assertThat(sessions.claimInitiation(owner, r.id()).permitted()).isTrue(); // Simulated process boundary.
        assertThat(transfers.initiate(owner, r.id()).state()).isEqualTo("INITIATING");
        verifyNoInteractions(storage);
        assertRejected("DELETE FROM multipart_initiation_dispatches WHERE session_id = ?", r.id());
        assertRejected("UPDATE multipart_initiation_dispatches SET initiation_id = gen_random_uuid() WHERE session_id = ?", r.id());
    }

    @Test void lostResultBindingAfterFolderTrashNeverReinitiatesAfterRestore() {
        UUID folder = folders.createFolder(owner, null, "destination");
        var r = reserve(folder);
        doAnswer(call -> {
            assertNoTransaction();
            folders.deleteFolder(owner, folder);
            return "known-but-not-bound";
        }).when(storage).initiateMultipart(any(), any(), any(), any());
        assertThat(transfers.initiate(owner, r.id()).state()).isEqualTo("INITIATING");
        folders.restoreFolder(owner, folder);
        assertThat(transfers.initiate(owner, r.id()).uploadId()).isNull();
        verify(storage, times(1)).initiateMultipart(any(), any(), any(), any());
    }

    @Test void expectationsCommitBeforeSigningAndRefreshKeepsRegistrationIdentity() {
        var r = open(null);
        when(storage.signPart(any(), any(), anyInt(), anyLong(), any(), any())).thenAnswer(call -> {
            assertNoTransaction();
            assertThat(jdbc.queryForObject("SELECT expected_sha256 FROM upload_session_parts WHERE session_id = ? AND part_number = 1",
                    String.class, r.id())).isEqualTo(SHA);
            return new MultipartStorage.SignedPart("https://fixture.invalid/?secret=redacted", Map.of(), call.getArgument(5));
        });
        var first = transfers.partUrls(owner, r.id(), List.of(part(1, 8388608)));
        var again = transfers.partUrls(owner, r.id(), List.of(part(1, 8388608)));
        assertThat(again.getFirst().part()).isEqualTo(first.getFirst().part());
        assertThatThrownBy(() -> transfers.partUrls(owner, r.id(), List.of(part(1, 1))))
                .isInstanceOf(MultipartConflictException.class);
        assertThat(first.getFirst().upload().expiresAt()).isBefore(Instant.now().plusSeconds(301));
        assertThat(first.toString()).doesNotContain("secret", "https://");
        assertRejected("UPDATE upload_session_parts SET expected_sha256 = expected_sha256 WHERE session_id = ?", r.id());
        assertRejected("DELETE FROM upload_session_parts WHERE session_id = ?", r.id());
    }

    @Test void differentDigestConflictsAndWholeBatchRollsBack() {
        var r = open(null);
        sessions.authorizeParts(owner, r.id(), List.of(part(2, 1)));
        String other = Base64.getEncoder().encodeToString(new byte[32]).replaceFirst("A", "B");
        assertThatThrownBy(() -> transfers.partUrls(owner, r.id(), List.of(part(1, 8388608), new PartExpectation(2, 1, other))))
                .isInstanceOf(MultipartConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_session_parts WHERE session_id = ?", Integer.class, r.id())).isEqualTo(1);
        verify(storage, never()).signPart(any(), any(), anyInt(), anyLong(), any(), any());
    }

    @ParameterizedTest @ValueSource(ints = {0, 3, 641, -1})
    void invalidPartNumbersFail(int number) {
        var r = open(null);
        assertThatThrownBy(() -> transfers.partUrls(owner, r.id(), List.of(part(number, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalidGeometryDigestDuplicateAndBatchBoundsFailBeforeSigning() {
        var r = open(null);
        for (var batch : List.of(List.<PartExpectation>of(), Collections.nCopies(101, part(1, 8388608)),
                List.of(part(1, 1)), List.of(part(2, 2)), List.of(part(1, 8388608), part(1, 8388608)),
                List.of(new PartExpectation(1, 8388608, "00")), List.of(new PartExpectation(1, 8388608, "A".repeat(43) + "A")))) {
            assertThatThrownBy(() -> transfers.partUrls(owner, r.id(), batch)).isInstanceOf(IllegalArgumentException.class);
        }
        assertRejected("INSERT INTO upload_session_parts(session_id,part_number,expected_length,expected_sha256,registration_id) "
                + "VALUES (?,2,2,'" + SHA + "',gen_random_uuid())", r.id());
        verify(storage, never()).signPart(any(), any(), anyInt(), anyLong(), any(), any());
    }

    @Test void signingFailureRetainsExpectationAndAllowsSameIdentityRefresh() {
        var r = open(null);
        when(storage.signPart(any(), any(), anyInt(), anyLong(), any(), any())).thenThrow(new MultipartStorageException());
        assertThatThrownBy(() -> transfers.partUrls(owner, r.id(), List.of(part(1, 8388608)))).isInstanceOf(MultipartStorageException.class);
        assertThat(sessions.authorizeParts(owner, r.id(), List.of(part(1, 8388608))).parts()).hasSize(1);
    }

    @Test void expiryStopsRegistrationListingAndRefreshButRetainsHistory() {
        var r = open(null);
        sessions.authorizeParts(owner, r.id(), List.of(part(1, 8388608)));
        tx.executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session");
            jdbc.update("UPDATE upload_sessions SET created_at = created_at - interval '25 hours', expires_at = expires_at - interval '25 hours' WHERE id = ?", r.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session");
        });
        assertThat(sessions.get(owner, r.id()).state()).isEqualTo("OPEN");
        assertThatThrownBy(() -> transfers.partUrls(owner, r.id(), List.of(part(1, 8388608)))).isInstanceOf(MultipartConflictException.class);
        assertThatThrownBy(() -> transfers.list(owner, r.id(), 0, 1)).isInstanceOf(MultipartConflictException.class);
    }

    @Test void listIsBoundedAndUsesExactRetainedIdentityOutsideTransaction() {
        var r = open(null);
        var page = new MultipartStorage.Page(List.of(new MultipartStorage.Part(1, 8388608, "opaque", SHA)), 1);
        when(storage.listParts(any(), eq(r.uploadId()), eq(0), eq(1))).thenAnswer(call -> { assertNoTransaction(); return page; });
        assertThat(transfers.list(owner, r.id(), 0, 1)).isEqualTo(page);
        for (int[] args : new int[][]{{-1, 1}, {3, 1}, {0, 0}, {0, 101}}) {
            assertThatThrownBy(() -> transfers.list(owner, r.id(), args[0], args[1])).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentRegistrationHasOneImmutableWinner(boolean identical) throws Exception {
        var r = open(null);
        String other = Base64.getEncoder().encodeToString(new byte[32]).replaceFirst("A", "B");
        var result = race(() -> sessions.authorizeParts(owner, r.id(), List.of(part(1, 8388608))),
                () -> sessions.authorizeParts(owner, r.id(), List.of(new PartExpectation(1, 8388608, identical ? SHA : other))));
        assertThat(result.getFirst()).isInstanceOf(Transfer.class);
        if (identical) { assertThat(((Transfer) result.getLast()).parts()).isEqualTo(((Transfer) result.getFirst()).parts()); }
        else { assertThat(result.getLast()).isInstanceOf(MultipartConflictException.class); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_session_parts WHERE session_id = ?", Integer.class, r.id())).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"TRASH,true", "TRASH,false", "PURGE,true", "PURGE,false"})
    void authorizationVersusFolderLifecycleUsesFreshPostLockState(String operation, boolean folderFirst) throws Exception {
        UUID folder = folders.createFolder(owner, null, "destination");
        var r = open(folder);
        if (operation.equals("PURGE")) { folders.deleteFolder(owner, folder); }
        Supplier<?> mutate = () -> { if (operation.equals("TRASH")) { folders.deleteFolder(owner, folder); }
            else { folders.requestPermanentDeletion(owner, folder); } return "changed"; };
        Supplier<?> authorize = () -> sessions.authorizeParts(owner, r.id(), List.of(part(1, 8388608)));
        var result = race(folderFirst ? mutate : authorize, folderFirst ? authorize : mutate);
        if (folderFirst) { assertThat(result.getLast()).isInstanceOf(FileNotFoundException.class); }
        else if (operation.equals("TRASH")) { assertThat(result.getFirst()).isInstanceOf(Transfer.class); }
        else { assertThat(result.getFirst()).isInstanceOf(FileNotFoundException.class); }
        assertThatThrownBy(() -> transfers.list(owner, r.id(), 0, 1)).isInstanceOf(FileNotFoundException.class);
    }

    @Test void duplicateInitiationPermissionHasOneWinner() throws Exception {
        var r = reserve(null);
        var result = race(() -> sessions.claimInitiation(owner, r.id()), () -> sessions.claimInitiation(owner, r.id()));
        assertThat(((Dispatch) result.getFirst()).permitted()).isTrue();
        assertThat(((Dispatch) result.getLast()).permitted()).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void realJwtEndpointsExposeReservationInitiationAndState() throws Exception {
        String token = "Bearer " + jwt.generateAccessToken(owner);
        String body = "{\"name\":\"http\",\"size\":1,\"contentType\":\"text/plain\"}";
        mvc.perform(post("/api/v1/upload-sessions").header("Authorization", token).header("Idempotency-Key", "http")
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RESERVED")).andExpect(jsonPath("$.partCount").value(1));
        UUID id = jdbc.queryForObject("SELECT id FROM upload_sessions WHERE owner_id = ?", UUID.class, owner);
        mvc.perform(post("/api/v1/upload-sessions/{id}/initiate", id).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"));
        mvc.perform(get("/api/v1/upload-sessions/{id}", id).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/upload-sessions/{id}/part-urls", id).header("Authorization", token)
                .contentType("application/json").content("{\"parts\":[{\"partNumber\":1,\"length\":1,\"sha256\":\"" + SHA + "\"}]}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        when(storage.listParts(any(), any(), anyInt(), anyInt())).thenReturn(new MultipartStorage.Page(List.of(), null));
        mvc.perform(get("/api/v1/upload-sessions/{id}/parts", id).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/upload-sessions/{id}/complete", id).header("Authorization", token)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/upload-sessions/{id}/abort", id).header("Authorization", token)).andExpect(status().isNotFound());
    }

    @Test void allEndpointsRequireJwtAndForeignOwnerMatchesMissingResource() throws Exception {
        var r = open(null);
        String foreign = "Bearer " + jwt.generateAccessToken(UUID.randomUUID());
        for (String suffix : List.of("", "/parts", "/initiate", "/part-urls")) {
            boolean post = suffix.equals("/initiate") || suffix.equals("/part-urls");
            String path = "/api/v1/upload-sessions/" + r.id() + suffix;
            var req = post ? post(path).contentType("application/json").content("{\"parts\":[]}") : get(path);
            mvc.perform(req).andExpect(status().isUnauthorized());
            mvc.perform(req.header("Authorization", foreign)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/upload-sessions").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> sessions.get(owner, UUID.randomUUID())).isInstanceOf(FileNotFoundException.class);
    }

    @Test void httpUnknownConflictAndValidationResponsesAreSanitized() throws Exception {
        var r = reserve(null);
        String token = "Bearer " + jwt.generateAccessToken(owner);
        doThrow(new MultipartStorageException()).when(storage).initiateMultipart(any(), any(), any(), any());
        mvc.perform(post("/api/v1/upload-sessions/{id}/initiate", r.id()).header("Authorization", token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("INITIATING"));
        mvc.perform(get("/api/v1/upload-sessions/{id}/parts", r.id()).header("Authorization", token)).andExpect(status().isConflict());
        var open = open(null);
        mvc.perform(post("/api/v1/upload-sessions/{id}/part-urls", open.id()).header("Authorization", token)
                .contentType("application/json").content("{\"parts\":[]}")).andExpect(status().isBadRequest());
    }

    private Session reserve(UUID folder) { return sessions.reserve(owner, UUID.randomUUID().toString(), new Request(folder, UUID.randomUUID().toString(), 8388609, "text/plain")); }
    private Session open(UUID folder) {
        var r = reserve(folder);
        var d = sessions.claimInitiation(owner, r.id());
        return sessions.recordUploadId(owner, r.id(), 1, d.transfer().session().initiationId(), "test-garage-id");
    }
    private PartExpectation part(int number, long length) { return new PartExpectation(number, length, SHA); }
    private void assertRejected(String sql, UUID id) { assertThatThrownBy(() -> jdbc.update(sql, id)).isInstanceOf(RuntimeException.class); }
    private static void assertNoTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private static Object capture(Supplier<?> task) { try { return task.get(); } catch (RuntimeException e) { return e; } }
    private List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var pid = new AtomicInteger();
        try {
            var winner = pool.submit(() -> capture(() -> tx.execute(status -> {
                Object result = capture(first); held.countDown(); await(release);
                if (result instanceof RuntimeException) { status.setRollbackOnly(); }
                return result;
            })));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var loser = pool.submit(() -> capture(() -> tx.execute(status -> {
                pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); entered.countDown(); return second.get();
            })));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); boolean blocked = false;
            while (System.nanoTime() < deadline) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid.get()));
                if (blocked) { break; } Thread.yield();
            }
            assertThat(blocked).isTrue(); assertThat(loser.isDone()).isFalse(); release.countDown();
            return List.of(winner.get(8, TimeUnit.SECONDS), loser.get(8, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) { throw new AssertionError("Latch timeout"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
