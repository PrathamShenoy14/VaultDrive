package com.vaultdrive.consumer;

import org.springframework.context.annotation.Import;

import com.vaultdrive.support.HistoricalObjectFixtures;
import com.vaultdrive.support.HistoricalUploadFixtures;
import com.vaultdrive.file.FileMetadataService;
import com.vaultdrive.file.FileService;
import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.BackgroundJobRepository;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.JobType;
import com.vaultdrive.outbox.OutboxEvent;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageBinding;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Import(HistoricalObjectFixtures.class)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=missing_job_test",
        "spring.flyway.schemas=missing_job_test", "spring.flyway.default-schema=missing_job_test"
})
@ActiveProfiles("test")
class MissingJobReconciliationIntegrationTest {
    @Autowired HistoricalObjectFixtures historicalObjects;

    @Autowired HistoricalUploadFixtures history;

    @Autowired private MissingJobReconciliationTransactions recovery;
    @Autowired private MissingJobReconciler disabled;
    @Autowired private NotificationIngestor ingestor;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private UserRepository users;
    @Autowired private FolderService folders;
    @Autowired private FileMetadataService metadata;
    @Autowired private FileService files;
    @Autowired private StoredFileRepository fileRepository;
    @Autowired private StorageBinding binding;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private BackgroundJobRepository repository;
    @Autowired private HierarchyCoordinator hierarchy;
    @Autowired private ObjectMapper mapper;
    @MockitoSpyBean private BackgroundJobService jobs;
    @MockitoSpyBean private ObjectStorageService storage;
    private TransactionTemplate tx;
    private UUID owner;
    private final List<UUID> events = new ArrayList<>();
    private final List<String> ownedKeys = new ArrayList<>();

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Recovery")).getId();
    }

    @AfterEach void cleanup() {
        reset(jobs, storage);
        // Only the one real Garage test registers a newly allocated disposable object.
        ownedKeys.forEach(key -> storage.delete(binding.target(key)));
        tx.executeWithoutResult(status -> {
            for (UUID event : events) {
                for (String table : List.of("notification_quarantine", "background_jobs", "outbox_events")) {
                    String field = table.equals("notification_quarantine") ? "event_id" : "id";
                    em.createNativeQuery("DELETE FROM " + table + " WHERE " + field + " = :id")
                            .setParameter("id", event).executeUpdate();
                }
            }
            for (String table : List.of("file_purge_targets", "file_storage_objects", "folder_purge_runs",
                    "files", "folders")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE owner_id = :owner")
                        .setParameter("owner", owner).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM users WHERE id = :owner").setParameter("owner", owner).executeUpdate();
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void createsMissingPublishedJobWithOriginalIdentityAndRepeatedPagesDoNothing(boolean file) {
        var event = fixture(file);
        var page = reconciler(20).reconcilePage(null);
        assertThat(page.scanned()).isEqualTo(1);
        assertThat(page.created()).isEqualTo(1);
        var job = repository.findById(event.getId()).orElseThrow();
        assertThat(job.getAggregateId()).isEqualTo(event.getAggregateId());
        assertThat(job.getJobType()).isEqualTo(file ? JobType.FILE_PURGE : JobType.FOLDER_PURGE);
        String before = jobSnapshot(event);
        assertThat(recovery.ingest(event.getId())).isEqualTo(MissingJobReconciliationTransactions.Outcome.ALREADY_PRESENT);
        assertThat(reconciler(20).reconcilePage(null).scanned()).isZero();
        assertThat(jobSnapshot(event)).isEqualTo(before);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "RUNNING", "SUCCEEDED", "FAILED"})
    void existingJobsRemainByteForByteUnchanged(String state) {
        var event = fixture(false);
        jobs.create(event.getId(), JobType.FOLDER_PURGE, event.getAggregateId());
        if (!state.equals("PENDING")) {
            var claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
            if (state.equals("SUCCEEDED")) { jobs.complete(claim); }
            if (state.equals("FAILED")) {
                update(event, "background_jobs", "status = 'FAILED', completed_at = clock_timestamp(), "
                        + "claim_token = NULL, claim_expires_at = NULL, failure_count = 1");
            }
        }
        String before = jobSnapshot(event);
        assertThat(reconciler(20).reconcilePage(null).scanned()).isZero();
        assertThat(recovery.ingest(event.getId())).isEqualTo(MissingJobReconciliationTransactions.Outcome.ALREADY_PRESENT);
        assertThat(jobSnapshot(event)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void consumerAndReconcilerSerializeOnSameEventInBothWinnerOrders(boolean consumerFirst) throws Exception {
        var event = fixture(false);
        var notification = PurgeNotification.parse(ConsumerTestFixtures.message(event, mapper), mapper);
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var competing = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (first.getAndSet(false)) { inserted.countDown(); await(release); }
            return result;
        }).when(jobs).createIfAbsent(eq(event.getId()), any(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            Runnable consumer = () -> ingestor.accept(notification);
            Runnable reconciler = () -> recovery.ingest(event.getId());
            var winner = pool.submit(consumerFirst ? consumer : reconciler);
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var contender = pool.submit(() -> {
                competing.countDown();
                (consumerFirst ? reconciler : consumer).run();
            });
            assertThat(competing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> contender.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            winner.get(5, TimeUnit.SECONDS);
            contender.get(5, TimeUnit.SECONDS);
            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findById(event.getId()).orElseThrow().getAttemptCount()).isZero();
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void unpublishedRecentAndUncommittedEventsAreIneligible() throws Exception {
        var pending = fixture(false);
        update(pending, "outbox_events", "delivery_status = 'PENDING', published_at = NULL");
        var recent = fixture(false);
        update(recent, "outbox_events", "published_at = clock_timestamp()");
        assertThat(reconciler(20).reconcilePage(null).scanned()).isZero();
        assertThat(recovery.ingest(pending.getId())).isEqualTo(MissingJobReconciliationTransactions.Outcome.SKIPPED);
        assertThat(recovery.ingest(recent.getId())).isEqualTo(MissingJobReconciliationTransactions.Outcome.SKIPPED);
        var pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                fixture(false);
                try { assertThat(pool.submit(() -> recovery.scan(null, 100)).get(5, TimeUnit.SECONDS)).isEmpty(); }
                catch (Exception error) { throw new AssertionError(error); }
                status.setRollbackOnly();
            });
        } finally { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "aggregate", "resource", "version", "type", "missing-owner", "typed-owner",
            "oversized", "missing-run", "missing-intent", "missing-target", "allocation", "file-version", "quarantine"})
    void inconsistentHistoricalEventsRemainVisibleAndBlocked(String corruption) {
        boolean file = List.of("missing-target", "allocation", "file-version").contains(corruption);
        var event = fixture(file);
        switch (corruption) {
            case "owner" -> update(event, "outbox_events", "payload = jsonb_set(payload, '{ownerId}', to_jsonb('"
                    + UUID.randomUUID() + "'::text))");
            case "aggregate" -> update(event, "outbox_events", "aggregate_type = 'FILE'");
            case "resource" -> update(event, "outbox_events", "payload = jsonb_set(payload, '{folderId}', to_jsonb('"
                    + UUID.randomUUID() + "'::text))");
            case "version" -> update(event, "outbox_events", "payload_version = 2");
            case "type" -> update(event, "outbox_events", "event_type = 'UNRELATED'");
            case "missing-owner" -> update(event, "outbox_events", "payload = payload - 'ownerId'");
            case "typed-owner" -> update(event, "outbox_events", "payload = jsonb_set(payload, '{ownerId}', '123'::jsonb)");
            case "oversized" -> update(event, "outbox_events", "payload = payload || jsonb_build_object('extra', repeat('x', 70000))");
            case "missing-run" -> tx.executeWithoutResult(status -> em.createNativeQuery(
                    "DELETE FROM folder_purge_runs WHERE event_id = :id").setParameter("id", event.getId()).executeUpdate());
            case "missing-target" -> tx.executeWithoutResult(status -> em.createNativeQuery(
                    "DELETE FROM file_purge_targets WHERE event_id = :id").setParameter("id", event.getId()).executeUpdate());
            case "missing-intent" -> updateResource(event, "folders", "purge_requested_at = NULL");
            case "file-version" -> updateResource(event, "files", "version = version + 1");
            case "allocation" -> tx.executeWithoutResult(status -> em.createNativeQuery(
                    "DELETE FROM file_storage_objects WHERE file_id = :id").setParameter("id", event.getAggregateId()).executeUpdate());
            case "quarantine" -> ingestor.quarantine("a".repeat(64),
                    new InvalidNotification(InvalidNotification.Reason.EVENT_MISMATCH, event.getId()));
        }
        var page = reconciler(20).reconcilePage(null);
        assertThat(page.scanned()).isEqualTo(1);
        assertThat(page.skipped()).isEqualTo(1);
        assertThat(page.failed()).isZero();
        assertThat(repository.count()).isZero();
        assertThat(outbox.existsById(event.getId())).isTrue();
        verifyNoInteractions(storage);
    }

    @Test void keysetPagesPassBlockedRowsAndWrapForRetry() {
        for (int i = 0; i < 5; i++) { fixture(false); }
        var order = recovery.scan(null, 100);
        var blocked = outbox.findById(order.getFirst().eventId()).orElseThrow();
        update(blocked, "outbox_events", "payload_version = 2");
        var service = reconciler(2);
        var first = service.reconcilePage(null);
        assertThat(first.scanned()).isEqualTo(2);
        assertThat(first.skipped()).isEqualTo(1);
        assertThat(first.created()).isEqualTo(1);
        var second = service.reconcilePage(first.nextCursor());
        assertThat(second.scanned()).isEqualTo(2);
        var third = service.reconcilePage(second.nextCursor());
        assertThat(third.scanned()).isEqualTo(1);
        assertThat(service.reconcilePage(third.nextCursor()).nextCursor()).isNull();
        assertThat(service.reconcilePage(null).skipped()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(4);
        assertThatIllegalArgumentException().isThrownBy(() -> recovery.scan(null, 101));
    }

    @Test void lockWaitTimeoutRollsBackOneEventAndDoesNotStarveFollowingPages() throws Exception {
        var event = fixture(false);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                hierarchy.acquireExclusive(owner);
                holding.countDown(); await(release);
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            var page = reconciler(20).reconcilePage(null);
            assertThat(page.failed()).isEqualTo(1);
            assertThat(page.nextCursor().eventId()).isEqualTo(event.getId());
            assertThat(repository.count()).isZero();
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertThat(reconciler(20).reconcilePage(null).created()).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void scanLockTimeoutIsSanitizedAndPreservesCursor() throws Exception {
        fixture(false);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                em.createNativeQuery("LOCK TABLE outbox_events IN ACCESS EXCLUSIVE MODE").executeUpdate();
                holding.countDown(); await(release);
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            var page = reconciler(20).reconcilePage(null);
            assertThat(page.scanFailed()).isTrue();
            assertThat(page.scanned()).isZero();
            assertThat(page.nextCursor()).isNull();
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void readsIntentFreshAfterWaitingForHierarchyLock() throws Exception {
        var event = fixture(false);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var competing = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                hierarchy.acquireExclusive(owner);
                em.createNativeQuery("UPDATE folders SET purge_requested_at = NULL WHERE id = :id")
                        .setParameter("id", event.getAggregateId()).executeUpdate();
                holding.countDown(); await(release);
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            var contender = pool.submit(() -> {
                competing.countDown();
                return recovery.ingest(event.getId());
            });
            assertThat(competing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> contender.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertThat(contender.get(5, TimeUnit.SECONDS)).isEqualTo(MissingJobReconciliationTransactions.Outcome.SKIPPED);
            assertThat(repository.count()).isZero();
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void statementTimeoutRollsBackJobInsert() {
        fixture(false);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            em.createNativeQuery("SELECT pg_sleep(6)").getSingleResult();
            return result;
        }).when(jobs).createIfAbsent(any(), any(), any());
        assertThat(reconciler(20).reconcilePage(null).failed()).isEqualTo(1);
        assertThat(repository.count()).isZero();
    }

    @Test void disabledServiceDoesNotScanOrCreateJobs() {
        fixture(false);
        assertThat(disabled.reconcilePage(null).scanned()).isZero();
        assertThat(repository.count()).isZero();
    }

    @Test void permissionActivityIsExcludedFromPurgeRecoveryBeforePagination() {
        var event = remember(outbox.saveAndFlush(OutboxEvent.permissionActivityRecorded(UUID.randomUUID(), java.time.Instant.now())));
        publish(event);
        assertThat(recovery.scan(null, 1)).isEmpty();
        assertThat(recovery.ingest(event.getId())).isEqualTo(MissingJobReconciliationTransactions.Outcome.SKIPPED);
        assertThat(repository.count()).isZero();
        verifyNoInteractions(storage);
    }

    @Test void recoveredJobDoesNotDeleteGarageBytesOrChangeProof() {
        var uploaded = historicalObjects.completed(owner, null, "disposable.txt", "text/plain", "recovery-control".getBytes());
        var file = fileRepository.findById(uploaded.getId()).orElseThrow();
        ownedKeys.add(file.getStorageKey());
        files.trashFile(owner, file.getId());
        metadata.requestPermanentDeletion(owner, file.getId());
        var event = remember(outbox.findByAggregateTypeAndAggregateId("FILE", file.getId()).getFirst());
        publish(event);
        String before = proofSnapshot(event);
        clearInvocations(storage);
        assertThat(reconciler(20).reconcilePage(null).created()).isEqualTo(1);
        verifyNoInteractions(storage);
        assertThat(proofSnapshot(event)).isEqualTo(before);
        try (var bytes = storage.download(file.getStorageKey()).inputStream()) {
            assertThat(new String(bytes.readAllBytes())).isEqualTo("recovery-control");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private OutboxEvent fixture(boolean file) {
        UUID resource;
        if (file) {
            resource = UUID.randomUUID();
            var row = history.reservation(new StoredFile(resource, owner, null, resource + ".txt",
                    "integration-tests/reconciliation/" + resource, "text/plain", 1));
            history.admission(row.getStorageKey()); // Metadata-only test fixture, no PUT.
            history.status(row, com.vaultdrive.file.FileStatus.READY);
            files.trashFile(owner, resource);
            metadata.requestPermanentDeletion(owner, resource);
        } else {
            resource = folders.createFolder(owner, null, UUID.randomUUID().toString());
            folders.deleteFolder(owner, resource);
            folders.requestPermanentDeletion(owner, resource);
        }
        var event = remember(outbox.findByAggregateTypeAndAggregateId(file ? "FILE" : "FOLDER", resource).getFirst());
        publish(event);
        return outbox.findById(event.getId()).orElseThrow();
    }

    private OutboxEvent remember(OutboxEvent event) { events.add(event.getId()); return event; }
    private void publish(OutboxEvent event) {
        update(event, "outbox_events", "delivery_status = 'PUBLISHED', published_at = clock_timestamp() - interval '10 minutes'");
    }
    private void update(OutboxEvent event, String table, String assignments) {
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE " + table + " SET " + assignments + " WHERE id = :id")
                .setParameter("id", event.getId()).executeUpdate());
    }
    private void updateResource(OutboxEvent event, String table, String assignments) {
        tx.executeWithoutResult(status -> em.createNativeQuery("UPDATE " + table + " SET " + assignments + " WHERE id = :id")
                .setParameter("id", event.getAggregateId()).executeUpdate());
    }
    private String jobSnapshot(OutboxEvent event) {
        return tx.execute(status -> (String) em.createNativeQuery("SELECT to_jsonb(j)::text FROM background_jobs j WHERE id = :id")
                .setParameter("id", event.getId()).getSingleResult());
    }
    private String proofSnapshot(OutboxEvent event) {
        return tx.execute(status -> (String) em.createNativeQuery("SELECT to_jsonb(t)::text FROM file_purge_targets t WHERE event_id = :id")
                .setParameter("id", event.getId()).getSingleResult());
    }
    private MissingJobReconciler reconciler(int size) {
        return new MissingJobReconciler(recovery, new MissingJobReconciliationProperties(
                true, Duration.ofMinutes(5), Duration.ofMinutes(1), size));
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
