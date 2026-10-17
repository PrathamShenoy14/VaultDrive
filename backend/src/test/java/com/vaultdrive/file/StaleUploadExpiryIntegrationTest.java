package com.vaultdrive.file;

import com.vaultdrive.folder.FolderService;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.JobType;
import com.vaultdrive.storage.FileStorageIdentityService;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.S3ObjectStorageService;
import com.vaultdrive.storage.StorageBinding;
import com.vaultdrive.storage.config.S3StorageProperties;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import com.vaultdrive.worker.FolderPurgeDiscoveryService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.vaultdrive.file.StaleUploadExpiryTransactions.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class StaleUploadExpiryIntegrationTest {
    private static final String SCHEMA = "upload_expiry_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @Autowired private StaleUploadExpiry expiry;
    @MockitoSpyBean private StaleUploadExpiryTransactions transactions;
    @Autowired private StaleUploadExpiryProperties properties;
    @Autowired private HierarchyCoordinator hierarchy;
    @Autowired private FileMetadataService metadata;
    @Autowired private FileStorageIdentityService identity;
    @Autowired private FolderService folders;
    @Autowired private BackgroundJobService jobs;
    @Autowired private FolderPurgeDiscoveryService discovery;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private EntityManager em;
    @Autowired private StorageBinding binding;
    @Autowired private S3StorageProperties s3Properties;
    @MockitoBean private ObjectStorageService storage;
    @MockitoSpyBean private S3Client s3;
    private TransactionTemplate tx;
    private UUID owner;

    @AfterAll static void removeDisposableSchema(@Autowired DataSource source) throws Exception {
        if (!SCHEMA.matches("upload_expiry_[0-9a-f]{32}")) { throw new AssertionError("Invalid test schema"); }
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Expiry")).getId();
        clearInvocations(storage, s3);
    }

    @AfterEach void cleanup() {
        verifyNoInteractions(storage, s3);
        reset(transactions);
        tx.executeWithoutResult(status -> {
            for (String table : List.of("folder_purge_files", "folder_purge_frontier")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE owner_id = :owner")
                        .setParameter("owner", owner).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM background_jobs WHERE id IN "
                    + "(SELECT event_id FROM folder_purge_runs WHERE owner_id = :owner)")
                    .setParameter("owner", owner).executeUpdate();
            em.createNativeQuery("DELETE FROM outbox_events WHERE aggregate_id IN "
                    + "(SELECT root_folder_id FROM folder_purge_runs WHERE owner_id = :owner)")
                    .setParameter("owner", owner).executeUpdate();
            for (String table : List.of("folder_purge_runs", "file_storage_objects", "files", "folders")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE owner_id = :owner")
                        .setParameter("owner", owner).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM users WHERE id = :owner").setParameter("owner", owner).executeUpdate();
        });
    }

    @Test void expiresOnlyAgedUnusedUploadingAndRetainsImmutableEvidenceAndNames() {
        assertThat(properties.minimumAge()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.pageSize()).isEqualTo(20);
        var stale = fixture(null, true);
        var fresh = fixture(null, false);
        var admitted = fixture(null, true);
        identity.beginWrite(admitted.getStorageKey());
        var failed = fixture(null, true);
        metadata.markFailed(failed);
        var ready = fixture(null, true);
        identity.beginWrite(ready.getStorageKey()); metadata.markReady(ready);
        String readyBefore = fileSnapshot(ready.getId());
        String failedBefore = allocationSnapshot(failed.getId());
        var page = expiry.expirePage(null);
        assertThat(page.scanned()).isEqualTo(2);
        assertThat(page.expired()).isEqualTo(1);
        assertThat(page.admissionWon()).isEqualTo(1);
        assertExpired(stale);
        assertThat(transactions.expire(stale.getId())).isEqualTo(ALREADY_RESOLVED);
        String evidence = allocationSnapshot(stale.getId());
        assertThat(transactions.expire(stale.getId())).isEqualTo(ALREADY_RESOLVED);
        assertThat(allocationSnapshot(stale.getId())).isEqualTo(evidence);
        assertThat(transactions.expire(fresh.getId())).isEqualTo(ALREADY_RESOLVED);
        assertThat(transactions.expire(ready.getId())).isEqualTo(ALREADY_RESOLVED);
        assertThat(transactions.expire(failed.getId())).isEqualTo(ALREADY_RESOLVED);
        assertThat(fileSnapshot(ready.getId())).isEqualTo(readyBefore);
        assertThat(allocationSnapshot(failed.getId())).isEqualTo(failedBefore);
        var replacement = metadata.createUploading(new StoredFile(UUID.randomUUID(), owner, null, stale.getName(),
                "disposable/" + UUID.randomUUID(), "text/plain", 1));
        assertThat(replacement.getStorageKey()).isNotEqualTo(stale.getStorageKey());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> em.createNativeQuery(
                "UPDATE file_storage_objects SET expired_file_version = expired_file_version + 1 WHERE file_id = :id")
                .setParameter("id", stale.getId()).executeUpdate())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> em.createNativeQuery(
                "UPDATE file_storage_objects SET write_precluded_at = NULL WHERE file_id = :id")
                .setParameter("id", stale.getId()).executeUpdate())).isInstanceOf(RuntimeException.class);
        assertThat(allocationSnapshot(stale.getId())).isEqualTo(evidence);
        assertThat(scalar("SELECT count(*) FROM outbox_events")).isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM background_jobs")).isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM file_purge_work")).isEqualTo(0L);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void admissionVersusExpiryBothWinnerOrders(boolean admissionFirst) throws Exception {
        var file = fixture(null, true);
        if (admissionFirst) {
            race(() -> { identity.beginWrite(file.getStorageKey()); return null; },
                    () -> transactions.expire(file.getId()), null, ADMISSION_WON);
            assertUploadingUnprecluded(file);
            assertThat(scalar("SELECT write_started FROM file_storage_objects WHERE file_id = :id", file.getId())).isEqualTo(true);
        } else {
            race(() -> raw().expire(file.getId()), () -> {
                assertThatThrownBy(() -> identity.beginWrite(file.getStorageKey())).isInstanceOf(RuntimeException.class);
                // Real adapter must reject before reaching the SDK PUT, even for a delayed request.
                var adapter = new S3ObjectStorageService(s3, s3Properties, identity, binding);
                assertThatThrownBy(() -> adapter.upload(file.getStorageKey(), new ByteArrayInputStream(new byte[]{1}), 1, "text/plain"))
                        .isInstanceOf(RuntimeException.class);
                return null;
            }, EXPIRED, null);
            assertExpired(file);
        }
    }

    @Test void concurrentExpiryHasOneWinnerAndReplayPreservesReceipt() throws Exception {
        var file = fixture(null, true);
        race(() -> raw().expire(file.getId()), () -> transactions.expire(file.getId()), EXPIRED, ALREADY_RESOLVED);
        assertExpired(file);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiryVersusReadyFinalizationBothWinnerOrders(boolean readyFirst) throws Exception {
        var file = fixture(null, true);
        if (readyFirst) {
            // The metadata boundary's CAS is deliberately tested independently of PUT/admission.
            race(() -> { metadata.markReady(file); return null; }, () -> transactions.expire(file.getId()), null, ALREADY_RESOLVED);
            assertThat(scalar("SELECT status FROM files WHERE id = :id", file.getId())).isEqualTo("READY");
            assertThat(scalar("SELECT reservation_expired_at IS NULL FROM file_storage_objects WHERE file_id = :id", file.getId())).isEqualTo(true);
        } else {
            race(() -> raw().expire(file.getId()), () -> {
                assertThatThrownBy(() -> metadata.markReady(file)).isInstanceOf(RuntimeException.class); return null;
            }, EXPIRED, null);
            assertExpired(file);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void admittedNormalFinalizationIsNeverExpiredInEitherOrder(boolean readyFirst) throws Exception {
        var file = fixture(null, true);
        identity.beginWrite(file.getStorageKey());
        if (readyFirst) {
            race(() -> { metadata.markReady(file); return null; }, () -> transactions.expire(file.getId()), null, ALREADY_RESOLVED);
        } else {
            race(() -> raw().expire(file.getId()), () -> { metadata.markReady(file); return null; }, ADMISSION_WON, null);
        }
        assertThat(scalar("SELECT status FROM files WHERE id = :id", file.getId())).isEqualTo("READY");
        assertThat(scalar("SELECT write_started AND write_precluded_at IS NULL AND reservation_expired_at IS NULL "
                + "FROM file_storage_objects WHERE file_id = :id", file.getId())).isEqualTo(true);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinaryFailureAndExpiryKeepDistinctEvidenceInBothOrders(boolean failureFirst) throws Exception {
        var file = fixture(null, true);
        if (failureFirst) {
            race(() -> { metadata.markFailed(file); return null; }, () -> transactions.expire(file.getId()), null, ALREADY_RESOLVED);
            assertThat(scalar("SELECT write_precluded_at IS NULL AND reservation_expired_at IS NULL "
                    + "FROM file_storage_objects WHERE file_id = :id", file.getId())).isEqualTo(true);
        } else {
            race(() -> raw().expire(file.getId()), () -> {
                assertThatThrownBy(() -> metadata.markFailed(file)).isInstanceOf(RuntimeException.class); return null;
            }, EXPIRED, null);
            assertExpired(file);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"trash-first", "expiry-trash", "restore-first", "expiry-restore", "purge-first", "expiry-purge"})
    void folderMutationRacesRevalidateAncestry(String order) throws Exception {
        UUID root = folders.createFolder(owner, null, "root");
        UUID child = folders.createFolder(owner, root, "child");
        var file = fixture(child, true);
        boolean restore = order.contains("restore"), purge = order.contains("purge");
        if (restore || purge) { folders.deleteFolder(owner, root); }
        Supplier<Object> mutation = () -> {
            if (restore) { folders.restoreFolder(owner, root); }
            else if (purge) { folders.requestPermanentDeletion(owner, root); }
            else { folders.deleteFolder(owner, root); }
            return null;
        };
        if (order.endsWith("first")) {
            race(mutation, () -> transactions.expire(file.getId()), null,
                    restore ? EXPIRED : purge ? DEFERRED_TO_PURGE : INCONSISTENT);
        } else {
            race(() -> raw().expire(file.getId()), mutation, restore || purge ? INCONSISTENT : EXPIRED, null);
        }
        if (order.equals("restore-first") || order.equals("expiry-trash")) { assertExpired(file); }
        else { assertUploadingUnprecluded(file); }
        if (purge) {
            String before = fileSnapshot(file.getId());
            assertThat(transactions.expire(file.getId())).isEqualTo(DEFERRED_TO_PURGE);
            assertThat(fileSnapshot(file.getId())).isEqualTo(before);
        }
    }

    @Test void failureAfterPreclusionRollsBackAndExplicitRetrySucceeds() {
        var file = fixture(null, true);
        String before = allocationSnapshot(file.getId());
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("""
                    CREATE FUNCTION reject_test_expiry() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF EXISTS (SELECT 1 FROM file_storage_objects WHERE file_id = NEW.id AND write_precluded_at IS NOT NULL) THEN
                            RAISE EXCEPTION 'test rollback after preclusion';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """).executeUpdate();
            em.createNativeQuery("CREATE TRIGGER reject_test_expiry BEFORE UPDATE ON files FOR EACH ROW EXECUTE FUNCTION reject_test_expiry()")
                    .executeUpdate();
        });
        try {
            var page = expiry.expirePage(null);
            assertThat(page.failed()).isEqualTo(1);
            assertThat(page.nextCursor()).isEqualTo(file.getId());
            assertThat(allocationSnapshot(file.getId())).isEqualTo(before);
            assertUploadingUnprecluded(file);
        } finally {
            tx.executeWithoutResult(status -> {
                em.createNativeQuery("DROP TRIGGER reject_test_expiry ON files").executeUpdate();
                em.createNativeQuery("DROP FUNCTION reject_test_expiry()").executeUpdate();
            });
        }
        assertThat(transactions.expire(file.getId())).isEqualTo(EXPIRED);
        assertExpired(file);
    }

    @Test void evidenceFailureAlsoRollsBackMetadataAndPreclusion() {
        var file = fixture(null, true);
        tx.executeWithoutResult(status -> em.createNativeQuery(
                "ALTER TABLE file_storage_objects ADD CONSTRAINT reject_test_receipt CHECK (reservation_expired_at IS NULL)").executeUpdate());
        try {
            assertThat(expiry.expirePage(null).failed()).isEqualTo(1);
            assertUploadingUnprecluded(file);
        } finally {
            tx.executeWithoutResult(status -> em.createNativeQuery(
                    "ALTER TABLE file_storage_objects DROP CONSTRAINT reject_test_receipt").executeUpdate());
        }
        identity.beginWrite(file.getStorageKey()); // Rolled-back preclusion did not consume admission.
    }

    @Test void statementTimeoutAfterPreclusionRollsBackWholeCandidate() {
        var file = fixture(null, true);
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("""
                    CREATE FUNCTION stall_test_expiry() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN PERFORM pg_sleep(6); RETURN NEW; END; $$
                    """).executeUpdate();
            em.createNativeQuery("CREATE TRIGGER stall_test_expiry BEFORE UPDATE ON files FOR EACH ROW EXECUTE FUNCTION stall_test_expiry()")
                    .executeUpdate();
        });
        try {
            var page = expiry.expirePage(null);
            assertThat(page.failed()).isEqualTo(1);
            assertThat(page.nextCursor()).isEqualTo(file.getId());
            assertUploadingUnprecluded(file);
        } finally {
            tx.executeWithoutResult(status -> {
                em.createNativeQuery("DROP TRIGGER stall_test_expiry ON files").executeUpdate();
                em.createNativeQuery("DROP FUNCTION stall_test_expiry()").executeUpdate();
            });
        }
        assertThat(transactions.expire(file.getId())).isEqualTo(EXPIRED);
    }

    @Test void sealedFolderManifestAndAllLifecycleEvidenceRemainUnchanged() {
        UUID root = folders.createFolder(owner, null, "root");
        var file = fixture(root, true);
        folders.deleteFolder(owner, root); folders.requestPermanentDeletion(owner, root);
        UUID event = (UUID) scalar("SELECT event_id FROM folder_purge_runs WHERE root_folder_id = :id", root);
        jobs.create(event, JobType.FOLDER_PURGE, root);
        var claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        boolean sealed = false;
        for (int i = 0; i < 10 && !sealed; i++) {
            long version = ((Number) scalar("SELECT progress_version FROM background_jobs WHERE id = :id", event)).longValue();
            sealed = discovery.discover(claim, version, 20).sealed();
        }
        assertThat(sealed).isTrue();
        String manifest = (String) scalar("SELECT to_jsonb(m)::text FROM folder_purge_files m WHERE file_id = :id", file.getId());
        String before = fileSnapshot(file.getId()), allocation = allocationSnapshot(file.getId());
        for (int i = 0; i < 2; i++) { assertThat(expiry.expirePage(null).deferredToPurge()).isEqualTo(1); }
        assertThat(fileSnapshot(file.getId())).isEqualTo(before);
        assertThat(allocationSnapshot(file.getId())).isEqualTo(allocation);
        assertThat(scalar("SELECT to_jsonb(m)::text FROM folder_purge_files m WHERE file_id = :id", file.getId())).isEqualTo(manifest);
        assertThat(scalar("SELECT count(*) FROM file_purge_work")).isEqualTo(0L);
    }

    @Test void evidenceSurvivesMetadataRemovalAndAllocationPreventsKeyReuse() {
        var file = fixture(null, true);
        assertThat(transactions.expire(file.getId())).isEqualTo(EXPIRED);
        String before = allocationSnapshot(file.getId());
        tx.executeWithoutResult(status -> em.createNativeQuery("DELETE FROM files WHERE id = :id")
                .setParameter("id", file.getId()).executeUpdate());
        assertThat(allocationSnapshot(file.getId())).isEqualTo(before);
        assertThat(transactions.expire(file.getId())).isEqualTo(INCONSISTENT); // Absence is no invented success.
        assertThatThrownBy(() -> metadata.createUploading(new StoredFile(UUID.randomUUID(), owner, null,
                file.getName(), file.getStorageKey(), "text/plain", 1))).isInstanceOf(RuntimeException.class);
        assertThat(allocationSnapshot(file.getId())).isEqualTo(before);
    }

    @Test void retainedHistoricalBindingAndExistingPreclusionArePreserved() {
        var file = fixture(null, true);
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM file_storage_objects WHERE file_id = :id").setParameter("id", file.getId()).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO file_storage_objects(storage_key, file_id, owner_id, storage_namespace, endpoint, region, bucket, write_precluded_at)
                    VALUES (:key, :id, :owner, 'original', 'http://historical.invalid', 'original', 'original', clock_timestamp())
                    """).setParameter("key", file.getStorageKey()).setParameter("id", file.getId()).setParameter("owner", owner).executeUpdate();
        });
        Object preclusion = scalar("SELECT write_precluded_at FROM file_storage_objects WHERE file_id = :id", file.getId());
        assertThat(transactions.expire(file.getId())).isEqualTo(EXPIRED);
        assertExpired(file);
        assertThat(scalar("SELECT write_precluded_at FROM file_storage_objects WHERE file_id = :id", file.getId())).isEqualTo(preclusion);
        assertThat(scalar("SELECT storage_namespace FROM file_storage_objects WHERE file_id = :id", file.getId())).isEqualTo("original");
    }

    @ParameterizedTest @ValueSource(strings = {"hierarchy", "file", "allocation", "scan"})
    void lockTimeoutRollsBackAdvancesProgressAndAllowsLaterExplicitRetry(String lock) throws Exception {
        var file = fixture(null, true);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var locker = pool.submit(() -> tx.executeWithoutResult(status -> {
                switch (lock) {
                    case "hierarchy" -> hierarchy.acquireExclusive(owner);
                    case "scan" -> em.createNativeQuery("LOCK TABLE files IN ACCESS EXCLUSIVE MODE").executeUpdate();
                    default -> em.createNativeQuery("SELECT " + (lock.equals("file") ? "id FROM files WHERE id" : "file_id FROM file_storage_objects WHERE file_id")
                            + " = :id FOR UPDATE").setParameter("id", file.getId()).getResultList();
                }
                held.countDown(); await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var page = expiry.expirePage(null);
            assertThat(page.scanFailed()).isEqualTo(lock.equals("scan") ? 1 : 0);
            assertThat(page.failed()).isEqualTo(lock.equals("scan") ? 0 : 1);
            assertThat(page.nextCursor()).isEqualTo(lock.equals("scan") ? null : file.getId());
            release.countDown(); locker.get(5, TimeUnit.SECONDS);
            assertUploadingUnprecluded(file);
            assertThat(transactions.expire(file.getId())).isEqualTo(EXPIRED);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "owner", "key", "file", "binding"})
    void invalidAllocationFailsClosed(String corruption) {
        var file = fixture(null, true);
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM file_storage_objects WHERE file_id = :id").setParameter("id", file.getId()).executeUpdate();
            if (!corruption.equals("missing")) {
                em.createNativeQuery("""
                        INSERT INTO file_storage_objects(storage_key, file_id, owner_id, storage_namespace, endpoint, region, bucket, write_started)
                        VALUES (:key, :file, :owner, :namespace, :endpoint, :region, :bucket, :started)
                        """).setParameter("key", corruption.equals("key") ? "disposable/" + UUID.randomUUID() : file.getStorageKey())
                        .setParameter("file", corruption.equals("file") ? UUID.randomUUID() : file.getId())
                        .setParameter("owner", corruption.equals("owner") ? UUID.randomUUID() : owner)
                        .setParameter("namespace", corruption.equals("binding") ? null : "old")
                        .setParameter("endpoint", corruption.equals("binding") ? null : "http://historical.invalid")
                        .setParameter("region", corruption.equals("binding") ? null : "old")
                        .setParameter("bucket", corruption.equals("binding") ? null : "old")
                        .setParameter("started", corruption.equals("binding")).executeUpdate();
            }
        });
        assertThat(transactions.expire(file.getId())).isEqualTo(INCONSISTENT);
        assertThat(scalar("SELECT status FROM files WHERE id = :id", file.getId())).isEqualTo("UPLOADING");
        // The intentionally wrong-owner allocation is also this test's disposable fixture.
        tx.executeWithoutResult(status -> em.createNativeQuery("DELETE FROM file_storage_objects WHERE storage_key = :key")
                .setParameter("key", file.getStorageKey()).executeUpdate());
    }

    @Test void invalidAncestryAndDirectTrashFailClosed() {
        UUID root = folders.createFolder(owner, null, "root");
        UUID child = folders.createFolder(owner, root, "child");
        var cyclic = fixture(child, true);
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE folders SET parent_folder_id = :child WHERE id = :root")
                .setParameter("child", child).setParameter("root", root).executeUpdate());
        assertThat(transactions.expire(cyclic.getId())).isEqualTo(INCONSISTENT);
        assertUploadingUnprecluded(cyclic);
        var trashed = fixture(null, true);
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE files SET deleted_at = clock_timestamp() WHERE id = :id")
                .setParameter("id", trashed.getId()).executeUpdate());
        assertThat(transactions.expire(trashed.getId())).isEqualTo(INCONSISTENT);
        assertThat(transactions.expire(UUID.randomUUID())).isEqualTo(INCONSISTENT);
    }

    @Test void freshValidationUsesCurrentVersionAndDatabaseAgeInsteadOfSelectionSnapshot() {
        var file = fixture(null, true);
        assertThat(transactions.scan(null)).contains(file.getId());
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE files SET version = version + 7 WHERE id = :id")
                .setParameter("id", file.getId()).executeUpdate());
        assertThat(transactions.expire(file.getId())).isEqualTo(EXPIRED);
        assertThat(scalar("SELECT expired_file_version FROM file_storage_objects WHERE file_id = :id", file.getId())).isEqualTo(8L);
        assertThatThrownBy(() -> metadata.markReady(file)).isInstanceOf(RuntimeException.class);
        var noLongerOld = fixture(null, true);
        assertThat(transactions.scan(null)).contains(noLongerOld.getId());
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE files SET created_at = clock_timestamp() WHERE id = :id")
                .setParameter("id", noLongerOld.getId()).executeUpdate());
        assertThat(transactions.expire(noLongerOld.getId())).isEqualTo(ALREADY_RESOLVED);
        assertUploadingUnprecluded(noLongerOld);
    }

    @Test void paginationAdvancesOverFailuresAndBlockersAndDoesNotSkipLaterCandidates() {
        for (int i = 0; i < 23; i++) { fixture(null, true); }
        var ids = transactions.scan(null);
        UUID broken = ids.getFirst(), blocked = ids.get(1);
        doThrow(new IllegalStateException("test-only")).when(transactions).expire(broken);
        String key = (String) scalar("SELECT storage_key FROM files WHERE id = :id", blocked);
        identity.beginWrite(key);
        var first = expiry.expirePage(null);
        assertThat(first.scanned()).isEqualTo(20);
        assertThat(first.failed()).isEqualTo(1);
        assertThat(first.admissionWon()).isEqualTo(1);
        assertThat(first.expired()).isEqualTo(18);
        assertThat(first.scanFailed()).isZero();
        var second = expiry.expirePage(first.nextCursor());
        assertThat(second.scanned()).isEqualTo(3);
        assertThat(second.expired()).isEqualTo(3);
        assertThat(expiry.expirePage(second.nextCursor()).exhausted()).isTrue();
    }

    @Test void maximumBoundsAndIndependentTransactions() {
        assertThatThrownBy(() -> new StaleUploadExpiryProperties(Duration.ZERO, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaleUploadExpiryProperties(Duration.ofDays(366), 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaleUploadExpiryProperties(Duration.ofHours(1), 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaleUploadExpiryProperties(Duration.ofHours(1), 0)).isInstanceOf(IllegalArgumentException.class);
        for (int i = 0; i < 101; i++) { fixture(null, true); }
        var max = new StaleUploadExpiryTransactions(em, hierarchy, new StaleUploadExpiryProperties(Duration.ofHours(1), 100));
        List<UUID> selected = tx.execute(status -> max.scan(null));
        assertThat(selected).hasSize(100);
        assertThatThrownBy(() -> tx.execute(status -> expiry.expirePage(null))).isInstanceOf(RuntimeException.class);
        UUID file = transactions.scan(null).getFirst();
        tx.executeWithoutResult(status -> {
            assertThat(transactions.expire(file)).isEqualTo(EXPIRED);
            status.setRollbackOnly(); // Candidate committed independently of caller rollback.
        });
        assertThat(scalar("SELECT status FROM files WHERE id = :id", file)).isEqualTo("FAILED");
    }

    private StaleUploadExpiryTransactions raw() { return new StaleUploadExpiryTransactions(em, hierarchy, properties); }

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

    private StoredFile fixture(UUID folder, boolean aged) {
        UUID id = UUID.randomUUID();
        var file = metadata.createUploading(new StoredFile(id, owner, folder, id + ".txt",
                "users/" + owner + "/files/" + id, "text/plain", 1));
        if (aged) {
            tx.executeWithoutResult(status -> em.createNativeQuery(
                    "UPDATE files SET created_at = clock_timestamp() - interval '2 hours' WHERE id = :id")
                    .setParameter("id", id).executeUpdate());
        }
        return file;
    }

    private void assertExpired(StoredFile file) {
        assertThat(scalar("SELECT status FROM files WHERE id = :id", file.getId())).isEqualTo("FAILED");
        assertThat(scalar("SELECT version FROM files WHERE id = :id", file.getId())).isEqualTo(file.getVersion() + 1);
        assertThat(scalar("""
                SELECT NOT write_started AND write_precluded_at IS NOT NULL AND reservation_expired_at >= write_precluded_at
                    AND expired_file_version = :version FROM file_storage_objects WHERE file_id = :id
                """.replace(":version", Long.toString(file.getVersion() + 1)), file.getId())).isEqualTo(true);
    }

    private void assertUploadingUnprecluded(StoredFile file) {
        assertThat(scalar("SELECT status FROM files WHERE id = :id", file.getId())).isEqualTo("UPLOADING");
        assertThat(scalar("SELECT write_precluded_at IS NULL AND reservation_expired_at IS NULL FROM file_storage_objects WHERE file_id = :id", file.getId()))
                .isEqualTo(true);
    }

    private Object scalar(String sql) { return tx.execute(status -> em.createNativeQuery(sql).getSingleResult()); }
    private Object scalar(String sql, UUID id) {
        return tx.execute(status -> em.createNativeQuery(sql).setParameter("id", id).getSingleResult());
    }
    private String fileSnapshot(UUID id) { return (String) scalar("SELECT to_jsonb(f)::text FROM files f WHERE id = :id", id); }
    private String allocationSnapshot(UUID id) { return (String) scalar("SELECT to_jsonb(a)::text FROM file_storage_objects a WHERE file_id = :id", id); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Latch timed out"); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
