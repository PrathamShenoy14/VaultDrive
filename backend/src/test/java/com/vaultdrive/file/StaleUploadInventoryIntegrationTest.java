package com.vaultdrive.file;

import com.vaultdrive.folder.FolderService;
import com.vaultdrive.storage.FileStorageIdentityService;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
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

import java.time.Duration;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.vaultdrive.file.StaleUploadInventoryTransactions.Classification.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class StaleUploadInventoryIntegrationTest {
    private static final String SCHEMA = "upload_inventory_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @Autowired private StaleUploadInventory inventory;
    @MockitoSpyBean private StaleUploadInventoryTransactions observations;
    @Autowired private StaleUploadInventoryProperties properties;
    @Autowired private FileMetadataService metadata;
    @Autowired private FileStorageIdentityService identity;
    @Autowired private FolderService folders;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private EntityManager em;
    @MockitoBean private ObjectStorageService storage;
    @MockitoSpyBean private S3Client s3;
    private TransactionTemplate tx;
    private UUID owner;

    @AfterAll static void removeDisposableSchema(@Autowired DataSource dataSource) throws Exception {
        if (!SCHEMA.matches("upload_inventory_[0-9a-f]{32}")) { throw new AssertionError("Invalid test schema"); }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Inventory")).getId();
        clearInvocations(storage, s3);
    }

    @AfterEach void cleanup() {
        verifyNoInteractions(storage, s3);
        reset(observations);
        tx.executeWithoutResult(status -> {
            // All rows belong to this disposable owner in this run's private schema.
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

    @Test void defaultsAndFreshVersusAgedReservations() {
        assertThat(properties.minimumAge()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.pageSize()).isEqualTo(20);
        fixture(null, false, false);
        fixture(null, false, true);
        var never = fixture(null, true, false);
        var admitted = fixture(null, true, true);
        var page = inventory.scanPage(null);
        assertThat(page.scanned()).isEqualTo(2);
        assertThat(page.neverAdmitted()).isEqualTo(1);
        assertThat(page.admittedUncertain()).isEqualTo(1);
        assertThat(observations.inspect(never.getId())).isEqualTo(NEVER_ADMITTED);
        assertThat(observations.inspect(admitted.getId())).isEqualTo(ADMITTED_UNCERTAIN);
    }

    @Test void failedAdmittedWriteRemainsUncertainAndFailedUnusedRemainsNeverAdmitted() {
        var admitted = fixture(null, true, true);
        var never = fixture(null, true, false);
        metadata.markFailed(admitted);
        metadata.markFailed(never);
        assertThat(inventory.scanPage(null).admittedUncertain()).isEqualTo(1);
        assertThat(inventory.scanPage(null).neverAdmitted()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "owner", "key", "file", "binding"})
    void missingOrMismatchedAllocationIsInconsistent(String corruption) {
        var file = fixture(null, true, false);
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM file_storage_objects WHERE file_id = :id")
                    .setParameter("id", file.getId()).executeUpdate();
            if (!corruption.equals("missing")) {
                em.createNativeQuery("""
                        INSERT INTO file_storage_objects(storage_key, file_id, owner_id,
                            storage_namespace, endpoint, region, bucket, write_started)
                        VALUES (:key, :id, :owner, :namespace, :endpoint, :region, :bucket, true)
                        """).setParameter("key", corruption.equals("key") ? "test/" + UUID.randomUUID() : file.getStorageKey())
                        .setParameter("id", corruption.equals("file") ? UUID.randomUUID() : file.getId())
                        .setParameter("owner", corruption.equals("owner") ? UUID.randomUUID() : owner)
                        .setParameter("namespace", corruption.equals("binding") ? null : "historical-namespace")
                        .setParameter("endpoint", corruption.equals("binding") ? null : "http://historical.invalid")
                        .setParameter("region", corruption.equals("binding") ? null : "historical-region")
                        .setParameter("bucket", corruption.equals("binding") ? null : "historical-bucket").executeUpdate();
            }
        });
        assertThat(inventory.scanPage(null).inconsistent()).isEqualTo(1);
    }

    @Test void retainedHistoricalBindingDoesNotNeedToMatchCurrentConfiguration() {
        var file = fixture(null, true, false);
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM file_storage_objects WHERE file_id = :id")
                    .setParameter("id", file.getId()).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO file_storage_objects(storage_key, file_id, owner_id,
                        storage_namespace, endpoint, region, bucket, write_started)
                    VALUES (:key, :id, :owner, 'old', 'http://historical.invalid', 'old', 'old', true)
                    """).setParameter("key", file.getStorageKey()).setParameter("id", file.getId())
                    .setParameter("owner", owner).executeUpdate();
        });
        assertThat(observations.inspect(file.getId())).isEqualTo(ADMITTED_UNCERTAIN);
    }

    @Test void pendingPurgeAncestryDefersButInconsistencyTakesPrecedence() {
        UUID root = folders.createFolder(owner, null, "root");
        UUID child = folders.createFolder(owner, root, "child");
        var never = fixture(child, true, false);
        var admitted = fixture(child, true, true);
        folders.deleteFolder(owner, root);
        folders.requestPermanentDeletion(owner, root);
        assertThat(inventory.scanPage(null).deferredToPurge()).isEqualTo(2);
        tx.executeWithoutResult(status -> em.createNativeQuery("DELETE FROM file_storage_objects WHERE file_id = :id")
                .setParameter("id", never.getId()).executeUpdate());
        assertThat(observations.inspect(never.getId())).isEqualTo(INCONSISTENT);
        assertThat(observations.inspect(admitted.getId())).isEqualTo(DEFERRED_TO_PURGE);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentAdmissionOrFinalizationUsesCommittedStatementSnapshots(boolean finalize) throws Exception {
        var file = fixture(null, true, finalize);
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var writer = pool.submit(() -> tx.executeWithoutResult(status -> {
                if (finalize) { metadata.markReady(file); }
                else { identity.beginWrite(file.getStorageKey()); }
                written.countDown();
                await(release);
            }));
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(observations.inspect(file.getId())).isEqualTo(finalize ? ADMITTED_UNCERTAIN : NEVER_ADMITTED);
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertThat(observations.inspect(file.getId())).isEqualTo(finalize ? ALREADY_RESOLVED : ADMITTED_UNCERTAIN);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void stateChangesBetweenSelectionAndInspectionAreCountedWithoutLosingCursor() {
        var file = fixture(null, true, true);
        doAnswer(call -> {
            Object selected = call.callRealMethod();
            var competing = new TransactionTemplate(manager);
            competing.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            competing.executeWithoutResult(status -> metadata.markReady(file));
            return selected;
        }).when(observations).scan(null);
        var page = inventory.scanPage(null);
        assertThat(page.alreadyResolved()).isEqualTo(1);
        assertThat(page.nextCursor()).isEqualTo(file.getId());
    }

    @Test void missingMetadataIsNotInventedResolution() {
        var file = fixture(null, true, true);
        tx.executeWithoutResult(status -> em.createNativeQuery("DELETE FROM files WHERE id = :id")
                .setParameter("id", file.getId()).executeUpdate());
        assertThat(observations.inspect(file.getId())).isEqualTo(INCONSISTENT);
    }

    @Test void keysetPagesAdvanceOverBlockersAndConcurrentResolution() {
        for (int i = 0; i < 23; i++) { fixture(null, true, true); }
        var first = inventory.scanPage(null);
        assertThat(first.scanned()).isEqualTo(20);
        assertThat(first.admittedUncertain()).isEqualTo(20);
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE files SET status = 'READY' WHERE id <= :id")
                .setParameter("id", first.nextCursor()).executeUpdate());
        var second = inventory.scanPage(first.nextCursor());
        assertThat(second.scanned()).isEqualTo(3);
        assertThat(second.admittedUncertain()).isEqualTo(3);
        assertThat(inventory.scanPage(second.nextCursor()).exhausted()).isTrue();
    }

    @Test void maximumPageAndRejectedOversizedConfiguration() {
        var max = new StaleUploadInventoryProperties(Duration.ofHours(1), 100);
        var reader = new StaleUploadInventoryTransactions(em, max);
        for (int i = 0; i < 101; i++) { fixture(null, true, false); }
        List<UUID> selected = tx.execute(status -> reader.scan(null));
        assertThat(selected).hasSize(100);
        assertThatThrownBy(() -> new StaleUploadInventoryProperties(Duration.ofHours(1), 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaleUploadInventoryProperties(Duration.ZERO, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void repeatedScansPreserveEveryBusinessTableAndUseDatabaseReadOnly() {
        fixture(null, true, false);
        fixture(null, true, true);
        String before = snapshot();
        for (int i = 0; i < 3; i++) { assertThat(inventory.scanPage(null).scanned()).isEqualTo(2); }
        assertThat(snapshot().equals(before)).isTrue();
        tx.executeWithoutResult(status -> {
            observations.inspect(UUID.randomUUID()); // REQUIRES_NEW must not alter caller transaction.
            assertThat(em.createNativeQuery("SHOW transaction_read_only").getSingleResult()).isEqualTo("off");
        });
    }

    @Test void databaseRejectsWritesWithinInventoryTransaction() {
        var reader = new StaleUploadInventoryTransactions(em, properties);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            reader.scan(null);
            assertThat(em.createNativeQuery("SHOW transaction_read_only").getSingleResult()).isEqualTo("on");
            em.createNativeQuery("UPDATE files SET status = status").executeUpdate();
        })).isInstanceOf(jakarta.persistence.PersistenceException.class);
    }

    @Test void configurableAgeAndCyclicAncestryFailClosed() {
        UUID root = folders.createFolder(owner, null, "root");
        UUID child = folders.createFolder(owner, root, "child");
        var file = fixture(child, true, false);
        var reader = new StaleUploadInventoryTransactions(em,
                new StaleUploadInventoryProperties(Duration.ofHours(3), 20));
        List<UUID> selected = tx.execute(status -> reader.scan(null));
        assertThat(selected).isEmpty();
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE folders SET parent_folder_id = :child WHERE id = :root")
                .setParameter("child", child).setParameter("root", root).executeUpdate());
        assertThat(observations.inspect(file.getId())).isEqualTo(INCONSISTENT);
    }

    @Test void failedInspectionAdvancesAndFailedSelectionRetainsCursor() {
        var file = fixture(null, true, false);
        doThrow(new IllegalStateException("test-only")).when(observations).inspect(file.getId());
        var page = inventory.scanPage(null);
        assertThat(page.scanFailed()).isEqualTo(1);
        assertThat(page.nextCursor()).isEqualTo(file.getId());
        assertThat(inventory.scanPage(page.nextCursor()).exhausted()).isTrue();
        doThrow(new IllegalStateException("test-only")).when(observations).scan(file.getId());
        assertThat(inventory.scanPage(file.getId()).nextCursor()).isEqualTo(file.getId());
        assertThat(inventory.scanPage(file.getId()).scanFailed()).isEqualTo(1);
    }

    @Test void tableLockTimesOutWithSanitizedFailedScan() throws Exception {
        fixture(null, true, false);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var locker = pool.submit(() -> tx.executeWithoutResult(status -> {
                em.createNativeQuery("LOCK TABLE files IN ACCESS EXCLUSIVE MODE").executeUpdate();
                locked.countDown(); await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var page = inventory.scanPage(null);
            assertThat(page.scanFailed()).isEqualTo(1);
            assertThat(page.scanned()).isZero();
            assertThat(page.nextCursor()).isNull();
            release.countDown(); locker.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    private StoredFile fixture(UUID folder, boolean aged, boolean admitted) {
        UUID id = UUID.randomUUID();
        var file = metadata.createUploading(new StoredFile(id, owner, folder, id + ".txt",
                "users/" + owner + "/files/" + id, "text/plain", 1));
        if (admitted) { identity.beginWrite(file.getStorageKey()); }
        if (aged) {
            tx.executeWithoutResult(status -> em.createNativeQuery(
                    "UPDATE files SET created_at = clock_timestamp() - interval '2 hours' WHERE id = :id")
                    .setParameter("id", id).executeUpdate());
        }
        return file;
    }

    private String snapshot() {
        return tx.execute(status -> {
            List<?> tables = em.createNativeQuery("SELECT tablename FROM pg_tables WHERE schemaname = current_schema() "
                    + "AND tablename <> 'flyway_schema_history' ORDER BY tablename").getResultList();
            var result = new ArrayList<String>();
            for (Object table : tables) {
                result.add(String.valueOf(em.createNativeQuery("SELECT coalesce(jsonb_agg(value ORDER BY value::text), '[]')::text "
                        + "FROM (SELECT to_jsonb(t) value FROM " + table + " t) rows").getSingleResult()));
            }
            return result.toString();
        });
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Latch timed out"); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
