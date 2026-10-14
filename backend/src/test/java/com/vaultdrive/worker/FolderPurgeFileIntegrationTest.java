package com.vaultdrive.worker;

import com.vaultdrive.file.FileMetadataService;
import com.vaultdrive.file.FileService;
import com.vaultdrive.file.FileStatus;
import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.BackgroundJobRepository;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobStatus;
import com.vaultdrive.job.JobType;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.storage.FileStorageIdentityService;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageBinding;
import com.vaultdrive.storage.StorageDeletionException;
import com.vaultdrive.storage.StorageTarget;
import com.vaultdrive.storage.config.S3StorageProperties;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/Garage; only this test's fresh UUID owner/key allocations are mutated. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=folder_file_purge_test",
        "spring.flyway.schemas=folder_file_purge_test", "spring.flyway.default-schema=folder_file_purge_test"
})
@ActiveProfiles("test")
class FolderPurgeFileIntegrationTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired UserRepository users;
    @Autowired FolderRepository folderRepository;
    @Autowired FolderService folders;
    @Autowired StoredFileRepository fileRepository;
    @Autowired FileService files;
    @Autowired FileMetadataService metadata;
    @Autowired FileStorageIdentityService identity;
    @Autowired OutboxEventRepository outbox;
    @Autowired BackgroundJobRepository jobRepository;
    @MockitoSpyBean BackgroundJobService jobs;
    @Autowired HierarchyCoordinator hierarchy;
    @Autowired FolderPurgeDiscoveryService discovery;
    @Autowired FolderPurgeJobHandler handler;
    @MockitoSpyBean FolderPurgeFileService progress;
    @Autowired FilePurgeProgressService standalone;
    @Autowired FilePurgeJobHandler fileHandler;
    @MockitoSpyBean ObjectStorageService storage;
    @Autowired StorageBinding binding;
    @Autowired S3StorageProperties properties;
    @Autowired S3Client s3;
    TransactionTemplate tx;
    UUID owner, root, event;
    final List<String> keys = new ArrayList<>();

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Folder bytes")).getId();
        root = folderRepository.saveAndFlush(new Folder(owner, null, "root")).getId();
    }

    @AfterEach void cleanup() {
        reset(storage);
        reset(AopTestUtils.<FolderPurgeFileService>getUltimateTargetObject(progress));
        reset(AopTestUtils.<BackgroundJobService>getUltimateTargetObject(jobs));
        try {
            for (String key : keys) {
                assertThat(key).startsWith("users/" + owner + "/files/");
                s3.deleteObject(r -> r.bucket(properties.bucket()).key(key));
            }
        } finally {
            tx.executeWithoutResult(ignored -> {
                for (String sql : new String[]{
                        "DELETE FROM folder_purge_file_progress WHERE event_id IN (SELECT event_id FROM folder_purge_runs WHERE owner_id = :owner)",
                        "DELETE FROM folder_purge_files WHERE owner_id = :owner",
                        "DELETE FROM folder_purge_frontier WHERE owner_id = :owner",
                        "DELETE FROM folder_purge_runs WHERE owner_id = :owner",
                        "DELETE FROM background_jobs WHERE id IN (SELECT id FROM outbox_events WHERE payload->>'ownerId' = CAST(:owner AS text))",
                        "DELETE FROM outbox_events WHERE payload->>'ownerId' = CAST(:owner AS text)",
                        "DELETE FROM file_purge_work WHERE owner_id = :owner",
                        "DELETE FROM file_purge_targets WHERE owner_id = :owner",
                        "DELETE FROM files WHERE owner_id = :owner",
                        "DELETE FROM file_storage_objects WHERE owner_id = :owner",
                        "DELETE FROM folders WHERE owner_id = :owner",
                        "DELETE FROM users WHERE id = :owner"}) {
                    em.createNativeQuery(sql).setParameter("owner", owner).executeUpdate();
                }
            });
        }
    }

    @Test void registeredHandlerDiscoversDeletesMultipleBatchesAndBeginsMetadataRemoval() throws Exception {
        StoredFile first = uploaded(root);
        StoredFile unrelated = uploaded(null);
        for (int i = 0; i < 42; i++) { raw(FileStatus.UPLOADING, false, true); }
        ClaimedJob claim = accept(false);
        int invocations = 0;
        while (!phase().equals("DELETE_FOLDERS")) {
            assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
            assertThat(jobs.continueJob(claim)).isTrue();
            assertThat(jobRepository.findById(event).orElseThrow().getFailureCount()).isZero();
            assertThat(++invocations).isLessThan(10);
            due(event, false);
            claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        }
        assertThat(invocations).isGreaterThanOrEqualTo(5);
        assertThat(count("folder_purge_file_progress", "completed_at IS NOT NULL")).isEqualTo(43);
        assertMissing(first);
        assertBytes(unrelated);
        assertThat(fileRepository.existsById(first.getId())).isTrue();
        assertThat(folderRepository.existsById(root)).isTrue();
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(count("file_purge_work", "metadata_finalized_at IS NOT NULL")).isEqualTo(20);
        assertThat(jobRepository.findById(event).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        verify(storage, times(1)).delete(any(StorageTarget.class));
    }

    @Test void healthyFileCheckpointsBeyondTenClaimsDoNotSpendFailureBudget() throws Exception {
        for (int i = 0; i < 15; i++) { raw(FileStatus.FAILED, false, true); }
        ClaimedJob claim = accept(true);
        for (int i = 0; i < 15; i++) {
            UUID file = progress.batch(claim, 1).getFirst();
            var ctx = context(claim);
            progress.prepare(ctx, file);
            assertThat(progress.completeFile(ctx, file, version())).isTrue();
            assertThat(jobs.continueJob(claim)).isTrue();
            due(event, false);
            claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        }
        assertThat(jobRepository.findById(event).orElseThrow().getFailureCount()).isZero();
        assertThat(progress.advancePhase(context(claim), version())).isTrue();
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @Test void crashAfterGarageDeleteBeforeReceiptCommitReplaysExactTarget() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(true);
        var target = AopTestUtils.<FolderPurgeFileService>getUltimateTargetObject(progress);
        doThrow(new IllegalStateException("simulated receipt crash")).when(target).completeFile(any(), eq(file.getId()), anyLong());
        long before = version();
        assertThatThrownBy(() -> handler.handle(context(claim))).hasMessageContaining("receipt crash");
        assertMissing(file);
        assertThat(count("file_purge_work", "bytes_deleted_at IS NOT NULL")).isZero();
        assertThat(version()).isEqualTo(before);
        assertThat(phase()).isEqualTo("DELETE_FILES");
        reset(target);
        due(event, true);
        var replacement = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        assertThat(handler.handle(context(replacement))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(count("file_purge_work", "bytes_deleted_at IS NOT NULL")).isEqualTo(1);
        verify(storage, times(2)).delete(binding.target(file.getStorageKey()));
        assertThat(jobRepository.findById(event).orElseThrow().getFailureCount()).isEqualTo(1);
    }

    @Test void missingObjectAndDuplicateMemberCompletionAreIdempotent() throws Exception {
        StoredFile file = uploaded(root);
        storage.delete(binding.target(file.getStorageKey()));
        clearInvocations(storage);
        ClaimedJob claim = accept(true);
        var ctx = context(claim);
        progress.prepare(ctx, file.getId());
        storage.delete(binding.target(file.getStorageKey()));
        assertThat(progress.completeFile(ctx, file.getId(), version())).isTrue();
        long after = version();
        assertThat(progress.prepare(ctx, file.getId()).proven()).isTrue();
        assertThat(progress.completeFile(ctx, file.getId(), after)).isFalse();
        assertThat(version()).isEqualTo(after);
        assertMissing(file);
        assertThat(progress.advancePhase(ctx, after)).isTrue();
        verify(storage, times(1)).delete(any(StorageTarget.class));
    }

    @ParameterizedTest @ValueSource(strings = {"FAILED", "RUNNING", "SUCCEEDED"})
    void standaloneOverlapReusesWorkWithoutChangingStandaloneState(String state) throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob fileClaim = standaloneClaim(file);
        var prepared = standalone.prepare(fileClaim);
        if (state.equals("SUCCEEDED")) {
            storage.delete(prepared.target());
            standalone.recordBytesDeleted(fileClaim);
            assertThat(jobs.complete(fileClaim)).isTrue();
        } else if (state.equals("FAILED")) {
            for (int i = 0; i < 10; i++) {
                assertThat(jobs.retry(fileClaim)).isTrue();
                if (i < 9) { due(fileClaim.id(), false); fileClaim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow(); }
            }
        }
        var before = jobRepository.findById(fileClaim.id()).orElseThrow();
        var count = before.getAttemptCount();
        var failures = before.getFailureCount();
        clearInvocations(storage);
        ClaimedJob folderClaim = accept(true);
        assertThat(handler.handle(context(folderClaim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        assertMissing(file);
        assertThat(count("file_purge_work", "TRUE")).isEqualTo(1);
        var after = jobRepository.findById(fileClaim.id()).orElseThrow();
        assertThat(after.getStatus().name()).isEqualTo(state);
        assertThat(after.getAttemptCount()).isEqualTo(count);
        assertThat(after.getFailureCount()).isEqualTo(failures);
        verify(storage, times(state.equals("SUCCEEDED") ? 0 : 1)).delete(any(StorageTarget.class));
        if (state.equals("RUNNING")) {
            assertThat(fileHandler.handle(context(fileClaim))).isEqualTo(JobHandler.Result.SUCCEEDED);
            assertThat(fileRepository.existsById(file.getId())).isFalse();
        }
    }

    @Test void finalizedStandaloneMemberReusesReceiptWithMissingMetadata() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob fileClaim = standaloneClaim(file);
        standalone.prepare(fileClaim);
        ClaimedJob folderClaim = accept(true);
        assertThat(fileHandler.handle(context(fileClaim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(fileRepository.existsById(file.getId())).isFalse();
        clearInvocations(storage);
        assertThat(handler.handle(context(folderClaim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @ParameterizedTest @ValueSource(strings = {"UPLOADING", "FAILED"})
    void admittedWritersRemainBlockedRegardlessOfAge(String state) throws Exception {
        StoredFile unsafe = raw(FileStatus.valueOf(state), true, true);
        sql("UPDATE files SET created_at = clock_timestamp() - interval '10 years', updated_at = clock_timestamp() - interval '10 years' WHERE id = :file", unsafe.getId());
        ClaimedJob claim = accept(true);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE); // durable blocked disposition
        assertThat(phase()).isEqualTo("DELETE_FILES");
        assertThat(count("file_purge_work", "TRUE")).isZero();
        assertThat(count("folder_purge_file_progress", "blocked_reason = 'UNSAFE_WRITER'")).isEqualTo(1);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.RETRY);
        assertThat(fileRepository.existsById(unsafe.getId())).isTrue();
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownOrMissingLegacyBindingIsNotReconstructed(boolean allocation) throws Exception {
        StoredFile legacy = raw(FileStatus.READY, true, false);
        if (allocation) {
            tx.executeWithoutResult(ignored -> em.createNativeQuery("""
                    INSERT INTO file_storage_objects(storage_key, file_id, owner_id, write_started)
                    SELECT storage_key, id, owner_id, TRUE FROM files WHERE id = :file
                    """).setParameter("file", legacy.getId()).executeUpdate());
        }
        ClaimedJob claim = accept(true);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(phase()).isEqualTo("DELETE_FILES");
        assertThat(count("folder_purge_file_progress", "blocked_reason = 'UNKNOWN_BINDING'")).isEqualTo(1);
        assertThat(count("file_purge_work", "TRUE")).isZero();
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @Test void blockedFilesDoNotStarveLaterEligibleFiles() throws Exception {
        for (int i = 0; i < 21; i++) { raw(FileStatus.FAILED, true, true); }
        StoredFile ready = uploaded(root);
        ClaimedJob claim = accept(true);
        for (int i = 0; i < 2; i++) {
            assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
            assertThat(jobs.continueJob(claim)).isTrue();
            due(event, false);
            claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        }
        assertMissing(ready);
        assertThat(phase()).isEqualTo("DELETE_FILES");
        long before = version();
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.RETRY);
        assertThat(version()).isEqualTo(before);
        assertThat(jobs.retry(claim)).isTrue();
        assertThat(jobRepository.findById(event).orElseThrow().getFailureCount()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"UPLOADING", "FAILED", "READY"})
    void neverAdmittedWritesGetPermanentProofWithoutDelete(String state) throws Exception {
        StoredFile file = raw(FileStatus.valueOf(state), false, true);
        ClaimedJob claim = accept(true);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(count("file_purge_work", "never_written_at IS NOT NULL AND bytes_deleted_at IS NULL")).isEqualTo(1);
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        assertThatThrownBy(() -> identity.beginWrite(file.getStorageKey())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql("UPDATE file_storage_objects SET write_started = TRUE WHERE file_id = :file", file.getId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql("UPDATE file_storage_objects SET write_precluded_at = NULL WHERE file_id = :file", file.getId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql("UPDATE file_purge_work SET never_written_at = NULL, never_written_job_id = NULL WHERE file_id = :file", file.getId())).isInstanceOf(RuntimeException.class);
        assertThat(fileRepository.existsById(file.getId())).isTrue();
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @ParameterizedTest @ValueSource(strings = {"version", "folder", "key", "updated", "status"})
    void freshLifecycleDriftBlocksDeletion(String field) throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(true);
        switch (field) {
            case "version" -> sql("UPDATE files SET version = version + 1 WHERE id = :file", file.getId());
            case "folder" -> {
                sql("UPDATE files SET folder_id = NULL WHERE id = :file", file.getId());
            }
            case "key" -> sql("UPDATE files SET storage_key = storage_key || '/changed' WHERE id = :file", file.getId());
            case "updated" -> sql("UPDATE files SET updated_at = updated_at + interval '1 second' WHERE id = :file", file.getId());
            case "status" -> sql("UPDATE files SET status = 'FAILED' WHERE id = :file", file.getId());
        }
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(phase()).isEqualTo("DELETE_FILES");
        assertBytes(file);
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @Test void configuredBindingDriftFailsClosedUsingOriginalAllocation() throws Exception {
        StoredFile file = uploaded(root);
        // Reserve a verified original binding in a disposable fixture different from today's client.
        StoredFile historical = tx.execute(ignored -> {
            UUID id = UUID.randomUUID();
            StoredFile f = new StoredFile(id, owner, root, id + ".txt", key(id), "text/plain", 1);
            f.markReady(); fileRepository.saveAndFlush(f);
            StorageTarget original = binding.target(f.getStorageKey());
            em.createNativeQuery("""
                    INSERT INTO file_storage_objects(storage_key, file_id, owner_id, storage_namespace, endpoint, region, bucket, write_started)
                    VALUES (:key, :file, :owner, :namespace, :endpoint, :region, :bucket, TRUE)
                    """).setParameter("key", original.key()).setParameter("file", id).setParameter("owner", owner)
                    .setParameter("namespace", original.namespace() + "-historical").setParameter("endpoint", original.endpoint())
                    .setParameter("region", original.region()).setParameter("bucket", original.bucket()).executeUpdate();
            return f;
        });
        ClaimedJob claim = accept(true);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.RETRY);
        assertMissing(file); // Eligible work still makes progress.
        assertThat(phase()).isEqualTo("DELETE_FILES");
        assertThat(count("file_purge_work", "bytes_deleted_at IS NOT NULL")).isEqualTo(1);
        verify(storage).delete(argThat((StorageTarget t) -> t.key().equals(historical.getStorageKey()) && t.namespace().endsWith("-historical")));
    }

    @ParameterizedTest @ValueSource(strings = {"TRANSIENT", "ACCESS_DENIED", "STORAGE_FAILURE"})
    void garageFailuresNeverCreateReceipts(String kind) throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(true);
        doThrow(new StorageDeletionException(StorageDeletionException.Kind.valueOf(kind))).when(storage).delete(any(StorageTarget.class));
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.RETRY);
        assertThat(count("file_purge_work", "bytes_deleted_at IS NOT NULL")).isZero();
        assertThat(phase()).isEqualTo("DELETE_FILES");
        assertBytes(file);
        reset(storage);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertMissing(file);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void staleOrExpiredWorkersCannotCreateTargetReceiptOrPhase(boolean replace) throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(true);
        if (replace) { sql("UPDATE background_jobs SET claim_token = gen_random_uuid() WHERE id = :event", null); }
        else { due(event, true); }
        long before = version();
        assertThatThrownBy(() -> progress.prepare(context(claim), file.getId())).hasMessageContaining("stale");
        assertThat(count("file_purge_work", "TRUE")).isZero();
        assertThatThrownBy(() -> progress.advancePhase(context(claim), before)).hasMessageContaining("stale");
        assertThat(version()).isEqualTo(before);
        assertBytes(file);
    }

    @Test void leaseExpirationDuringFinalJobLockWaitRollsBackReceiptAndCheckpoint() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(true);
        var ctx = context(claim);
        progress.prepare(ctx, file.getId());
        storage.delete(binding.target(file.getStorageKey()));
        CountDownLatch locked = new CountDownLatch(1), checkpoint = new CountDownLatch(1), release = new CountDownLatch(1);
        var jobsTarget = AopTestUtils.<BackgroundJobService>getUltimateTargetObject(jobs);
        doAnswer(call -> { checkpoint.countDown(); return call.callRealMethod(); }).when(jobsTarget).checkpoint(any(), anyLong());
        long before = version();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.executeWithoutResult(ignored -> {
                em.createNativeQuery("SELECT id FROM background_jobs WHERE id = :event FOR UPDATE").setParameter("event", event).getSingleResult();
                locked.countDown(); await(release);
                em.createNativeQuery("UPDATE background_jobs SET claim_expires_at = clock_timestamp() - interval '1 second' WHERE id = :event")
                        .setParameter("event", event).executeUpdate();
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var receipt = executor.submit(() -> progress.completeFile(ctx, file.getId(), before));
                assertThat(checkpoint.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> receipt.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown(); holder.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> receipt.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(IllegalStateException.class);
            } finally { release.countDown(); }
        }
        assertThat(version()).isEqualTo(before);
        assertThat(count("file_purge_work", "bytes_deleted_at IS NOT NULL")).isZero();
        assertThat(count("folder_purge_file_progress", "completed_at IS NOT NULL")).isZero();
        assertThat(phase()).isEqualTo("DELETE_FILES");
    }

    @Test void duplicateConcurrentWorkersShareOneMonotonicReceipt() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(true);
        progress.prepare(context(claim), file.getId());
        storage.delete(binding.target(file.getStorageKey()));
        long before = version();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { await(start); return progress.completeFile(context(claim), file.getId(), before); });
            var two = executor.submit(() -> { await(start); return progress.completeFile(context(claim), file.getId(), before); });
            start.countDown();
            assertThat(List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(version()).isEqualTo(before + 1);
        assertThat(count("file_purge_work", "bytes_deleted_at IS NOT NULL")).isEqualTo(1);
    }

    @Test void storageIoHoldsNoDatabaseOrHierarchyLocksAndCancellationLosesNoProof() throws Exception {
        StoredFile file = uploaded(root);
        StoredFile unrelated = uploaded(null);
        ClaimedJob claim = accept(true);
        var ctx = context(claim);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            tx.executeWithoutResult(ignored -> {
                hierarchy.acquireExclusive(owner);
                em.createNativeQuery("SELECT id FROM files WHERE id = :file FOR UPDATE NOWAIT").setParameter("file", file.getId()).getSingleResult();
                em.createNativeQuery("SELECT file_id FROM file_purge_work WHERE file_id = :file FOR UPDATE NOWAIT").setParameter("file", file.getId()).getSingleResult();
                em.createNativeQuery("SELECT id FROM background_jobs WHERE id = :event FOR UPDATE NOWAIT").setParameter("event", event).getSingleResult();
            });
            call.callRealMethod(); ctx.cancel(); return null;
        }).when(storage).delete(any(StorageTarget.class));
        assertThatThrownBy(() -> handler.handle(ctx)).isInstanceOf(InterruptedException.class);
        assertMissing(file); assertBytes(unrelated);
        assertThat(count("file_purge_work", "bytes_deleted_at IS NOT NULL")).isZero();
        assertThat(phase()).isEqualTo("DELETE_FILES");
    }

    @Test void phaseAndMemberGuardsRejectIncompleteProofAndRollbackAtomically() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(true);
        assertThat(progress.advancePhase(context(claim), version())).isFalse();
        assertThatThrownBy(() -> sql("UPDATE folder_purge_runs SET phase = 'DELETE_FOLDERS' WHERE event_id = :event", null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> em.createNativeQuery("""
                INSERT INTO folder_purge_file_progress(event_id, file_id, completed_at) VALUES (:event, :file, clock_timestamp())
                """).setParameter("event", event).setParameter("file", file.getId()).executeUpdate())).isInstanceOf(RuntimeException.class);
        progress.prepare(context(claim), file.getId());
        storage.delete(binding.target(file.getStorageKey()));
        long before = version();
        tx.executeWithoutResult(status -> {
            try { progress.completeFile(context(claim), file.getId(), before); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            status.setRollbackOnly();
        });
        assertThat(version()).isEqualTo(before);
        assertThat(count("file_purge_work", "bytes_deleted_at IS NOT NULL")).isZero();
        assertThat(count("folder_purge_file_progress", "TRUE")).isZero();
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThatThrownBy(() -> sql("UPDATE folder_purge_runs SET phase = 'DELETE_FILES' WHERE event_id = :event", null)).isInstanceOf(RuntimeException.class);
    }

    @Test void unsealedManifestCannotAuthorizeDeletionOrCreateWork() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(false);
        assertThatThrownBy(() -> progress.prepare(context(claim), file.getId())).hasMessageContaining("membership");
        assertThat(count("file_purge_work", "TRUE")).isZero();
        assertThatThrownBy(() -> sql("UPDATE folder_purge_runs SET phase = 'DELETE_FOLDERS', discovery_sealed_at = clock_timestamp() WHERE event_id = :event", null))
                .isInstanceOf(RuntimeException.class);
        assertThat(phase()).isEqualTo("DISCOVER");
        assertBytes(file);
    }

    @Test void missingFileWithoutByteProofBlocksRatherThanInferringAbsence() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(true);
        progress.prepare(context(claim), file.getId());
        fileRepository.deleteById(file.getId());
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(phase()).isEqualTo("DELETE_FILES");
        assertBytes(file);
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @Test void failedStandaloneWithOnlyTargetCanBeDeletedByFolderWithoutRearming() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob fileClaim = standaloneClaim(file);
        for (int i = 0; i < 10; i++) {
            assertThat(jobs.retry(fileClaim)).isTrue();
            if (i < 9) { due(fileClaim.id(), false); fileClaim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow(); }
        }
        assertThat(countWork()).isZero();
        ClaimedJob claim = accept(true);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertMissing(file);
        assertThat(jobRepository.findById(fileClaim.id()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
    }

    @Test void legacyStandaloneWithoutOriginalTargetCannotUseTodaysVerifiedAllocation() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob fileClaim = standaloneClaim(file);
        tx.executeWithoutResult(ignored -> em.createNativeQuery("DELETE FROM file_purge_targets WHERE event_id = :id")
                .setParameter("id", fileClaim.id()).executeUpdate());
        ClaimedJob claim = accept(true);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(count("folder_purge_file_progress", "blocked_reason = 'UNKNOWN_TARGET'")).isEqualTo(1);
        assertThat(countWork()).isZero();
        assertBytes(file);
        assertThat(phase()).isEqualTo("DELETE_FILES");
    }

    @Test void leaseLostAfterDeleteCannotRecordProofAndReplacementReplays() throws Exception {
        StoredFile file = uploaded(root);
        StoredFile unrelated = uploaded(null);
        ClaimedJob claim = accept(true);
        doAnswer(call -> { call.callRealMethod(); due(event, true); return null; }).when(storage).delete(any(StorageTarget.class));
        assertThatThrownBy(() -> handler.handle(context(claim))).isInstanceOf(IllegalStateException.class);
        assertMissing(file); assertBytes(unrelated);
        assertThat(countWork()).isEqualTo(1);
        assertThat(count("file_purge_work", "bytes_deleted_at IS NOT NULL")).isZero();
        assertThat(phase()).isEqualTo("DELETE_FILES");
        reset(storage);
        var replacement = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        assertThat(handler.handle(context(replacement))).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
    }

    @Test void neverWrittenProofAllocationAndMemberCheckpointRollBackTogether() throws Exception {
        StoredFile file = raw(FileStatus.UPLOADING, false, true);
        ClaimedJob claim = accept(true);
        progress.prepare(context(claim), file.getId());
        long before = version();
        assertThatThrownBy(() -> progress.completeFile(context(claim), file.getId(), before - 1)).hasMessageContaining("Checkpoint");
        assertThat(count("file_purge_work", "never_written_at IS NOT NULL")).isZero();
        Boolean unprecluded = tx.execute(ignored -> (Boolean) em.createNativeQuery("SELECT write_precluded_at IS NULL FROM file_storage_objects WHERE file_id = :file")
                .setParameter("file", file.getId()).getSingleResult());
        assertThat(unprecluded).isTrue();
        assertThat(count("folder_purge_file_progress", "TRUE")).isZero();
        assertThat(version()).isEqualTo(before);
        assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
    }

    @Test void phaseTransitionCheckpointFailureRetainsDeleteFiles() throws Exception {
        StoredFile file = raw(FileStatus.FAILED, false, true);
        ClaimedJob claim = accept(true);
        progress.prepare(context(claim), file.getId());
        progress.completeFile(context(claim), file.getId(), version());
        long before = version();
        assertThatThrownBy(() -> progress.advancePhase(context(claim), before - 1)).hasMessageContaining("Checkpoint");
        assertThat(phase()).isEqualTo("DELETE_FILES");
        assertThat(version()).isEqualTo(before);
        assertThat(progress.advancePhase(context(claim), before)).isTrue();
        assertThat(folderRepository.existsById(root)).isTrue();
    }

    @Test void uploadAdmissionWinningBeforeRootAcceptanceIsUnsafeEvenWithoutKnownPutOutcome() throws Exception {
        StoredFile file = raw(FileStatus.UPLOADING, false, true);
        CountDownLatch admitted = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> tx.executeWithoutResult(ignored -> {
                identity.beginWrite(file.getStorageKey()); admitted.countDown(); await(release);
            }));
            try {
                assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
                var folder = executor.submit(() -> accept(true));
                assertThatThrownBy(() -> folder.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown(); writer.get(5, TimeUnit.SECONDS);
                ClaimedJob claim = folder.get(5, TimeUnit.SECONDS);
                assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
                assertThat(phase()).isEqualTo("DELETE_FILES");
                assertThat(count("folder_purge_file_progress", "blocked_reason = 'UNSAFE_WRITER'")).isEqualTo(1);
                assertThat(countWork()).isZero();
            } finally { release.countDown(); }
        }
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @Test void cancelledOrShortRuntimeNeverBeginsDeletionAndUnrelatedMemberIsRejected() throws Exception {
        StoredFile file = uploaded(root);
        StoredFile outsider = uploaded(null);
        ClaimedJob claim = accept(true);
        var ctx = context(claim); ctx.cancel();
        assertThatThrownBy(() -> handler.handle(ctx)).isInstanceOf(InterruptedException.class);
        var shortCtx = new JobExecutionContext(claim, Duration.ofSeconds(1).toNanos(), System::nanoTime);
        assertThatThrownBy(() -> handler.handle(shortCtx)).isInstanceOf(InterruptedException.class);
        assertThatThrownBy(() -> progress.prepare(context(claim), outsider.getId())).hasMessageContaining("membership");
        assertThat(countWork()).isZero();
        assertBytes(file); assertBytes(outsider);
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @Test void databaseRejectsNeverWrittenReceiptForAdmittedOrUnprecludedAllocation() throws Exception {
        StoredFile admitted = uploaded(root);
        StoredFile unprecluded = raw(FileStatus.UPLOADING, false, true);
        ClaimedJob claim = accept(true);
        progress.prepare(context(claim), admitted.getId());
        progress.prepare(context(claim), unprecluded.getId());
        for (StoredFile file : List.of(admitted, unprecluded)) {
            assertThatThrownBy(() -> sql("UPDATE file_purge_work SET never_written_at = clock_timestamp(), never_written_job_id = :event WHERE file_id = :file", file.getId()))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(count("file_purge_work", "never_written_at IS NOT NULL")).isZero();
        assertBytes(admitted);
    }

    @Test void changedOwnershipCannotAuthorizeOriginalTargetsDelete() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob claim = accept(true);
        UUID otherOwner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Other owner")).getId();
        try {
            tx.executeWithoutResult(ignored -> em.createNativeQuery("UPDATE files SET owner_id = :owner, folder_id = NULL WHERE id = :file")
                    .setParameter("owner", otherOwner).setParameter("file", file.getId()).executeUpdate());
            assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
            assertThat(phase()).isEqualTo("DELETE_FILES");
            assertThat(count("folder_purge_file_progress", "blocked_reason = 'IDENTITY_MISMATCH'")).isEqualTo(1);
            assertBytes(file);
            verify(storage, never()).delete(any(StorageTarget.class));
        } finally {
            tx.executeWithoutResult(ignored -> em.createNativeQuery("UPDATE files SET owner_id = :owner, folder_id = :root WHERE id = :file")
                    .setParameter("owner", owner).setParameter("root", root).setParameter("file", file.getId()).executeUpdate());
            users.deleteById(otherOwner);
        }
    }

    @Test void expiredClaimCannotCommitPhaseEvenWithEveryFileComplete() throws Exception {
        StoredFile file = raw(FileStatus.UPLOADING, false, true);
        ClaimedJob claim = accept(true);
        progress.prepare(context(claim), file.getId());
        progress.completeFile(context(claim), file.getId(), version());
        long before = version();
        due(event, true);
        assertThatThrownBy(() -> progress.advancePhase(context(claim), before)).hasMessageContaining("Checkpoint");
        assertThat(phase()).isEqualTo("DELETE_FILES");
        assertThat(version()).isEqualTo(before);
        assertThat(count("folder_purge_file_progress", "completed_at IS NOT NULL")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void runtimeYieldKeepsUsefulCheckpointAndPreservesKnownStorageFailure(boolean failure) throws Exception {
        uploaded(root); uploaded(root);
        ClaimedJob claim = accept(true);
        var clock = new AtomicLong();
        var ctx = new JobExecutionContext(claim, Duration.ofSeconds(50).toNanos(), clock::get);
        doAnswer(call -> {
            call.callRealMethod();
            clock.set(Duration.ofSeconds(25).toNanos()); // Still locally live, insufficient for another unit.
            if (failure) { throw new StorageDeletionException(StorageDeletionException.Kind.TRANSIENT); }
            return null;
        }).when(storage).delete(any(StorageTarget.class));
        assertThat(handler.handle(ctx)).isEqualTo(failure ? JobHandler.Result.RETRY : JobHandler.Result.CONTINUE);
        assertThat(count("folder_purge_file_progress", "completed_at IS NOT NULL")).isEqualTo(failure ? 0 : 1);
        assertThat(phase()).isEqualTo("DELETE_FILES");
        verify(storage, times(1)).delete(any(StorageTarget.class));
    }

    @Test void inFlightStandaloneDeleteDoesNotPreventFolderReceiptAndLateFinalization() throws Exception {
        StoredFile file = uploaded(root);
        ClaimedJob fileClaim = standaloneClaim(file);
        ClaimedJob folderClaim = accept(true);
        CountDownLatch deleting = new CountDownLatch(1), release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.getAndIncrement() == 0) { deleting.countDown(); await(release); }
            return call.callRealMethod();
        }).when(storage).delete(any(StorageTarget.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var fileExecution = executor.submit(() -> fileHandler.handle(context(fileClaim)));
            try {
                assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(handler.handle(context(folderClaim))).isEqualTo(JobHandler.Result.CONTINUE);
                assertThat(phase()).isEqualTo("DELETE_FOLDERS");
                Object receipt = scalar("SELECT bytes_deleted_at FROM file_purge_work WHERE byte_receipt_job_id = :event");
                release.countDown();
                assertThat(fileExecution.get(5, TimeUnit.SECONDS)).isEqualTo(JobHandler.Result.SUCCEEDED);
                assertThat(scalar("SELECT bytes_deleted_at FROM file_purge_work WHERE byte_receipt_job_id = :event")).isEqualTo(receipt);
                assertThat(count("file_purge_work", "metadata_finalized_at IS NOT NULL")).isEqualTo(1);
                assertThat(fileRepository.existsById(file.getId())).isFalse();
                assertThat(folderRepository.existsById(root)).isTrue();
                assertMissing(file);
                verify(storage, times(2)).delete(binding.target(file.getStorageKey()));
            } finally { release.countDown(); }
        }
    }

    private StoredFile uploaded(UUID folder) {
        var result = files.uploadFile(owner, folder, new MockMultipartFile("file", UUID.randomUUID() + ".txt", "text/plain", "fixture".getBytes()));
        StoredFile f = fileRepository.findById(result.fileId()).orElseThrow();
        keys.add(f.getStorageKey()); return f;
    }
    private String key(UUID id) { String key = "users/" + owner + "/files/" + id; keys.add(key); return key; }
    private StoredFile raw(FileStatus status, boolean write, boolean allocation) {
        return tx.execute(ignored -> {
            UUID id = UUID.randomUUID();
            StoredFile f = new StoredFile(id, owner, root, id + ".txt", key(id), "text/plain", 1);
            if (status == FileStatus.READY) { f.markReady(); }
            if (status == FileStatus.FAILED) { f.markFailed(); }
            fileRepository.saveAndFlush(f);
            if (allocation) {
                identity.reserve(f);
                if (write) { em.createNativeQuery("UPDATE file_storage_objects SET write_started = TRUE WHERE file_id = :file").setParameter("file", id).executeUpdate(); }
            }
            return f;
        });
    }
    private ClaimedJob standaloneClaim(StoredFile file) {
        files.trashFile(owner, file.getId()); metadata.requestPermanentDeletion(owner, file.getId());
        UUID id = outbox.findByAggregateTypeAndAggregateId("FILE", file.getId()).getFirst().getId();
        jobs.create(id, JobType.FILE_PURGE, file.getId()); return jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
    }
    private ClaimedJob accept(boolean seal) {
        tx.executeWithoutResult(ignored -> { folderRepository.findById(root).orElseThrow().softDelete(); folderRepository.flush(); });
        folders.requestPermanentDeletion(owner, root);
        event = outbox.findByAggregateTypeAndAggregateId("FOLDER", root).getFirst().getId();
        jobs.create(event, JobType.FOLDER_PURGE, root);
        ClaimedJob claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        if (seal) {
            for (int i = 0; i < 100; i++) { if (discovery.discover(claim, version(), 100).sealed()) { return claim; } }
            throw new AssertionError("Discovery did not seal");
        }
        return claim;
    }
    private JobExecutionContext context(ClaimedJob claim) { return new JobExecutionContext(claim, Duration.ofMinutes(5).toNanos(), System::nanoTime); }
    private long version() { return ((Number) scalar("SELECT progress_version FROM background_jobs WHERE id = :event")).longValue(); }
    private long countWork() { return count("file_purge_work", "TRUE"); }
    private String phase() { return (String) scalar("SELECT phase FROM folder_purge_runs WHERE event_id = :event"); }
    private Object scalar(String sql) { return tx.execute(ignored -> em.createNativeQuery(sql).setParameter("event", event).getSingleResult()); }
    private long count(String table, String predicate) {
        String ownerCondition = table.equals("file_purge_work") ? "owner_id = :owner" : "event_id = :event";
        return tx.execute(ignored -> ((Number) em.createNativeQuery("SELECT count(*) FROM " + table + " WHERE " + ownerCondition + " AND " + predicate)
                .setParameter(table.equals("file_purge_work") ? "owner" : "event", table.equals("file_purge_work") ? owner : event).getSingleResult()).longValue());
    }
    private void sql(String sql, UUID file) {
        tx.executeWithoutResult(ignored -> {
            var query = em.createNativeQuery(sql);
            if (sql.contains(":event")) { query.setParameter("event", event); }
            if (sql.contains(":file")) { query.setParameter("file", file); }
            query.executeUpdate();
        });
    }
    private void due(UUID id, boolean expire) {
        tx.executeWithoutResult(ignored -> em.createNativeQuery("UPDATE background_jobs SET next_attempt_at = clock_timestamp() - interval '1 second'"
                + (expire ? ", claim_expires_at = clock_timestamp() - interval '1 second'" : "") + " WHERE id = :id").setParameter("id", id).executeUpdate());
    }
    private void assertMissing(StoredFile file) {
        assertThatThrownBy(() -> s3.getObjectAsBytes(r -> r.bucket(properties.bucket()).key(file.getStorageKey()))).isInstanceOf(NoSuchKeyException.class);
    }
    private void assertBytes(StoredFile file) {
        assertThat(s3.getObjectAsBytes(r -> r.bucket(properties.bucket()).key(file.getStorageKey())).asUtf8String()).isEqualTo("fixture");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Latch timeout"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
