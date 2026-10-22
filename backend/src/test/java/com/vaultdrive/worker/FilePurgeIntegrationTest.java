package com.vaultdrive.worker;

import org.springframework.context.annotation.Import;

import com.vaultdrive.support.HistoricalObjectFixtures;
import com.vaultdrive.support.HistoricalUploadFixtures;
import com.vaultdrive.file.FileMetadataService;
import com.vaultdrive.file.FileService;
import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.BackgroundJobRepository;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobStatus;
import com.vaultdrive.job.JobType;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.outbox.OutboxEvent;
import com.vaultdrive.outbox.OutboxWriter;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageBinding;
import com.vaultdrive.storage.StorageDeletionException;
import com.vaultdrive.storage.StorageTarget;
import com.vaultdrive.storage.config.S3StorageProperties;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real PostgreSQL and Garage, scoped to fresh owner/file UUIDs; no shared storage fixtures. */
@Import(HistoricalObjectFixtures.class)
@SpringBootTest
@ActiveProfiles("test")
class FilePurgeIntegrationTest {
    private static final String QUOTA_TEST_SCHEMA = "file_purge_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
    @org.springframework.test.context.DynamicPropertySource
    static void quotaIsolatedSchema(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + QUOTA_TEST_SCHEMA);
        r.add("spring.flyway.schemas", () -> QUOTA_TEST_SCHEMA);
        r.add("spring.flyway.default-schema", () -> QUOTA_TEST_SCHEMA);
    }

    @Autowired com.vaultdrive.deletion.DeletionOperationService operations;

    @Autowired HistoricalObjectFixtures historicalObjects;

    @Autowired HistoricalUploadFixtures history;

    @Autowired private FileService files;
    @Autowired private FileMetadataService metadata;
    @Autowired private StoredFileRepository fileRepository;
    @Autowired private UserRepository users;
    @Autowired private OutboxEventRepository outbox;
    @MockitoSpyBean private OutboxWriter writer;
    @Autowired private FilePurgeJobHandler handler;
    @MockitoSpyBean private FilePurgeProgressService progress;
    @Autowired private FilePurgeRecoveryService recovery;
    @Autowired private FolderService folders;
    @Autowired private FolderRepository folderRepository;
    @Autowired private HierarchyCoordinator hierarchy;
    @MockitoSpyBean private ObjectStorageService storage;
    @MockitoSpyBean private BackgroundJobService jobs;
    @Autowired private BackgroundJobRepository jobRepository;
    @Autowired private StorageBinding binding;
    @Autowired private S3StorageProperties properties;
    @Autowired private S3Client s3;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private UUID owner;
    private TransactionTemplate transaction;
    private final List<UUID> fileIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final List<UUID> folderIds = new ArrayList<>();

    @BeforeEach void setup() {
        transaction = new TransactionTemplate(transactionManager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Purge fixture")).getId();
    }

    @AfterEach void cleanup() {
        reset(storage, jobs);
        reset(AopTestUtils.<FilePurgeProgressService>getUltimateTargetObject(progress));
        reset(AopTestUtils.<OutboxWriter>getUltimateTargetObject(writer));
        transaction.executeWithoutResult(status -> {
            for (Object row : entityManager.createNativeQuery("""
                    SELECT id, storage_key FROM files WHERE owner_id = :owner
                    UNION SELECT file_id, storage_key FROM file_storage_objects WHERE owner_id = :owner
                    """)
                    .setParameter("owner", owner).getResultList()) {
                Object[] values = (Object[]) row;
                UUID id = (UUID) values[0];
                if (!fileIds.contains(id)) { fileIds.add(id); }
                String key = (String) values[1];
                if (!objectKeys.contains(key)) { objectKeys.add(key); }
                outbox.findByAggregateTypeAndAggregateId("FILE", id).forEach(event -> {
                    if (!eventIds.contains(event.getId())) { eventIds.add(event.getId()); }
                });
            }
        });
        try {
            // Only keys allocated by this invocation's fresh owner/file fixtures.
            for (String key : objectKeys) {
                assertThat(key).startsWith("users/" + owner + "/files/");
                s3.deleteObject(request -> request.bucket(properties.bucket()).key(key));
            }
        } finally {
            transaction.executeWithoutResult(status -> {
                jobRepository.deleteAllById(eventIds);
                outbox.deleteAllById(eventIds);
                entityManager.createNativeQuery("DELETE FROM file_purge_targets WHERE owner_id = :owner")
                        .setParameter("owner", owner).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM file_purge_work WHERE owner_id = :owner")
                        .setParameter("owner", owner).executeUpdate();
                fileRepository.deleteAllById(fileIds);
                fileRepository.flush();
                entityManager.createNativeQuery("DELETE FROM file_storage_objects WHERE owner_id = :owner")
                        .setParameter("owner", owner).executeUpdate();
                for (UUID folder : folderIds) {
                    outbox.deleteAll(outbox.findByAggregateTypeAndAggregateId("FOLDER", folder));
                    folderRepository.deleteById(folder);
                    folderRepository.flush();
                }
                users.deleteById(owner);
            });
        }
    }

    @Test void deletionPollingFollowsIntentExecutionAndDurableLogicalCompletion() throws Exception {
        StoredFile file = uploaded();
        UUID event = request(file);
        assertThat(operations.get(owner, event).state().name()).isEqualTo("ACCEPTED");
        jobs.create(event, JobType.FILE_PURGE, file.getId());
        assertThat(operations.get(owner, event).state().name()).isEqualTo("PROCESSING");
        ClaimedJob claim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        // The receipt is authoritative before the separate generic job completion transaction.
        var completed = operations.get(owner, event);
        assertThat(completed.state().name()).isEqualTo("LOGICALLY_COMPLETED");
        assertThat(completed.logicallyCompletedAt()).isNotNull();
        assertThat(fileRepository.findById(file.getId())).isEmpty();
        assertThat(jobs.complete(claim)).isTrue();
        assertThat(operations.get(owner, event)).isEqualTo(completed);
    }

    @Test void deletionPollingReportsExhaustedExecutionWithoutClaimingLogicalCompletion() {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        transaction.executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE background_jobs SET status = 'FAILED', completed_at = clock_timestamp(),
                    claim_token = NULL, claim_expires_at = NULL WHERE id = :id
                """).setParameter("id", claim.id()).executeUpdate());
        assertThat(operations.get(owner, claim.id()).state().name()).isEqualTo("FAILED");
        assertThat(operations.get(owner, claim.id()).logicallyCompletedAt()).isNull();
        assertBytes(file, "fixture");
    }
    @Test void persistedTargetDeletesBytesOutsideTransactionsAndCanReplayAfterLostCompletion() throws Exception {
        StoredFile file = uploaded();
        StoredFile unrelated = uploaded("unrelated.txt");
        ClaimedJob claim = purge(file);
        var event = outbox.findById(claim.id()).orElseThrow();
        assertThat(event.getPayloadVersion()).isEqualTo(1);
        assertThat(event.getPayload()).isEqualTo(Map.of("ownerId", owner.toString(), "fileId", file.getId().toString()));
        AtomicReference<StorageTarget> invoked = new AtomicReference<>();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // Both the resource and job locks have been released before Garage I/O.
            transaction.executeWithoutResult(status -> {
                entityManager.createNativeQuery("SELECT id FROM files WHERE id = :id FOR UPDATE NOWAIT")
                        .setParameter("id", file.getId()).getSingleResult();
                entityManager.createNativeQuery("SELECT id FROM background_jobs WHERE id = :id FOR UPDATE NOWAIT")
                        .setParameter("id", claim.id()).getSingleResult();
                entityManager.createNativeQuery("SELECT file_id FROM file_purge_work WHERE file_id = :id FOR UPDATE NOWAIT")
                        .setParameter("id", file.getId()).getSingleResult();
            });
            invoked.set(call.getArgument(0));
            return call.callRealMethod();
        }).when(storage).delete(any(StorageTarget.class));

        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(invoked.get()).isEqualTo(binding.target(file.getStorageKey()));
        assertMissing(file);
        assertBytes(unrelated, "fixture");
        // Crash window: the first DELETE succeeded but job completion was not recorded.
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jobs.complete(claim)).isTrue();
        assertThat(jobRepository.findById(claim.id()).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(fileRepository.findById(file.getId())).isEmpty();
        assertReceipts(file, true, true);
        verify(storage, times(1)).delete(any(StorageTarget.class));
    }

    @Test void actualWorkerRecordsFencedSuccessOnlyAfterStorageSuccess() throws Exception {
        StoredFile file = uploaded();
        UUID event = request(file);
        jobs.create(event, JobType.FILE_PURGE, file.getId());
        CountDownLatch complete = new CountDownLatch(1);
        doAnswer(call -> {
            var result = call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { complete.countDown(); }
            });
            return result;
        }).when(jobs).complete(any());
        var properties = new JobWorkerProperties(false, 1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofMinutes(1), Duration.ofSeconds(1));
        try (var worker = new JobWorker(jobs, List.of(handler), properties)) {
            worker.poll(); // Explicit test execution; configured background scheduling remains off.
            assertThat(complete.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jobRepository.findById(event).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            assertMissing(file);
        }
    }

    @Test void deleteWithoutReceiptReplaysTheSameTargetAfterCrash() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        doThrow(new IllegalStateException("Injected crash before receipt"))
                .when(AopTestUtils.<FilePurgeProgressService>getUltimateTargetObject(progress)).recordBytesDeleted(any());
        assertThatThrownBy(() -> handler.handle(context(claim))).isInstanceOf(IllegalStateException.class);
        assertMissing(file);
        assertReceipts(file, false, false);
        assertThat(fileRepository.findById(file.getId())).isPresent();
        reset(AopTestUtils.<FilePurgeProgressService>getUltimateTargetObject(progress));
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        verify(storage, times(2)).delete(binding.target(file.getStorageKey()));
        assertReceipts(file, true, true);
    }

    @Test void receiptSurvivesFinalizationCrashAndRetrySkipsGarage() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        doThrow(new IllegalStateException("Injected crash after receipt"))
                .when(AopTestUtils.<FilePurgeProgressService>getUltimateTargetObject(progress)).finalizeMetadata(any());
        assertThatThrownBy(() -> handler.handle(context(claim))).isInstanceOf(IllegalStateException.class);
        assertReceipts(file, true, false);
        assertThat(fileRepository.findById(file.getId())).isPresent();
        reset(AopTestUtils.<FilePurgeProgressService>getUltimateTargetObject(progress));
        assertThat(jobs.retry(claim)).isTrue();
        due(claim.id(), false);
        ClaimedJob next = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(handler.handle(context(next))).isEqualTo(JobHandler.Result.SUCCEEDED);
        verify(storage, times(1)).delete(any(StorageTarget.class));
        assertReceipts(file, true, true);
        assertThat(fileRepository.findById(file.getId())).isEmpty();
    }

    @Test void activityInsertFailureRollsBackFileRemovalAndFinalizationReceipt() {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file); deleteAndRecord(claim);
        transaction.executeWithoutResult(status -> {
            entityManager.createNativeQuery("CREATE FUNCTION test_delete_activity_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'history unavailable'; END $$").executeUpdate();
            entityManager.createNativeQuery("CREATE TRIGGER test_delete_activity_failure BEFORE INSERT ON resource_activity_events FOR EACH ROW EXECUTE FUNCTION test_delete_activity_failure()").executeUpdate();
        });
        try {
            assertThatThrownBy(() -> progress.finalizeMetadata(claim)).isInstanceOf(RuntimeException.class);
            assertThat(fileRepository.findById(file.getId())).isPresent(); assertReceipts(file, true, false);
        } finally {
            transaction.executeWithoutResult(status -> {
                entityManager.createNativeQuery("DROP TRIGGER test_delete_activity_failure ON resource_activity_events").executeUpdate();
                entityManager.createNativeQuery("DROP FUNCTION test_delete_activity_failure()").executeUpdate();
            });
        }
        assertThat(progress.finalizeMetadata(claim)).isTrue(); assertReceipts(file, true, true);
    }

    @Test void workReceiptAndMetadataTransactionsRollBackIndependently() {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        transaction.executeWithoutResult(status -> { progress.prepare(claim); status.setRollbackOnly(); });
        assertThat(workCount()).isZero();
        var prepared = progress.prepare(claim);
        storage.delete(prepared.target());
        transaction.executeWithoutResult(status -> { progress.recordBytesDeleted(claim); status.setRollbackOnly(); });
        assertReceipts(file, false, false);
        progress.recordBytesDeleted(claim);
        transaction.executeWithoutResult(status -> {
            assertThat(progress.finalizeMetadata(claim)).isTrue();
            assertThat(entityManager.createNativeQuery("SELECT count(*) FROM files WHERE id = :file")
                    .setParameter("file", file.getId()).getSingleResult()).isEqualTo(0L);
            status.setRollbackOnly();
        });
        assertReceipts(file, true, false);
        assertThat(fileRepository.findById(file.getId())).isPresent();
        assertThat(progress.finalizeMetadata(claim)).isTrue();
        assertReceipts(file, true, true);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void missingMetadataWithoutReceiptNeverMeansSuccess(boolean prepared) {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        if (prepared) { progress.prepare(claim); }
        fileRepository.deleteById(file.getId());
        assertThat(operations.get(owner, claim.id()).state().name()).isNotEqualTo("LOGICALLY_COMPLETED");
        assertThat(operations.get(owner, claim.id()).logicallyCompletedAt()).isNull();
        clearInvocations(storage);
        assertThatThrownBy(() -> handler.handle(context(claim))).isInstanceOf(IllegalStateException.class);
        verify(storage, never()).delete(any(StorageTarget.class));
        if (prepared) { assertReceipts(file, false, false); }
        else { assertThat(workCount()).isZero(); }
        assertBytes(file, "fixture");
    }

    @Test void missingMetadataWithByteReceiptDoesNotInventFinalizationReceipt() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        deleteAndRecord(claim);
        fileRepository.deleteById(file.getId());
        clearInvocations(storage);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        verify(storage, never()).delete(any(StorageTarget.class));
        assertReceipts(file, true, false);
        assertThat(jobs.complete(claim)).isTrue();
        assertThat(recovery.recoverPage(null, 100).blocked()).isEqualTo(1);
        assertReceipts(file, true, false);
    }

    @Test void replacedTokenRollsBackReceiptAndCannotFinalize() {
        StoredFile file = uploaded();
        ClaimedJob stale = purge(file);
        var prepared = progress.prepare(stale);
        storage.delete(prepared.target());
        due(stale.id(), true);
        ClaimedJob current = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThatThrownBy(() -> progress.recordBytesDeleted(stale))
                .isInstanceOf(FilePurgeProgressService.StaleClaimException.class);
        assertReceipts(file, false, false);
        progress.recordBytesDeleted(current);
        assertThatThrownBy(() -> progress.finalizeMetadata(stale))
                .isInstanceOf(FilePurgeProgressService.StaleClaimException.class);
        assertReceipts(file, true, false);
        assertThat(fileRepository.findById(file.getId())).isPresent();
        assertThat(progress.finalizeMetadata(current)).isTrue();
    }

    @Test void receiptFenceChecksDatabaseClockAfterWaitingForJobLock() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        var prepared = progress.prepare(claim);
        storage.delete(prepared.target());
        assertExpiredAfterJobLockWait(claim, () -> progress.recordBytesDeleted(claim));
        assertReceipts(file, false, false);
        assertThat(fileRepository.findById(file.getId())).isPresent();
    }

    @Test void finalizationFenceChecksDatabaseClockAfterWaitingForJobLock() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        deleteAndRecord(claim);
        assertExpiredAfterJobLockWait(claim, () -> progress.finalizeMetadata(claim));
        assertReceipts(file, true, false);
        assertThat(fileRepository.findById(file.getId())).isPresent();
    }

    @Test void duplicateConcurrentExecutionSharesOneWorkAndFinalization() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        CyclicBarrier deleting = new CyclicBarrier(2);
        doAnswer(call -> {
            deleting.await(5, TimeUnit.SECONDS); // Both prepare before either receipt commits.
            return call.callRealMethod();
        }).when(storage).delete(any(StorageTarget.class));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> handler.handle(context(claim)));
            var two = executor.submit(() -> handler.handle(context(claim)));
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(JobHandler.Result.SUCCEEDED);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(JobHandler.Result.SUCCEEDED);
            assertThat(workCount()).isEqualTo(1);
            assertReceipts(file, true, true);
            assertThat(fileRepository.findById(file.getId())).isEmpty();
        } finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @ParameterizedTest @ValueSource(strings = {
            "version = version + 1", "updated_at = updated_at + interval '1 second'",
            "status = 'FAILED'", "deleted_at = deleted_at + interval '1 second'",
            "purge_requested_at = purge_requested_at + interval '1 second'"})
    void changedLifecycleAfterDeleteRetainsMetadataAndByteSuccess(String change) throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        deleteAndRecord(claim);
        transaction.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE files SET " + change + " WHERE id = :file")
                .setParameter("file", file.getId()).executeUpdate());
        assertThat(progress.finalizeMetadata(claim)).isFalse();
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertReceipts(file, true, false);
        assertThat(fileRepository.findById(file.getId())).isPresent();
    }

    @Test void changedStorageKeyOrFolderCannotBeFinalized() {
        UUID destination = folder();
        for (String change : List.of("storage_key = storage_key || '/changed'", "folder_id = :folder")) {
            StoredFile file = uploaded();
            ClaimedJob claim = purge(file);
            deleteAndRecord(claim);
            transaction.executeWithoutResult(status -> {
                var query = entityManager.createNativeQuery("UPDATE files SET " + change + " WHERE id = :file")
                        .setParameter("file", file.getId());
                if (change.contains(":folder")) { query.setParameter("folder", destination); }
                query.executeUpdate();
            });
            assertThat(progress.finalizeMetadata(claim)).isFalse();
            assertReceipts(file, true, false);
            assertThat(fileRepository.findById(file.getId())).isPresent();
            assertThat(jobs.complete(claim)).isTrue();
        }
    }

    @Test void changedOwnershipCannotBeFinalized() {
        UUID otherOwner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Other fixture")).getId();
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        deleteAndRecord(claim);
        try {
            changeOwnerFixture(file.getId(), otherOwner);
            assertThat(progress.finalizeMetadata(claim)).isFalse();
            assertReceipts(file, true, false);
            assertThat(fileRepository.findById(file.getId()).orElseThrow().getOwnerId()).isEqualTo(otherOwner);
        } finally {
            changeOwnerFixture(file.getId(), owner);
            users.deleteById(otherOwner);
        }
    }

    private void changeOwnerFixture(UUID file, UUID replacementOwner) {
        // Current quota accounting forbids this historical corruption in ordinary writes.
        transaction.executeWithoutResult(status -> {
            entityManager.createNativeQuery("ALTER TABLE files DISABLE TRIGGER trg_account_file_storage").executeUpdate();
            entityManager.createNativeQuery("UPDATE files SET owner_id=:owner WHERE id=:file")
                    .setParameter("owner", replacementOwner).setParameter("file", file).executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE files ENABLE TRIGGER trg_account_file_storage").executeUpdate();
        });
    }

    @Test void finalizationPreservesPermanentAllocationTargetAndJobIdentity() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jobs.complete(claim)).isTrue();
        assertThat(targetCount()).isEqualTo(1);
        assertThat(workCount()).isEqualTo(1);
        assertThat(jobRepository.findById(claim.id())).isPresent();
        StoredFile reused = new StoredFile(UUID.randomUUID(), owner, null, "reused.txt", file.getStorageKey(), null, 1);
        assertThatThrownBy(() -> history.reservation(reused)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> history.admission(file.getStorageKey())).isInstanceOf(IllegalStateException.class);
        transaction.executeWithoutResult(status -> assertThat(entityManager.createNativeQuery(
                "SELECT count(*) FROM file_storage_objects WHERE file_id = :file")
                .setParameter("file", file.getId()).getSingleResult()).isEqualTo(1L));
    }

    @Test void workIdentityAndReceiptsAreImmutableAndFinalizationRequiresBytes() {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        progress.prepare(claim);
        assertSqlRejected("UPDATE file_purge_work SET bucket = 'wrong' WHERE owner_id = :owner");
        assertSqlRejected("UPDATE file_purge_work SET file_version = file_version + 1 WHERE owner_id = :owner");
        assertSqlRejected("UPDATE file_purge_work SET metadata_finalized_at = clock_timestamp() WHERE owner_id = :owner");
        deleteAndRecord(claim);
        assertSqlRejected("UPDATE file_purge_work SET bytes_deleted_at = NULL, byte_receipt_job_id = NULL WHERE owner_id = :owner");
        assertSqlRejected("UPDATE file_purge_work SET byte_receipt_job_id = gen_random_uuid() WHERE owner_id = :owner");
        assertThat(progress.finalizeMetadata(claim)).isTrue();
        assertSqlRejected("UPDATE file_purge_work SET metadata_finalized_at = NULL WHERE owner_id = :owner");
        assertReceipts(file, true, true);
    }

    @Test void historicalSuccessImportsAuthoritativeOutcomeWithoutGarageOrJobReset() {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        storage.delete(binding.target(file.getStorageKey())); // Old handler's authoritative success.
        assertThat(jobs.complete(claim)).isTrue();
        assertThat(operations.get(owner, claim.id()).state().name()).isEqualTo("BLOCKED");
        assertThat(operations.get(owner, claim.id()).reasonCode()).isEqualTo("METADATA_PENDING");
        var before = jobRepository.findById(claim.id()).orElseThrow();
        clearInvocations(storage);
        var page = recovery.recoverPage(null, 1);
        assertThat(page.examined()).isEqualTo(1);
        assertThat(page.finalized()).isEqualTo(1);
        assertThat(operations.get(owner, claim.id()).state().name()).isEqualTo("LOGICALLY_COMPLETED");
        assertThat(page.nextCursor()).isEqualTo(claim.id());
        verify(storage, never()).delete(any(StorageTarget.class));
        assertReceipts(file, true, true);
        assertThat(fileRepository.findById(file.getId())).isEmpty();
        var after = jobRepository.findById(claim.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(after.getAttemptCount()).isEqualTo(before.getAttemptCount());
        assertThat(after.getCompletedAt()).isEqualTo(before.getCompletedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(recovery.recoverPage(null, 1).examined()).isZero();
    }

    @Test void historicalMissingOrChangedMetadataDoesNotInventWorkAndFailedJobsAreExcluded() {
        StoredFile missing = uploaded();
        ClaimedJob first = purge(missing);
        storage.delete(binding.target(missing.getStorageKey()));
        assertThat(jobs.complete(first)).isTrue();
        fileRepository.deleteById(missing.getId());
        StoredFile changed = uploaded();
        ClaimedJob second = purge(changed);
        storage.delete(binding.target(changed.getStorageKey()));
        assertThat(jobs.complete(second)).isTrue();
        transaction.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE files SET version = version + 1 WHERE id = :file")
                .setParameter("file", changed.getId()).executeUpdate());
        StoredFile failed = uploaded();
        ClaimedJob third = purge(failed);
        transaction.executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE background_jobs SET status = 'FAILED', completed_at = clock_timestamp(),
                    claim_token = NULL, claim_expires_at = NULL WHERE id = :id
                """).setParameter("id", third.id()).executeUpdate());
        var page = recovery.recoverPage(null, 100);
        assertThat(page.examined()).isEqualTo(2);
        assertThat(page.blocked()).isEqualTo(2);
        assertThat(page.finalized()).isZero();
        assertThat(workCount()).isZero();
        assertThat(jobRepository.findById(first.id()).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertBytes(failed, "fixture");
    }

    @Test void recoveryKeysetBoundsProgressPastBlockedRows() {
        for (int i = 0; i < 3; i++) {
            StoredFile file = uploaded();
            ClaimedJob claim = purge(file);
            storage.delete(binding.target(file.getStorageKey()));
            assertThat(jobs.complete(claim)).isTrue();
            fileRepository.deleteById(file.getId());
        }
        var first = recovery.recoverPage(null, 2);
        assertThat(first.examined()).isEqualTo(2);
        assertThat(first.blocked()).isEqualTo(2);
        var second = recovery.recoverPage(first.nextCursor(), 2);
        assertThat(second.examined()).isEqualTo(1);
        assertThat(recovery.recoverPage(second.nextCursor(), 2).examined()).isZero();
        assertThatThrownBy(() -> recovery.recoverPage(null, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void pendingParentAllowsByteReceiptButRetainsFileForFutureDiscovery() throws Exception {
        UUID folder = folder();
        StoredFile file = uploadedIn(folder);
        ClaimedJob claim = purge(file);
        folders.deleteFolder(owner, folder);
        folders.requestPermanentDeletion(owner, folder);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jobs.complete(claim)).isTrue();
        assertMissing(file);
        assertReceipts(file, true, false);
        assertThat(fileRepository.findById(file.getId())).isPresent();
        assertThat(recovery.recoverPage(null, 100).blocked()).isEqualTo(1);
        assertReceipts(file, true, false);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void folderPurgeAndFileFinalizationRespectBothWinnerOrders(boolean folderWins) throws Exception {
        UUID folder = folder();
        StoredFile file = uploadedIn(folder);
        ClaimedJob claim = purge(file);
        deleteAndRecord(claim);
        folders.deleteFolder(owner, folder);
        CountDownLatch protectedWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                if (folderWins) { folders.requestPermanentDeletion(owner, folder); }
                else { assertThat(progress.finalizeMetadata(claim)).isTrue(); }
                protectedWrite.countDown();
                await(release);
            }));
            assertThat(protectedWrite.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                attempted.countDown();
                if (folderWins) { assertThat(progress.finalizeMetadata(claim)).isFalse(); }
                else { folders.requestPermanentDeletion(owner, folder); }
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertReceipts(file, true, !folderWins);
            assertThat(fileRepository.findById(file.getId()).isPresent()).isEqualTo(folderWins);
            assertThat(folderRepository.findById(folder).orElseThrow().getPurgeRequestedAt()).isNotNull();
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void finalizerReadsFreshStateAfterHierarchyLockEvenWithCachedEntity() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        deleteAndRecord(claim);
        CountDownLatch cached = new CountDownLatch(1);
        CountDownLatch modified = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> transaction.execute(status -> {
                entityManager.find(StoredFile.class, file.getId());
                cached.countDown();
                await(modified);
                return progress.finalizeMetadata(claim);
            }));
            assertThat(cached.await(5, TimeUnit.SECONDS)).isTrue();
            transaction.executeWithoutResult(status -> {
                hierarchy.acquireExclusive(owner);
                entityManager.createNativeQuery("UPDATE files SET version = version + 1 WHERE id = :file")
                        .setParameter("file", file.getId()).executeUpdate();
            });
            modified.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            assertReceipts(file, true, false);
            assertThat(fileRepository.findById(file.getId())).isPresent();
        } finally { modified.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    private void deleteAndRecord(ClaimedJob claim) {
        var prepared = progress.prepare(claim);
        storage.delete(prepared.target());
        progress.recordBytesDeleted(claim);
    }

    private void assertSqlRejected(String sql) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> entityManager.createNativeQuery(sql)
                .setParameter("owner", owner).executeUpdate())).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    private long workCount() {
        return transaction.execute(status -> ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM file_purge_work WHERE owner_id = :owner")
                .setParameter("owner", owner).getSingleResult()).longValue());
    }

    private UUID folder() {
        UUID id = folders.createFolder(owner, null, "Purge folder");
        folderIds.add(id);
        return id;
    }

    private StoredFile uploadedIn(UUID folder) {
        var result = historicalObjects.completed(owner, folder, "fixture.txt", "text/plain", "fixture".getBytes());
        fileIds.add(result.getId());
        StoredFile file = fileRepository.findById(result.getId()).orElseThrow();
        objectKeys.add(file.getStorageKey());
        return file;
    }

    private void assertExpiredAfterJobLockWait(ClaimedJob claim, Runnable mutation) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                entityManager.createNativeQuery("SELECT id FROM background_jobs WHERE id = :id FOR UPDATE")
                        .setParameter("id", claim.id()).getSingleResult();
                locked.countDown();
                await(release);
                due(claim.id(), true); // Commit expiration while the contender is waiting.
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var contender = executor.submit(() -> { attempted.countDown(); mutation.run(); });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> contender.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> contender.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(FilePurgeProgressService.StaleClaimException.class);
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    @Test void initiallyMissingObjectIsSuccess() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        s3.deleteObject(request -> request.bucket(properties.bucket()).key(file.getStorageKey()));
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jobs.complete(claim)).isTrue();
        assertMissing(file);
    }

    @Test void storageFailureRetainsTargetAndBytesAndRetriesWithNewClaim() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob first = purge(file);
        doThrow(new StorageDeletionException(StorageDeletionException.Kind.TRANSIENT))
                .when(storage).delete(any(StorageTarget.class));
        assertThat(handler.handle(context(first))).isEqualTo(JobHandler.Result.RETRY);
        assertThat(jobs.retry(first)).isTrue();
        assertBytes(file, "fixture");
        assertThat(targetCount()).isEqualTo(1);
        reset(storage);
        due(first.id(), false);
        ClaimedJob retry = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(retry.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(handler.handle(context(retry))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jobs.complete(first)).isFalse();
        assertThat(jobs.complete(retry)).isTrue();
        assertMissing(file);
    }

    @Test void expiredOrReplacedClaimDoesNotStartGarageDelete() throws Exception {
        StoredFile file = uploaded();
        ClaimedJob stale = purge(file);
        due(stale.id(), true);
        ClaimedJob replacement = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        clearInvocations(storage);
        assertThatThrownBy(() -> handler.handle(context(stale))).isInstanceOf(InterruptedException.class);
        verify(storage, never()).delete(any(StorageTarget.class));
        assertBytes(file, "fixture");
        assertThat(handler.handle(context(replacement))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jobs.complete(replacement)).isTrue();
    }

    @Test void cancellationPreventsDelete() {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        JobExecutionContext context = context(claim);
        context.cancel();
        clearInvocations(storage);
        assertThatThrownBy(() -> handler.handle(context)).isInstanceOf(InterruptedException.class);
        verify(storage, never()).delete(any(StorageTarget.class));
        assertBytes(file, "fixture");
    }

    @Test void staleInFlightDeleteCanRepeatOnlyTheOldTargetAndCannotCompleteNewClaim() throws Exception {
        StoredFile old = uploaded();
        ClaimedJob stale = purge(old);
        StoredFile newer = uploaded(); // Same display name, distinct immutable key.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Delete release timed out"); }
            return call.callRealMethod();
        }).when(storage).delete(any(StorageTarget.class));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> handler.handle(context(stale)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            due(stale.id(), true);
            ClaimedJob replacement = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
            reset(storage); // New claimant can execute independently of the paused old invocation.
            assertThat(handler.handle(context(replacement))).isEqualTo(JobHandler.Result.SUCCEEDED);
            assertThat(jobs.complete(replacement)).isTrue();
            release.countDown();
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(jobs.complete(stale)).isFalse();
            assertMissing(old);
            assertBytes(newer, "fixture");
            assertThat(jobRepository.findById(stale.id()).orElseThrow().getClaimToken()).isNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test void legacyRequestHasNoGuessedTargetAndCannotDelete() {
        StoredFile file = rawFile();
        file.markReady();
        file.softDelete();
        fileRepository.saveAndFlush(file);
        ClaimedJob claim = purgeAlreadyTrashed(file);
        clearInvocations(storage);
        assertThat(targetCount()).isZero();
        assertThatThrownBy(() -> handler.handle(context(claim))).isInstanceOf(IllegalStateException.class)
                .hasMessage("Verified purge target is absent");
        verify(storage, never()).delete(any(StorageTarget.class));
        assertThat(jobs.retry(claim)).isTrue();
        assertThat(jobRepository.findById(claim.id()).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test void changedFileVersionAndWrongJobAggregateFailClosed() {
        StoredFile file = uploaded();
        ClaimedJob claim = purge(file);
        clearInvocations(storage);
        ClaimedJob wrong = new ClaimedJob(claim.id(), claim.jobType(), claim.aggregateType(), UUID.randomUUID(),
                claim.claimToken(), claim.claimExpiresAt(), claim.attemptCount());
        assertThatThrownBy(() -> handler.handle(context(wrong))).isInstanceOf(IllegalStateException.class);
        transaction.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE files SET version = version + 1 WHERE id = :id").setParameter("id", file.getId()).executeUpdate());
        assertThatThrownBy(() -> handler.handle(context(claim))).isInstanceOf(IllegalStateException.class);
        verify(storage, never()).delete(any(StorageTarget.class));
        assertBytes(file, "fixture");
    }

    @Test void immutableTargetsAndAllocationsRejectSqlReplacement() {
        StoredFile file = uploaded();
        purge(file);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE file_purge_targets SET bucket = 'wrong' WHERE owner_id = :owner")
                .setParameter("owner", owner).executeUpdate())).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE file_storage_objects SET write_started = FALSE WHERE owner_id = :owner")
                .setParameter("owner", owner).executeUpdate())).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE file_storage_objects SET endpoint = 'wrong' WHERE owner_id = :owner")
                .setParameter("owner", owner).executeUpdate())).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertBytes(file, "fixture");
    }

    @Test void migrationReservesLegacyKeysWithoutInventingBindings() throws Exception {
        String schema = "purge_migration_" + UUID.randomUUID().toString().replace("-", "");
        var migration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("15").load();
        try {
            migration.migrate();
            UUID legacyFile = UUID.randomUUID();
            try (var connection = dataSource.getConnection()) {
                String previousSchema = connection.getSchema();
                try {
                connection.setSchema(schema);
                try (var insert = connection.prepareStatement("INSERT INTO users(id,email,password_hash,display_name) VALUES (?,?,?,?)")) {
                    insert.setObject(1, owner); insert.setString(2, owner + "@example.com");
                    insert.setString(3, "test-hash"); insert.setString(4, "Legacy fixture"); insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO files(id,owner_id,name,storage_key,size_bytes,status,created_at,updated_at,deleted_at,purge_requested_at)
                        VALUES (?,?,'legacy.txt',?,1,'READY',now(),now(),now(),now())
                        """)) {
                    insert.setObject(1, legacyFile); insert.setObject(2, owner);
                    insert.setString(3, "legacy/" + legacyFile); insert.executeUpdate();
                }
                } finally { connection.setSchema(previousSchema); }
            }
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            try (var connection = dataSource.getConnection()) {
                String previousSchema = connection.getSchema();
                try {
                connection.setSchema(schema);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT storage_namespace, endpoint, region, bucket, write_started FROM file_storage_objects")) {
                    assertThat(rows.next()).isTrue();
                    for (int i = 1; i <= 4; i++) { assertThat(rows.getObject(i)).isNull(); }
                    assertThat(rows.getBoolean(5)).isTrue();
                    assertThat(rows.next()).isFalse();
                }
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM file_purge_targets")) {
                    rows.next(); assertThat(rows.getInt(1)).isZero();
                }
                } finally { connection.setSchema(previousSchema); }
            }
        } finally {
            // Flyway is explicitly scoped to this newly allocated fixture schema only.
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).cleanDisabled(false).load().clean();
        }
    }

    @Test void realOutboxInsertConstraintFailureRollsBackTargetAndIntent() {
        StoredFile file = uploaded();
        files.trashFile(owner, file.getId());
        doAnswer(call -> {
            assertThat(targetCount()).isEqualTo(1);
            OutboxEvent event = call.getArgument(0);
            ReflectionTestUtils.setField(event, "payloadVersion", 0);
            return call.callRealMethod();
        }).when(AopTestUtils.<OutboxWriter>getUltimateTargetObject(writer)).append(any());
        assertThatThrownBy(() -> metadata.requestPermanentDeletion(owner, file.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(targetCount()).isZero();
        assertThat(outbox.findByAggregateTypeAndAggregateId("FILE", file.getId())).isEmpty();
        assertThat(fileRepository.findById(file.getId()).orElseThrow().getPurgeRequestedAt()).isNull();
    }

    @Test void independentTransactionCannotSeeUncommittedTargetOrIntent() throws Exception {
        StoredFile file = uploaded();
        files.trashFile(owner, file.getId());
        var executor = Executors.newSingleThreadExecutor();
        try {
            transaction.executeWithoutResult(status -> {
                metadata.requestPermanentDeletion(owner, file.getId());
                assertThat(targetCount()).isEqualTo(1);
                try {
                    executor.submit(() -> {
                        assertThat(targetCount()).isZero();
                        assertThat(outbox.findByAggregateTypeAndAggregateId("FILE", file.getId())).isEmpty();
                        assertThat(fileRepository.findById(file.getId()).orElseThrow().getPurgeRequestedAt()).isNull();
                    }).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            assertThat(targetCount()).isEqualTo(1);
        } finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void purgeTargetOutboxAndIntentRollBackTogether(boolean failOutbox) {
        StoredFile file = uploaded();
        files.trashFile(owner, file.getId());
        if (failOutbox) {
            doThrow(new IllegalStateException("Injected outbox failure"))
                    .when(AopTestUtils.<OutboxWriter>getUltimateTargetObject(writer)).append(any());
        }
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            metadata.requestPermanentDeletion(owner, file.getId());
            assertThat(targetCount()).isEqualTo(1);
            throw new IllegalStateException("Outer rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(targetCount()).isZero();
        assertThat(outbox.findByAggregateTypeAndAggregateId("FILE", file.getId())).isEmpty();
        assertThat(fileRepository.findById(file.getId()).orElseThrow().getPurgeRequestedAt()).isNull();
        assertBytes(file, "fixture");
    }

    private StoredFile uploaded() {
        return uploaded("fixture.txt");
    }

    private StoredFile uploaded(String name) {
        var uploaded = historicalObjects.completed(owner, null, name, "text/plain", "fixture".getBytes());
        fileIds.add(uploaded.getId());
        StoredFile file = fileRepository.findById(uploaded.getId()).orElseThrow();
        objectKeys.add(file.getStorageKey());
        return file;
    }

    private StoredFile rawFile() {
        UUID id = UUID.randomUUID();
        fileIds.add(id);
        return new StoredFile(id, owner, null, "fixture.txt", "users/" + owner + "/files/" + id, "text/plain", 7);
    }

    private UUID request(StoredFile file) {
        files.trashFile(owner, file.getId());
        metadata.requestPermanentDeletion(owner, file.getId());
        UUID event = outbox.findByAggregateTypeAndAggregateId("FILE", file.getId()).getFirst().getId();
        eventIds.add(event);
        return event;
    }

    private ClaimedJob purge(StoredFile file) {
        UUID event = request(file);
        jobs.create(event, JobType.FILE_PURGE, file.getId());
        return jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
    }

    private ClaimedJob purgeAlreadyTrashed(StoredFile file) {
        metadata.requestPermanentDeletion(owner, file.getId());
        UUID event = outbox.findByAggregateTypeAndAggregateId("FILE", file.getId()).getFirst().getId();
        eventIds.add(event);
        jobs.create(event, JobType.FILE_PURGE, file.getId());
        return jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
    }

    private JobExecutionContext context(ClaimedJob claim) {
        return new JobExecutionContext(claim, Duration.ofMinutes(1).toNanos(), System::nanoTime);
    }

    private long targetCount() {
        return transaction.execute(status -> ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM file_purge_targets WHERE owner_id = :owner").setParameter("owner", owner).getSingleResult()).longValue());
    }

    private void assertReceipts(StoredFile file, boolean bytes, boolean finalized) {
        transaction.executeWithoutResult(status -> {
            long events = ((Number) entityManager.createNativeQuery("SELECT count(*) FROM resource_activity_events WHERE resource_type = 'FILE' AND resource_id = :file AND event_type = 'PERMANENTLY_DELETED' AND actor_attribution = 'DELETION_REQUESTER'")
                    .setParameter("file", file.getId()).getSingleResult()).longValue();
            assertThat(events).isEqualTo(finalized ? 1 : 0);
        });
        transaction.executeWithoutResult(status -> {
            Object[] receipts = (Object[]) entityManager.createNativeQuery("""
                    SELECT bytes_deleted_at IS NOT NULL, metadata_finalized_at IS NOT NULL
                    FROM file_purge_work WHERE file_id = :file
                    """).setParameter("file", file.getId()).getSingleResult();
            assertThat(receipts).containsExactly(bytes, finalized);
        });
    }

    private void due(UUID id, boolean expire) {
        transaction.executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE background_jobs SET next_attempt_at = clock_timestamp() - interval '1 second'"
                        + (expire ? ", claim_expires_at = clock_timestamp() - interval '1 second'" : "")
                        + " WHERE id = :id").setParameter("id", id).executeUpdate());
    }

    private void assertMissing(StoredFile file) {
        assertThatThrownBy(() -> s3.getObjectAsBytes(request -> request.bucket(properties.bucket()).key(file.getStorageKey())))
                .isInstanceOf(NoSuchKeyException.class);
    }

    private void assertBytes(StoredFile file, String expected) {
        assertThat(s3.getObjectAsBytes(request -> request.bucket(properties.bucket()).key(file.getStorageKey())).asUtf8String())
                .isEqualTo(expected);
    }
}
