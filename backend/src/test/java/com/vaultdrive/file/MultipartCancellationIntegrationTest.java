package com.vaultdrive.file;

import com.vaultdrive.file.MultipartCancellationTransactions.*;
import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.security.JwtService;
import com.vaultdrive.storage.*;
import com.vaultdrive.storage.MultipartStorage.*;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(40)
class MultipartCancellationIntegrationTest {
    static final String SCHEMA = "multipart_m4_" + UUID.randomUUID().toString().replace("-", "");
    static final String SHA = Base64.getEncoder().encodeToString(new byte[32]);
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartCancellationTransactions transactions;
    @Autowired MultipartCancellationService cancellations;
    @Autowired MultipartCompletionTransactions completionTx;
    @Autowired MultipartCompletionService completions;
    @Autowired MultipartTransferService transfers;
    @Autowired FolderService folders;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired com.vaultdrive.job.BackgroundJobService jobs;
    @Autowired com.vaultdrive.worker.FolderPurgeDiscoveryService discovery;
    @Autowired com.vaultdrive.worker.FolderPurgeFileService purge;
    @MockitoBean ObjectStorageService storage;
    @MockitoSpyBean StorageBinding binding;
    UUID owner;
    Session session;
    TransactionTemplate tx;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "M4")).getId();
        reset(storage);
        session = reserve(null);
        doAnswer(call -> {
            assertNoTransaction();
            assertThat(jdbc.queryForObject("SELECT dispatch_id IS NOT NULL AND disposition = 'UNKNOWN' "
                    + "FROM multipart_cancellations WHERE session_id = ?", Boolean.class, session.id())).isTrue();
            return null;
        }).when(storage).abortMultipart(any(), any());
    }
    @AfterEach void preserveFences() {
        verify(storage, never()).delete(any(StorageTarget.class)); verify(storage, never()).delete(anyString());
        verify(storage, never()).completeMultipart(any(), any(), anyList(), any());
        verify(storage, never()).inspectMultipartObject(any());
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE id = ?", String.class, session.fileId())).isEqualTo("UPLOADING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_storage_objects WHERE owner_id = ? "
                + "AND (put_succeeded_at IS NOT NULL OR admitted_file_version IS NOT NULL OR write_precluded_at IS NOT NULL)", Integer.class, owner)).isZero();
    }
    @AfterAll static void dispose(@Autowired DataSource source) throws Exception {
        assertThat(SCHEMA).matches("multipart_m4_[0-9a-f]{32}");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test"); s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void neverInitiatedCancellationIsDurableIdempotentAndHasNoStorageCalls() {
        var result = cancellations.cancel(owner, session.id());
        assertThat(result.cancelled()).isTrue(); assertThat(result.evidenceKind()).isEqualTo("NEVER_INITIATED");
        assertThat(result.dispatchId()).isNull(); assertThat(result.acknowledgedAt()).isNotNull();
        assertThat(cancellations.cancel(owner, session.id())).isEqualTo(result);
        assertThat(cancellations.recover(owner, session.id())).isEqualTo(result);
        assertThat(result.session().state()).isEqualTo("ABANDONMENT_DECIDED");
        assertThatThrownBy(() -> transfers.initiate(owner, session.id())).isInstanceOf(MultipartConflictException.class);
        verifyNoInteractions(storage);
    }

    @Test void openCancellationAbortsExactIdEvenWithoutUploadedPartsAndNeverRepeats() {
        open();
        var result = cancellations.cancel(owner, session.id());
        assertThat(result.cancelled()).isTrue(); assertThat(result.evidenceKind()).isEqualTo("ABORT_ACK");
        assertThat(result.dispatchId()).isNotNull();
        var row = jdbc.queryForMap("SELECT * FROM multipart_cancellations WHERE session_id = ?", session.id());
        assertThat(cancellations.cancel(owner, session.id())).isEqualTo(result);
        assertThat(cancellations.recover(owner, session.id())).isEqualTo(result);
        assertThat(jdbc.queryForMap("SELECT * FROM multipart_cancellations WHERE session_id = ?", session.id())).isEqualTo(row);
        verify(storage, times(1)).abortMultipart(any(), eq(session.uploadId()));
        assertThatThrownBy(() -> sessions.authorizeParts(owner, session.id(), List.of(part()))).isInstanceOf(MultipartConflictException.class);
        assertThatThrownBy(() -> completions.complete(owner, session.id())).isInstanceOf(MultipartConflictException.class);
        assertThatThrownBy(() -> completions.recover(owner, session.id())).isInstanceOf(MultipartConflictException.class);
    }

    @Test void unknownInitiationRemainsBlockedAndNeverAbortsOrBindsLateResult() {
        var d = sessions.claimInitiation(owner, session.id());
        var result = cancellations.cancel(owner, session.id());
        assertThat(result.disposition()).isEqualTo("BLOCKED"); assertThat(result.reason()).isEqualTo("UPLOAD_ID_UNKNOWN");
        assertThat(result.dispatchId()).isNull();
        assertThatThrownBy(() -> sessions.recordUploadId(owner, session.id(), 1, d.transfer().session().initiationId(), "late"))
                .isInstanceOf(MultipartConflictException.class);
        assertThat(cancellations.recover(owner, session.id())).isEqualTo(result); verifyNoInteractions(storage);
    }

    @Test void oldUnverifiedOpenSessionHasNoAbortAuthority() {
        var s = sessions.beginInitiation(owner, session.id(), 0, UUID.randomUUID());
        session = sessions.recordUploadId(owner, s.id(), 1, s.initiationId(), "unverified");
        assertThat(cancellations.cancel(owner, session.id()).reason()).isEqualTo("INITIATION_UNVERIFIED");
        verifyNoInteractions(storage);
    }

    @Test void crashBeforePermissionCanResumeOnce() {
        open(); var intent = transactions.cancel(owner, session.id());
        assertThat(intent.disposition()).isEqualTo("PENDING"); verifyNoInteractions(storage);
        var restarted = new MultipartCancellationService(transactions, storage);
        assertThat(restarted.recover(owner, session.id()).cancelled()).isTrue();
        verify(storage, times(1)).abortMultipart(any(), any());
    }

    @Test void crashAfterPermissionBeforeIoNeverDispatchesOnRestart() {
        open(); transactions.cancel(owner, session.id());
        var permit = transactions.claim(owner, session.id()); assertThat(permit.permitted()).isTrue();
        var restarted = new MultipartCancellationService(transactions, storage);
        assertThat(restarted.recover(owner, session.id()).disposition()).isEqualTo("UNKNOWN");
        assertThat(restarted.cancel(owner, session.id()).dispatchId()).isEqualTo(permit.cancellation().dispatchId());
        verifyNoInteractions(storage);
    }

    @Test void lostAbortResponseAndRestartRetainUnknownWithoutAnyProbeOrRedispatch() {
        open(); doThrow(new MultipartStorageException()).when(storage).abortMultipart(any(), any());
        var unknown = cancellations.cancel(owner, session.id());
        assertThat(unknown.disposition()).isEqualTo("UNKNOWN"); assertThat(unknown.acknowledgedAt()).isNull();
        var restarted = new MultipartCancellationService(transactions, storage);
        assertThat(restarted.cancel(owner, session.id())).isEqualTo(unknown);
        assertThat(restarted.recover(owner, session.id())).isEqualTo(unknown);
        verify(storage, times(1)).abortMultipart(any(), any());
    }

    @Test void crashAfterSuccessfulAbortBeforeAcknowledgementCommitRemainsUnknown() {
        open(); transactions.cancel(owner, session.id()); var permit = transactions.claim(owner, session.id());
        storage.abortMultipart(permit.target(), session.uploadId());
        var restarted = new MultipartCancellationService(transactions, storage);
        assertThat(restarted.recover(owner, session.id()).disposition()).isEqualTo("UNKNOWN");
        verify(storage, times(1)).abortMultipart(any(), any());
    }

    @Test void failedAcknowledgementCommitNeverPermitsAnotherAbort() {
        open(); failAcknowledgement(true);
        try { assertThat(cancellations.cancel(owner, session.id()).disposition()).isEqualTo("UNKNOWN"); }
        finally { failAcknowledgement(false); }
        assertThat(cancellations.recover(owner, session.id()).disposition()).isEqualTo("UNKNOWN");
        verify(storage, times(1)).abortMultipart(any(), any());
    }

    @Test void failedDispatchCommitReturnsDurablePendingIntentWithoutIoAndCanResume() {
        open();
        jdbc.execute("CREATE FUNCTION test_dispatch_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.dispatch_id IS NOT NULL THEN RAISE EXCEPTION 'fixture dispatch failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_dispatch_failure BEFORE UPDATE ON multipart_cancellations FOR EACH ROW EXECUTE FUNCTION test_dispatch_failure()");
        try {
            var result = cancellations.cancel(owner, session.id());
            assertThat(result.disposition()).isEqualTo("PENDING"); assertThat(result.dispatchId()).isNull(); verifyNoInteractions(storage);
        } finally { jdbc.execute("DROP TRIGGER test_dispatch_failure ON multipart_cancellations"); jdbc.execute("DROP FUNCTION test_dispatch_failure()"); }
        assertThat(cancellations.recover(owner, session.id()).cancelled()).isTrue();
        verify(storage, times(1)).abortMultipart(any(), any());
    }

    @Test void lostPermissionCommitAcknowledgementNeverGrantsEphemeralDispatchAuthority() {
        open();
        var uncertainTransactions = mock(MultipartCancellationTransactions.class, org.mockito.AdditionalAnswers.delegatesTo(transactions));
        doAnswer(call -> { transactions.claim(owner, session.id()); throw new IllegalStateException("simulated acknowledgement loss after committed claim"); })
                .when(uncertainTransactions).claim(owner, session.id());
        var service = new MultipartCancellationService(uncertainTransactions, storage);
        var result = service.cancel(owner, session.id());
        assertThat(result.disposition()).isEqualTo("UNKNOWN"); assertThat(result.dispatchId()).isNotNull();
        assertThat(cancellations.recover(owner, session.id())).isEqualTo(result); verifyNoInteractions(storage);
    }

    @Test void expiryBatchReportsFailedTransactionAndContinuesWithoutPartialDecision() {
        open(); age(); var failed = session;
        session = reserve(null); age();
        jdbc.execute("CREATE FUNCTION test_expiry_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.session_id = '" + failed.id()
                + "'::uuid THEN RAISE EXCEPTION 'fixture expiry failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_expiry_failure BEFORE INSERT ON multipart_cancellations FOR EACH ROW EXECUTE FUNCTION test_expiry_failure()");
        try {
            var page = cancellations.expire(null, 100);
            assertThat(page.results()).anySatisfy(e -> { assertThat(e.id()).isEqualTo(failed.id()); assertThat(e.reason()).isEqualTo("DATABASE_UNAVAILABLE"); });
            assertThat(page.results()).anySatisfy(e -> { assertThat(e.id()).isEqualTo(session.id()); assertThat(e.disposition()).isEqualTo("CANCELLED"); });
            assertThat(jdbc.queryForObject("SELECT decision_id FROM upload_sessions WHERE id = ?", UUID.class, failed.id())).isNull();
        } finally { jdbc.execute("DROP TRIGGER test_expiry_failure ON multipart_cancellations"); jdbc.execute("DROP FUNCTION test_expiry_failure()"); }
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void pendingFolderPurgeRejectsCancellationAndExpiryWithoutChangingSnapshots(boolean initiated) {
        UUID folder = folders.createFolder(owner, null, "purge-first"); session = reserve(folder); if (initiated) open(); age();
        folders.deleteFolder(owner, folder); folders.requestPermanentDeletion(owner, folder);
        var snapshot = jdbc.queryForMap("SELECT * FROM files WHERE id = ?", session.fileId());
        assertThatThrownBy(() -> cancellations.cancel(owner, session.id())).isInstanceOf(FileNotFoundException.class);
        assertThat(transactions.expire(candidate()).reason()).isEqualTo("LIFECYCLE_BLOCKED");
        assertThat(jdbc.queryForMap("SELECT * FROM files WHERE id = ?", session.fileId())).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("SELECT decision_id FROM upload_sessions WHERE id = ?", UUID.class, session.id())).isNull();
        verifyNoInteractions(storage);
    }

    @Test void acknowledgedAbortSurvivesRestartWithoutIo() {
        open(); var result = cancellations.cancel(owner, session.id()); clearInvocations(storage);
        assertThat(new MultipartCancellationService(transactions, storage).recover(owner, session.id())).isEqualTo(result);
        verifyNoInteractions(storage);
    }

    @Test void bindingMismatchDecidesCancellationButBlocksIoUntilExplicitRecoveryWithOriginalBinding() {
        open(); doReturn(false).when(binding).matches(any());
        try { assertThat(cancellations.cancel(owner, session.id()).reason()).isEqualTo("IDENTITY_MISMATCH"); verifyNoInteractions(storage); }
        finally { doCallRealMethod().when(binding).matches(any()); }
        assertThat(cancellations.recover(owner, session.id()).cancelled()).isTrue();
        verify(storage, times(1)).abortMultipart(any(), eq(session.uploadId()));
    }

    @Test void oldAbandonmentCanBeAdoptedWithoutChangingItsDecision() {
        open(); var abandoned = sessions.decide(owner, session.id(), 2, Decision.ABANDON, UUID.randomUUID());
        assertThatThrownBy(() -> cancellations.recover(owner, session.id())).isInstanceOf(MultipartConflictException.class);
        assertThat(cancellations.cancel(owner, session.id()).session()).isEqualTo(abandoned);
    }

    @Test void cancellationCannotCrossFrozenOrDispatchedCompletionDecision() {
        open(); var snapshot = completionTx.authorize(owner, session.id()); completionTx.freeze(owner, snapshot, manifest(snapshot));
        assertThatThrownBy(() -> cancellations.cancel(owner, session.id())).isInstanceOf(MultipartConflictException.class);
        assertThatThrownBy(() -> cancellations.recover(owner, session.id())).isInstanceOf(MultipartConflictException.class);
        completionTx.claim(owner, session.id());
        assertThatThrownBy(() -> cancellations.cancel(owner, session.id())).isInstanceOf(MultipartConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM multipart_cancellations WHERE session_id = ?", Integer.class, session.id())).isZero();
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void cancellationVersusCompletionBothWinnerOrders(boolean cancelFirst) throws Exception {
        open(); var snapshot = completionTx.authorize(owner, session.id());
        Supplier<?> cancel = () -> transactions.cancel(owner, session.id());
        Supplier<?> complete = () -> completionTx.freeze(owner, snapshot, manifest(snapshot));
        var results = race(cancelFirst ? cancel : complete, cancelFirst ? complete : cancel);
        assertThat(results.getLast()).isInstanceOf(MultipartConflictException.class);
        assertThat(sessions.get(owner, session.id()).state()).isEqualTo(cancelFirst ? "ABANDONMENT_DECIDED" : "COMPLETION_DECIDED");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void expiryVersusCompletionBothWinnerOrders(boolean expiryFirst) throws Exception {
        open(); var snapshot = completionTx.authorize(owner, session.id());
        // Completion commits while live; expiry waits and must preserve that decision. Expiry first uses DB-aged fixture.
        if (expiryFirst) age();
        Supplier<?> expire = () -> transactions.expire(candidate());
        Supplier<?> complete = () -> completionTx.freeze(owner, snapshot, manifest(snapshot));
        var result = race(expiryFirst ? expire : complete, expiryFirst ? complete : expire);
        if (expiryFirst) assertThat(result.getLast()).isInstanceOf(MultipartConflictException.class);
        else assertThat(((Expiry) result.getLast()).reason()).isEqualTo("DECISION_EXISTS");
        assertThat(sessions.get(owner, session.id()).state()).isEqualTo(expiryFirst ? "ABANDONMENT_DECIDED" : "COMPLETION_DECIDED");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void expiryVersusPartAuthorizationBothOrders(boolean expiryFirst) throws Exception {
        open(); if (expiryFirst) age();
        Supplier<?> parts = () -> sessions.authorizeParts(owner, session.id(), List.of(part()));
        Supplier<?> expire = () -> { if (!expiryFirst) age(); return transactions.expire(candidate()); };
        var result = race(expiryFirst ? expire : parts, expiryFirst ? parts : expire);
        if (expiryFirst) assertThat(result.getLast()).isInstanceOf(MultipartConflictException.class);
        else assertThat(result.getFirst()).isInstanceOf(Transfer.class);
        assertThat(sessions.get(owner, session.id()).state()).isEqualTo("ABANDONMENT_DECIDED");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void cancellationOrExpiryDuringSigningRejectsResponse(boolean expire) {
        open();
        doAnswer(call -> { assertNoTransaction(); if (expire) { age(); transactions.expire(candidate()); } else transactions.cancel(owner, session.id());
            return new SignedPart("fixture", Map.of(), Instant.now().plusSeconds(30)); }).when(storage).signPart(any(), any(), anyInt(), anyLong(), any(), any());
        assertThatThrownBy(() -> transfers.partUrls(owner, session.id(), List.of(part()))).isInstanceOf(MultipartConflictException.class);
        assertThat(sessions.get(owner, session.id()).state()).isEqualTo("ABANDONMENT_DECIDED");
    }

    @Test void explicitExpiryIsBoundedDatabaseOnlyAndPreservesCompletionDecisions() {
        open(); age();
        var page = cancellations.expire(null, 100);
        assertThat(page.results()).anySatisfy(e -> { assertThat(e.id()).isEqualTo(session.id()); assertThat(e.disposition()).isEqualTo("PENDING"); });
        assertThat(transactions.retained(owner, session.id()).cause()).isEqualTo("EXPIRED");
        assertThat(cancellations.expire(null, 100).results()).noneMatch(e -> e.id().equals(session.id()));
        assertThatThrownBy(() -> cancellations.expire(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cancellations.expire(null, 101)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage);
        assertThat(cancellations.recover(owner, session.id()).cancelled()).isTrue();
    }

    @Test void unexpiredSessionIsNotExpiredAndReservedExpiryNeedsNoAbort() {
        assertThat(transactions.expire(candidate()).reason()).isEqualTo("NOT_EXPIRED");
        age(); assertThat(transactions.expire(candidate()).disposition()).isEqualTo("CANCELLED");
        assertThat(cancellations.cancel(owner, session.id()).cause()).isEqualTo("EXPIRED"); verifyNoInteractions(storage);
    }

    @Test void expiryCursorAdvancesPastBlockedFolderWithoutMutation() {
        UUID folder = folders.createFolder(owner, null, "expired-blocked"); session = reserve(folder); age();
        folders.deleteFolder(owner, folder);
        assertThat(transactions.expire(candidate()).reason()).isEqualTo("LIFECYCLE_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT decision_id FROM upload_sessions WHERE id = ?", UUID.class, session.id())).isNull();
        var page = cancellations.expire(null, 1); assertThat(page.results()).hasSize(1); assertThat(page.nextCursor()).isNotNull();
        var next = cancellations.expire(page.nextCursor(), 100);
        assertThat(next.results()).noneMatch(e -> e.id().equals(page.results().getFirst().id()));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void folderTrashAndCancellationBothOrdersRespectFreshBarrier(boolean cancelFirst) throws Exception {
        UUID folder = folders.createFolder(owner, null, "trash-race"); session = reserve(folder); open();
        Supplier<?> cancel = () -> transactions.cancel(owner, session.id());
        Supplier<?> trash = () -> { folders.deleteFolder(owner, folder); return "trashed"; };
        var result = race(cancelFirst ? cancel : trash, cancelFirst ? trash : cancel);
        if (!cancelFirst) assertThat(result.getLast()).isInstanceOf(FileNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM upload_sessions WHERE id = ?", String.class, session.id()))
                .isEqualTo(cancelFirst ? "ABANDONMENT_DECIDED" : "OPEN");
        verifyNoInteractions(storage);
    }

    @Test void folderTrashAfterIntentBlocksUndispatchedAbortUntilRestore() {
        UUID folder = folders.createFolder(owner, null, "after-intent"); session = reserve(folder); open();
        transactions.cancel(owner, session.id()); folders.deleteFolder(owner, folder);
        assertThat(transactions.claim(owner, session.id()).cancellation().reason()).isEqualTo("LIFECYCLE_BLOCKED");
        assertThatThrownBy(() -> cancellations.recover(owner, session.id())).isInstanceOf(FileNotFoundException.class);
        verifyNoInteractions(storage); folders.restoreFolder(owner, folder);
        assertThat(cancellations.recover(owner, session.id()).cancelled()).isTrue();
    }

    @Test void folderPurgeDuringAbortRetainsAcknowledgementAndLeavesManifestUnsafe() throws Exception {
        UUID folder = folders.createFolder(owner, null, "purge-during"); session = reserve(folder); open();
        doAnswer(call -> { assertNoTransaction(); folders.deleteFolder(owner, folder); folders.requestPermanentDeletion(owner, folder); return null; })
                .when(storage).abortMultipart(any(), any());
        assertThat(cancellations.cancel(owner, session.id()).cancelled()).isTrue();
        UUID event = jdbc.queryForObject("SELECT event_id FROM folder_purge_runs WHERE root_folder_id = ?", UUID.class, folder);
        jobs.create(event, com.vaultdrive.job.JobType.FOLDER_PURGE, folder);
        var claim = jobs.claimNext(com.vaultdrive.job.JobType.FOLDER_PURGE).orElseThrow();
        for (int i = 0; i < 10; i++) {
            long version = jdbc.queryForObject("SELECT progress_version FROM background_jobs WHERE id = ?", Long.class, event);
            if (discovery.discover(claim, version, 20).sealed()) break;
        }
        var manifest = jdbc.queryForMap("SELECT * FROM folder_purge_files WHERE file_id = ?", session.fileId());
        var context = mock(com.vaultdrive.worker.JobExecutionContext.class); when(context.claim()).thenReturn(claim);
        assertThatThrownBy(() -> purge.prepare(context, session.fileId())).isInstanceOfSatisfying(com.vaultdrive.worker.FolderPurgeFileService.BlockedFile.class,
                e -> assertThat(e.reason()).isEqualTo("UNSAFE_WRITER"));
        assertThat(jdbc.queryForMap("SELECT * FROM folder_purge_files WHERE file_id = ?", session.fileId())).isEqualTo(manifest);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_purge_work WHERE file_id = ?", Integer.class, session.fileId())).isZero();
        assertThatThrownBy(() -> cancellations.recover(owner, session.id())).isInstanceOf(FileNotFoundException.class);
    }

    @Test void concurrentRequestsHaveOneAbortDispatcherAndNoLockAcrossIo() throws Exception {
        open(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { assertNoTransaction(); entered.countDown(); await(release); return null; }).when(storage).abortMultipart(any(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> cancellations.cancel(owner, session.id())); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> cancellations.cancel(owner, session.id()));
            assertThat(second.get(5, TimeUnit.SECONDS).disposition()).isEqualTo("UNKNOWN");
            release.countDown(); assertThat(first.get(8, TimeUnit.SECONDS).cancelled()).isTrue();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        verify(storage, times(1)).abortMultipart(any(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"decision_id = gen_random_uuid()", "garage_upload_id = 'changed'", "cause = 'EXPIRED'",
            "storage_identity = '{}'::jsonb", "initiation_id = gen_random_uuid()", "revision = revision + 1"})
    void databaseRejectsIdentityOrBareRevisionChanges(String update) {
        open(); transactions.cancel(owner, session.id());
        assertThatThrownBy(() -> jdbc.update("UPDATE multipart_cancellations SET " + update + " WHERE session_id = ?", session.id())).isInstanceOf(RuntimeException.class);
    }

    @Test void dispatchAndAcknowledgedEvidenceCannotBeReassignedOrDeleted() {
        open(); transactions.cancel(owner, session.id()); var permit = transactions.claim(owner, session.id());
        for (String update : List.of("dispatch_id = gen_random_uuid(), revision = revision + 1", "dispatch_id = NULL, dispatch_decided_at = NULL, revision = revision + 1")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE multipart_cancellations SET " + update + " WHERE session_id = ?", session.id())).isInstanceOf(RuntimeException.class);
        }
        transactions.acknowledge(owner, permit);
        assertThatThrownBy(() -> jdbc.update("UPDATE multipart_cancellations SET acknowledged_at = clock_timestamp(), revision = revision + 1 WHERE session_id = ?", session.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM multipart_cancellations WHERE session_id = ?", session.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM files WHERE id = ?", session.fileId())).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(storage);
    }

    @Test void rollbackRestoresDecisionAndIntentAtomically() {
        open(); tx.executeWithoutResult(status -> { transactions.cancel(owner, session.id()); status.setRollbackOnly(); });
        assertThat(sessions.get(owner, session.id())).isEqualTo(session);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM multipart_cancellations WHERE session_id = ?", Integer.class, session.id())).isZero();
    }

    @Test void httpJwtOwnershipAndStableStatuses() throws Exception {
        String token = "Bearer " + jwt.generateAccessToken(owner);
        for (String action : List.of("cancel", "cancel/recover")) {
            mvc.perform(post("/api/v1/upload-sessions/" + session.id() + "/" + action)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/upload-sessions/" + session.id() + "/" + action).header("Authorization", "Bearer " + jwt.generateAccessToken(UUID.randomUUID())))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/upload-sessions/" + UUID.randomUUID() + "/" + action).header("Authorization", token)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/upload-sessions/{id}/cancel/recover", session.id()).header("Authorization", token)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/upload-sessions/{id}/cancel", session.id()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value("CANCELLED"));
        mvc.perform(post("/api/v1/upload-sessions/{id}/cancel", session.id()).header("Authorization", token)).andExpect(status().isOk());
        session = reserve(null); open(); doThrow(new MultipartStorageException()).when(storage).abortMultipart(any(), any());
        mvc.perform(post("/api/v1/upload-sessions/{id}/cancel", session.id()).header("Authorization", token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.disposition").value("UNKNOWN"));
        mvc.perform(post("/api/v1/upload-sessions/{id}/cancel/recover", session.id()).header("Authorization", token)).andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/upload-sessions/{id}/cancellation", session.id()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value("UNKNOWN"));
        mvc.perform(get("/api/v1/upload-sessions/{id}/cancellation", session.id()).header("Authorization", "Bearer " + jwt.generateAccessToken(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/upload-sessions/{id}/cancellation", session.id())).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void expiryAndCancellationRetainTheFirstCauseAndDecision(boolean expiryFirst) throws Exception {
        open(); age();
        Supplier<?> expire = () -> transactions.expire(candidate());
        Supplier<?> cancel = () -> transactions.cancel(owner, session.id());
        var result = race(expiryFirst ? expire : cancel, expiryFirst ? cancel : expire);
        assertThat(result).noneMatch(RuntimeException.class::isInstance);
        var retained = transactions.retained(owner, session.id());
        assertThat(retained.cause()).isEqualTo(expiryFirst ? "EXPIRED" : "CANCEL");
        assertThat(retained.session().revision()).isEqualTo(3);
        verifyNoInteractions(storage);
    }

    @Test void serviceRejectsAmbientTransactionsForEveryOperation() {
        for (Runnable task : List.<Runnable>of(() -> cancellations.cancel(owner, session.id()),
                () -> cancellations.recover(owner, session.id()), () -> cancellations.expire(null, 1))) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> task.run())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        }
        assertThat(sessions.get(owner, session.id())).isEqualTo(session); verifyNoInteractions(storage);
    }

    private Session reserve(UUID folder) { return sessions.reserve(owner, UUID.randomUUID().toString(), new Request(folder, UUID.randomUUID().toString(), 1, "text/plain")); }
    private void open() {
        var d = sessions.claimInitiation(owner, session.id());
        session = sessions.recordUploadId(owner, session.id(), 1, d.transfer().session().initiationId(), "fixture-" + session.id());
        sessions.authorizeParts(owner, session.id(), List.of(part()));
    }
    private PartExpectation part() { return new PartExpectation(1, 1, SHA); }
    private Candidate candidate() { return new Candidate(owner, session.id()); }
    private List<ManifestPart> manifest(MultipartCompletionTransactions.Snapshot s) {
        return s.registered().stream().map(p -> new ManifestPart(p.partNumber(), p.length(), p.sha256(), p.registrationId(), "opaque")).toList();
    }
    private void age() {
        tx.executeWithoutResult(status -> { jdbc.execute("ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session");
            jdbc.update("UPDATE upload_sessions SET created_at = created_at - interval '25 hours', expires_at = expires_at - interval '25 hours' WHERE id = ?", session.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"); jdbc.execute("ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session"); });
    }
    private void failAcknowledgement(boolean install) {
        if (install) {
            jdbc.execute("CREATE FUNCTION test_abort_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.disposition = 'CANCELLED' THEN RAISE EXCEPTION 'fixture acknowledgement failure'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER test_abort_failure BEFORE UPDATE ON multipart_cancellations FOR EACH ROW EXECUTE FUNCTION test_abort_failure()");
        } else { jdbc.execute("DROP TRIGGER test_abort_failure ON multipart_cancellations"); jdbc.execute("DROP FUNCTION test_abort_failure()"); }
    }
    private static void assertNoTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private static Object capture(Supplier<?> task) { try { return task.get(); } catch (RuntimeException e) { return e; } }
    private List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var pid = new AtomicInteger();
        try {
            var winner = pool.submit(() -> capture(() -> tx.execute(status -> {
                Object result = capture(first); held.countDown(); await(release); if (result instanceof RuntimeException) status.setRollbackOnly(); return result;
            })));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var loser = pool.submit(() -> capture(() -> tx.execute(status -> { pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); entered.countDown(); return second.get(); })));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); boolean blocked = false;
            while (System.nanoTime() < deadline) { blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid.get())); if (blocked) break; Thread.yield(); }
            assertThat(blocked).isTrue(); assertThat(loser.isDone()).isFalse(); release.countDown();
            return List.of(winner.get(8, TimeUnit.SECONDS), loser.get(8, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
