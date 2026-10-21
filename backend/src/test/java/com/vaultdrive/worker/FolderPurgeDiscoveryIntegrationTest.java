package com.vaultdrive.worker;

import org.springframework.context.annotation.Import;

import com.vaultdrive.support.HistoricalUploadFixtures;
import com.vaultdrive.file.FileMetadataService;
import com.vaultdrive.file.FileStatus;
import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.ClaimedJob;
import com.vaultdrive.job.JobType;
import com.vaultdrive.outbox.OutboxEvent;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.outbox.OutboxWriter;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/** Real PostgreSQL only: metadata fixtures and simulated authoritative byte receipts; no Garage calls. */
@Import(HistoricalUploadFixtures.class)
@SpringBootTest
@ActiveProfiles("test")
class FolderPurgeDiscoveryIntegrationTest {
    private static final String QUOTA_TEST_SCHEMA = "folder_discovery_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
    @org.springframework.test.context.DynamicPropertySource
    static void quotaIsolatedSchema(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + QUOTA_TEST_SCHEMA);
        r.add("spring.flyway.schemas", () -> QUOTA_TEST_SCHEMA);
        r.add("spring.flyway.default-schema", () -> QUOTA_TEST_SCHEMA);
    }

    @Autowired HistoricalUploadFixtures history;
    @Autowired com.vaultdrive.file.MultipartSessionService multipart;

    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private UserRepository users;
    @Autowired private FolderRepository folderRepository;
    @Autowired private StoredFileRepository files;
    @Autowired private FolderService folders;
    @Autowired private FileMetadataService metadata;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private OutboxWriter writer;
    @MockitoSpyBean private BackgroundJobService jobs;
    @Autowired private HierarchyCoordinator hierarchy;
    @Autowired private FolderPurgeDiscoveryService discovery;
    @Autowired private FolderPurgeDiscovery adapter;
    @Autowired private FilePurgeProgressService progress;
    private TransactionTemplate tx;
    private UUID owner;
    private UUID root;
    private UUID event;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        // This dedicated schema contains only these disposable fixtures. Clean jobs left by a
        // previously interrupted test after its root fixture was removed, never live roots.
        tx.executeWithoutResult(status -> em.createNativeQuery("""
                DELETE FROM background_jobs j WHERE j.job_type = 'FOLDER_PURGE'
                    AND NOT EXISTS (SELECT 1 FROM folders f WHERE f.id = j.aggregate_id)
                """).executeUpdate());
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Discovery")).getId();
        root = folder(null, false);
    }

    @AfterEach void cleanup() {
        reset(AopTestUtils.<BackgroundJobService>getUltimateTargetObject(jobs));
        tx.executeWithoutResult(status -> {
            if (event != null) {
                // Corruption tests may change ownerId or remove the event; retain its original ID.
                em.createNativeQuery("DELETE FROM background_jobs WHERE id = :event").setParameter("event", event).executeUpdate();
                em.createNativeQuery("DELETE FROM outbox_events WHERE id = :event").setParameter("event", event).executeUpdate();
            }
            for (String sql : new String[]{
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

    @Test void newRequestCommitsRunWithOriginalIntentAndV1Event() {
        accept();
        assertThat(count("folder_purge_runs")).isEqualTo(1);
        assertThat(count("folder_purge_frontier")).isZero(); // No API subtree snapshot.
        assertThat(scalar("SELECT r.requested_at = f.purge_requested_at AND r.requested_at = e.created_at "
                + "FROM folder_purge_runs r JOIN folders f ON f.id = r.root_folder_id "
                + "JOIN outbox_events e ON e.id = r.event_id WHERE r.event_id = :event")).isEqualTo(true);
        assertThat(outbox.findById(event).orElseThrow().getPayloadVersion()).isEqualTo(1);
    }

    @Test void rollbackAfterRunCreationRemovesIntentEventAndRun() {
        trashRoot();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            folders.requestPermanentDeletion(owner, root);
            throw new IllegalStateException("simulated crash");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(folderRepository.findById(root).orElseThrow().getPurgeRequestedAt()).isNull();
        assertThat(count("folder_purge_runs")).isZero();
        assertThat(outbox.findByAggregateTypeAndAggregateId("FOLDER", root)).isEmpty();
    }

    @Test void runInsertFailureRollsBackEventAndRootIntent() {
        trashRoot();
        tx.executeWithoutResult(status -> em.createNativeQuery("""
                INSERT INTO folder_purge_runs(event_id, owner_id, root_folder_id, requested_at)
                VALUES (:event, :owner, :root, clock_timestamp())
                """).setParameter("event", UUID.randomUUID()).setParameter("owner", owner)
                .setParameter("root", root).executeUpdate());
        assertThatThrownBy(() -> folders.requestPermanentDeletion(owner, root))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(folderRepository.findById(root).orElseThrow().getPurgeRequestedAt()).isNull();
        assertThat(outbox.findByAggregateTypeAndAggregateId("FOLDER", root)).isEmpty();
    }

    @Test void finalJobLockWaitExpiryRollsBackDiscoveredMembersAndSeal() throws Exception {
        file(root, FileStatus.READY, false, false);
        ClaimedJob claim = acceptAndClaim();
        discovery.discover(claim, 0, 10);
        CountDownLatch jobLocked = new CountDownLatch(1);
        CountDownLatch checkpointReached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            checkpointReached.countDown();
            return invocation.callRealMethod();
        }).when(AopTestUtils.<BackgroundJobService>getUltimateTargetObject(jobs)).checkpoint(any(), anyLong());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                em.createNativeQuery("SELECT id FROM background_jobs WHERE id = :id FOR UPDATE")
                        .setParameter("id", event).getSingleResult();
                jobLocked.countDown();
                await(release);
                em.createNativeQuery("UPDATE background_jobs SET claim_expires_at = clock_timestamp() - interval '1 second' WHERE id = :id")
                        .setParameter("id", event).executeUpdate();
            }));
            try {
                assertThat(jobLocked.await(5, TimeUnit.SECONDS)).isTrue();
                var scan = executor.submit(() -> discovery.discover(claim, 1, 10));
                assertThat(checkpointReached.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> scan.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                holder.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> scan.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(IllegalStateException.class);
            } finally { release.countDown(); }
        }
        assertThat(count("folder_purge_files")).isZero();
        assertThat(phase()).isEqualTo("DISCOVER");
        assertThat(version()).isEqualTo(1);
    }

    @Test void corruptAllocationOwnerFailsClosedWithoutLosingMembership() {
        UUID file = file(root, FileStatus.READY, false, false);
        tx.executeWithoutResult(status -> em.createNativeQuery("""
                INSERT INTO file_storage_objects(storage_key, file_id, owner_id, write_started)
                SELECT storage_key, id, :wrongOwner, TRUE FROM files WHERE id = :file
                """).setParameter("wrongOwner", UUID.randomUUID()).setParameter("file", file).executeUpdate());
        ClaimedJob claim = acceptAndClaim();
        discovery.discover(claim, 0, 10);
        assertThatThrownBy(() -> discovery.discover(claim, 1, 10)).hasMessageContaining("Inconsistent");
        assertThat(files.existsById(file)).isTrue();
        assertThat(count("folder_purge_files")).isZero();
        assertThat(phase()).isEqualTo("DISCOVER");
        // The deliberately invalid allocation's owner is outside the fixture cleanup selector.
        tx.executeWithoutResult(status -> em.createNativeQuery("DELETE FROM file_storage_objects WHERE file_id = :file")
                .setParameter("file", file).executeUpdate());
    }

    @Test void manifestSnapshotRemainsStableWhenAdmittedUploadBecomesFailed() {
        UUID file = file(root, FileStatus.UPLOADING, false, false);
        ClaimedJob claim = acceptAndClaim();
        drain(claim, 10);
        StoredFile captured = files.findById(file).orElseThrow();
        history.status(captured, FileStatus.FAILED);
        assertThat(files.findById(file).orElseThrow().getStatus()).isEqualTo(FileStatus.FAILED);
        assertThat(scalar("SELECT file_status FROM folder_purge_files WHERE event_id = :event")).isEqualTo("UPLOADING");
        assertThat(count("folder_purge_files")).isEqualTo(1);
        assertThatThrownBy(() -> sql("UPDATE folder_purge_files SET file_status = 'FAILED' WHERE event_id = :event"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test void largeDeepTreeIncludesTrashedFoldersAndEveryUploadStatusAcrossRestarts() {
        tx.executeWithoutResult(status -> {
            UUID parent = root;
            for (int i = 0; i < 60; i++) {
                parent = folder(parent, i % 3 == 0);
                file(parent, FileStatus.values()[i % 3], i % 2 == 0, false);
            }
            for (int i = 0; i < 125; i++) {
                UUID child = folder(root, i % 2 == 0);
                file(child, FileStatus.values()[i % 3], i % 2 == 0, false);
            }
            for (int i = 0; i < 207; i++) { file(root, FileStatus.values()[i % 3], i % 2 == 0, false); }
        });
        ClaimedJob claim = acceptAndClaim();
        int batches = 0;
        while (true) {
            var batch = discovery.discover(claim, version(), 37);
            batches++;
            assertThat(jobs.continueJob(claim)).isTrue();
            if (batch.sealed()) { break; }
            due(event);
            claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
            assertThat(batches).isLessThan(600);
        }
        assertThat(batches).isGreaterThan(10);
        assertThat(count("folder_purge_frontier")).isEqualTo(186);
        assertThat(count("folder_purge_files")).isEqualTo(392);
        assertThat(scalar("SELECT count(DISTINCT file_status) FROM folder_purge_files WHERE event_id = :event")).isEqualTo(3L);
        assertThat(scalar("SELECT count(*) FROM folder_purge_files WHERE event_id = :event AND deleted_at IS NOT NULL"))
                .isEqualTo(197L);
        assertThat(scalar("SELECT count(*) FROM folder_purge_frontier WHERE event_id = :event AND deleted_at IS NOT NULL"))
                .isEqualTo(84L);
        assertThat(scalar("SELECT failure_count FROM background_jobs WHERE id = :event")).isEqualTo(0);
        assertThat(scalar("SELECT status FROM background_jobs WHERE id = :event")).isEqualTo("PENDING");
        assertThat(phase()).isEqualTo("DELETE_FILES");
        assertThat(count("file_purge_work")).isZero(); // Unsafe inherited files are not made executable.
    }

    @Test void exactPageBoundaryNeedsExhaustionAndSealsOnlyAfterEveryFileScan() {
        for (int i = 0; i < 4; i++) { file(root, FileStatus.READY, false, false); }
        ClaimedJob claim = acceptAndClaim();
        assertThat(discovery.discover(claim, 0, 2).sealed()).isFalse(); // Children empty; files not scanned.
        assertThat(discovery.discover(claim, 1, 2).sealed()).isFalse();
        assertThat(count("folder_purge_files")).isEqualTo(2);
        assertThat(discovery.discover(claim, 2, 2).sealed()).isFalse(); // Full page is not proof of exhaustion.
        assertThat(discovery.discover(claim, 3, 2).sealed()).isTrue();
        long version = version();
        assertThat(discovery.discover(claim, version, 2)).isEqualTo(new FolderPurgeDiscoveryService.Batch(false, true));
        assertThat(version()).isEqualTo(version); // Sealed replay is not new progress.
    }

    @Test void atomicCheckpointFailureRollsBackMembersCursorsAndSeal() {
        file(root, FileStatus.FAILED, false, false);
        ClaimedJob claim = acceptAndClaim();
        discovery.discover(claim, 0, 10);
        assertThatThrownBy(() -> discovery.discover(claim, 0, 10)).hasMessageContaining("Checkpoint is stale");
        assertThat(count("folder_purge_files")).isZero();
        assertThat(phase()).isEqualTo("DISCOVER");
        assertThat(scalar("SELECT files_complete FROM folder_purge_frontier WHERE event_id = :event")).isEqualTo(false);
        assertThat(discovery.discover(claim, 1, 10).sealed()).isTrue();
        assertThat(count("folder_purge_files")).isEqualTo(1);
    }

    @Test void crashAfterCheckpointRecoversWithOneAbandonmentAndCannotReplayRevision() {
        file(root, FileStatus.UPLOADING, false, false);
        ClaimedJob old = acceptAndClaim();
        discovery.discover(old, 0, 1);
        expire(event);
        ClaimedJob next = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        assertThatThrownBy(() -> discovery.discover(old, 1, 1)).hasMessageContaining("Checkpoint is stale");
        assertThat(count("folder_purge_files")).isZero();
        assertThatThrownBy(() -> discovery.discover(next, 0, 1)).hasMessageContaining("Checkpoint is stale");
        assertThat(discovery.discover(next, 1, 1).sealed()).isFalse();
        assertThat(discovery.discover(next, 2, 1).sealed()).isTrue();
        assertThat(scalar("SELECT failure_count FROM background_jobs WHERE id = :event")).isEqualTo(1);
    }

    @Test void legacyCommittedEventInitializesConservatively() {
        trashRoot();
        event = tx.execute(status -> {
            Folder f = folderRepository.findById(root).orElseThrow();
            f.requestPermanentDeletion();
            folderRepository.flush();
            return writer.append(OutboxEvent.folderPurgeRequested(owner, root, f.getPurgeRequestedAt())).getId();
        });
        jobs.create(event, JobType.FOLDER_PURGE, root);
        ClaimedJob claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        assertThat(count("folder_purge_runs")).isZero();
        discovery.discover(claim, 0, 5);
        assertThat(count("folder_purge_runs")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"timestamp", "owner", "payload", "missing"})
    void legacyMismatchDoesNotInventRun(String corruption) {
        trashRoot();
        event = tx.execute(status -> {
            Folder f = folderRepository.findById(root).orElseThrow();
            f.requestPermanentDeletion();
            folderRepository.flush();
            return writer.append(OutboxEvent.folderPurgeRequested(owner, root, f.getPurgeRequestedAt())).getId();
        });
        jobs.create(event, JobType.FOLDER_PURGE, root);
        ClaimedJob claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        switch (corruption) {
            case "timestamp" -> sql("UPDATE folders SET purge_requested_at = purge_requested_at + interval '1 second' WHERE id = :root");
            case "owner" -> sql("UPDATE outbox_events SET payload = jsonb_set(payload, '{ownerId}', to_jsonb('" + UUID.randomUUID() + "'::text)) WHERE id = :event");
            case "payload" -> sql("UPDATE outbox_events SET payload = jsonb_set(payload, '{folderId}', to_jsonb('" + UUID.randomUUID() + "'::text)) WHERE id = :event");
            case "missing" -> sql("DELETE FROM outbox_events WHERE id = :event");
            default -> throw new AssertionError();
        }
        assertThatThrownBy(() -> discovery.discover(claim, 0, 5)).isInstanceOf(IllegalStateException.class);
        assertThat(count("folder_purge_runs")).isZero();
        assertThat(version()).isZero();
        // Preserve fixture cleanup when the event was deliberately removed.
        if (corruption.equals("missing")) { sql("DELETE FROM background_jobs WHERE id = :event"); }
    }

    @Test void cycleFailsWithoutGrowingForeverOrSealing() {
        UUID child = folder(root, false);
        ClaimedJob claim = acceptAndClaim();
        sql("UPDATE folders SET parent_folder_id = '" + child + "' WHERE id = :root");
        boolean failed = false;
        for (int i = 0; i < 6; i++) {
            try { discovery.discover(claim, version(), 5); }
            catch (RuntimeException corrupt) { failed = true; break; }
        }
        assertThat(failed).isTrue();
        assertThat(phase()).isEqualTo("DISCOVER");
        assertThat(count("folder_purge_frontier")).isLessThanOrEqualTo(2);
    }

    @Test void changedRecordedParentFailsOnFreshRead() {
        UUID child = folder(root, false);
        ClaimedJob claim = acceptAndClaim();
        discovery.discover(claim, 0, 5);
        sql("UPDATE folders SET parent_folder_id = NULL WHERE id = '" + child + "'");
        assertThatThrownBy(() -> {
            for (int i = 0; i < 5; i++) { discovery.discover(claim, version(), 5); }
        }).hasMessageContaining("Inconsistent");
        assertThat(phase()).isEqualTo("DISCOVER");
    }

    @Test void corruptFileParentAtRootIsDetectedBeforeSeal() {
        UUID file = file(root, FileStatus.READY, false, false);
        ClaimedJob claim = acceptAndClaim();
        discovery.discover(claim, 0, 1);
        discovery.discover(claim, 1, 1); // Full file page; final exhaustion remains pending.
        sql("UPDATE files SET folder_id = NULL WHERE id = '" + file + "'");
        assertThatThrownBy(() -> discovery.discover(claim, 2, 1)).hasMessageContaining("Inconsistent");
        assertThat(phase()).isEqualTo("DISCOVER");
        assertThat(version()).isEqualTo(2);
        assertThat(count("folder_purge_files")).isEqualTo(1);
    }

    @Test void authoritativeJobIdentityMismatchRollsBackCheckpoint() {
        accept();
        jobs.create(event, JobType.FOLDER_PURGE, UUID.randomUUID());
        ClaimedJob actual = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        var forged = new ClaimedJob(actual.id(), actual.jobType(), actual.aggregateType(), root,
                actual.claimToken(), actual.claimExpiresAt(), actual.attemptCount());
        assertThatThrownBy(() -> discovery.discover(forged, 0, 10)).hasMessageContaining("Checkpoint is stale");
        assertThat(count("folder_purge_frontier")).isZero();
        assertThat(version()).isZero();
    }

    @Test void databaseGuardsRejectPrematureSealIdentityChangesAndCursorRegression() {
        folder(root, false);
        ClaimedJob claim = acceptAndClaim();
        discovery.discover(claim, 0, 1);
        for (String mutation : new String[]{
                "UPDATE folder_purge_runs SET phase = 'DELETE_FILES', discovery_sealed_at = clock_timestamp() WHERE event_id = :event",
                "UPDATE folder_purge_runs SET owner_id = '" + UUID.randomUUID() + "' WHERE event_id = :event",
                "UPDATE folder_purge_frontier SET child_cursor = NULL WHERE event_id = :event AND child_cursor IS NOT NULL",
                "UPDATE folder_purge_frontier SET parent_folder_id = NULL WHERE event_id = :event AND NOT is_root"}) {
            assertThatThrownBy(() -> sql(mutation)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        }
        assertThat(version()).isEqualTo(1);
    }

    @Test void standaloneFinalizationWaitsForSealThenUsesRetainedMembership() {
        UUID file = file(root, FileStatus.READY, true, true);
        metadata.requestPermanentDeletion(owner, file);
        UUID fileEvent = outbox.findByAggregateTypeAndAggregateId("FILE", file).getFirst().getId();
        jobs.create(fileEvent, JobType.FILE_PURGE, file);
        ClaimedJob fileClaim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        progress.prepare(fileClaim);
        progress.recordBytesDeleted(fileClaim); // Simulated authoritative adapter result; no actual object.
        ClaimedJob folderClaim = acceptAndClaim();
        assertThat(progress.finalizeMetadata(fileClaim)).isFalse();
        discovery.discover(folderClaim, 0, 5);
        assertThat(progress.finalizeMetadata(fileClaim)).isFalse();
        assertThat(discovery.discover(folderClaim, 1, 5).sealed()).isTrue();
        assertThat(progress.finalizeMetadata(fileClaim)).isTrue();
        assertThat(files.existsById(file)).isFalse();
        assertThat(count("folder_purge_files")).isEqualTo(1);
        assertThat(count("file_storage_objects")).isEqualTo(1);
        assertThat(progress.finalizeMetadata(fileClaim)).isTrue();
    }

    @Test void standaloneFinalizationWinnerIsExcludedFromLaterAcceptedMembership() throws Exception {
        UUID file = file(root, FileStatus.READY, true, true);
        metadata.requestPermanentDeletion(owner, file);
        UUID fileEvent = outbox.findByAggregateTypeAndAggregateId("FILE", file).getFirst().getId();
        jobs.create(fileEvent, JobType.FILE_PURGE, file);
        ClaimedJob fileClaim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        progress.prepare(fileClaim);
        progress.recordBytesDeleted(fileClaim);
        trashRoot();
        CountDownLatch finalized = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tx.executeWithoutResult(status -> {
                assertThat(progress.finalizeMetadata(fileClaim)).isTrue();
                finalized.countDown();
                await(release);
            }));
            try {
                assertThat(finalized.await(5, TimeUnit.SECONDS)).isTrue();
                var request = executor.submit(() -> folders.requestPermanentDeletion(owner, root));
                assertThatThrownBy(() -> request.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                request.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        event = outbox.findByAggregateTypeAndAggregateId("FOLDER", root).getFirst().getId();
        jobs.create(event, JobType.FOLDER_PURGE, root);
        drain(jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow(), 10);
        assertThat(count("folder_purge_files")).isZero();
    }

    @Test void concurrentFinalizerBlocksDuringDiscoveryThenRetainsFileUntilSeal() throws Exception {
        UUID file = file(root, FileStatus.READY, true, true);
        metadata.requestPermanentDeletion(owner, file);
        UUID fileEvent = outbox.findByAggregateTypeAndAggregateId("FILE", file).getFirst().getId();
        jobs.create(fileEvent, JobType.FILE_PURGE, file);
        ClaimedJob fileClaim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        progress.prepare(fileClaim);
        progress.recordBytesDeleted(fileClaim);
        ClaimedJob folderClaim = acceptAndClaim();
        CountDownLatch page = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var scan = executor.submit(() -> tx.executeWithoutResult(status -> {
                discovery.discover(folderClaim, 0, 5);
                page.countDown();
                await(release);
            }));
            try {
                assertThat(page.await(5, TimeUnit.SECONDS)).isTrue();
                var finalizer = executor.submit(() -> progress.finalizeMetadata(fileClaim));
                assertThatThrownBy(() -> finalizer.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                scan.get(5, TimeUnit.SECONDS);
                assertThat(finalizer.get(5, TimeUnit.SECONDS)).isFalse();
            } finally { release.countDown(); }
        }
        assertThat(files.existsById(file)).isTrue();
        assertThat(discovery.discover(folderClaim, 1, 5).sealed()).isTrue();
        assertThat(progress.finalizeMetadata(fileClaim)).isTrue();
    }

    @Test void adapterUsesContinueAndHonorsCancellationAndRuntime() throws Exception {
        ClaimedJob claim = acceptAndClaim();
        var context = new JobExecutionContext(claim, Duration.ofMinutes(1).toNanos(), System::nanoTime);
        assertThat(adapter.discover(context, 5)).isEqualTo(JobHandler.Result.CONTINUE);
        assertThat(jobs.continueJob(claim)).isTrue();
        due(event);
        claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        var cancelled = new JobExecutionContext(claim, Duration.ofMinutes(1).toNanos(), System::nanoTime);
        cancelled.cancel();
        assertThatThrownBy(() -> adapter.discover(cancelled, 5)).isInstanceOf(InterruptedException.class);
        var shortRuntime = new JobExecutionContext(claim, Duration.ofSeconds(1).toNanos(), System::nanoTime);
        assertThatThrownBy(() -> adapter.discover(shortRuntime, 5)).isInstanceOf(InterruptedException.class);
        assertThat(version()).isEqualTo(1);
        var clock = new AtomicLong();
        var expired = new JobExecutionContext(claim, Duration.ofMinutes(1).toNanos(), clock::get);
        clock.set(Duration.ofSeconds(31).toNanos());
        assertThatThrownBy(() -> adapter.discover(expired, 5)).isInstanceOf(InterruptedException.class);
    }

    @Test void cancellationWhileWaitingForHierarchyRollsBackPage() throws Exception {
        ClaimedJob claim = acceptAndClaim();
        var context = new JobExecutionContext(claim, Duration.ofMinutes(1).toNanos(), System::nanoTime);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                hierarchy.acquireShared(owner);
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var scan = executor.submit(() -> adapter.discover(context, 10));
                assertThatThrownBy(() -> scan.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                context.cancel();
                release.countDown();
                holder.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> scan.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(InterruptedException.class);
            } finally { release.countDown(); }
        }
        assertThat(count("folder_purge_frontier")).isZero();
        assertThat(version()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"move", "restore", "upload", "purge"})
    void acceptedBarrierRejectsConcurrentMembershipChanges(String operation) throws Exception {
        UUID child = folder(root, operation.equals("restore") || operation.equals("purge"));
        trashRoot();
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var request = executor.submit(() -> tx.executeWithoutResult(status -> {
                folders.requestPermanentDeletion(owner, root);
                accepted.countDown();
                await(release);
            }));
            try {
                assertThat(accepted.await(5, TimeUnit.SECONDS)).isTrue();
                var competing = executor.submit(() -> {
                    switch (operation) {
                        case "move" -> folders.moveFolder(owner, child, null);
                        case "restore" -> folders.restoreFolder(owner, child);
                        case "upload" -> multipart.reserve(owner, UUID.randomUUID().toString(),
                                new com.vaultdrive.file.MultipartSessionService.Request(child, "late.txt", 1, "text/plain"));
                        case "purge" -> folders.requestPermanentDeletion(owner, child);
                        default -> throw new AssertionError();
                    }
                });
                assertThatThrownBy(() -> competing.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                request.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> competing.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(FolderNotFoundException.class);
            } finally { release.countDown(); }
        }
        event = outbox.findByAggregateTypeAndAggregateId("FOLDER", root).getFirst().getId();
        jobs.create(event, JobType.FOLDER_PURGE, root);
        drain(jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow(), 1);
        assertThat(count("folder_purge_frontier")).isEqualTo(2);
        assertThat(folderRepository.findById(child).orElseThrow().getParentFolderId()).isEqualTo(root);
    }

    private UUID folder(UUID parent, boolean trashed) {
        Folder f = new Folder(owner, parent, UUID.randomUUID().toString());
        if (trashed) { f.softDelete(); }
        return folderRepository.saveAndFlush(f).getId();
    }
    private UUID file(UUID parent, FileStatus status, boolean trashed, boolean allocation) {
        return tx.execute(ignored -> {
            UUID id = UUID.randomUUID();
            StoredFile f = new StoredFile(id, owner, parent, id + ".txt", "fixture/" + id, "text/plain", 1);
            if (status == FileStatus.READY) { f.markReady(); }
            if (status == FileStatus.FAILED) { f.markFailed(); }
            if (trashed) { f.softDelete(); }
            files.saveAndFlush(f);
            if (allocation) {
                history.allocation(f);
                em.createNativeQuery("UPDATE file_storage_objects SET write_started = TRUE WHERE file_id = :id")
                        .setParameter("id", id).executeUpdate();
            }
            return id;
        });
    }
    private void trashRoot() {
        tx.executeWithoutResult(status -> {
            folderRepository.findById(root).orElseThrow().softDelete();
            folderRepository.flush();
        });
    }
    private void accept() {
        trashRoot();
        folders.requestPermanentDeletion(owner, root);
        event = outbox.findByAggregateTypeAndAggregateId("FOLDER", root).getFirst().getId();
    }
    private ClaimedJob acceptAndClaim() {
        accept();
        jobs.create(event, JobType.FOLDER_PURGE, root);
        return jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
    }
    private void drain(ClaimedJob claim, int size) {
        for (int i = 0; i < 1000; i++) {
            if (discovery.discover(claim, version(), size).sealed()) { return; }
        }
        throw new AssertionError("Discovery did not terminate");
    }
    private long count(String table) {
        return tx.execute(status -> ((Number) em.createNativeQuery("SELECT count(*) FROM " + table + " WHERE owner_id = :owner")
                .setParameter("owner", owner).getSingleResult()).longValue());
    }
    private Object scalar(String sql) {
        return tx.execute(status -> em.createNativeQuery(sql).setParameter("event", event).getSingleResult());
    }
    private long version() { return ((Number) scalar("SELECT progress_version FROM background_jobs WHERE id = :event")).longValue(); }
    private String phase() { return (String) scalar("SELECT phase FROM folder_purge_runs WHERE event_id = :event"); }
    private void sql(String sql) {
        tx.executeWithoutResult(status -> {
            var query = em.createNativeQuery(sql);
            if (sql.contains(":root")) { query.setParameter("root", root); }
            if (sql.contains(":event")) { query.setParameter("event", event); }
            query.executeUpdate();
        });
    }
    private void due(UUID id) {
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE background_jobs SET next_attempt_at = clock_timestamp() WHERE id = :id")
                .setParameter("id", id).executeUpdate());
    }
    private void expire(UUID id) {
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE background_jobs SET claim_expires_at = clock_timestamp() - interval '1 second', "
                        + "next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = :id")
                .setParameter("id", id).executeUpdate());
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Latch timeout"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
