package com.vaultdrive.file;

import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.storage.*;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(30)
class MultipartSessionIntegrationTest {
    private static final String SCHEMA = "multipart_m1_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired FileStorageIdentityService identity;
    @Autowired FileMetadataService metadata;
    @Autowired StoredFileRepository files;
    @Autowired UploadFinalizationTransactions receipts;
    @Autowired StaleUploadExpiryTransactions expiry;
    @Autowired KnownSuccessCleanupTransactions cleanup;
    @Autowired FolderService folders;
    @Autowired UserRepository users;
    @Autowired HierarchyCoordinator hierarchy;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource source;
    @Autowired com.vaultdrive.job.BackgroundJobService jobs;
    @Autowired com.vaultdrive.worker.FolderPurgeDiscoveryService discovery;
    @Autowired com.vaultdrive.worker.FolderPurgeFileService purgeFiles;
    @MockitoBean ObjectStorageService storage;
    @MockitoBean S3Client s3;
    TransactionTemplate tx;
    UUID owner;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "Multipart")).getId();
        clearInvocations(storage, s3);
    }
    @AfterEach void noStorageCalls() { verifyNoInteractions(storage, s3); }
    @AfterAll static void dispose(@Autowired DataSource source) throws Exception {
        assertThat(SCHEMA).matches("multipart_m1_[0-9a-f]{32}");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test");
            s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void reservationIsAtomicCanonicalAndUsesDatabaseTime() {
        var a = reserve("same", " file.txt ");
        assertThat(sessions.reserve(owner, "same", request(null, "file.txt"))).isEqualTo(a);
        assertThat(a.state()).isEqualTo("RESERVED");
        assertThat(a.revision()).isZero();
        assertThat(a.uploadId()).isNull();
        assertThat(Duration.between(a.createdAt(), a.expiresAt())).isEqualTo(Duration.ofHours(24));
        assertThat(jdbc.queryForObject("SELECT created_at <= clock_timestamp() AND expires_at > clock_timestamp() "
                + "FROM upload_sessions WHERE id = ?", Boolean.class, a.id())).isTrue();
        assertCount(1);
        assertThat(jdbc.queryForObject("SELECT part_count FROM upload_sessions WHERE id = ?", Integer.class, a.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT upload_protocol FROM file_storage_objects WHERE file_id = ?", String.class, a.fileId()))
                .isEqualTo("DIRECT_MULTIPART");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void sameKeyConcurrentRequestsHaveOneAllocation(boolean samePayload) throws Exception {
        var result = race(() -> reserve("key", "a"), () -> reserve("key", samePayload ? "a" : "b"));
        assertThat(result.first()).isInstanceOf(Session.class);
        if (samePayload) { assertThat(result.second()).isEqualTo(result.first()); }
        else { assertThat(result.second()).isInstanceOf(IllegalStateException.class); }
        assertCount(1);
    }

    @Test void concurrentDifferentKeysStillRespectActiveNameConstraint() throws Exception {
        var result = race(() -> reserve("a", "same-name"), () -> reserve("b", "same-name"));
        assertThat(result.first()).isInstanceOf(Session.class);
        assertThat(result.second()).isInstanceOf(RuntimeException.class);
        assertCount(1);
    }

    @Test void allCanonicalFieldsParticipateInIdempotencyAndOwnerScopesAreIndependent() {
        reserve("key", "a");
        UUID folder = folders.createFolder(owner, null, "destination");
        for (var request : new Request[]{request(null, "b"), request(folder, "a"),
                new Request(null, "a", 1, "text/plain"), new Request(null, "a", 8388609, "image/png")}) {
            assertThatThrownBy(() -> sessions.reserve(owner, "key", request)).isInstanceOf(IllegalStateException.class);
        }
        UUID another = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "Other")).getId();
        assertThat(sessions.reserve(another, "key", request(null, "a")).id()).isNotNull();
        assertCount(1);
    }

    @ParameterizedTest @CsvSource({"TRASH,true", "TRASH,false", "RESTORE,true", "RESTORE,false", "PURGE,true", "PURGE,false"})
    void reservationVersusFolderLifecycle(String operation, boolean reservationFirst) throws Exception {
        UUID folder = folders.createFolder(owner, null, "destination");
        if (!operation.equals("TRASH")) { folders.deleteFolder(owner, folder); }
        Supplier<?> reservation = () -> sessions.reserve(owner, "key", request(folder, "a"));
        Supplier<?> mutation = () -> {
            switch (operation) {
                case "TRASH" -> folders.deleteFolder(owner, folder);
                case "RESTORE" -> folders.restoreFolder(owner, folder);
                case "PURGE" -> folders.requestPermanentDeletion(owner, folder);
            }
            return "MUTATED";
        };
        // Hold the correct hierarchy mode even for a rejected reservation, then observe the waiter.
        Supplier<?> first = () -> {
            if (reservationFirst) { hierarchy.acquireShared(owner); }
            else { hierarchy.acquireExclusive(owner); }
            return capture(reservationFirst ? reservation : mutation);
        };
        var result = race(first, reservationFirst ? mutation : reservation);
        Object reserved = reservationFirst ? result.first() : result.second();
        assertThat(reservationFirst ? result.second() : result.first()).isEqualTo("MUTATED");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM folders WHERE id = ?", Boolean.class, folder))
                .isEqualTo(!operation.equals("RESTORE"));
        assertThat(jdbc.queryForObject("SELECT purge_requested_at IS NOT NULL FROM folders WHERE id = ?", Boolean.class, folder))
                .isEqualTo(operation.equals("PURGE"));
        boolean succeeds = operation.equals("TRASH") ? reservationFirst : operation.equals("RESTORE") && !reservationFirst;
        if (succeeds) { assertThat(reserved).isInstanceOf(Session.class); assertCount(1); }
        else { assertThat(reserved).isInstanceOf(RuntimeException.class); assertCount(0); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void completionAndAbandonmentHaveExactlyOnePermanentWinner(boolean completeFirst) throws Exception {
        var open = open();
        UUID firstId = UUID.randomUUID();
        Decision first = completeFirst ? Decision.COMPLETE : Decision.ABANDON;
        Decision second = completeFirst ? Decision.ABANDON : Decision.COMPLETE;
        var result = race(() -> sessions.decide(owner, open.id(), 2, first, firstId),
                () -> sessions.decide(owner, open.id(), 2, second, UUID.randomUUID()));
        assertThat(result.first()).isInstanceOf(Session.class);
        assertThat(result.second()).isInstanceOf(IllegalStateException.class);
        var decided = (Session) result.first();
        assertThat(decided.revision()).isEqualTo(3);
        assertThat(sessions.decide(owner, open.id(), 2, first, firstId)).isEqualTo(decided);
        assertThatThrownBy(() -> sessions.decide(owner, open.id(), 3, first, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT decision_id FROM upload_sessions WHERE id = ?", UUID.class, open.id())).isEqualTo(firstId);
        assertSqlRejected("UPDATE files SET status = 'READY' WHERE id = ?", open.fileId());
    }

    @Test void concurrentDuplicateDecisionsReturnIdenticalOutcome() throws Exception {
        var open = open();
        UUID decision = UUID.randomUUID();
        var result = race(() -> sessions.decide(owner, open.id(), 2, Decision.COMPLETE, decision),
                () -> sessions.decide(owner, open.id(), 2, Decision.COMPLETE, decision));
        assertThat(result.second()).isEqualTo(result.first());
        assertThat(((Session) result.first()).revision()).isEqualTo(3);
    }

    @Test void initiationIdentityAndUploadIdAreOneTimeAndRevisionDoesNotAdvanceOnReplay() {
        var reserved = reserve("key", "a");
        UUID initiation = UUID.randomUUID();
        var initiating = sessions.beginInitiation(owner, reserved.id(), 0, initiation);
        assertThat(initiating.state()).isEqualTo("INITIATING");
        assertThat(initiating.uploadId()).isNull();
        assertThat(sessions.beginInitiation(owner, reserved.id(), 0, initiation)).isEqualTo(initiating);
        assertThatThrownBy(() -> sessions.beginInitiation(owner, reserved.id(), 1, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sessions.recordUploadId(owner, reserved.id(), 1, UUID.randomUUID(), "upload")).isInstanceOf(IllegalStateException.class);
        var open = sessions.recordUploadId(owner, reserved.id(), 1, initiation, "upload");
        assertThat(sessions.recordUploadId(owner, reserved.id(), 1, initiation, "upload")).isEqualTo(open);
        assertThatThrownBy(() -> sessions.recordUploadId(owner, reserved.id(), 2, initiation, "other")).isInstanceOf(IllegalStateException.class);
    }

    @Test void rollbackRestoresReservationAdmissionDecisionAndRevision() {
        assertThatThrownBy(() -> tx.execute(s -> { reserve("rollback", "a"); throw new IllegalStateException("injected"); }))
                .isInstanceOf(IllegalStateException.class);
        assertCount(0);
        var reserved = reserve("key", "a");
        UUID initiation = UUID.randomUUID();
        assertThatThrownBy(() -> tx.execute(s -> {
            sessions.beginInitiation(owner, reserved.id(), 0, initiation);
            throw new IllegalStateException("injected");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT write_started FROM file_storage_objects WHERE file_id = ?", Boolean.class, reserved.fileId())).isFalse();
        assertThat(reserve("key", "a")).isEqualTo(reserved);
        var open = sessions.recordUploadId(owner, reserved.id(), 1,
                sessions.beginInitiation(owner, reserved.id(), 0, initiation).initiationId(), "upload");
        assertThatThrownBy(() -> tx.execute(s -> {
            sessions.decide(owner, open.id(), 2, Decision.COMPLETE, UUID.randomUUID());
            throw new IllegalStateException("injected");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reserve("key", "a")).isEqualTo(open);
        assertThatThrownBy(() -> sessions.decide(owner, open.id(), 1, Decision.ABANDON, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test void unknownForeignAndChangedBindingFailClosed() {
        var reserved = reserve("key", "a");
        UUID foreign = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "Other")).getId();
        assertThatThrownBy(() -> sessions.beginInitiation(foreign, reserved.id(), 0, UUID.randomUUID())).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> sessions.beginInitiation(owner, UUID.randomUUID(), 0, UUID.randomUUID())).isInstanceOf(FileNotFoundException.class);
        assertSqlRejected("UPDATE upload_sessions SET bucket = 'different' WHERE id = ?", reserved.id());
        assertSqlRejected("UPDATE file_storage_objects SET endpoint = 'http://different' WHERE file_id = ?", reserved.fileId());
        jdbc.update("UPDATE files SET storage_key = 'mismatched-' || id WHERE id = ?", reserved.fileId());
        assertThatThrownBy(() -> sessions.beginInitiation(owner, reserved.id(), 0, UUID.randomUUID())).isInstanceOf(FileNotFoundException.class);
    }

    @Test void foreignDestinationAndUnknownOwnerCannotReserve() {
        UUID folder = folders.createFolder(owner, null, "foreign");
        UUID foreign = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "Other")).getId();
        assertThatThrownBy(() -> sessions.reserve(foreign, "key", request(folder, "a"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sessions.reserve(UUID.randomUUID(), "key", request(null, "a"))).isInstanceOf(FileNotFoundException.class);
        assertCount(0);
    }

    @ParameterizedTest @ValueSource(strings = {"../bad", "bad/name", "bad\\name", "\n", " "})
    void invalidNamesCreateNothing(String name) {
        assertThatThrownBy(() -> reserve("key", name)).isInstanceOf(com.vaultdrive.file.exception.InvalidFileNameException.class);
        assertCount(0);
    }

    @Test void invalidDeclarationsAndKeysCreateNothing() {
        for (long size : new long[]{0, -1, MultipartSessionService.MAX_SIZE + 1}) {
            assertThatThrownBy(() -> sessions.reserve(owner, "key", new Request(null, "a", size, "text/plain")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String type : new String[]{"", "x".repeat(256), "text/plain\r\nX: y", "*/*", "not-a-type"}) {
            assertThatThrownBy(() -> sessions.reserve(owner, "key", new Request(null, "a", 1, type))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> reserve("bad key", "a")).isInstanceOf(IllegalArgumentException.class);
        assertCount(0);
    }

    @Test void geometryBoundariesAndDefaultTypeAreCanonical() {
        long[] sizes = {1, 8388608, 8388609, MultipartSessionService.MAX_SIZE};
        for (long size : sizes) {
            var request = new Request(null, "geometry-" + size, size, null);
            var session = sessions.reserve(owner, "geometry-" + size, request);
            assertThat(jdbc.queryForObject("SELECT part_count FROM upload_sessions WHERE id = ?", Integer.class, session.id()))
                    .isEqualTo((int) ((size + MultipartSessionService.PART_SIZE - 1) / MultipartSessionService.PART_SIZE));
            assertThat(sessions.reserve(owner, "geometry-" + size,
                    new Request(null, request.name(), size, " application/octet-stream "))).isEqualTo(session);
            assertSqlRejected("UPDATE upload_sessions SET part_size = 5242880 WHERE id = ?", session.id());
        }
        assertCount(4);
    }

    @Test void legacyAdmissionReceiptExpiryRecoveryCleanupAndReadyCannotAdoptMultipart() {
        var reserved = reserve("key", "a");
        var file = files.findById(reserved.fileId()).orElseThrow();
        assertThatThrownBy(() -> identity.beginWrite(file.getStorageKey())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> receipts.recordSuccess(file)).isInstanceOf(IllegalStateException.class);
        assertThat(receipts.hasSuccess(file)).isFalse();
        assertThat(receipts.recover(file.getId())).isEqualTo(UploadFinalizationTransactions.Outcome.NO_RECEIPT);
        assertThat(expiry.expire(file.getId())).isEqualTo(StaleUploadExpiryTransactions.Outcome.INCONSISTENT);
        assertThat(cleanup.prepare(file.getId()).outcome()).isEqualTo(KnownSuccessCleanupTransactions.Outcome.BLOCKED);
        assertThatThrownBy(() -> metadata.markReady(file)).isInstanceOf(RuntimeException.class);
        assertSqlRejected("UPDATE files SET status = 'READY' WHERE id = ?", file.getId());
        assertSqlRejected("DELETE FROM files WHERE id = ?", file.getId());
        assertSqlRejected("UPDATE file_storage_objects SET upload_protocol = 'SERVER_PUT' WHERE file_id = ?", file.getId());
        assertSqlRejected("UPDATE file_storage_objects SET write_precluded_at = clock_timestamp() WHERE file_id = ?", file.getId());
        assertSqlRejected("UPDATE file_storage_objects SET put_succeeded_at = clock_timestamp() WHERE file_id = ?", file.getId());
        assertSqlRejected("DELETE FROM file_storage_objects WHERE file_id = ?", file.getId());
        assertSqlRejected("DELETE FROM upload_sessions WHERE id = ?", reserved.id());
        assertThat(files.findById(file.getId()).orElseThrow().getStatus()).isEqualTo(FileStatus.UPLOADING);
    }

    @ParameterizedTest @ValueSource(strings = {"TRASH", "PURGE", "VERSION", "FAILED"})
    void decisionsRequireFreshOriginalLifecycle(String barrier) {
        UUID folder = folders.createFolder(owner, null, "destination");
        var r = sessions.reserve(owner, "key", request(folder, "a"));
        UUID init = UUID.randomUUID();
        sessions.beginInitiation(owner, r.id(), 0, init);
        sessions.recordUploadId(owner, r.id(), 1, init, "upload");
        if (barrier.equals("VERSION")) { jdbc.update("UPDATE files SET version = version + 1 WHERE id = ?", r.fileId()); }
        else if (barrier.equals("FAILED")) { jdbc.update("UPDATE files SET status = 'FAILED' WHERE id = ?", r.fileId()); }
        else {
            folders.deleteFolder(owner, folder);
            if (barrier.equals("PURGE")) { folders.requestPermanentDeletion(owner, folder); }
        }
        for (var decision : Decision.values()) {
            assertThatThrownBy(() -> sessions.decide(owner, r.id(), 2, decision, UUID.randomUUID())).isInstanceOf(FileNotFoundException.class);
        }
        assertThat(jdbc.queryForObject("SELECT revision FROM upload_sessions WHERE id = ?", Long.class, r.id())).isEqualTo(2);
        // Sharing M3 reauthorizes even identical reservation retries against current lifecycle.
        assertThatThrownBy(() -> sessions.reserve(owner, "key", request(folder, "a"))).isInstanceOf(RuntimeException.class);
    }

    @Test void directSqlCannotSkipRevisionOpenOrReverseDecision() {
        var r = reserve("key", "a");
        assertSqlRejected("UPDATE upload_sessions SET state = 'OPEN', revision = 1 WHERE id = ?", r.id());
        assertSqlRejected("UPDATE upload_sessions SET revision = revision + 1 WHERE id = ?", r.id());
        assertSqlRejected("UPDATE upload_sessions SET state = 'INITIATING', initiation_id = gen_random_uuid(), revision = 2 WHERE id = ?", r.id());
        var abandoned = sessions.decide(owner, r.id(), 0, Decision.ABANDON, UUID.randomUUID());
        assertThat(abandoned.revision()).isEqualTo(1);
        assertSqlRejected("UPDATE upload_sessions SET state = 'RESERVED', decision_id = NULL, decided_at = NULL, revision = 2 WHERE id = ?", r.id());
        assertSqlRejected("UPDATE files SET status = 'READY' WHERE id = ?", r.fileId());
    }

    @Test void allocationWithoutSessionAndSessionWithUnknownAllocationRollBack() {
        assertThatThrownBy(() -> tx.execute(status -> {
            UUID id = UUID.randomUUID();
            StoredFile file = files.saveAndFlush(new StoredFile(id, owner, null, "orphan", "orphan-" + id, "text/plain", 1));
            identity.reserveMultipart(file);
            return null; // Deferred pairing constraint fails at commit.
        })).isInstanceOf(RuntimeException.class);
        assertCount(0);
        var r = reserve("key", "a");
        assertSqlRejected("""
                INSERT INTO upload_sessions(id,file_id,owner_id,original_file_version,storage_key,storage_namespace,
                    endpoint,region,bucket,file_name,declared_size,declared_type,part_size,part_count,idempotency_key,request_fingerprint)
                SELECT gen_random_uuid(),gen_random_uuid(),owner_id,original_file_version,storage_key,storage_namespace,
                    endpoint,region,bucket,file_name,declared_size,declared_type,part_size,part_count,'unknown',request_fingerprint
                FROM upload_sessions WHERE id = ?
                """, r.id());
        assertCount(1);
    }

    @Test void expiredAuthorizationCannotInitiateButAbandonmentRemainsPossible() {
        var r = reserve("key", "a");
        // Corrupt only this disposable fixture's clock fields; production identity guards are restored before use.
        tx.executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session");
            jdbc.update("UPDATE upload_sessions SET created_at = created_at - interval '25 hours', "
                    + "expires_at = expires_at - interval '25 hours' WHERE id = ?", r.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session");
        });
        assertThatThrownBy(() -> sessions.beginInitiation(owner, r.id(), 0, UUID.randomUUID())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT write_started FROM file_storage_objects WHERE file_id = ?", Boolean.class, r.fileId())).isFalse();
        assertThat(sessions.decide(owner, r.id(), 0, Decision.ABANDON, UUID.randomUUID()).state()).isEqualTo("ABANDONMENT_DECIDED");
        assertSqlRejected("UPDATE files SET status = 'READY' WHERE id = ?", r.fileId());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unfinishedMultipartAllowsOnlyLogicalSealedFolderDisposal(boolean admitted) throws Exception {
        UUID root = folders.createFolder(owner, null, "root");
        var r = sessions.reserve(owner, "key", request(root, "a"));
        if (admitted) { sessions.beginInitiation(owner, r.id(), 0, UUID.randomUUID()); }
        folders.deleteFolder(owner, root);
        folders.requestPermanentDeletion(owner, root);
        UUID event = jdbc.queryForObject("SELECT event_id FROM folder_purge_runs WHERE root_folder_id = ?", UUID.class, root);
        jobs.create(event, com.vaultdrive.job.JobType.FOLDER_PURGE, root);
        var claim = jobs.claimNext(com.vaultdrive.job.JobType.FOLDER_PURGE).orElseThrow();
        assertThat(claim.id()).isEqualTo(event);
        boolean sealed = false;
        for (int i = 0; i < 10 && !sealed; i++) {
            long version = jdbc.queryForObject("SELECT progress_version FROM background_jobs WHERE id = ?", Long.class, event);
            sealed = discovery.discover(claim, version, 20).sealed();
        }
        assertThat(sealed).isTrue();
        var context = mock(com.vaultdrive.worker.JobExecutionContext.class);
        when(context.claim()).thenReturn(claim);
        assertThat(purgeFiles.prepare(context, r.fileId()).logicalOnly()).isTrue();
        assertThat(jdbc.queryForObject("SELECT bytes_deleted_at IS NULL AND never_written_at IS NULL FROM file_purge_work WHERE file_id = ?", Boolean.class, r.fileId())).isTrue();
        assertSqlRejected("DELETE FROM files WHERE id = ?", r.fileId());
    }

    @Test void v26UpgradePreservesExactPutReceiptAndCleanupEvidence() throws Exception {
        String schema = "multipart_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("26").load();
        try {
            flyway.migrate();
            String before;
            try (var c = source.getConnection()) {
                String previous = c.getSchema();
                try {
                    c.setSchema(schema);
                    try (var s = c.createStatement()) {
                        s.execute("INSERT INTO users(id,email,password_hash,display_name) VALUES ('" + owner + "','upgrade@example.com','fixture','Upgrade')");
                        s.execute("INSERT INTO files(id,owner_id,name,storage_key,content_type,size_bytes,status,created_at,updated_at) "
                                + "VALUES ('" + owner + "','" + owner + "','legacy','legacy','text/plain',1,'UPLOADING',clock_timestamp(),clock_timestamp())");
                        s.execute("INSERT INTO file_storage_objects(file_id,owner_id,storage_key,storage_namespace,endpoint,region,bucket) "
                                + "VALUES ('" + owner + "','" + owner + "','legacy','original','http://original.invalid','original','original')");
                        s.execute("UPDATE file_storage_objects SET write_started = true, admitted_file_version = 0");
                        s.execute("UPDATE file_storage_objects SET put_succeeded_at = clock_timestamp()");
                        s.execute("UPDATE files SET status = 'FAILED', version = 1");
                        s.execute("INSERT INTO upload_cleanup_intents(file_id,file_snapshot,put_succeeded_at,reason) "
                                + "SELECT f.id,upload_cleanup_file_snapshot(f),a.put_succeeded_at,'RECEIPT_BACKED_FAILED' "
                                + "FROM files f JOIN file_storage_objects a ON a.file_id = f.id");
                    }
                    before = upgradeSnapshot(c, false);
                } finally { c.setSchema(previous); }
            }
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            try (var c = source.getConnection()) {
                String previous = c.getSchema();
                try {
                    c.setSchema(schema);
                    assertThat(upgradeSnapshot(c, true)).isEqualTo(before);
                    try (var s = c.createStatement(); var r = s.executeQuery("SELECT upload_protocol, (SELECT count(*) FROM upload_sessions) FROM file_storage_objects")) {
                        assertThat(r.next()).isTrue();
                        assertThat(r.getString(1)).isEqualTo("SERVER_PUT");
                        assertThat(r.getInt(2)).isZero();
                    }
                } finally { c.setSchema(previous); }
            }
        } finally {
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(false).load().clean();
        }
    }

    private String upgradeSnapshot(java.sql.Connection c, boolean migrated) throws Exception {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT jsonb_build_array("
                + "(SELECT to_jsonb(a)" + (migrated ? " - 'upload_protocol'" : "") + " FROM file_storage_objects a),"
                + "(SELECT to_jsonb(i) FROM upload_cleanup_intents i),(SELECT to_jsonb(f) FROM files f))::text")) {
            assertThat(r.next()).isTrue();
            return r.getString(1);
        }
    }

    private Session open() {
        var r = reserve("key", "a");
        UUID init = UUID.randomUUID();
        sessions.beginInitiation(owner, r.id(), 0, init);
        return sessions.recordUploadId(owner, r.id(), 1, init, "fixture-upload-id");
    }
    private Session reserve(String key, String name) { return sessions.reserve(owner, key, request(null, name)); }
    private Request request(UUID folder, String name) { return new Request(folder, name, 8388609, "text/plain"); }
    private void assertCount(int count) {
        for (String table : new String[]{"files", "file_storage_objects", "upload_sessions"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE owner_id = ?", Integer.class, owner)).isEqualTo(count);
        }
    }
    private void assertSqlRejected(String sql, UUID id) { assertThatThrownBy(() -> jdbc.update(sql, id)).isInstanceOf(RuntimeException.class); }
    private static Object capture(Supplier<?> operation) {
        try { return operation.get(); } catch (RuntimeException e) { return e; }
    }
    private record Race(Object first, Object second) { }
    /** Commit first only after PostgreSQL confirms the second independent transaction is blocked. */
    private Race race(Supplier<?> first, Supplier<?> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var pid = new AtomicInteger();
        try {
            var winner = pool.submit(() -> capture(() -> tx.execute(status -> {
                Object result = first.get();
                held.countDown();
                await(release);
                // Rejected nested service calls mark rollback-only; the fixture still holds its locks.
                if (result instanceof RuntimeException) { status.setRollbackOnly(); }
                return result;
            })));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var loser = pool.submit(() -> capture(() -> tx.execute(status -> {
                pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                entered.countDown();
                return second.get();
            })));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid.get()));
                if (blocked) { break; }
                Thread.yield();
            }
            assertThat(blocked).as("second transaction waits on the committed winner's lock").isTrue();
            assertThat(loser.isDone()).isFalse();
            release.countDown();
            return new Race(winner.get(8, TimeUnit.SECONDS), loser.get(8, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) { throw new AssertionError("Latch timeout"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
