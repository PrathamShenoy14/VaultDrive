package com.vaultdrive.file;

import org.springframework.context.annotation.Import;

import com.vaultdrive.support.HistoricalObjectFixtures;
import com.vaultdrive.support.HistoricalUploadFixtures;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.storage.*;
import com.vaultdrive.storage.config.S3StorageProperties;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.vaultdrive.file.KnownSuccessCleanupTransactions.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/Garage; only this class's successful disposable writes may be removed. */
@Import(HistoricalObjectFixtures.class)
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnownSuccessCleanupIntegrationTest {
    @Autowired HistoricalObjectFixtures historicalObjects;

    @Autowired HistoricalUploadFixtures history;

    private static final String SCHEMA = "known_cleanup_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicBoolean RETAIN = new AtomicBoolean();
    private static final byte[] BYTES = {2, 4, 6, 8};
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired KnownSuccessCleanupTransactions cleanup;
    @Autowired KnownSuccessCleanup operation;
    @Autowired KnownSuccessCleanupProperties properties;
    @Autowired UploadFinalizationTransactions success;
    @Autowired FileMetadataService metadata;
    @Autowired StoredFileRepository files;
    @Autowired HierarchyCoordinator hierarchy;
    @Autowired FolderService folders;
    @Autowired StorageBinding binding;
    @Autowired S3StorageProperties storageProperties;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource source;
    @Autowired com.vaultdrive.job.BackgroundJobService jobs;
    @Autowired com.vaultdrive.worker.FolderPurgeDiscoveryService discovery;
    @MockitoSpyBean ObjectStorageService storage;
    @MockitoSpyBean S3Client s3;
    private TransactionTemplate tx;
    private UUID owner;
    private final Set<String> owned = ConcurrentHashMap.newKeySet();

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Cleanup")).getId();
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Object response = i.callRealMethod();
            owned.add(((PutObjectRequest) i.getArgument(0)).key());
            return response;
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @AfterEach void disposeKnownWrites() {
        // Do not remove metadata/allocation tombstones. Dispose the private schema only after all bytes are gone.
        try {
            for (String key : owned) {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(storageProperties.bucket()).key(key).build());
                assertThatThrownBy(() -> s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(storageProperties.bucket()).key(key).build())).isInstanceOf(NoSuchKeyException.class);
            }
        } catch (Throwable failure) {
            RETAIN.set(true);
            throw new AssertionError("Disposable cleanup failed; retain " + SCHEMA, failure);
        }
    }
    @AfterAll static void removeSchema(@Autowired DataSource source) throws Exception {
        if (RETAIN.get()) { return; }
        if (!SCHEMA.matches("known_cleanup_[0-9a-f]{32}")) { throw new AssertionError("Invalid schema"); }
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test @Order(1) void boundedExplicitPagesDeleteEligibleFilesAndRetainPermanentAuthority() {
        assertThat(properties.pageSize()).isEqualTo(20);
        for (int i = 0; i < 23; i++) { failed(null); }
        var first = operation.cleanupPage(null);
        assertThat(first.scanned()).isEqualTo(20); assertThat(first.deleted()).isEqualTo(20);
        var second = operation.cleanupPage(first.nextCursor());
        assertThat(second.scanned()).isEqualTo(3); assertThat(second.deleted()).isEqualTo(3);
        assertThat(operation.cleanupPage(second.nextCursor()).exhausted()).isTrue();
        assertThat(operation.cleanupPage(null).alreadyComplete()).isEqualTo(20);
        assertThatThrownBy(() -> tx.execute(s -> operation.cleanupPage(null))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new KnownSuccessCleanupProperties(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnownSuccessCleanupProperties(101)).isInstanceOf(IllegalArgumentException.class);
        List<UUID> selected = tx.execute(s -> new KnownSuccessCleanupTransactions(em, hierarchy, binding,
                new KnownSuccessCleanupProperties(100)).scan(null));
        assertThat(selected).hasSize(23);
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test void eligibleFailedCleanupKeepsUnrelatedObjectReadableAndDuplicateInvocationSkipsDelete() throws Exception {
        var file = failed(null); var control = put(null);
        String before = snapshot(file), allocation = allocation(file);
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            tx.executeWithoutResult(s -> {
                em.createNativeQuery("SET LOCAL lock_timeout='1s'").executeUpdate();
                hierarchy.acquireExclusive(owner);
                for (String table : List.of("files", "file_storage_objects", "upload_cleanup_progress")) {
                    String column = table.equals("files") ? "id" : "file_id";
                    em.createNativeQuery("SELECT " + column + " FROM " + table + " WHERE " + column + "=:id FOR UPDATE")
                            .setParameter("id", file.getId()).getResultList();
                }
            });
            return i.callRealMethod();
        }).when(storage).delete(binding.target(file.getStorageKey()));
        clearInvocations(storage, s3);
        assertThat(one(file).deleted()).isEqualTo(1);
        assertThat(one(file).alreadyComplete()).isEqualTo(1);
        assertThat(snapshot(file)).isEqualTo(before); assertThat(allocation(file)).isEqualTo(allocation);
        verify(storage, times(1)).delete(binding.target(file.getStorageKey()));
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
        absent(file); readable(control);
        assertThat(scalar("SELECT decided_at <= deleted_at FROM upload_cleanup_intents JOIN upload_cleanup_receipts USING(file_id) WHERE file_id = :id", file.getId())).isEqualTo(true);
    }

    @ParameterizedTest @ValueSource(strings = {"no-receipt", "historical", "uploading", "ready", "unknown", "binding",
            "version", "destination", "key", "missing", "no-allocation", "owner", "trash", "work"})
    void rejectsEveryExcludedIdentityWithoutStorageIO(String exclusion) {
        StoredFile file;
        if (exclusion.equals("unknown") || exclusion.equals("binding") || exclusion.equals("historical")) {
            file = fixture(null);
            if (exclusion.equals("unknown")) {
                sql("DELETE FROM file_storage_objects WHERE file_id = :id", file.getId());
                tx.executeWithoutResult(s -> em.createNativeQuery("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,write_started) VALUES (:key,:id,:owner,true)")
                        .setParameter("key", file.getStorageKey()).setParameter("id", file.getId()).setParameter("owner", owner).executeUpdate());
            } else if (exclusion.equals("binding")) {
                // Simulate another original destination by reserving it before admission, never rewriting a binding.
                sql("DELETE FROM file_storage_objects WHERE file_id = :id", file.getId());
                tx.executeWithoutResult(s -> em.createNativeQuery("""
                        INSERT INTO file_storage_objects(storage_key,file_id,owner_id,storage_namespace,endpoint,region,bucket)
                        SELECT storage_key,id,owner_id,'other-original',:endpoint,:region,:bucket FROM files WHERE id = :id
                        """).setParameter("id", file.getId()).setParameter("endpoint", binding.target("x").endpoint())
                        .setParameter("region", storageProperties.region()).setParameter("bucket", storageProperties.bucket()).executeUpdate());
                sql("UPDATE file_storage_objects SET write_started=true, admitted_file_version=0 WHERE file_id=:id", file.getId());
                history.receipt(file); // Database-only trusted-caller fixture; no PUT dispatched.
            } else { sql("UPDATE file_storage_objects SET write_started=true WHERE file_id=:id", file.getId()); }
            history.status(file, FileStatus.FAILED);
        } else {
            file = put(null);
            if (!exclusion.equals("no-receipt")) { history.receipt(file); }
            if (exclusion.equals("ready")) { success.recover(file.getId()); }
            else if (!exclusion.equals("uploading")) { history.status(file, FileStatus.FAILED); }
            switch (exclusion) {
                case "version" -> sql("UPDATE files SET version=version+1 WHERE id=:id", file.getId());
                case "destination" -> {
                    UUID folder = folders.createFolder(owner, null, "destination");
                    sql("UPDATE files SET folder_id='" + folder + "' WHERE id=:id", file.getId());
                }
                case "key" -> sql("UPDATE files SET storage_key='mismatched-key' WHERE id=:id", file.getId());
                case "missing" -> sql("DELETE FROM files WHERE id=:id", file.getId());
                case "no-allocation" -> sql("DELETE FROM file_storage_objects WHERE file_id=:id", file.getId());
                case "owner" -> {
                    UUID other = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Other")).getId();
                    // Historical mismatch fixture: current quota accounting prohibits creating it normally.
                    tx.executeWithoutResult(status -> {
                        em.createNativeQuery("ALTER TABLE files DISABLE TRIGGER trg_account_file_storage").executeUpdate();
                        em.createNativeQuery("UPDATE files SET owner_id=:owner WHERE id=:id")
                                .setParameter("owner", other).setParameter("id", file.getId()).executeUpdate();
                        em.createNativeQuery("ALTER TABLE files ENABLE TRIGGER trg_account_file_storage").executeUpdate();
                    });
                }
                case "trash" -> sql("UPDATE files SET deleted_at=clock_timestamp() WHERE id=:id", file.getId());
                case "work" -> sql("""
                        INSERT INTO file_purge_work(file_id,owner_id,folder_id,file_version,file_status,file_created_at,file_updated_at,
                            deleted_at,purge_requested_at,storage_namespace,endpoint,region,bucket,storage_key)
                        SELECT f.id,f.owner_id,f.folder_id,f.version,f.status,f.created_at,f.updated_at,f.deleted_at,f.purge_requested_at,
                            a.storage_namespace,a.endpoint,a.region,a.bucket,a.storage_key
                        FROM files f JOIN file_storage_objects a ON a.file_id=f.id WHERE f.id=:id
                        """, file.getId());
            }
        }
        clearInvocations(storage, s3);
        assertThat(cleanup.prepare(file.getId()).outcome()).isEqualTo(BLOCKED);
        assertThat(one(file).blocked()).isEqualTo(1);
        assertThat(has("upload_cleanup_intents", file)).isFalse();
        verifyNoInteractions(storage, s3);
    }

    @Test void intentRollbackAndLostPreparationAcknowledgementNeverDispatchWithoutCommittedAuthority() {
        var file = failed(null);
        tx.executeWithoutResult(s -> { assertThat(raw().prepare(file.getId()).outcome()).isEqualTo(CLAIMED); s.setRollbackOnly(); });
        assertThat(has("upload_cleanup_intents", file)).isFalse();
        assertThat(has("upload_cleanup_progress", file)).isFalse();
        var boundary = boundary(file);
        doAnswer(i -> { cleanup.prepare(file.getId()); throw new IllegalStateException("lost acknowledgement"); }).when(boundary).prepare(file.getId());
        clearInvocations(storage);
        assertThat(new KnownSuccessCleanup(boundary, storage).cleanupPage(null).failed()).isEqualTo(1);
        verifyNoInteractions(storage);
        assertThat(cleanup.prepare(file.getId()).outcome()).isEqualTo(BUSY);
        expire(file);
        assertThat(one(file).deleted()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void crashesBeforeDeleteAndAfterDeleteBeforeReceiptRetryTheAbandonedTarget(boolean deleteBeforeCrash) {
        var file = failed(null);
        var abandoned = cleanup.prepare(file.getId()).claim();
        String intent = (String) scalar("SELECT to_jsonb(i)::text FROM upload_cleanup_intents i WHERE file_id=:id", file.getId());
        if (deleteBeforeCrash) { storage.delete(abandoned.target()); absent(file); }
        else { assertThat(has("upload_cleanup_receipts", file)).isFalse(); }
        expire(file);
        assertThat(cleanup.complete(abandoned)).isFalse();
        var retry = cleanup.prepare(file.getId()).claim();
        assertThat(retry.revision()).isEqualTo(abandoned.revision() + 1);
        storage.delete(retry.target()); assertThat(cleanup.complete(retry)).isTrue();
        // An old external DELETE may still arrive, but the permanent snapshot/key can never become READY/reused.
        storage.delete(abandoned.target());
        assertThat(cleanup.failed(abandoned)).isFalse(); absent(file);
        assertThat(scalar("SELECT to_jsonb(i)::text FROM upload_cleanup_intents i WHERE file_id=:id", file.getId())).isEqualTo(intent);
        assertThatThrownBy(() -> history.status(file, FileStatus.READY)).isInstanceOf(RuntimeException.class);
        assertThat(success.recover(file.getId())).isEqualTo(UploadFinalizationTransactions.Outcome.BLOCKED);
        assertThatThrownBy(() -> sql("UPDATE files SET status='READY',version=version+1 WHERE id=:id", file.getId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> history.admission(file.getStorageKey())).isInstanceOf(RuntimeException.class);
    }

    @Test void committedDeletionReceiptLostAcknowledgementReplaysWithoutDelete() {
        var file = failed(null); var boundary = boundary(file);
        doAnswer(i -> { assertThat(cleanup.complete(i.getArgument(0))).isTrue(); throw new IllegalStateException("lost acknowledgement"); }).when(boundary).complete(any());
        var orchestrator = new KnownSuccessCleanup(boundary, storage);
        assertThat(orchestrator.cleanupPage(null).failed()).isEqualTo(1);
        String receipt = (String) scalar("SELECT to_jsonb(r)::text FROM upload_cleanup_receipts r WHERE file_id=:id", file.getId());
        clearInvocations(storage);
        assertThat(one(file).alreadyComplete()).isEqualTo(1); verifyNoInteractions(storage);
        assertThat(scalar("SELECT to_jsonb(r)::text FROM upload_cleanup_receipts r WHERE file_id=:id", file.getId())).isEqualTo(receipt);
    }

    @Test void storageFailurePersistsRetryAndDoesNotInventReceipt() {
        var file = failed(null);
        doThrow(new StorageDeletionException(StorageDeletionException.Kind.TRANSIENT)).when(storage).delete(binding.target(file.getStorageKey()));
        assertThat(one(file).failed()).isEqualTo(1);
        assertThat(has("upload_cleanup_intents", file)).isTrue(); assertThat(has("upload_cleanup_receipts", file)).isFalse();
        assertThat(cleanup.prepare(file.getId()).outcome()).isEqualTo(BUSY);
        assertThat(scalar("SELECT last_failure FROM upload_cleanup_progress WHERE file_id=:id", file.getId())).isEqualTo("STORAGE_FAILURE");
        doCallRealMethod().when(storage).delete(binding.target(file.getStorageKey()));
        expire(file);
        assertThat(one(file).deleted()).isEqualTo(1); absent(file);
    }

    @Test void competingClaimsAndInvocationsCommitOneLiveClaim() throws Exception {
        var file = failed(null);
        race(() -> raw().prepare(file.getId()).outcome(), () -> cleanup.prepare(file.getId()).outcome(), CLAIMED, BUSY);
        expire(file);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        doAnswer(i -> { entered.countDown(); await(release); return i.callRealMethod(); }).when(storage).delete(binding.target(file.getStorageKey()));
        try {
            var first = pool.submit(() -> one(file));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(one(file).busy()).isEqualTo(1);
            release.countDown(); assertThat(first.get(10, TimeUnit.SECONDS).deleted()).isEqualTo(1);
            assertThat(((Number) scalar("SELECT revision FROM upload_cleanup_progress WHERE file_id=:id", file.getId())).longValue()).isEqualTo(2);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @ParameterizedTest @ValueSource(strings = {"trash-first", "cleanup-trash", "restore-first", "cleanup-restore", "purge-first", "cleanup-purge"})
    void folderTrashRestorePurgeWinnerOrdersPreserveSnapshots(String order) throws Exception {
        UUID root = folders.createFolder(owner, null, "root");
        UUID child = folders.createFolder(owner, root, "child");
        var file = failed(child);
        boolean restore = order.contains("restore"), purge = order.contains("purge");
        if (restore || purge) { folders.deleteFolder(owner, root); }
        Supplier<Object> mutation = () -> {
            if (restore) { folders.restoreFolder(owner, root); }
            else if (purge) { folders.requestPermanentDeletion(owner, root); }
            else { folders.deleteFolder(owner, root); }
            return null;
        };
        if (order.endsWith("first")) { race(mutation, () -> cleanup.prepare(file.getId()).outcome(), null, restore ? CLAIMED : BLOCKED); }
        else { race(() -> raw().prepare(file.getId()).outcome(), mutation, restore || purge ? BLOCKED : CLAIMED, null); }
        assertThat(scalar("SELECT status FROM files WHERE id=:id", file.getId())).isEqualTo("FAILED");
        if (restore) { expireIfPresent(file); assertThat(one(file).deleted()).isEqualTo(1); }
        if (purge) { assertThat(cleanup.prepare(file.getId()).outcome()).isEqualTo(BLOCKED); }
    }

    @Test void cleanupFirstThenSealedFolderMembershipRetainsFailedSnapshotAndDoesNotClaimPurgeProof() {
        UUID root = folders.createFolder(owner, null, "root"); var file = failed(root);
        var claim = cleanup.prepare(file.getId()).claim(); String before = snapshot(file);
        folders.deleteFolder(owner, root); folders.requestPermanentDeletion(owner, root); seal(root);
        String member = (String) scalar("SELECT to_jsonb(m)::text FROM folder_purge_files m WHERE file_id=:id", file.getId());
        storage.delete(claim.target()); assertThat(cleanup.complete(claim)).isTrue();
        assertThat(snapshot(file)).isEqualTo(before);
        assertThat(scalar("SELECT to_jsonb(m)::text FROM folder_purge_files m WHERE file_id=:id", file.getId())).isEqualTo(member);
        assertThat(has("file_purge_work", file)).isFalse();
    }

    @Test void sealedFolderPurgeFirstRejectsNewAuthorityWithoutChangingMember() {
        UUID root = folders.createFolder(owner, null, "root"); var file = failed(root);
        folders.deleteFolder(owner, root); folders.requestPermanentDeletion(owner, root); seal(root);
        String member = (String) scalar("SELECT to_jsonb(m)::text FROM folder_purge_files m WHERE file_id=:id", file.getId());
        assertThat(cleanup.prepare(file.getId()).outcome()).isEqualTo(BLOCKED);
        assertThat(has("upload_cleanup_intents", file)).isFalse();
        assertThat(scalar("SELECT to_jsonb(m)::text FROM folder_purge_files m WHERE file_id=:id", file.getId())).isEqualTo(member);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void delayedUncertainPutNeverGetsCleanupRegardlessOfAgeOrHead(boolean bytesAlreadyAccepted) throws Exception {
        var file = fixture(null);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        doAnswer(i -> {
            Object response = null;
            if (bytesAlreadyAccepted) { response = i.callRealMethod(); owned.add(file.getStorageKey()); }
            entered.countDown(); await(release);
            if (!bytesAlreadyAccepted) { response = i.callRealMethod(); owned.add(file.getStorageKey()); }
            return response;
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        try {
            history.admission(file.getStorageKey());
            var writer = pool.submit(() -> historicalObjects.write(file, BYTES));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            history.status(file, FileStatus.FAILED);
            sql("UPDATE files SET created_at=clock_timestamp()-interval '100 years' WHERE id=:id", file.getId());
            if (bytesAlreadyAccepted) { assertThat(s3.headObject(HeadObjectRequest.builder().bucket(storageProperties.bucket()).key(file.getStorageKey()).build()).contentLength()).isEqualTo(BYTES.length); }
            else { assertThatThrownBy(() -> s3.headObject(HeadObjectRequest.builder().bucket(storageProperties.bucket()).key(file.getStorageKey()).build())).isInstanceOf(NoSuchKeyException.class); }
            clearInvocations(storage, s3);
            assertThat(one(file).blocked()).isEqualTo(1);
            assertThat(cleanup.scan(null)).doesNotContain(file.getId());
            verifyNoInteractions(storage, s3);
            release.countDown(); writer.get(10, TimeUnit.SECONDS);
            readable(file); assertThat(has("upload_cleanup_intents", file)).isFalse();
        } catch (Throwable failure) {
            RETAIN.set(true); throw failure; // Preserve uncertain admission evidence on any unexpected failure.
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
    }

    @Test void databaseGuardsIntentReceiptSnapshotRetentionAndAllocationReference() {
        var file = failed(null); var claim = cleanup.prepare(file.getId()).claim();
        storage.delete(claim.target()); assertThat(cleanup.complete(claim)).isTrue();
        for (String query : List.of("UPDATE upload_cleanup_intents SET reason='other' WHERE file_id=:id",
                "DELETE FROM upload_cleanup_intents WHERE file_id=:id", "DELETE FROM upload_cleanup_progress WHERE file_id=:id",
                "UPDATE upload_cleanup_receipts SET deleted_at=clock_timestamp() WHERE file_id=:id",
                "DELETE FROM upload_cleanup_receipts WHERE file_id=:id", "DELETE FROM file_storage_objects WHERE file_id=:id",
                "UPDATE files SET version=version+1 WHERE id=:id", "UPDATE upload_cleanup_progress SET revision=0 WHERE file_id=:id")) {
            assertThatThrownBy(() -> sql(query, file.getId())).isInstanceOf(RuntimeException.class);
        }
        var other = failed(null); var stale = cleanup.prepare(other.getId()).claim(); expire(other);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> em.createNativeQuery("""
                INSERT INTO upload_cleanup_receipts(file_id,claim_token,claim_revision,outcome)
                VALUES (:id,:token,:revision,'DELETED_OR_ABSENT')
                """).setParameter("id", other.getId()).setParameter("token", stale.token()).setParameter("revision", stale.revision()).executeUpdate()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void receiptRollbackRetainsClaimAndAbsentObjectForReplay() {
        var file = failed(null); var claim = cleanup.prepare(file.getId()).claim();
        storage.delete(claim.target());
        tx.executeWithoutResult(s -> { assertThat(raw().complete(claim)).isTrue(); s.setRollbackOnly(); });
        assertThat(has("upload_cleanup_receipts", file)).isFalse();
        assertThat(cleanup.complete(claim)).isTrue(); absent(file);
    }

    @Test void acceptedSnapshotAndReadyFenceAreIndependentOfConnectionTimezone() {
        var file = failed(null); cleanup.prepare(file.getId());
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("SET LOCAL TIME ZONE 'Asia/Kolkata'").executeUpdate();
            em.createNativeQuery("UPDATE files SET status=status WHERE id=:id").setParameter("id", file.getId()).executeUpdate();
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            em.createNativeQuery("SET LOCAL TIME ZONE 'America/New_York'").executeUpdate();
            em.createNativeQuery("UPDATE files SET status='READY' WHERE id=:id").setParameter("id", file.getId()).executeUpdate();
        })).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"entity-ready", "recovery-ready"})
    void readyAttemptStartedBeforeIntentCommitStillLosesToPermanentFence(String path) throws Exception {
        var file = failed(null);
        race(() -> raw().prepare(file.getId()).outcome(), () -> {
            if (path.equals("entity-ready")) {
                assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    var current = files.findById(file.getId()).orElseThrow();
                    current.markReady(); files.saveAndFlush(current);
                })).isInstanceOf(RuntimeException.class);
            } else { assertThat(success.recover(file.getId())).isEqualTo(UploadFinalizationTransactions.Outcome.BLOCKED); }
            return null;
        }, CLAIMED, null);
        assertThat(scalar("SELECT status FROM files WHERE id=:id", file.getId())).isEqualTo("FAILED");
    }

    @ParameterizedTest @ValueSource(strings = {"cycle", "depth"})
    void invalidAncestryNeverCreatesAuthority(String shape) {
        UUID root = folders.createFolder(owner, null, "root");
        UUID child = folders.createFolder(owner, root, "child");
        var file = failed(child);
        if (shape.equals("cycle")) {
            sql("UPDATE folders SET parent_folder_id='" + child + "' WHERE id=:id", root);
        } else {
            // Direct test-only chain, no app transaction or storage request spans this construction.
            UUID last = root;
            for (int i = 0; i < 128; i++) {
                UUID parent = UUID.randomUUID(); UUID previous = last;
                tx.executeWithoutResult(s -> {
                    em.createNativeQuery("INSERT INTO folders(id,owner_id,name,created_at,updated_at) VALUES (:id,:owner,:name,clock_timestamp(),clock_timestamp())")
                            .setParameter("id", parent).setParameter("owner", owner).setParameter("name", parent.toString()).executeUpdate();
                    em.createNativeQuery("UPDATE folders SET parent_folder_id=:parent WHERE id=:id")
                            .setParameter("parent", parent).setParameter("id", previous).executeUpdate();
                });
                last = parent;
            }
        }
        clearInvocations(storage, s3);
        assertThat(cleanup.prepare(file.getId()).outcome()).isEqualTo(BLOCKED);
        assertThat(has("upload_cleanup_intents", file)).isFalse(); verifyNoInteractions(storage, s3);
    }

    @Test void databaseRejectsInventedIntentSnapshotAndReceiptIdentity() {
        var file = failed(null);
        for (String snapshot : List.of("'{}'::jsonb", "upload_cleanup_file_snapshot(f)")) {
            String timestamp = snapshot.startsWith("'") ? "a.put_succeeded_at" : "a.put_succeeded_at + interval '1 second'";
            assertThatThrownBy(() -> sql("INSERT INTO upload_cleanup_intents(file_id,file_snapshot,put_succeeded_at,reason) "
                    + "SELECT f.id," + snapshot + "," + timestamp + ",'RECEIPT_BACKED_FAILED' "
                    + "FROM files f JOIN file_storage_objects a ON a.file_id=f.id WHERE f.id=:id", file.getId())).isInstanceOf(RuntimeException.class);
        }
        assertThat(has("upload_cleanup_intents", file)).isFalse();
    }

    @Test void leaseExpiryWhileWaitingForFinalClaimLockRejectsBothOutcomeKinds() throws Exception {
        var file = failed(null); var claim = cleanup.prepare(file.getId()).claim();
        race(() -> { expire(file); return null; }, () -> cleanup.complete(claim), null, false);
        assertThat(has("upload_cleanup_receipts", file)).isFalse();
        var next = cleanup.prepare(file.getId()).claim();
        race(() -> { expire(file); return null; }, () -> cleanup.failed(next), null, false);
        assertThat(has("upload_cleanup_receipts", file)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"hierarchy", "file", "allocation", "progress", "scan"})
    void boundedLockWaitsLeaveAuthorityRecoverable(String lock) throws Exception {
        var file = failed(null);
        if (lock.equals("progress")) { cleanup.prepare(file.getId()); expire(file); }
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var locker = pool.submit(() -> tx.executeWithoutResult(s -> {
                switch (lock) {
                    case "hierarchy" -> hierarchy.acquireExclusive(owner);
                    case "scan" -> em.createNativeQuery("LOCK TABLE file_storage_objects IN ACCESS EXCLUSIVE MODE").executeUpdate();
                    default -> {
                        String table = lock.equals("file") ? "files" : lock.equals("allocation") ? "file_storage_objects" : "upload_cleanup_progress";
                        String column = lock.equals("file") ? "id" : "file_id";
                        em.createNativeQuery("SELECT " + column + " FROM " + table + " WHERE " + column + "=:id FOR UPDATE")
                                .setParameter("id", file.getId()).getResultList();
                    }
                }
                held.countDown(); await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            if (lock.equals("scan")) { assertThatThrownBy(() -> cleanup.scan(null)).isInstanceOf(RuntimeException.class); }
            else { assertThat(one(file).failed()).isEqualTo(1); }
            assertThat(has("upload_cleanup_receipts", file)).isFalse();
            release.countDown(); locker.get(5, TimeUnit.SECONDS);
            assertThat(one(file).deleted()).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void statementTimeoutRollsBackIntentAndClaimTogether() {
        var file = failed(null);
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("CREATE FUNCTION stall_cleanup_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(6); RETURN NEW; END; $$").executeUpdate();
            em.createNativeQuery("CREATE TRIGGER zz_stall_cleanup_test BEFORE INSERT ON upload_cleanup_intents FOR EACH ROW EXECUTE FUNCTION stall_cleanup_test()")
                    .executeUpdate();
        });
        try {
            assertThat(one(file).failed()).isEqualTo(1);
            assertThat(has("upload_cleanup_intents", file)).isFalse();
            assertThat(has("upload_cleanup_progress", file)).isFalse();
        } finally {
            tx.executeWithoutResult(s -> {
                em.createNativeQuery("DROP TRIGGER zz_stall_cleanup_test ON upload_cleanup_intents").executeUpdate();
                em.createNativeQuery("DROP FUNCTION stall_cleanup_test()").executeUpdate();
            });
        }
        assertThat(one(file).deleted()).isEqualTo(1);
    }

    @Test void v25UpgradePreservesHistoricalAllocationsAndCreatesNoCleanupAuthority() throws Exception {
        String schema = "cleanup_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("25").load();
        try {
            flyway.migrate();
            try (var c = source.getConnection()) {
                String previous = c.getSchema();
                try {
                    c.setSchema(schema);
                    try (var s = c.createStatement()) {
                        s.executeUpdate("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,write_started) VALUES ('historical',gen_random_uuid(),gen_random_uuid(),true)");
                    }
                } finally { c.setSchema(previous); }
            }
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            try (var c = source.getConnection()) {
                String previous = c.getSchema();
                try {
                    c.setSchema(schema);
                    try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*),count(put_succeeded_at) FROM file_storage_objects")) {
                        assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(1); assertThat(r.getInt(2)).isZero();
                    }
                    try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM upload_cleanup_intents")) {
                        assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isZero();
                    }
                } finally { c.setSchema(previous); }
            }
        } finally {
            // Only this new database-only schema; no Garage allocation was dispatched from it.
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(false).load().clean();
        }
    }

    private void seal(UUID root) {
        UUID event = (UUID) scalar("SELECT event_id FROM folder_purge_runs WHERE root_folder_id=:id", root);
        jobs.create(event, com.vaultdrive.job.JobType.FOLDER_PURGE, root);
        // Earlier cases may retain claimed jobs; only this new pending job is claimable.
        var claim = jobs.claimNext(com.vaultdrive.job.JobType.FOLDER_PURGE).orElseThrow();
        assertThat(claim.id()).isEqualTo(event);
        boolean sealed = false;
        for (int i = 0; i < 10 && !sealed; i++) {
            long version = ((Number) scalar("SELECT progress_version FROM background_jobs WHERE id=:id", event)).longValue();
            sealed = discovery.discover(claim, version, 20).sealed();
        }
        assertThat(sealed).isTrue();
    }
    private KnownSuccessCleanupTransactions raw() { return new KnownSuccessCleanupTransactions(em, hierarchy, binding, properties); }
    private KnownSuccessCleanupTransactions boundary(StoredFile file) {
        var boundary = mock(KnownSuccessCleanupTransactions.class);
        when(boundary.scan(null)).thenReturn(List.of(file.getId()));
        when(boundary.prepare(file.getId())).thenAnswer(i -> cleanup.prepare(file.getId()));
        when(boundary.complete(any())).thenAnswer(i -> cleanup.complete(i.getArgument(0)));
        when(boundary.failed(any())).thenAnswer(i -> cleanup.failed(i.getArgument(0)));
        return boundary;
    }
    private KnownSuccessCleanup.Page one(StoredFile file) { return new KnownSuccessCleanup(boundary(file), storage).cleanupPage(null); }
    private StoredFile fixture(UUID folder) {
        UUID id = UUID.randomUUID();
        return history.reservation(new StoredFile(id, owner, folder, id + ".txt", "integration-tests/" + SCHEMA + "/" + id, "text/plain", BYTES.length));
    }
    private StoredFile put(UUID folder) {
        var file = fixture(folder);
        history.admission(file.getStorageKey());
        try { historicalObjects.write(file, BYTES); }
        catch (Exception failure) { RETAIN.set(true); throw new AssertionError("Uncertain write; retain " + SCHEMA, failure); }
        return file;
    }
    private StoredFile failed(UUID folder) { var f = put(folder); history.receipt(f); history.status(f, FileStatus.FAILED); return f; }
    private void expire(StoredFile file) {
        sql("UPDATE upload_cleanup_progress SET lease_until=CASE WHEN claim_token IS NULL THEN NULL ELSE clock_timestamp()-interval '1 second' END,retry_after=clock_timestamp()-interval '1 second' WHERE file_id=:id", file.getId());
    }
    private void expireIfPresent(StoredFile file) { if (has("upload_cleanup_intents", file)) { expire(file); } }
    private boolean has(String table, StoredFile f) { return (Boolean) scalar("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE file_id=:id)", f.getId()); }
    private void sql(String query, UUID id) { tx.executeWithoutResult(s -> em.createNativeQuery(query).setParameter("id", id).executeUpdate()); }
    private Object scalar(String query, UUID id) { return tx.execute(s -> em.createNativeQuery(query).setParameter("id", id).getSingleResult()); }
    private String snapshot(StoredFile f) { return (String) scalar("SELECT to_jsonb(f)::text FROM files f WHERE id=:id", f.getId()); }
    private String allocation(StoredFile f) { return (String) scalar("SELECT to_jsonb(a)::text FROM file_storage_objects a WHERE file_id=:id", f.getId()); }
    private void absent(StoredFile f) { assertThatThrownBy(() -> storage.download(f.getStorageKey())).isInstanceOf(NoSuchKeyException.class); }
    private void readable(StoredFile f) throws Exception { try (var in = storage.download(f.getStorageKey()).inputStream()) { assertThat(in.readAllBytes()).isEqualTo(BYTES); } }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Latch timeout"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    /** Observe PostgreSQL blocking while the winner holds the actual protocol locks before commit. */
    private void race(Supplier<?> first, Supplier<?> second, Object expectedFirst, Object expectedSecond) throws Exception {
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var pid = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var winner = pool.submit(() -> tx.execute(s -> {
                pid.set(((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue());
                Object result = first.get(); held.countDown(); await(release); return result;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var contender = pool.submit(second::get);
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            boolean blocked;
            do {
                blocked = tx.execute(s -> (Boolean) em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid)))")
                        .setParameter("pid", pid.get()).getSingleResult());
                if (blocked) { break; } Thread.yield();
            } while (System.nanoTime() < deadline);
            assertThat(blocked).isTrue(); assertThat(contender.isDone()).isFalse();
            release.countDown();
            assertThat(winner.get(5, TimeUnit.SECONDS)).isEqualTo(expectedFirst);
            assertThat(contender.get(5, TimeUnit.SECONDS)).isEqualTo(expectedSecond);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }
}
