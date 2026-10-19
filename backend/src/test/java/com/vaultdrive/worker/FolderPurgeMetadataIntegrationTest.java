package com.vaultdrive.worker;

import org.springframework.context.annotation.Import;

import com.vaultdrive.support.HistoricalObjectFixtures;
import com.vaultdrive.support.HistoricalUploadFixtures;
import com.vaultdrive.file.FileMetadataService;
import com.vaultdrive.file.FileService;
import com.vaultdrive.file.FileStatus;
import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.job.BackgroundJobRepository;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobStatus;
import com.vaultdrive.job.JobType;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.storage.ObjectStorageService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/Garage; only this test's fresh UUID owner/key allocations are mutated. */
@Import(HistoricalObjectFixtures.class)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=folder_metadata_purge_test",
        "spring.flyway.schemas=folder_metadata_purge_test", "spring.flyway.default-schema=folder_metadata_purge_test"
})
@ActiveProfiles("test")
class FolderPurgeMetadataIntegrationTest {
    @Autowired HistoricalObjectFixtures historicalObjects;

    @Autowired HistoricalUploadFixtures history;

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired UserRepository users;
    @Autowired FolderRepository folderRepository;
    @Autowired FolderService folders;
    @Autowired StoredFileRepository fileRepository;
    @Autowired FileService files;
    @Autowired FileMetadataService metadata;
    @Autowired OutboxEventRepository outbox;
    @Autowired BackgroundJobRepository jobRepository;
    @MockitoSpyBean BackgroundJobService jobs;
    @Autowired FolderPurgeDiscoveryService discovery;
    @Autowired FolderPurgeJobHandler handler;
    @MockitoSpyBean FilePurgeProgressService standalone;
    @Autowired FolderPurgeMetadataService removal;
    @Autowired FilePurgeJobHandler fileHandler;
    @MockitoSpyBean ObjectStorageService storage;
    @Autowired S3StorageProperties properties;
    @Autowired S3Client s3;
    TransactionTemplate tx;
    UUID owner, root, event;
    final List<String> keys = new ArrayList<>();

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Folder metadata")).getId();
        root = folderRepository.saveAndFlush(new Folder(owner, null, "root")).getId();
    }

    @AfterEach void cleanup() {
        reset(storage);
        reset(AopTestUtils.<BackgroundJobService>getUltimateTargetObject(jobs));
        reset(AopTestUtils.<FilePurgeProgressService>getUltimateTargetObject(standalone));
        try {
            for (String key : keys) {
                assertThat(key).startsWith("users/" + owner + "/files/");
                s3.deleteObject(r -> r.bucket(properties.bucket()).key(key));
            }
        } finally {
            tx.executeWithoutResult(ignored -> {
                for (String sql : new String[]{
                        "DELETE FROM folder_purge_removals WHERE event_id IN (SELECT event_id FROM folder_purge_runs WHERE owner_id = :owner)",
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

    @Test void nestedFoldersAreRemovedLeafFirstWithRootLastAndRetainedProof() throws Exception {
        UUID child = child(root), grandchild = child(child), sibling = child(root);
        StoredFile bytes = uploaded(grandchild);
        raw(FileStatus.FAILED, false, true);
        ClaimedJob claim = ready();
        long before = version();
        assertThat(remove(claim, 20)).isEqualTo(JobHandler.Result.CONTINUE); // File metadata page.
        assertThat(fileRepository.existsById(bytes.getId())).isFalse();
        assertMissing(bytes);
        assertThat(folderRepository.existsById(grandchild)).isTrue();
        assertThat(remove(claim, 20)).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(folderRepository.existsById(grandchild)).isFalse();
        assertThat(folderRepository.existsById(sibling)).isFalse();
        assertThat(folderRepository.existsById(child)).isTrue();
        assertThat(folderRepository.existsById(root)).isTrue();
        assertThat(remove(claim, 20)).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(folderRepository.existsById(child)).isFalse();
        assertThat(remove(claim, 20)).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(version()).isEqualTo(before + 4);
        assertFinalized(4, 2);
        assertThat(jobs.complete(claim)).isTrue();
    }

    @Test void manyBatchesContinueBeyondTenClaimsWithoutSpendingFailureBudget() throws Exception {
        for (int i = 0; i < 225; i++) { child(root); raw(FileStatus.UPLOADING, false, true); }
        // Discovery can exceed the fixture helper's 100-page limit for a large frontier.
        ClaimedJob claim = accept(false);
        for (int i = 0; i < 1000 && !phase().equals("DELETE_FOLDERS"); i++) {
            assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
            claim = continueClaim(claim);
        }
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        int batches = 0;
        while (true) {
            long before = version();
            var result = handler.handle(context(claim));
            assertThat(version()).isEqualTo(before + 1);
            batches++;
            if (result == JobHandler.Result.SUCCEEDED) { break; }
            assertThat(result).isEqualTo(JobHandler.Result.CONTINUE);
            assertThat(batches).isLessThan(100);
            claim = continueClaim(claim);
        }
        assertThat(batches).isGreaterThan(20);
        assertFinalized(226, 225);
        assertThat(jobRepository.findById(event).orElseThrow().getFailureCount()).isZero();
        assertThat(jobs.complete(claim)).isTrue();
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @Test void crashAfterChildCommitResumesParentWithoutFabricatingAnotherReceipt() throws Exception {
        UUID child = child(root), leaf = child(child);
        ClaimedJob abandoned = ready();
        assertThat(remove(abandoned, 1)).isEqualTo(JobHandler.Result.CONTINUE);
        Object receipt = scalar("SELECT removed_at FROM folder_purge_removals WHERE event_id = :event");
        due(event, true);
        ClaimedJob replacement = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        assertThat(remove(replacement, 1)).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(folderRepository.existsById(child)).isFalse();
        assertThat(folderRepository.existsById(leaf)).isFalse();
        assertThat((Object) tx.execute(ignored -> em.createNativeQuery("SELECT removed_at FROM folder_purge_removals WHERE event_id = :event AND folder_id = :leaf")
                .setParameter("event", event).setParameter("leaf", leaf).getSingleResult())).isEqualTo(receipt);
        assertThat(remove(replacement, 1)).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jobRepository.findById(event).orElseThrow().getFailureCount()).isEqualTo(1);
    }

    @Test void crashAfterRootCommitBeforeJobCompletionReplaysOnlyDurableSuccess() throws Exception {
        ClaimedJob abandoned = ready();
        assertThat(remove(abandoned, 20)).isEqualTo(JobHandler.Result.SUCCEEDED);
        long before = version();
        Object finalized = scalar("SELECT finalized_at FROM folder_purge_runs WHERE event_id = :event");
        assertThat(handler.handle(context(abandoned))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(version()).isEqualTo(before);
        due(event, true);
        ClaimedJob replacement = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        assertThatThrownBy(() -> handler.handle(context(abandoned))).hasMessageContaining("stale");
        assertThat(handler.handle(context(replacement))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(scalar("SELECT finalized_at FROM folder_purge_runs WHERE event_id = :event")).isEqualTo(finalized);
        assertThat(version()).isEqualTo(before);
        assertThat(jobs.complete(abandoned)).isFalse();
        assertThat(jobs.complete(replacement)).isTrue();
        assertFinalized(1, 0);
    }

    @Test void staleCheckpointRollsBackRootRemovalReceiptAndRunFinalization() throws Exception {
        ClaimedJob claim = ready();
        long before = version();
        assertThatThrownBy(() -> removal.removeBatch(context(claim), before - 1, 20)).hasMessageContaining("Checkpoint");
        assertThat(folderRepository.existsById(root)).isTrue();
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        assertThat(count("folder_purge_removals", "TRUE")).isZero();
        assertThat(version()).isEqualTo(before);
    }

    @Test void expiredAndReplacedWorkerCannotRemoveChildOrFileMetadata() throws Exception {
        UUID child = child(root);
        StoredFile f = raw(FileStatus.UPLOADING, false, true);
        ClaimedJob old = ready();
        due(event, true);
        ClaimedJob current = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        assertThatThrownBy(() -> remove(old, 20)).hasMessageContaining("Checkpoint");
        assertThat(fileRepository.existsById(f.getId())).isTrue();
        assertThat(count("file_purge_work", "metadata_finalized_at IS NOT NULL")).isZero();
        assertThat(remove(current, 20)).isEqualTo(JobHandler.Result.CONTINUE);
        assertThatThrownBy(() -> remove(old, 20)).hasMessageContaining("Checkpoint");
        assertThat(folderRepository.existsById(child)).isTrue();
        assertThat(count("folder_purge_removals", "TRUE")).isZero();
    }

    @Test void simultaneousDuplicateBatchHasOneCheckpointWinner() throws Exception {
        UUID leaf = child(root);
        ClaimedJob claim = ready();
        long revision = version();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); await(release); return call.callRealMethod(); })
                .when(AopTestUtils.<BackgroundJobService>getUltimateTargetObject(jobs)).checkpoint(any(), eq(revision));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> removal.removeBatch(context(claim), revision, 1));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> removal.removeBatch(context(claim), revision, 1));
                assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(JobHandler.Result.CONTINUE);
                assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("Checkpoint is stale or claim is not live");
            } finally { release.countDown(); }
        }
        assertThat(folderRepository.existsById(leaf)).isFalse();
        assertThat(folderRepository.existsById(root)).isTrue();
        assertThat(count("folder_purge_removals", "TRUE")).isEqualTo(1);
        assertThat(version()).isEqualTo(revision + 1);
    }

    @Test void leaseExpiryDuringFinalJobLockWaitRollsBackEveryRootWrite() throws Exception {
        ClaimedJob claim = ready();
        long before = version();
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), fencing = new CountDownLatch(1);
        doAnswer(call -> { fencing.countDown(); return call.callRealMethod(); }).when(AopTestUtils.<BackgroundJobService>getUltimateTargetObject(jobs)).checkpoint(any(), eq(before));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var blocker = executor.submit(() -> tx.executeWithoutResult(ignored -> {
                em.createNativeQuery("SELECT id FROM background_jobs WHERE id = :event FOR UPDATE").setParameter("event", event).getSingleResult();
                locked.countDown(); await(release);
                em.createNativeQuery("UPDATE background_jobs SET claim_expires_at = clock_timestamp() - interval '1 second' WHERE id = :event")
                        .setParameter("event", event).executeUpdate();
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var worker = executor.submit(() -> remove(claim, 20));
                assertThat(fencing.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> worker.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown(); blocker.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> worker.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("Checkpoint is stale or claim is not live");
            } finally { release.countDown(); }
        }
        assertThat(folderRepository.existsById(root)).isTrue();
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        assertThat(count("folder_purge_removals", "TRUE")).isZero();
        assertThat(version()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void standaloneFinalizerAndFolderFinalizerReuseTheSameReceiptWithoutJobReset(boolean standaloneFirst) throws Exception {
        StoredFile f = uploaded(root);
        ClaimedJob fileClaim = standaloneClaim(f);
        ClaimedJob folderClaim = ready();
        Object byteReceipt = scalar("SELECT bytes_deleted_at FROM file_purge_work WHERE byte_receipt_job_id = :event");
        var fileJob = jobRepository.findById(fileClaim.id()).orElseThrow();
        if (standaloneFirst) { assertThat(standalone.finalizeMetadata(fileClaim)).isTrue(); }
        else { assertThat(remove(folderClaim, 20)).isEqualTo(JobHandler.Result.CONTINUE); }
        assertThat(standalone.finalizeMetadata(fileClaim)).isTrue();
        assertThat(remove(folderClaim, 20)).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jobRepository.findById(fileClaim.id()).orElseThrow().getStatus()).isEqualTo(fileJob.getStatus());
        assertThat(jobRepository.findById(fileClaim.id()).orElseThrow().getAttemptCount()).isEqualTo(fileJob.getAttemptCount());
        assertThat(scalar("SELECT bytes_deleted_at FROM file_purge_work WHERE byte_receipt_job_id = :event")).isEqualTo(byteReceipt);
        assertFinalized(1, 1);
        assertThat(fileHandler.handle(context(fileClaim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertMissing(f);
    }

    @Test void failedStandaloneJobDoesNotPreventFolderFinalizationOrGetReset() throws Exception {
        StoredFile f = uploaded(root);
        ClaimedJob fileClaim = standaloneClaim(f);
        for (int i = 0; i < 10; i++) {
            jobs.retry(fileClaim);
            if (i < 9) { due(fileClaim.id(), false); fileClaim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow(); }
        }
        ClaimedJob folderClaim = ready();
        assertThat(remove(folderClaim, 20)).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(remove(folderClaim, 20)).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jobRepository.findById(fileClaim.id()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(jobRepository.findById(fileClaim.id()).orElseThrow().getFailureCount()).isEqualTo(10);
        assertFinalized(1, 1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentStandaloneAndFolderFinalizationSerializeInBothWinnerOrders(boolean standaloneFirst) throws Exception {
        StoredFile f = uploaded(root);
        ClaimedJob fileClaim = standaloneClaim(f), folderClaim = ready();
        CountDownLatch removed = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tx.execute(ignored -> {
                try {
                    if (standaloneFirst) { standalone.finalizeMetadata(fileClaim); }
                    else { remove(folderClaim, 20); }
                } catch (Exception e) { throw new AssertionError(e); }
                removed.countDown(); await(release);
                return true;
            }));
            try {
                assertThat(removed.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> standaloneFirst ? remove(folderClaim, 20) : standalone.finalizeMetadata(fileClaim));
                assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(standaloneFirst ? JobHandler.Result.SUCCEEDED : true);
            } finally { release.countDown(); }
        }
        if (!standaloneFirst) { assertThat(remove(folderClaim, 20)).isEqualTo(JobHandler.Result.SUCCEEDED); }
        assertFinalized(1, 1);
        assertThat(jobRepository.findById(fileClaim.id()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        verify(storage, times(1)).delete(any(StorageTarget.class));
    }

    @Test void cancellationAfterMetadataWritesRollsBackRemovalAndReceiptTogether() throws Exception {
        StoredFile f = raw(FileStatus.UPLOADING, false, true);
        ClaimedJob claim = ready();
        JobExecutionContext ctx = context(claim);
        long before = version();
        doAnswer(call -> { Object result = call.callRealMethod(); ctx.cancel(); return result; })
                .when(AopTestUtils.<FilePurgeProgressService>getUltimateTargetObject(standalone)).finalizeFolderMember(eq(event), eq(f.getId()), eq(owner));
        assertThatThrownBy(() -> removal.removeBatch(ctx, before, 20)).isInstanceOf(InterruptedException.class);
        assertThat(fileRepository.existsById(f.getId())).isTrue();
        assertThat(count("file_purge_work", "metadata_finalized_at IS NOT NULL")).isZero();
        assertThat(version()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"work", "progress", "file-manifest", "folder-manifest", "file-metadata", "folder-metadata", "lifecycle", "binding", "target"})
    void missingOrCorruptProofCannotAuthorizeMetadataRemoval(String fault) throws Exception {
        UUID child = child(root);
        StoredFile f = uploaded(root);
        if (fault.equals("target")) { standaloneClaim(f); }
        ClaimedJob claim = ready();
        long before = version();
        switch (fault) {
            case "work" -> sql("DELETE FROM file_purge_work WHERE file_id = :file", f.getId());
            case "progress" -> sql("DELETE FROM folder_purge_file_progress WHERE event_id = :event", null);
            case "file-manifest" -> {
                sql("DELETE FROM folder_purge_file_progress WHERE event_id = :event", null);
                sql("DELETE FROM folder_purge_files WHERE event_id = :event", null);
            }
            case "folder-manifest" -> sql("DELETE FROM folder_purge_frontier WHERE event_id = :event AND folder_id <> '" + root + "'", null);
            case "file-metadata" -> sql("DELETE FROM files WHERE id = :file", f.getId());
            case "folder-metadata" -> sql("DELETE FROM folders WHERE id = :file", child);
            case "lifecycle" -> sql("UPDATE files SET version = version + 1 WHERE id = :file", f.getId());
            case "binding" -> sql("DELETE FROM file_storage_objects WHERE file_id = :file", f.getId());
            case "target" -> sql("DELETE FROM file_purge_targets WHERE file_id = :file", f.getId());
            default -> throw new AssertionError(fault);
        }
        assertThatThrownBy(() -> remove(claim, 20)).isInstanceOf(IllegalStateException.class);
        assertThat(folderRepository.existsById(root)).isTrue();
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        assertThat(count("folder_purge_removals", "TRUE")).isZero();
        assertThat(version()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unexpectedDescendantsFailClosedAndNonCascadingForeignKeysProtectParent(boolean file) throws Exception {
        ClaimedJob claim = ready();
        if (file) { raw(FileStatus.UPLOADING, false, true); } else { child(root); }
        assertThatThrownBy(() -> sql("DELETE FROM folders WHERE id = :file", root)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> remove(claim, 20)).hasMessageContaining("manifest");
        assertThat(folderRepository.existsById(root)).isTrue();
        assertThat(count("folder_purge_removals", "TRUE")).isZero();
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
    }

    @Test void missingRootWithoutFinalizedRunIsNeverSuccess() throws Exception {
        ClaimedJob claim = ready();
        sql("DELETE FROM folders WHERE id = :file", root);
        assertThatThrownBy(() -> handler.handle(context(claim))).hasMessageContaining("manifest");
        assertThatThrownBy(() -> sql("UPDATE folder_purge_runs SET phase = 'FINALIZED', finalized_at = clock_timestamp() WHERE event_id = :event", null))
                .isInstanceOf(RuntimeException.class);
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        assertThat(jobRepository.findById(event).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test void finalizedReplayWithMissingRetainedReceiptIsRejected() throws Exception {
        ClaimedJob claim = ready();
        assertThat(remove(claim, 20)).isEqualTo(JobHandler.Result.SUCCEEDED);
        sql("DELETE FROM folder_purge_removals WHERE event_id = :event", null);
        assertThatThrownBy(() -> handler.handle(context(claim))).hasMessageContaining("manifest");
        assertThat(jobRepository.findById(event).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test void receiptAndFinalizedStateCannotRegressOrBeForged() throws Exception {
        ClaimedJob claim = ready();
        assertThatThrownBy(() -> sql("INSERT INTO folder_purge_removals(event_id, folder_id) VALUES (:event, :file)", root))
                .isInstanceOf(RuntimeException.class);
        assertThat(remove(claim, 20)).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThatThrownBy(() -> sql("UPDATE folder_purge_removals SET removed_at = clock_timestamp() WHERE event_id = :event", null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql("UPDATE folder_purge_runs SET phase = 'DELETE_FOLDERS', finalized_at = NULL WHERE event_id = :event", null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql("UPDATE folder_purge_runs SET manifest_folder_count = manifest_folder_count + 1 WHERE event_id = :event", null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void cancellationAndShortRuntimeLeaveMetadataUntouched() throws Exception {
        ClaimedJob claim = ready();
        JobExecutionContext cancelled = context(claim); cancelled.cancel();
        assertThatThrownBy(() -> removeWith(cancelled)).isInstanceOf(InterruptedException.class);
        var shortRuntime = new JobExecutionContext(claim, Duration.ofSeconds(5).toNanos(), System::nanoTime);
        assertThatThrownBy(() -> handler.handle(shortRuntime)).isInstanceOf(InterruptedException.class);
        assertThat(folderRepository.existsById(root)).isTrue();
        assertThat(count("folder_purge_removals", "TRUE")).isZero();
    }

    @Test void v21RetainedRunMigratesWithSealedCountsAndCannotInventFinalization() {
        // All DDL/data, including the scratch schema creation, roll back in this transaction.
        tx.executeWithoutResult(status -> {
            em.unwrap(org.hibernate.Session.class).doWork(connection -> {
                String schema = "slice6_migration_" + UUID.randomUUID().toString().replace("-", "");
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + schema);
                    statement.execute("SET LOCAL search_path TO " + schema);
                    var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                            .getResources("classpath:db/migration/V*.sql");
                    java.util.Arrays.sort(resources, java.util.Comparator.comparingInt(r ->
                            Integer.parseInt(r.getFilename().substring(1, r.getFilename().indexOf("__")))));
                    for (var resource : resources) {
                        if (resource.getFilename().startsWith("V22__")) { continue; }
                        try (var stream = resource.getInputStream()) {
                            statement.execute(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                        } catch (java.io.IOException e) { throw new java.sql.SQLException(e); }
                    }
                    UUID run = UUID.randomUUID(), folder = UUID.randomUUID();
                    statement.execute("INSERT INTO folder_purge_runs(event_id, owner_id, root_folder_id, requested_at) VALUES ('"
                            + run + "', '" + owner + "', '" + folder + "', clock_timestamp())");
                    statement.execute("INSERT INTO folder_purge_frontier(event_id, folder_id, owner_id, is_root, children_complete, files_complete) VALUES ('"
                            + run + "', '" + folder + "', '" + owner + "', true, true, true)");
                    statement.execute("UPDATE folder_purge_runs SET phase = 'DELETE_FILES', discovery_sealed_at = clock_timestamp()");
                    statement.execute("UPDATE folder_purge_runs SET phase = 'DELETE_FOLDERS'");
                    try (var stream = new org.springframework.core.io.ClassPathResource("db/migration/V22__folder_metadata_finalization.sql").getInputStream()) {
                        statement.execute(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    } catch (java.io.IOException e) { throw new java.sql.SQLException(e); }
                    try (var result = statement.executeQuery("SELECT phase, manifest_folder_count, manifest_file_count, finalized_at FROM folder_purge_runs")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo("DELETE_FOLDERS");
                        assertThat(result.getLong(2)).isEqualTo(1);
                        assertThat(result.getLong(3)).isZero();
                        assertThat(result.getObject(4)).isNull();
                    }
                    var savepoint = connection.setSavepoint();
                    assertThatThrownBy(() -> statement.execute("UPDATE folder_purge_runs SET phase = 'FINALIZED', finalized_at = clock_timestamp()"))
                            .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("proof");
                    connection.rollback(savepoint);
                } catch (java.io.IOException e) { throw new java.sql.SQLException(e); }
            });
            status.setRollbackOnly();
        });
    }

    private JobHandler.Result removeWith(JobExecutionContext context) throws Exception { return removal.removeBatch(context, version(), 20); }
    private UUID child(UUID parent) { return folderRepository.saveAndFlush(new Folder(owner, parent, UUID.randomUUID().toString())).getId(); }
    private ClaimedJob ready() throws Exception {
        ClaimedJob claim = accept(true);
        for (int i = 0; i < 100 && !phase().equals("DELETE_FOLDERS"); i++) {
            assertThat(handler.handle(context(claim))).isEqualTo(JobHandler.Result.CONTINUE);
        }
        assertThat(phase()).isEqualTo("DELETE_FOLDERS");
        return claim;
    }
    private JobHandler.Result remove(ClaimedJob claim, int limit) throws Exception { return removal.removeBatch(context(claim), version(), limit); }
    private ClaimedJob continueClaim(ClaimedJob claim) {
        assertThat(jobs.continueJob(claim)).isTrue(); due(event, false);
        return jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
    }
    private void assertFinalized(long folders, long files) {
        assertThat(phase()).isEqualTo("FINALIZED");
        assertThat(folderRepository.existsById(root)).isFalse();
        assertThat(scalar("SELECT finalized_at FROM folder_purge_runs WHERE event_id = :event")).isNotNull();
        assertThat(count("folder_purge_removals", "TRUE")).isEqualTo(folders);
        assertThat(count("folder_purge_frontier", "TRUE")).isEqualTo(folders);
        assertThat(count("folder_purge_files", "TRUE")).isEqualTo(files);
        assertThat(count("file_purge_work", "metadata_finalized_at IS NOT NULL")).isEqualTo(files);
    }

    private StoredFile uploaded(UUID folder) {
        var result = historicalObjects.completed(owner, folder, UUID.randomUUID() + ".txt", "text/plain", "fixture".getBytes());
        StoredFile f = fileRepository.findById(result.getId()).orElseThrow();
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
                history.allocation(f);
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
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Latch timeout"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
