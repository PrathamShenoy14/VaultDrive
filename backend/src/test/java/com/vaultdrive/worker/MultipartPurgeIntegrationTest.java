package com.vaultdrive.worker;
import com.vaultdrive.file.*;

import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.file.MultipartCompletionTransactions.*;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.job.*;
import com.vaultdrive.storage.*;
import com.vaultdrive.storage.MultipartStorage.*;
import com.vaultdrive.user.*;
import com.vaultdrive.worker.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(45)
class MultipartPurgeIntegrationTest {
    static final String SCHEMA = "multipart_m5_" + UUID.randomUUID().toString().replace("-", "");
    static final String SHA = Base64.getEncoder().encodeToString(new byte[32]);
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartCancellationService cancel;
    @Autowired MultipartCancellationTransactions cancellation;
    @Autowired MultipartCompletionTransactions completion;
    @Autowired FolderService folders;
    @Autowired FileService files;
    @Autowired FileMetadataService metadata;
    @Autowired StoredFileRepository repository;
    @Autowired UserRepository users;
    @Autowired BackgroundJobService jobs;
    @Autowired FolderPurgeJobHandler folderHandler;
    @Autowired FilePurgeJobHandler fileHandler;
    @Autowired FolderPurgeDiscoveryService discovery;
    @Autowired FolderPurgeFileService member;
    @Autowired FolderPurgeMetadataService removal;
    @Autowired FilePurgeProgressService progress;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean ObjectStorageService storage;
    UUID owner;
    TransactionTemplate tx;
    @BeforeEach void setup() {
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "M5")).getId();
        tx = new TransactionTemplate(manager); reset(storage);
        doAnswer(c -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return null; })
            .when(storage).delete(any(StorageTarget.class));
    }
    @AfterAll static void dispose(@Autowired DataSource source) throws Exception {
        assertThat(SCHEMA).matches("multipart_m5_[0-9a-f]{32}");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test"); s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"RESERVED", "INITIATING", "OPEN", "UNKNOWN", "EXPIRED"})
    void abandonmentReleasesNameWithoutReleasingStorageIdentity(String state) {
        var s = reserve(null, "reuse");
        if (state.equals("INITIATING")) sessions.claimInitiation(owner, s.id());
        else if (List.of("OPEN", "UNKNOWN", "EXPIRED").contains(state)) s = open(s);
        if (state.equals("UNKNOWN")) doThrow(new MultipartStorageException()).when(storage).abortMultipart(any(), any());
        if (state.equals("EXPIRED")) {
            age(s); cancellation.expire(new MultipartCancellationTransactions.Candidate(owner, s.id()));
        } else cancel.cancel(owner, s.id());
        var old = s;
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE id = ?", String.class, s.fileId())).isEqualTo("FAILED");
        var replacement = reserve(null, "reuse");
        assertThat(replacement.fileId()).isNotEqualTo(s.fileId());
        assertThat(key(replacement)).isNotEqualTo(key(s));
        assertThat(sessions.get(owner, s.id()).state()).isEqualTo("ABANDONMENT_DECIDED");
        assertThatThrownBy(() -> sessions.authorizeParts(owner, old.id(), List.of(new PartExpectation(1, 1, SHA))))
            .isInstanceOf(MultipartConflictException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE file_storage_objects SET storage_key = ? WHERE file_id = ?", key(old), replacement.fileId()))
            .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_purge_work WHERE file_id = ?", Integer.class, old.fileId())).isZero();
        verify(storage, never()).delete(any(StorageTarget.class));
        // Existing legacy reservation sees the released name too (under another new allocation).
        cancel.cancel(owner, replacement.id());
        var legacy = metadata.createUploading(new StoredFile(UUID.randomUUID(), owner, null, "reuse", "fixture/" + UUID.randomUUID(), "text/plain", 1));
        assertThat(legacy.getStatus()).isEqualTo(FileStatus.UPLOADING);
    }

    @Test void nameReleaseRollsBackWithIntentAndDuplicateReleaseDoesNotAdvanceVersion() {
        var s = reserve(null, "atomic");
        tx.executeWithoutResult(t -> { cancellation.cancel(owner, s.id()); t.setRollbackOnly(); });
        assertThat(status(s)).isEqualTo("UPLOADING");
        assertThatThrownBy(() -> reserve(null, "atomic")).isInstanceOf(RuntimeException.class);
        cancellation.cancel(owner, s.id());
        var row = jdbc.queryForMap("SELECT * FROM files WHERE id = ?", s.fileId());
        cancellation.cancel(owner, s.id());
        assertThat(jdbc.queryForMap("SELECT * FROM files WHERE id = ?", s.fileId())).isEqualTo(row);
        assertThat(((Number)row.get("version")).longValue()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"RESERVED", "INITIATING", "OPEN", "CANCELLED", "ABORT_UNKNOWN", "EXPIRED", "COMPLETION_PENDING", "COMPLETION_UNKNOWN", "STORAGE_COMPLETE"})
    void folderPurgeDiscardsUnfinishedMetadataAndRetainsAllPhysicalUncertainty(String state) throws Exception {
        UUID root = folders.createFolder(owner, null, "root");
        var s = reserve(root, "unfinished");
        if (state.equals("INITIATING")) sessions.claimInitiation(owner, s.id());
        else if (!state.equals("RESERVED")) s = open(s);
        if (state.equals("CANCELLED")) cancel.cancel(owner, s.id());
        if (state.equals("ABORT_UNKNOWN")) {
            doThrow(new MultipartStorageException()).when(storage).abortMultipart(any(), any()); cancel.cancel(owner, s.id());
        }
        if (state.equals("EXPIRED")) { age(s); cancellation.expire(new MultipartCancellationTransactions.Candidate(owner, s.id())); }
        if (state.startsWith("COMPLETION") || state.equals("STORAGE_COMPLETE")) {
            freeze(s);
            if (!state.equals("COMPLETION_PENDING")) {
                var permit = completion.claim(owner, s.id());
                if (state.equals("STORAGE_COMPLETE")) completion.recordReceipt(owner, permit.snapshot(), evidence(permit.snapshot()), Evidence.COMPLETE_HEAD);
            }
        }
        var before = jdbc.queryForMap("SELECT * FROM upload_sessions WHERE id = ?", s.id());
        var claim = folderClaim(root); drain(claim);
        assertLogical(s); assertThat(jdbc.queryForMap("SELECT * FROM upload_sessions WHERE id = ?", s.id())).isEqualTo(before);
        assertThat(folderHandler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM folders WHERE id = ?", Integer.class, root)).isZero();
        var old = s;
        assertThatThrownBy(() -> sessions.get(owner, old.id())).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE file_purge_work SET bytes_deleted_at = clock_timestamp(), byte_receipt_job_id = ? WHERE file_id = ?", claim.id(), old.fileId()))
            .isInstanceOf(RuntimeException.class);
        verify(storage, never()).delete(any(StorageTarget.class));
    }

    @Test void readyStandalonePurgeUsesExactReceiptBindingAndReplaysWithoutDelete() throws Exception {
        var s = ready(reserve(null, "ready")); var target = completion.authorize(owner, s.id()).target();
        files.trashFile(owner, s.fileId()); metadata.requestPermanentDeletion(owner, s.fileId());
        UUID event = jdbc.queryForObject("SELECT event_id FROM file_purge_targets WHERE file_id = ?", UUID.class, s.fileId());
        jobs.create(event, JobType.FILE_PURGE, s.fileId()); var claim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(fileHandler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(fileHandler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        verify(storage, times(1)).delete(target);
        assertThat(repository.existsById(s.fileId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT bytes_deleted_at IS NOT NULL AND metadata_finalized_at IS NOT NULL AND multipart_discarded_at IS NULL FROM file_purge_work WHERE file_id = ?", Boolean.class, s.fileId())).isTrue();
        assertThat(jobs.complete(claim)).isTrue(); assertThat(progress.recoverSucceeded(event)).isTrue();
        var newer = reserve(null, "ready"); assertThat(key(newer)).isNotEqualTo(key(s));
        assertThat(progress.recoverSucceeded(event)).isTrue(); verify(storage, times(1)).delete(target);
    }

    @Test void readyFolderTrashRestoreAndPurgePreserveBindingAndUseByteProof() throws Exception {
        UUID root = folders.createFolder(owner, null, "ready-root"); var s = ready(reserve(root, "ready")); String key = key(s);
        folders.deleteFolder(owner, root);
        assertThatThrownBy(() -> sessions.get(owner, s.id())).isInstanceOf(FileNotFoundException.class);
        folders.restoreFolder(owner, root); assertThat(key(s)).isEqualTo(key);
        assertThat(sessions.get(owner, s.id()).completion().ready()).isTrue();
        var claim = folderClaim(root); drain(claim);
        verify(storage, times(1)).delete(argThat((StorageTarget t) -> t.key().equals(key)));
        assertThat(jdbc.queryForObject("SELECT bytes_deleted_at IS NOT NULL AND multipart_discarded_at IS NULL FROM file_purge_work WHERE file_id = ?", Boolean.class, s.fileId())).isTrue();
    }

    @Test void uncertainDeleteRetainsReadyMetadataAndRetryUsesOriginalKey() throws Exception {
        UUID root = folders.createFolder(owner, null, "uncertain"); var s = ready(reserve(root, "ready"));
        var claim = folderClaim(root); seal(claim);
        doThrow(new StorageDeletionException(StorageDeletionException.Kind.TRANSIENT)).when(storage).delete(any(StorageTarget.class));
        assertThat(folderHandler.handle(context(claim))).isEqualTo(JobHandler.Result.RETRY);
        assertThat(repository.existsById(s.fileId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT bytes_deleted_at IS NULL FROM file_purge_work WHERE file_id = ?", Boolean.class, s.fileId())).isTrue();
        doNothing().when(storage).delete(any(StorageTarget.class)); drain(claim);
        verify(storage, times(2)).delete(argThat((StorageTarget t) -> t.key().equals(key(s))));
    }

    @Test void logicalCheckpointFailureRollsBackAndCrashAfterProofResumesWithoutIo() throws Exception {
        UUID root = folders.createFolder(owner, null, "crash"); var s = open(reserve(root, "open"));
        var claim = folderClaim(root); seal(claim); member.prepare(context(claim), s.fileId()); long v = version(claim);
        assertThatThrownBy(() -> member.completeFile(context(claim), s.fileId(), v - 1)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT multipart_discarded_at IS NULL FROM file_purge_work WHERE file_id = ?", Boolean.class, s.fileId())).isTrue();
        assertThat(member.completeFile(context(claim), s.fileId(), v)).isTrue();
        Object proof = jdbc.queryForObject("SELECT multipart_discarded_at FROM file_purge_work WHERE file_id = ?", Object.class, s.fileId());
        // Abandoned live claim is replaced using the existing durable recovery protocol.
        jdbc.update("UPDATE background_jobs SET claim_expires_at = clock_timestamp() - interval '1 second', next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = ?", claim.id());
        var replacement = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        assertThatThrownBy(() -> member.prepare(context(claim), s.fileId())).isInstanceOf(RuntimeException.class);
        drain(replacement); assertLogical(s);
        assertThat(jdbc.queryForObject("SELECT multipart_discarded_at FROM file_purge_work WHERE file_id = ?", Object.class, s.fileId())).isEqualTo(proof);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void completionFinalizationVersusFolderPurgeHasBothSerialOrders(boolean readyFirst) throws Exception {
        UUID root = folders.createFolder(owner, null, "race"); var s = open(reserve(root, "race")); freeze(s);
        var permit = completion.claim(owner, s.id()); completion.recordReceipt(owner, permit.snapshot(), evidence(permit.snapshot()), Evidence.COMPLETE_HEAD);
        Supplier<Object> ready = () -> completion.finalizeReady(owner, s.id());
        Supplier<Object> purge = () -> { folders.deleteFolder(owner, root); folders.requestPermanentDeletion(owner, root); return "PURGE"; };
        race(readyFirst ? ready : purge, readyFirst ? purge : ready);
        var claim = claimExisting(root); drain(claim);
        if (readyFirst) {
            verify(storage, times(1)).delete(any(StorageTarget.class));
            assertThat(jdbc.queryForObject("SELECT bytes_deleted_at IS NOT NULL FROM file_purge_work WHERE file_id = ?", Boolean.class, s.fileId())).isTrue();
        } else { assertLogical(s); verifyNoInteractions(storage); }
    }

    @Test void alreadyPermittedCompletionCanRecordLateFactAfterLogicalRemovalButCannotResurrect() throws Exception {
        UUID root = folders.createFolder(owner, null, "late"); var s = open(reserve(root, "late")); freeze(s);
        var permit = completion.claim(owner, s.id()); var claim = folderClaim(root); drain(claim); assertLogical(s);
        completion.recordReceipt(owner, permit.snapshot(), evidence(permit.snapshot()), Evidence.COMPLETE_HEAD);
        assertThat(completion.finalizeReady(owner, s.id()).completion().reason()).isEqualTo("FINALIZATION_BLOCKED");
        assertLogical(s); assertThat(folderHandler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThatThrownBy(() -> jdbc.update("UPDATE file_purge_work SET multipart_discarded_at = NULL WHERE file_id = ?", s.fileId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM file_storage_objects WHERE file_id = ?", s.fileId())).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(storage);
    }

    @Test void readyFileRestoreResolvesNameCollisionAndKeepsOriginalStorageIdentity() {
        var s=ready(reserve(null,"report.txt")); String original=key(s); files.trashFile(owner,s.fileId());
        var replacement=reserve(null,"report.txt"); files.restoreFile(owner,s.fileId());
        assertThat(jdbc.queryForObject("SELECT name FROM files WHERE id = ?",String.class,s.fileId())).isEqualTo("report (restored).txt");
        assertThat(key(s)).isEqualTo(original); assertThat(key(replacement)).isNotEqualTo(original);
        assertThat(sessions.get(owner,s.id()).completion().ready()).isTrue(); verifyNoInteractions(storage);
    }

    @Test void acknowledgementPermittedBeforeFolderPurgeSurvivesLogicalRemoval() throws Exception {
        UUID root=folders.createFolder(owner,null,"abort-late"); var s=open(reserve(root,"late"));
        cancellation.cancel(owner,s.id()); var permit=cancellation.claim(owner,s.id()); assertThat(permit.permitted()).isTrue();
        var claim=folderClaim(root); drain(claim); assertLogical(s);
        assertThat(cancellation.acknowledge(owner,permit).evidenceKind()).isEqualTo("ABORT_ACK");
        assertLogical(s); assertThat(folderHandler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED); verifyNoInteractions(storage);
    }

    @Test void completedDeleteBeforeReceiptCommitCanRepeatOnlyTheSameRetainedTarget() throws Exception {
        var s=ready(reserve(null,"crash-delete")); files.trashFile(owner,s.fileId()); metadata.requestPermanentDeletion(owner,s.fileId());
        UUID event=jdbc.queryForObject("SELECT event_id FROM file_purge_targets WHERE file_id = ?",UUID.class,s.fileId());
        jobs.create(event,JobType.FILE_PURGE,s.fileId()); var claim=jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        var first=progress.prepare(claim); storage.delete(first.target()); // Crash boundary: no receipt.
        assertThat(repository.existsById(s.fileId())).isTrue(); assertThat(progress.prepare(claim).bytesDeleted()).isFalse();
        assertThat(progress.prepare(claim).target()).isEqualTo(first.target());
        assertThat(fileHandler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        verify(storage,times(2)).delete(first.target()); assertThat(repository.existsById(s.fileId())).isFalse();
    }

    @Test void oldCancellationIntentReleasesNameOnExplicitRepeatWithoutChangingItsEvidence() {
        var s=reserve(null,"m4"); s=sessions.decide(owner,s.id(),0,Decision.ABANDON,UUID.randomUUID());
        jdbc.update("INSERT INTO multipart_cancellations(session_id,decision_id,initiation_id,garage_upload_id,storage_identity,cause) SELECT id,decision_id,initiation_id,garage_upload_id,multipart_storage_identity(s),'CANCEL' FROM upload_sessions s WHERE id = ?",s.id());
        var before=jdbc.queryForMap("SELECT * FROM multipart_cancellations WHERE session_id = ?",s.id());
        cancellation.cancel(owner,s.id()); assertThat(status(s)).isEqualTo("FAILED");
        assertThat(jdbc.queryForMap("SELECT * FROM multipart_cancellations WHERE session_id = ?",s.id())).isEqualTo(before);
        assertThat(reserve(null,"m4").fileId()).isNotEqualTo(s.fileId()); verifyNoInteractions(storage);
    }

    private Session reserve(UUID folder, String name) { return sessions.reserve(owner, UUID.randomUUID().toString(), new Request(folder, name, 1, "text/plain")); }
    private Session open(Session s) {
        var d = sessions.claimInitiation(owner, s.id());
        var result = sessions.recordUploadId(owner, s.id(), 1, d.transfer().session().initiationId(), "fixture-" + s.id());
        sessions.authorizeParts(owner, s.id(), List.of(new PartExpectation(1, 1, SHA))); return result;
    }
    private Snapshot freeze(Session s) {
        var snapshot = completion.authorize(owner, s.id());
        return completion.freeze(owner, snapshot, snapshot.registered().stream().map(p -> new ManifestPart(p.partNumber(),p.length(),p.sha256(),p.registrationId(),"opaque")).toList());
    }
    private Session ready(Session s) {
        s = open(s); freeze(s); var permit = completion.claim(owner, s.id());
        completion.recordReceipt(owner, permit.snapshot(), evidence(permit.snapshot()), Evidence.COMPLETE_HEAD);
        return completion.finalizeReady(owner, s.id());
    }
    private CompletedObject evidence(Snapshot s) { return new CompletedObject(1,s.expectedSha256(),"opaque-final","text/plain",Map.of("vaultdrive-session",s.session().id().toString(),"vaultdrive-initiation",s.session().initiationId().toString())); }
    private String key(Session s) { return jdbc.queryForObject("SELECT storage_key FROM file_storage_objects WHERE file_id = ?", String.class, s.fileId()); }
    private String status(Session s) { return jdbc.queryForObject("SELECT status FROM files WHERE id = ?", String.class, s.fileId()); }
    private void age(Session s) {
        tx.executeWithoutResult(t -> {
            jdbc.execute("ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session");
            jdbc.update("UPDATE upload_sessions SET created_at = created_at - interval '25 hours', expires_at = expires_at - interval '25 hours' WHERE id = ?", s.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"); jdbc.execute("ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session");
        });
    }
    private ClaimedJob folderClaim(UUID root) { folders.deleteFolder(owner, root); folders.requestPermanentDeletion(owner, root); return claimExisting(root); }
    private ClaimedJob claimExisting(UUID root) {
        UUID event = jdbc.queryForObject("SELECT event_id FROM folder_purge_runs WHERE root_folder_id = ?", UUID.class, root);
        jobs.create(event, JobType.FOLDER_PURGE, root); return jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
    }
    private JobExecutionContext context(ClaimedJob claim) { return new JobExecutionContext(claim, Duration.ofMinutes(5).toNanos(), System::nanoTime); }
    private long version(ClaimedJob claim) { return jdbc.queryForObject("SELECT progress_version FROM background_jobs WHERE id = ?", Long.class, claim.id()); }
    private void seal(ClaimedJob claim) throws Exception { for (int i=0;i<20;i++) if (discovery.discover(claim,version(claim),20).sealed()) return; throw new AssertionError("Unsealed"); }
    private void drain(ClaimedJob claim) throws Exception { for (int i=0;i<30;i++) if (folderHandler.handle(context(claim)) == JobHandler.Result.SUCCEEDED) return; throw new AssertionError("Unfinished"); }
    private void assertLogical(Session s) {
        assertThat(repository.existsById(s.fileId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT multipart_discarded_at IS NOT NULL AND metadata_finalized_at IS NOT NULL AND bytes_deleted_at IS NULL AND never_written_at IS NULL FROM file_purge_work WHERE file_id = ?", Boolean.class, s.fileId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_storage_objects WHERE file_id = ?", Integer.class, s.fileId())).isEqualTo(1);
    }
    private void race(Supplier<Object> first, Supplier<Object> second) throws Exception {
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1); var pid = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> tx.execute(t -> { Object result=first.get(); held.countDown(); await(release); return result; }));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var b = pool.submit(() -> tx.execute(t -> { pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)); entered.countDown(); return second.get(); }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean blocked=false;
            while (System.nanoTime()<deadline) { if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0",Boolean.class,pid.get()))) { blocked=true; break; } Thread.yield(); }
            assertThat(blocked).isTrue(); assertThat(b.isDone()).isFalse(); release.countDown(); a.get(8,TimeUnit.SECONDS); b.get(8,TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
    }
    private static void await(CountDownLatch l) { try { if (!l.await(8,TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
}
