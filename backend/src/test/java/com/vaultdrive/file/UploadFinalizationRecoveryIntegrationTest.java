package com.vaultdrive.file;

import org.springframework.context.annotation.Import;

import com.vaultdrive.support.HistoricalUploadFixtures;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.vaultdrive.file.UploadFinalizationTransactions.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import(HistoricalUploadFixtures.class)
@SpringBootTest
@ActiveProfiles("test")
class UploadFinalizationRecoveryIntegrationTest {
    @Autowired HistoricalUploadFixtures history;

    private static final String SCHEMA = "upload_success_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired private UploadFinalizationTransactions transactions;
    @Autowired private UploadFinalizationRecovery recovery;
    @Autowired private UploadFinalizationRecoveryProperties properties;
    @Autowired private StoredFileRepository files;
    @Autowired private HierarchyCoordinator hierarchy;
    @Autowired private FolderService folders;
    @Autowired private UserRepository users;
    @Autowired private StaleUploadExpiryTransactions expiry;
    @Autowired private EntityManager em;
    @Autowired private com.vaultdrive.activity.ResourceActivityService activity;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private DataSource dataSource;
    @Autowired private com.vaultdrive.job.BackgroundJobService jobs;
    @Autowired private com.vaultdrive.worker.FolderPurgeDiscoveryService discovery;
    @MockitoBean private ObjectStorageService storage;
    @MockitoBean private S3Client s3;
    private TransactionTemplate tx;
    private UUID owner;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Success")).getId();
    }
    @AfterEach void cleanup() {
        verifyNoInteractions(storage, s3); // No PUT, HEAD, DELETE, or other storage call in receipt/recovery.
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM folder_purge_files WHERE owner_id = :owner").setParameter("owner", owner).executeUpdate();
            em.createNativeQuery("DELETE FROM folder_purge_frontier WHERE owner_id = :owner").setParameter("owner", owner).executeUpdate();
            em.createNativeQuery("DELETE FROM background_jobs WHERE id IN (SELECT event_id FROM folder_purge_runs WHERE owner_id = :owner)")
                    .setParameter("owner", owner).executeUpdate();
            em.createNativeQuery("DELETE FROM outbox_events WHERE aggregate_id IN (SELECT root_folder_id FROM folder_purge_runs WHERE owner_id = :owner)")
                    .setParameter("owner", owner).executeUpdate();
            for (String table : List.of("folder_purge_runs", "file_storage_objects", "files", "folders")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE owner_id = :owner").setParameter("owner", owner).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM users WHERE id = :owner").setParameter("owner", owner).executeUpdate();
        });
    }
    @AfterAll static void removeSchema(@Autowired DataSource source) throws Exception {
        if (!SCHEMA.matches("upload_success_[0-9a-f]{32}")) { throw new AssertionError("Invalid test schema"); }
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }
    }

    @Test void crashAfterPutBeforeReceiptStaysUnknownAndIsNeverAdopted() {
        var file = admitted(null);
        assertThat(transactions.recover(file.getId())).isEqualTo(NO_RECEIPT);
        assertThat(recovery.recoverPage(null).scanned()).isZero();
        assertUploading(file);
        assertThat(scalar("SELECT put_succeeded_at IS NULL FROM file_storage_objects WHERE file_id = :id", file.getId())).isEqualTo(true);
    }

    @Test void committedReceiptSurvivesCrashBeforeReadyAndDuplicateReplay() {
        var file = proven(null);
        String receipt = allocation(file);
        assertUploading(file);
        history.receipt(file);
        assertThat(allocation(file)).isEqualTo(receipt);
        assertThat(transactions.recover(file.getId())).isEqualTo(RECOVERED);
        assertThat(transactions.recover(file.getId())).isEqualTo(ALREADY_READY);
        assertThat(activity.list(owner, com.vaultdrive.activity.ResourceActivityService.ResourceType.FILE, file.getId(), null, null, null, null, 0, 100).content()).hasSize(1);
        assertThat(activity.list(owner, com.vaultdrive.activity.ResourceActivityService.ResourceType.FILE, file.getId(), null, null, null, null, 0, 100).content().getFirst().actorAttribution())
                .isEqualTo(com.vaultdrive.activity.ResourceActivityService.ActorAttribution.UPLOAD_INITIATOR);
        history.receipt(file);
        assertThat(allocation(file)).isEqualTo(receipt);
        assertThat(scalar("SELECT version FROM files WHERE id = :id", file.getId())).isEqualTo(file.getVersion() + 1);
    }

    @Test void readyLostAcknowledgementResolvesWithoutChangingReceiptOrVersion() {
        var file = proven(null);
        assertThat(transactions.recover(file.getId())).isEqualTo(RECOVERED); // Commit succeeded, caller lost the reply.
        String ready = fileSnapshot(file), receipt = allocation(file);
        assertThat(transactions.recover(file.getId())).isEqualTo(ALREADY_READY);
        assertThat(fileSnapshot(file)).isEqualTo(ready);
        assertThat(allocation(file)).isEqualTo(receipt);
    }

    @Test void readyRollbackRetainsCommittedReceiptForExplicitRetry() {
        var file = proven(null);
        String receipt = allocation(file);
        tx.executeWithoutResult(status -> { assertThat(raw().recover(file.getId())).isEqualTo(RECOVERED); status.setRollbackOnly(); });
        assertUploading(file);
        assertThat(activity.list(owner, com.vaultdrive.activity.ResourceActivityService.ResourceType.FILE, file.getId(), null, null, null, null, 0, 100).content()).isEmpty();
        assertThat(allocation(file)).isEqualTo(receipt);
        assertThat(transactions.recover(file.getId())).isEqualTo(RECOVERED);
    }

    @Test void databaseEnforcesReceiptAndAdmissionIdentityAndGeneratesTimestamp() {
        var file = proven(null);
        String receipt = allocation(file);
        for (String change : List.of("put_succeeded_at = NULL", "put_succeeded_at = clock_timestamp() + interval '1 day'",
                "admitted_file_version = admitted_file_version + 1", "admitted_file_version = NULL",
                "admitted_folder_id = gen_random_uuid()", "write_started = false", "write_precluded_at = clock_timestamp()",
                "storage_key = 'replacement'", "owner_id = gen_random_uuid()", "bucket = 'replacement'")) {
            assertThatThrownBy(() -> update("UPDATE file_storage_objects SET " + change + " WHERE file_id = :id", file))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(allocation(file)).isEqualTo(receipt);
        assertThat(scalar("SELECT put_succeeded_at <= clock_timestamp() AND put_succeeded_at > clock_timestamp() - interval '1 minute' "
                + "FROM file_storage_objects WHERE file_id = :id", file.getId())).isEqualTo(true);
    }

    @Test void historicalAdmissionCannotAcquireSnapshotOrReceiptAndUnknownBindingIsBlocked() {
        var file = fixture(null);
        update("UPDATE file_storage_objects SET write_started = true WHERE file_id = :id", file); // Pre-B3 style admission.
        assertThatThrownBy(() -> update("UPDATE file_storage_objects SET admitted_file_version = 0 WHERE file_id = :id", file))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> history.receipt(file)).isInstanceOf(RuntimeException.class);
        update("DELETE FROM file_storage_objects WHERE file_id = :id", file);
        tx.executeWithoutResult(status -> em.createNativeQuery("INSERT INTO file_storage_objects(storage_key, file_id, owner_id, write_started) "
                + "VALUES (:key, :id, :owner, true)").setParameter("key", file.getStorageKey()).setParameter("id", file.getId())
                .setParameter("owner", owner).executeUpdate());
        assertThat(transactions.recover(file.getId())).isEqualTo(NO_RECEIPT);
        assertThatThrownBy(() -> history.receipt(file)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"version", "failed", "missing", "allocation", "destination", "purge-work"})
    void changedMetadataAndConflictingTerminalEvidenceFailClosed(String mutation) {
        var file = proven(null);
        String receipt = allocation(file);
        switch (mutation) {
            case "version" -> update("UPDATE files SET version = version + 1 WHERE id = :id", file);
            case "failed" -> history.status(file, FileStatus.FAILED);
            case "missing" -> update("DELETE FROM files WHERE id = :id", file);
            case "allocation" -> update("UPDATE files SET storage_key = 'different-key' WHERE id = :id", file);
            case "destination" -> {
                UUID folder = folders.createFolder(owner, null, "changed");
                update("UPDATE files SET folder_id = '" + folder + "' WHERE id = :id", file);
            }
            case "purge-work" -> tx.executeWithoutResult(status -> em.createNativeQuery("""
                    INSERT INTO file_purge_work(file_id, owner_id, folder_id, file_version, file_status,
                        file_created_at, file_updated_at, deleted_at, purge_requested_at,
                        storage_namespace, endpoint, region, bucket, storage_key)
                    SELECT f.id, f.owner_id, f.folder_id, f.version, f.status, f.created_at, f.updated_at,
                        f.deleted_at, f.purge_requested_at, a.storage_namespace, a.endpoint, a.region, a.bucket, a.storage_key
                    FROM files f JOIN file_storage_objects a ON a.file_id = f.id WHERE f.id = :id
                    """).setParameter("id", file.getId()).executeUpdate());
        }
        String current = mutation.equals("missing") ? "" : fileSnapshot(file);
        assertThat(transactions.recover(file.getId())).isEqualTo(BLOCKED);
        assertThat(allocation(file)).isEqualTo(receipt);
        if (!mutation.equals("missing")) { assertThat(fileSnapshot(file)).isEqualTo(current); }
        if (mutation.equals("purge-work")) { update("DELETE FROM file_purge_work WHERE file_id = :id", file); }
    }

    @Test void trashedDestinationStillRecordsSuccessAndRestoreCanUnblockIt() {
        UUID folder = folders.createFolder(owner, null, "folder");
        var file = admitted(folder);
        folders.deleteFolder(owner, folder);
        history.receipt(file);
        assertThat(transactions.recover(file.getId())).isEqualTo(BLOCKED);
        assertUploading(file);
        folders.restoreFolder(owner, folder);
        assertThat(transactions.recover(file.getId())).isEqualTo(RECOVERED);
    }

    @Test void duplicateAndConcurrentRecoveryHaveExactlyOneReadyTransition() throws Exception {
        var file = proven(null);
        race(() -> raw().recover(file.getId()), () -> transactions.recover(file.getId()), RECOVERED, ALREADY_READY);
        assertThat(transactions.recover(file.getId())).isEqualTo(ALREADY_READY);
    }


    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recoveryRacingExpiryNeverEstablishesConflictingExpiry(boolean recoveryFirst) throws Exception {
        var file = proven(null);
        update("UPDATE files SET created_at = clock_timestamp() - interval '2 hours' WHERE id = :id", file);
        if (recoveryFirst) {
            race(() -> raw().recover(file.getId()), () -> expiry.expire(file.getId()), RECOVERED,
                    StaleUploadExpiryTransactions.Outcome.ALREADY_RESOLVED);
        } else {
            var rawExpiry = new StaleUploadExpiryTransactions(em, hierarchy, new StaleUploadExpiryProperties(Duration.ofHours(1), 20));
            race(() -> rawExpiry.expire(file.getId()), () -> transactions.recover(file.getId()),
                    StaleUploadExpiryTransactions.Outcome.ADMISSION_WON, RECOVERED);
        }
        assertThat(scalar("SELECT reservation_expired_at IS NULL AND write_precluded_at IS NULL FROM file_storage_objects WHERE file_id = :id",
                file.getId())).isEqualTo(true);
    }

    @ParameterizedTest @ValueSource(strings = {"trash-first", "recovery-trash", "restore-first", "recovery-restore", "purge-first", "recovery-purge"})
    void folderTrashRestorePurgeRacesPreserveBarriers(String order) throws Exception {
        UUID root = folders.createFolder(owner, null, "root");
        UUID child = folders.createFolder(owner, root, "child");
        var file = proven(child);
        boolean restore = order.contains("restore"), purge = order.contains("purge");
        if (restore || purge) { folders.deleteFolder(owner, root); }
        Supplier<Object> mutation = () -> {
            if (restore) { folders.restoreFolder(owner, root); }
            else if (purge) { folders.requestPermanentDeletion(owner, root); }
            else { folders.deleteFolder(owner, root); }
            return null;
        };
        if (order.endsWith("first")) {
            race(mutation, () -> transactions.recover(file.getId()), null, restore ? RECOVERED : BLOCKED);
        } else {
            race(() -> raw().recover(file.getId()), mutation, restore || purge ? BLOCKED : RECOVERED, null);
        }
        if (purge) {
            String snapshot = fileSnapshot(file);
            assertThat(transactions.recover(file.getId())).isEqualTo(BLOCKED);
            assertThat(fileSnapshot(file)).isEqualTo(snapshot);
            // Remove only this test's intent fixtures before generic fixture cleanup.
            tx.executeWithoutResult(status -> {
                em.createNativeQuery("DELETE FROM outbox_events WHERE aggregate_id = :root").setParameter("root", root).executeUpdate();
                em.createNativeQuery("DELETE FROM folder_purge_runs WHERE root_folder_id = :root").setParameter("root", root).executeUpdate();
            });
        }
    }

    @Test void expiryWinnerCannotReceiveSuccessEvidenceOrRecover() {
        var file = fixture(null);
        update("UPDATE files SET created_at = clock_timestamp() - interval '2 hours' WHERE id = :id", file);
        assertThat(expiry.expire(file.getId())).isEqualTo(StaleUploadExpiryTransactions.Outcome.EXPIRED);
        assertThatThrownBy(() -> history.receipt(file)).isInstanceOf(RuntimeException.class);
        assertThat(transactions.recover(file.getId())).isEqualTo(NO_RECEIPT);
    }

    @Test void boundedKeysetsAdvancePastBlockedAndResolvedReceipts() {
        assertThat(properties.pageSize()).isEqualTo(20);
        for (int i = 0; i < 23; i++) {
            var file = proven(null);
            if (i % 2 == 0) { history.status(file, FileStatus.FAILED); }
        }
        var first = recovery.recoverPage(null);
        assertThat(first.scanned()).isEqualTo(20);
        assertThat(first.blocked() + first.recovered()).isEqualTo(20);
        var second = recovery.recoverPage(first.nextCursor());
        assertThat(second.scanned()).isEqualTo(3);
        assertThat(recovery.recoverPage(second.nextCursor()).exhausted()).isTrue();
        assertThat(recovery.recoverPage(null).alreadyReady()).isPositive();
        assertThatThrownBy(() -> tx.execute(status -> recovery.recoverPage(null))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new UploadFinalizationRecoveryProperties(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadFinalizationRecoveryProperties(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"hierarchy", "file", "allocation", "scan"})
    void lockTimeoutRollsBackAdvancesProgressAndAllowsLaterExplicitRetry(String lock) throws Exception {
        var file = proven(null);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var locker = pool.submit(() -> tx.executeWithoutResult(status -> {
                switch (lock) {
                    case "hierarchy" -> hierarchy.acquireExclusive(owner);
                    case "scan" -> em.createNativeQuery("LOCK TABLE file_storage_objects IN ACCESS EXCLUSIVE MODE").executeUpdate();
                    default -> em.createNativeQuery("SELECT " + (lock.equals("file") ? "id FROM files WHERE id" : "file_id FROM file_storage_objects WHERE file_id")
                            + " = :id FOR UPDATE").setParameter("id", file.getId()).getResultList();
                }
                held.countDown(); await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var page = recovery.recoverPage(null);
            assertThat(page.scanFailed()).isEqualTo(lock.equals("scan") ? 1 : 0);
            assertThat(page.failed()).isEqualTo(lock.equals("scan") ? 0 : 1);
            assertThat(page.nextCursor()).isEqualTo(lock.equals("scan") ? null : file.getId());
            release.countDown(); locker.get(5, TimeUnit.SECONDS);
            assertUploading(file);
            assertThat(transactions.recover(file.getId())).isEqualTo(RECOVERED);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void statementTimeoutRollsBackReadyAndKeepsReceiptForExplicitRetry() {
        var file = proven(null);
        String receipt = allocation(file);
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("CREATE FUNCTION stall_test_ready() RETURNS trigger LANGUAGE plpgsql AS $$ "
                    + "BEGIN PERFORM pg_sleep(6); RETURN NEW; END; $$").executeUpdate();
            em.createNativeQuery("CREATE TRIGGER stall_test_ready BEFORE UPDATE ON files "
                    + "FOR EACH ROW EXECUTE FUNCTION stall_test_ready()").executeUpdate();
        });
        try {
            var page = recovery.recoverPage(null);
            assertThat(page.failed()).isEqualTo(1); assertThat(page.nextCursor()).isEqualTo(file.getId());
            assertUploading(file); assertThat(allocation(file)).isEqualTo(receipt);
        } finally {
            tx.executeWithoutResult(status -> {
                em.createNativeQuery("DROP TRIGGER stall_test_ready ON files").executeUpdate();
                em.createNativeQuery("DROP FUNCTION stall_test_ready()").executeUpdate();
            });
        }
        assertThat(transactions.recover(file.getId())).isEqualTo(RECOVERED);
    }

    @Test void pageHardCapAndCandidateTransactionAreIndependentOfCallerRollback() {
        for (int i = 0; i < 101; i++) { proven(null); }
        var max = new UploadFinalizationTransactions(em, hierarchy, files, new UploadFinalizationRecoveryProperties(100), activity);
        List<UUID> selected = tx.execute(status -> max.scan(null));
        assertThat(selected).hasSize(100);
        UUID id = transactions.scan(null).getFirst();
        tx.executeWithoutResult(status -> {
            assertThat(transactions.recover(id)).isEqualTo(RECOVERED); status.setRollbackOnly();
        });
        assertThat(transactions.recover(id)).isEqualTo(ALREADY_READY);
    }

    @Test void v24UpgradeRetainsHistoricalAllocationsWithoutFabricatingSuccess() throws Exception {
        String schema = "upload_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var old = org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("24").load();
        try {
            old.migrate();
            try (var c = dataSource.getConnection()) {
                String previous = c.getSchema();
                try {
                    c.setSchema(schema);
                    try (var q = c.createStatement()) {
                        q.executeUpdate("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,write_started) "
                                + "VALUES ('historical-unknown',gen_random_uuid(),gen_random_uuid(),true)");
                        q.executeUpdate("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,storage_namespace,endpoint,region,bucket,write_started) "
                                + "VALUES ('historical-known',gen_random_uuid(),gen_random_uuid(),'original','http://historical.invalid','original','original',true), "
                                + "('unused',gen_random_uuid(),gen_random_uuid(),'original','http://historical.invalid','original','original',false)");
                    }
                } finally { c.setSchema(previous); }
            }
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            try (var c = dataSource.getConnection()) {
                String previous = c.getSchema();
                try {
                    c.setSchema(schema);
                    try (var q = c.createStatement(); var r = q.executeQuery("SELECT count(*), count(admitted_file_version), "
                            + "count(admitted_folder_id), count(put_succeeded_at) FROM file_storage_objects")) {
                        assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(3);
                        for (int i = 2; i <= 4; i++) { assertThat(r.getInt(i)).isZero(); }
                    }
                    try (var q = c.createStatement(); var r = q.executeQuery("SELECT write_started, storage_namespace "
                            + "FROM file_storage_objects WHERE storage_key = 'historical-unknown'")) {
                        assertThat(r.next()).isTrue(); assertThat(r.getBoolean(1)).isTrue(); assertThat(r.getString(2)).isNull();
                    }
                } finally { c.setSchema(previous); }
            }
        } finally {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).cleanDisabled(false).load().clean();
        }
    }

    @Test void sealedPurgeMembershipIsUnchangedAndReceiptDoesNotAuthorizePurgeOrRecovery() {
        UUID root = folders.createFolder(owner, null, "root");
        var file = proven(root);
        folders.deleteFolder(owner, root); folders.requestPermanentDeletion(owner, root);
        UUID event = (UUID) scalar("SELECT event_id FROM folder_purge_runs WHERE root_folder_id = :id", root);
        jobs.create(event, com.vaultdrive.job.JobType.FOLDER_PURGE, root);
        var claim = jobs.claimNext(com.vaultdrive.job.JobType.FOLDER_PURGE).orElseThrow();
        boolean sealed = false;
        for (int i = 0; i < 10 && !sealed; i++) {
            long version = ((Number) scalar("SELECT progress_version FROM background_jobs WHERE id = :id", event)).longValue();
            sealed = discovery.discover(claim, version, 20).sealed();
        }
        assertThat(sealed).isTrue();
        String member = (String) scalar("SELECT to_jsonb(m)::text FROM folder_purge_files m WHERE file_id = :id", file.getId());
        String current = fileSnapshot(file), receipt = allocation(file);
        assertThat(transactions.recover(file.getId())).isEqualTo(BLOCKED);
        assertThat(transactions.recover(file.getId())).isEqualTo(BLOCKED);
        assertThat(fileSnapshot(file)).isEqualTo(current); assertThat(allocation(file)).isEqualTo(receipt);
        assertThat(scalar("SELECT to_jsonb(m)::text FROM folder_purge_files m WHERE file_id = :id", file.getId())).isEqualTo(member);
        assertThat(scalar("SELECT EXISTS (SELECT 1 FROM file_purge_work WHERE file_id = :id)", file.getId())).isEqualTo(false);
    }

    private UploadFinalizationTransactions raw() { return new UploadFinalizationTransactions(em, hierarchy, files, properties, activity); }
    /** Hold the first real operation's transaction open; observe PostgreSQL blocking before releasing it. */
    private void race(Supplier<?> first, Supplier<?> second, Object firstExpected, Object secondExpected) throws Exception {
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2); var pid = new AtomicInteger();
        try {
            var winner = pool.submit(() -> tx.execute(status -> {
                pid.set(((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue());
                Object result = first.get(); held.countDown(); await(release); return result;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var contender = pool.submit(second::get);
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            boolean blocked;
            do {
                blocked = tx.execute(status -> (Boolean) em.createNativeQuery(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid)))")
                        .setParameter("pid", pid.get()).getSingleResult());
                if (blocked) { break; }
                Thread.yield();
            } while (System.nanoTime() < deadline);
            assertThat(blocked).as("competing operation waits for the first PostgreSQL transaction").isTrue();
            assertThat(contender.isDone()).isFalse();
            release.countDown();
            assertThat(winner.get(5, TimeUnit.SECONDS)).isEqualTo(firstExpected);
            assertThat(contender.get(5, TimeUnit.SECONDS)).isEqualTo(secondExpected);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    private StoredFile fixture(UUID folder) {
        UUID id = UUID.randomUUID();
        return history.reservation(new StoredFile(id, owner, folder, id + ".txt", "disposable/" + owner + "/" + id, "text/plain", 1));
    }
    private StoredFile admitted(UUID folder) {
        var file = fixture(folder); history.admission(file.getStorageKey()); return file;
    }
    private StoredFile proven(UUID folder) {
        var file = admitted(folder); history.receipt(file); return file;
    }
    private void update(String sql, StoredFile file) {
        tx.executeWithoutResult(status -> em.createNativeQuery(sql).setParameter("id", file.getId()).executeUpdate());
    }
    private Object scalar(String sql, UUID id) {
        return tx.execute(status -> em.createNativeQuery(sql).setParameter("id", id).getSingleResult());
    }
    private String allocation(StoredFile file) { return (String) scalar("SELECT to_jsonb(a)::text FROM file_storage_objects a WHERE file_id = :id", file.getId()); }
    private String fileSnapshot(StoredFile file) { return (String) scalar("SELECT to_jsonb(f)::text FROM files f WHERE id = :id", file.getId()); }
    private void assertUploading(StoredFile file) { assertThat(scalar("SELECT status FROM files WHERE id = :id", file.getId())).isEqualTo("UPLOADING"); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Latch timed out"); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
