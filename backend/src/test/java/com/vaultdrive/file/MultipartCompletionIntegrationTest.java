package com.vaultdrive.file;

import com.vaultdrive.file.MultipartCompletionTransactions.*;
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
import java.security.MessageDigest;
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
class MultipartCompletionIntegrationTest {
    static final String SCHEMA = "multipart_m3_" + UUID.randomUUID().toString().replace("-", "");
    static final String SHA = Base64.getEncoder().encodeToString(new byte[32]);
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartCompletionTransactions transactions;
    @Autowired MultipartCompletionService completions;
    @Autowired MultipartTransferService transfers;
    @Autowired StoredFileRepository files;
    @Autowired FileService legacy;
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
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "M3")).getId();
        reset(storage);
        session = open(null, 8388609);
        when(storage.listParts(any(), any(), anyInt(), anyInt())).thenAnswer(call -> {
            assertNoTransaction(); return new Page(List.of(new Part(1, 8388608, "opaque-one", SHA), new Part(2, 1, "opaque-two", SHA)), null);
        });
        when(storage.completeMultipart(any(), any(), anyList(), any())).thenAnswer(call -> {
            assertNoTransaction();
            assertThat(jdbc.queryForObject("SELECT dispatch_id IS NOT NULL AND disposition = 'UNKNOWN' "
                    + "FROM multipart_completions WHERE session_id = ?", Boolean.class, session.id())).isTrue();
            return new CompletionAck("opaque-final", call.getArgument(3), "COMPOSITE");
        });
        when(storage.inspectMultipartObject(any())).thenAnswer(call -> { assertNoTransaction(); return evidence(session); });
    }
    @AfterEach void preserveProtocolFences() {
        verify(storage, never()).delete(any(StorageTarget.class)); verify(storage, never()).delete(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_storage_objects WHERE owner_id = ? "
                + "AND (put_succeeded_at IS NOT NULL OR admitted_file_version IS NOT NULL)", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_cleanup_intents WHERE file_id = ?", Integer.class, session.fileId())).isZero();
    }
    @AfterAll static void dispose(@Autowired DataSource source) throws Exception {
        assertThat(SCHEMA).matches("multipart_m3_[0-9a-f]{32}");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test"); s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void completionFreezesManifestCommitsReceiptThenReadyAndDuplicatesDoNoIo() {
        var ready = completions.complete(owner, session.id());
        assertReady(ready);
        assertThat(ready.state()).isEqualTo("COMPLETION_DECIDED");
        assertThat(jdbc.queryForObject("SELECT garage_upload_id FROM multipart_completion_receipts WHERE session_id = ?", String.class, session.id()))
                .isEqualTo(session.uploadId());
        assertThat(jdbc.queryForObject("SELECT manifest_hash = encode(sha256(convert_to(manifest::text,'UTF8')),'hex') "
                + "FROM multipart_completions WHERE session_id = ?", Boolean.class, session.id())).isTrue();
        var row = jdbc.queryForMap("SELECT * FROM multipart_completion_receipts WHERE session_id = ?", session.id());
        clearInvocations(storage);
        assertThat(completions.complete(owner, session.id())).isEqualTo(ready);
        assertThat(completions.recover(owner, session.id())).isEqualTo(ready);
        assertThat(sessions.get(owner, session.id())).isEqualTo(ready);
        assertThat(jdbc.queryForMap("SELECT * FROM multipart_completion_receipts WHERE session_id = ?", session.id())).isEqualTo(row);
        verifyNoInteractions(storage);
        assertThatThrownBy(() -> transfers.partUrls(owner, session.id(), List.of(new PartExpectation(1, 8388608, SHA))))
                .isInstanceOf(MultipartConflictException.class);
    }

    @Test void frozenUndispatchedDecisionCanResumeButCrashAfterPermissionNeverRedispatches() {
        var frozen = frozen();
        assertThat(frozen.session().completion().disposition()).isEqualTo("PENDING");
        verifyNoInteractions(storage);
        transactions.claim(owner, session.id()); // Simulated death after committed permission and before the call.
        doThrow(new MultipartStorageException()).when(storage).inspectMultipartObject(any());
        var restarted = new MultipartCompletionService(transactions, storage);
        assertThat(restarted.complete(owner, session.id()).completion().disposition()).isEqualTo("UNKNOWN");
        assertThat(restarted.recover(owner, session.id()).completion().receiptRecorded()).isFalse();
        verify(storage, never()).completeMultipart(any(), any(), anyList(), any());
        assertThat(jdbc.queryForObject("SELECT dispatch_id FROM multipart_completions WHERE session_id = ?", UUID.class, session.id())).isNotNull();
    }

    @Test void crashBeforeDispatchPermissionResumesExactlyOnce() {
        frozen();
        var result = completions.recover(owner, session.id());
        assertReady(result);
        verify(storage, times(1)).completeMultipart(any(), eq(session.uploadId()), anyList(), any());
        verify(storage, never()).listParts(any(), any(), anyInt(), anyInt());
    }

    @Test void responseLossRetainsUnknownAndExplicitPositiveRecoveryNeverCompletesAgain() {
        doThrow(new MultipartStorageException()).when(storage).completeMultipart(any(), any(), anyList(), any());
        var unknown = completions.complete(owner, session.id());
        assertThat(unknown.completion().disposition()).isEqualTo("UNKNOWN");
        assertThat(unknown.completion().receiptRecorded()).isFalse();
        completions.complete(owner, session.id());
        assertReady(completions.recover(owner, session.id()));
        verify(storage, times(1)).completeMultipart(any(), any(), anyList(), any());
        assertThat(jdbc.queryForObject("SELECT evidence_kind FROM multipart_completion_receipts WHERE session_id = ?", String.class, session.id())).isEqualTo("HEAD_RECOVERY");
    }

    @Test void receiptPersistenceFailureRetainsBytesAndRecoveryOnlyObserves() {
        failReceipt(true);
        try {
            assertThat(completions.complete(owner, session.id()).completion().receiptRecorded()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM multipart_completion_receipts WHERE session_id = ?", Integer.class, session.id())).isZero();
        } finally { failReceipt(false); }
        assertReady(completions.recover(owner, session.id()));
        verify(storage, times(1)).completeMultipart(any(), any(), anyList(), any());
    }

    @Test void receiptSurvivesReadyFailureAndRecoveryNeedsNoStorage() {
        failReady(true);
        try {
            var result = completions.complete(owner, session.id());
            assertThat(result.completion().receiptRecorded()).isTrue();
            assertThat(result.completion().disposition()).isEqualTo("BLOCKED");
            assertThat(result.completion().reason()).isEqualTo("FINALIZATION_BLOCKED");
        } finally { failReady(false); }
        clearInvocations(storage);
        assertReady(completions.recover(owner, session.id())); verifyNoInteractions(storage);
    }

    @Test void crashAfterReceiptCommitRecoversMetadataOnly() {
        var permit = transactions.claim(owner, frozen().session().id());
        transactions.recordReceipt(owner, permit.snapshot(), evidence(session), Evidence.COMPLETE_HEAD);
        clearInvocations(storage);
        assertReady(completions.recover(owner, session.id())); verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(strings = {"SIZE","SHA","METADATA","INITIATION","TYPE","ETAG"})
    void insufficientPositiveEvidenceRemainsBlockedWithoutReceipt(String mismatch) {
        var good = evidence(session);
        var metadata = new HashMap<>(good.metadata());
        if (mismatch.equals("METADATA")) metadata.put("vaultdrive-session", UUID.randomUUID().toString());
        if (mismatch.equals("INITIATION")) metadata.put("vaultdrive-initiation", UUID.randomUUID().toString());
        doReturn(new CompletedObject(mismatch.equals("SIZE") ? 3 : good.size(), mismatch.equals("SHA") ? SHA : good.sha256(),
                mismatch.equals("ETAG") ? "other-final" : good.etag(), mismatch.equals("TYPE") ? "image/png" : good.contentType(), metadata))
                .when(storage).inspectMultipartObject(any());
        var result = completions.complete(owner, session.id());
        assertThat(result.completion().disposition()).isEqualTo("BLOCKED");
        assertThat(result.completion().reason()).isEqualTo("EVIDENCE_MISMATCH");
        assertThat(result.completion().receiptRecorded()).isFalse();
        completions.complete(owner, session.id());
        verify(storage, times(1)).completeMultipart(any(), any(), anyList(), any());
    }

    @Test void headAbsenceAndBadAckNeverBecomeFailureOrRetryAuthority() {
        doThrow(new MultipartStorageException()).when(storage).inspectMultipartObject(any());
        assertThat(completions.complete(owner, session.id()).completion().disposition()).isEqualTo("UNKNOWN");
        assertThat(completions.recover(owner, session.id()).completion().disposition()).isEqualTo("UNKNOWN");
        verify(storage, times(1)).completeMultipart(any(), any(), anyList(), any());
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE id = ?", String.class, session.fileId())).isEqualTo("UPLOADING");
    }

    @ParameterizedTest @ValueSource(strings = {"SHA", "TYPE", "ETAG"})
    void invalidCompletionAcknowledgementCannotSupplyAReceipt(String invalid) {
        doReturn(new CompletionAck(invalid.equals("ETAG") ? "" : "opaque-final", invalid.equals("SHA") ? SHA : composite(),
                invalid.equals("TYPE") ? "FULL_OBJECT" : "COMPOSITE")).when(storage).completeMultipart(any(), any(), anyList(), any());
        var result = completions.complete(owner, session.id());
        assertThat(result.completion().reason()).isEqualTo("EVIDENCE_MISMATCH");
        assertThat(result.completion().receiptRecorded()).isFalse();
        verify(storage, never()).inspectMultipartObject(any());
    }

    @Test void configurationMismatchBlocksDispatchAndReceiptBackedReadyUntilExactBindingReturns() {
        frozen(); doReturn(false).when(binding).matches(any(StorageTarget.class));
        assertThat(completions.recover(owner, session.id()).completion().reason()).isEqualTo("IDENTITY_MISMATCH");
        verifyNoInteractions(storage);
        doCallRealMethod().when(binding).matches(any(StorageTarget.class));
        var permit = transactions.claim(owner, session.id());
        transactions.recordReceipt(owner, permit.snapshot(), evidence(session), Evidence.COMPLETE_HEAD);
        doReturn(false).when(binding).matches(any(StorageTarget.class));
        assertThat(completions.recover(owner, session.id()).completion().reason()).isEqualTo("IDENTITY_MISMATCH");
        doCallRealMethod().when(binding).matches(any(StorageTarget.class));
        assertReady(completions.recover(owner, session.id())); verifyNoInteractions(storage);
    }

    @Test void openBindingMismatchNeverFreezesOrCallsGarage() {
        doReturn(false).when(binding).matches(any(StorageTarget.class));
        assertThatThrownBy(() -> completions.complete(owner, session.id())).isInstanceOf(MultipartConflictException.class);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(strings = {"LENGTH", "SHA", "REGISTRATION", "NUMBER"})
    void databaseRejectsForgedManifestAndRollsBackTheDecision(String field) {
        var snapshot = transactions.authorize(owner, session.id());
        var manifest = new ArrayList<>(manifest(snapshot)); var part = manifest.getFirst();
        manifest.set(0, new ManifestPart(field.equals("NUMBER") ? 2 : part.partNumber(), field.equals("LENGTH") ? 7 : part.length(),
                field.equals("SHA") ? shaDifferent() : part.sha256(), field.equals("REGISTRATION") ? UUID.randomUUID() : part.registrationId(), part.etag()));
        assertThatThrownBy(() -> transactions.freeze(owner, snapshot, manifest)).isInstanceOf(RuntimeException.class);
        assertThat(sessions.get(owner, session.id()).state()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM multipart_completions WHERE session_id = ?", Integer.class, session.id())).isZero();
    }

    @Test void receiptDatabaseGuardRejectsUnboundSizeChecksumManifestMetadataAndDispatch() {
        transactions.claim(owner, frozen().session().id());
        for (String field : List.of("SIZE", "SHA", "MANIFEST", "METADATA", "DISPATCH")) {
            String sql = """
                    INSERT INTO multipart_completion_receipts(session_id,file_id,decision_id,dispatch_id,initiation_id,
                        garage_upload_id,storage_identity,manifest_hash,size_bytes,sha256,etag,content_type,object_metadata,evidence_kind)
                    SELECT s.id,s.file_id,s.decision_id,
                    """ + (field.equals("DISPATCH") ? "gen_random_uuid()" : "c.dispatch_id") + ",s.initiation_id,s.garage_upload_id,multipart_storage_identity(s),"
                    + (field.equals("MANIFEST") ? "repeat('0',64)" : "c.manifest_hash") + ","
                    + (field.equals("SIZE") ? "s.declared_size + 1" : "s.declared_size") + ","
                    + (field.equals("SHA") ? "'" + SHA + "'" : "c.expected_sha256")
                    + ",'opaque-final',s.declared_type,jsonb_build_object('vaultdrive-session',"
                    + (field.equals("METADATA") ? "gen_random_uuid()::text" : "s.id::text")
                    + ",'vaultdrive-initiation',s.initiation_id::text),'HEAD_RECOVERY' FROM upload_sessions s "
                    + "JOIN multipart_completions c ON c.session_id = s.id WHERE s.id = ?";
            assertThatThrownBy(() -> jdbc.update(sql, session.id())).isInstanceOf(RuntimeException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM multipart_completion_receipts WHERE session_id = ?", Integer.class, session.id())).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE multipart_completions SET dispatch_id = gen_random_uuid(), revision = revision + 1 WHERE session_id = ?", session.id()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void readySessionReadsFollowAuthorizedCurrentFileLocationAfterMetadataChanges() {
        assertReady(completions.complete(owner, session.id()));
        UUID folder = folders.createFolder(owner, null, "new-location");
        legacy.renameFile(owner, session.fileId(), "renamed"); legacy.moveFile(owner, session.fileId(), folder);
        clearInvocations(storage);
        assertReady(completions.complete(owner, session.id())); verifyNoInteractions(storage);
        folders.deleteFolder(owner, folder);
        assertThatThrownBy(() -> completions.recover(owner, session.id())).isInstanceOf(FileNotFoundException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING","EXTRA","LENGTH","SHA","EMPTY_ETAG","ORDER","DUPLICATE","PAGINATION","NULL_SHA"})
    void invalidAuthoritativePartsNeverChooseCompletion(String invalid) {
        var one = new Part(1, 8388608, "opaque-one", SHA); var two = new Part(2, 1, "opaque-two", SHA);
        Page page = switch (invalid) {
            case "MISSING" -> new Page(List.of(one), null);
            case "EXTRA" -> new Page(List.of(one, two, new Part(3, 1, "extra", SHA)), null);
            case "LENGTH" -> new Page(List.of(new Part(1, 7, "opaque", SHA), two), null);
            case "SHA" -> new Page(List.of(new Part(1, 8388608, "opaque", shaDifferent()), two), null);
            case "NULL_SHA" -> new Page(List.of(new Part(1, 8388608, "opaque", null), two), null);
            case "EMPTY_ETAG" -> new Page(List.of(new Part(1, 8388608, "", SHA), two), null);
            case "ORDER" -> new Page(List.of(two, one), null);
            case "DUPLICATE" -> new Page(List.of(one, one), null);
            default -> new Page(List.of(one), 2);
        };
        doReturn(page).when(storage).listParts(any(), any(), anyInt(), anyInt());
        assertThatThrownBy(() -> completions.complete(owner, session.id())).isInstanceOf(MultipartConflictException.class);
        assertThat(sessions.get(owner, session.id()).state()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM multipart_completions WHERE session_id = ?", Integer.class, session.id())).isZero();
        verify(storage, never()).completeMultipart(any(), any(), anyList(), any());
    }

    @Test void validAuthoritativePaginationFreezesOrderedEtags() {
        doAnswer(call -> { assertNoTransaction(); return (int) call.getArgument(2) == 0
                ? new Page(List.of(new Part(1, 8388608, "one", SHA)), 1)
                : new Page(List.of(new Part(2, 1, "two", SHA)), null); })
                .when(storage).listParts(any(), any(), anyInt(), anyInt());
        assertReady(completions.complete(owner, session.id()));
        var capture = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(storage).completeMultipart(any(), any(), capture.capture(), any());
        assertThat(capture.getValue()).extracting(p -> ((ManifestPart) p).etag()).containsExactly("one", "two");
    }

    @Test void missingRegisteredExpectationsAndLegacyDecisionAreNotDispatchAuthority() {
        var unregistered = sessions.reserve(owner, "unregistered", new Request(null, "unregistered", 1, "text/plain"));
        var d = sessions.claimInitiation(owner, unregistered.id());
        sessions.recordUploadId(owner, unregistered.id(), 1, d.transfer().session().initiationId(), "unregistered-id");
        assertThatThrownBy(() -> completions.complete(owner, unregistered.id())).isInstanceOf(MultipartConflictException.class);
        sessions.decide(owner, session.id(), session.revision(), Decision.COMPLETE, UUID.randomUUID());
        assertThat(completions.recover(owner, session.id()).completion()).isNull();
        verifyNoInteractions(storage);
    }

    @Test void frozenManifestDispatchAndReceiptCannotBeChangedOrDeleted() {
        assertReady(completions.complete(owner, session.id()));
        for (String sql : List.of("UPDATE multipart_completions SET manifest = '[]'::jsonb WHERE session_id = ?",
                "UPDATE multipart_completions SET dispatch_id = gen_random_uuid(), revision = revision + 1 WHERE session_id = ?",
                "UPDATE multipart_completions SET revision = revision + 1 WHERE session_id = ?",
                "DELETE FROM multipart_completions WHERE session_id = ?",
                "UPDATE multipart_completion_receipts SET etag = 'other' WHERE session_id = ?",
                "DELETE FROM multipart_completion_receipts WHERE session_id = ?")) {
            assertThatThrownBy(() -> jdbc.update(sql, session.id())).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM files WHERE id = ?", session.fileId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sessions.decide(owner, session.id(), 3, Decision.ABANDON, UUID.randomUUID())).isInstanceOf(MultipartConflictException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void completionAndAbandonmentHaveOnePermanentWinner(boolean completeFirst) throws Exception {
        var snapshot = transactions.authorize(owner, session.id());
        Supplier<?> complete = () -> transactions.freeze(owner, snapshot, manifest(snapshot));
        Supplier<?> abandon = () -> sessions.decide(owner, session.id(), session.revision(), Decision.ABANDON, UUID.randomUUID());
        var result = race(completeFirst ? complete : abandon, completeFirst ? abandon : complete);
        assertThat(result.getLast()).isInstanceOf(MultipartConflictException.class);
        assertThat(sessions.get(owner, session.id()).state()).isEqualTo(completeFirst ? "COMPLETION_DECIDED" : "ABANDONMENT_DECIDED");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void readyAndFolderTrashBothOrdersValidateFreshState(boolean readyFirst) throws Exception {
        UUID folder = folders.createFolder(owner, null, "race"); session = open(folder, 8388609);
        var permit = transactions.claim(owner, frozen().session().id());
        transactions.recordReceipt(owner, permit.snapshot(), evidence(session), Evidence.COMPLETE_HEAD);
        Supplier<?> finalize = () -> transactions.finalizeReady(owner, session.id());
        Supplier<?> trash = () -> { folders.deleteFolder(owner, folder); return "trashed"; };
        var result = race(readyFirst ? finalize : trash, readyFirst ? trash : finalize);
        assertThat(result).noneMatch(RuntimeException.class::isInstance);
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE id = ?", String.class, session.fileId()))
                .isEqualTo(readyFirst ? "READY" : "UPLOADING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM multipart_completion_receipts WHERE session_id = ?", Integer.class, session.id())).isEqualTo(1);
        assertThatThrownBy(() -> sessions.get(owner, session.id())).isInstanceOf(FileNotFoundException.class);
        verifyNoInteractions(storage);
    }

    @Test void concurrentRequestsDispatchOnceAndReleaseAllDatabaseLocksForStorage() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { assertNoTransaction(); entered.countDown(); await(release); return new CompletionAck("opaque-final", call.getArgument(3), "COMPOSITE"); })
                .when(storage).completeMultipart(any(), any(), anyList(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> completions.complete(owner, session.id()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> completions.complete(owner, session.id()));
            assertThat(second.get(5, TimeUnit.SECONDS).completion().disposition()).isEqualTo("UNKNOWN");
            release.countDown(); assertReady(first.get(8, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        verify(storage, times(1)).completeMultipart(any(), any(), anyList(), any());
    }

    @Test void concurrentFreezeAndDispatchClaimsRetainOneManifestAndOnePermission() throws Exception {
        var snapshot = transactions.authorize(owner, session.id());
        var freezeResults = race(() -> transactions.freeze(owner, snapshot, manifest(snapshot)), () -> transactions.freeze(owner, snapshot, manifest(snapshot)));
        assertThat(freezeResults.getFirst()).isEqualTo(freezeResults.getLast());
        var claims = race(() -> transactions.claim(owner, session.id()), () -> transactions.claim(owner, session.id()));
        assertThat(((Permit) claims.getFirst()).permitted()).isTrue(); assertThat(((Permit) claims.getLast()).permitted()).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void concurrentPositiveRecoveryRecordsOneReceiptWithoutRedispatch() throws Exception {
        transactions.claim(owner, frozen().session().id());
        var gate = new CyclicBarrier(2);
        doAnswer(call -> { assertNoTransaction(); gate.await(5, TimeUnit.SECONDS); return evidence(session); }).when(storage).inspectMultipartObject(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> completions.recover(owner, session.id()));
            var second = pool.submit(() -> completions.recover(owner, session.id()));
            assertReady(first.get(10, TimeUnit.SECONDS)); assertReady(second.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM multipart_completion_receipts WHERE session_id = ?", Integer.class, session.id())).isEqualTo(1);
        verify(storage, never()).completeMultipart(any(), any(), anyList(), any());
    }

    @Test void folderTrashDuringStorageRetainsReceiptAndRestoredDestinationCanFinalize() {
        UUID folder = folders.createFolder(owner, null, "during-storage"); session = open(folder, 8388609);
        doAnswer(call -> { assertNoTransaction(); folders.deleteFolder(owner, folder); return new CompletionAck("opaque-final", call.getArgument(3), "COMPOSITE"); })
                .when(storage).completeMultipart(any(), any(), anyList(), any());
        var blocked = completions.complete(owner, session.id());
        assertThat(blocked.completion().receiptRecorded()).isTrue(); assertThat(blocked.completion().disposition()).isEqualTo("BLOCKED");
        folders.restoreFolder(owner, folder); clearInvocations(storage);
        assertReady(completions.recover(owner, session.id())); verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void pendingFolderPurgeRetainsSnapshotAndNeverAuthorizesMultipartBytesDeletion(boolean readyFirst) throws Exception {
        UUID folder = folders.createFolder(owner, null, "purge"); session = open(folder, 8388609);
        var permit = transactions.claim(owner, frozen().session().id());
        transactions.recordReceipt(owner, permit.snapshot(), evidence(session), Evidence.COMPLETE_HEAD);
        if (readyFirst) assertReady(transactions.finalizeReady(owner, session.id()));
        folders.deleteFolder(owner, folder); folders.requestPermanentDeletion(owner, folder);
        if (!readyFirst) assertThat(transactions.finalizeReady(owner, session.id()).completion().disposition()).isEqualTo("BLOCKED");
        UUID event = jdbc.queryForObject("SELECT event_id FROM folder_purge_runs WHERE root_folder_id = ?", UUID.class, folder);
        jobs.create(event, com.vaultdrive.job.JobType.FOLDER_PURGE, folder);
        var claim = jobs.claimNext(com.vaultdrive.job.JobType.FOLDER_PURGE).orElseThrow();
        for (int i = 0; i < 10; i++) {
            long version = jdbc.queryForObject("SELECT progress_version FROM background_jobs WHERE id = ?", Long.class, event);
            if (discovery.discover(claim, version, 20).sealed()) break;
        }
        var context = mock(com.vaultdrive.worker.JobExecutionContext.class); when(context.claim()).thenReturn(claim);
        assertThatThrownBy(() -> purge.prepare(context, session.fileId())).isInstanceOfSatisfying(com.vaultdrive.worker.FolderPurgeFileService.BlockedFile.class,
                e -> assertThat(e.reason()).isEqualTo("UNSAFE_WRITER"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_purge_work WHERE file_id = ?", Integer.class, session.fileId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE id = ?", String.class, session.fileId())).isEqualTo(readyFirst ? "READY" : "UPLOADING");
        verifyNoInteractions(storage);
    }

    @Test void expiredUndispatchedPermissionBlocksButReceiptFinalizationCanRecoverAfterExpiry() {
        frozen(); expire();
        assertThat(completions.recover(owner, session.id()).completion().reason()).isEqualTo("AUTHORIZATION_EXPIRED");
        verifyNoInteractions(storage);
        session = open(null, 8388609);
        var permit = transactions.claim(owner, frozen().session().id()); expire();
        transactions.recordReceipt(owner, permit.snapshot(), evidence(session), Evidence.COMPLETE_HEAD);
        assertReady(completions.recover(owner, session.id())); verifyNoInteractions(storage);
    }

    @Test void jwtCompletionRecoveryForeignAccessAndGetExposeDurableReadiness() throws Exception {
        String token = "Bearer " + jwt.generateAccessToken(owner);
        mvc.perform(post("/api/v1/upload-sessions/{id}/complete", session.id()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completion.disposition").value("READY"))
                .andExpect(jsonPath("$.completion.receiptRecorded").value(true));
        mvc.perform(get("/api/v1/upload-sessions/{id}", session.id()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completion.disposition").value("READY"));
        mvc.perform(post("/api/v1/upload-sessions/{id}/recover", session.id()).header("Authorization", token)).andExpect(status().isOk());
        for (String action : List.of("complete", "recover")) {
            mvc.perform(post("/api/v1/upload-sessions/{id}/{action}", session.id(), action)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/upload-sessions/{id}/{action}", session.id(), action)
                    .header("Authorization", "Bearer " + jwt.generateAccessToken(UUID.randomUUID()))).andExpect(status().isNotFound());
        }
    }

    @Test void httpUnknownBlockedAndIncompatibleDecisionsHaveExpectedStatuses() throws Exception {
        String token = "Bearer " + jwt.generateAccessToken(owner);
        doThrow(new MultipartStorageException()).when(storage).completeMultipart(any(), any(), anyList(), any());
        mvc.perform(post("/api/v1/upload-sessions/{id}/complete", session.id()).header("Authorization", token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.completion.disposition").value("UNKNOWN"));
        doReturn(new CompletedObject(1, SHA, "wrong", "text/plain", Map.of())).when(storage).inspectMultipartObject(any());
        mvc.perform(post("/api/v1/upload-sessions/{id}/recover", session.id()).header("Authorization", token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.completion.disposition").value("BLOCKED"));
        session = open(null, 8388609);
        sessions.decide(owner, session.id(), session.revision(), Decision.ABANDON, UUID.randomUUID());
        mvc.perform(post("/api/v1/upload-sessions/{id}/complete", session.id()).header("Authorization", token)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/upload-sessions/{id}/recover", session.id()).header("Authorization", token)).andExpect(status().isConflict());
    }

    private Session open(UUID folder, long size) {
        var r = sessions.reserve(owner, UUID.randomUUID().toString(), new Request(folder, UUID.randomUUID().toString(), size, "text/plain"));
        var d = sessions.claimInitiation(owner, r.id());
        var opened = sessions.recordUploadId(owner, r.id(), 1, d.transfer().session().initiationId(), "fixture-" + r.id());
        sessions.authorizeParts(owner, r.id(), List.of(new PartExpectation(1, 8388608, SHA), new PartExpectation(2, 1, SHA)));
        return opened;
    }
    private Snapshot frozen() { var s = transactions.authorize(owner, session.id()); return transactions.freeze(owner, s, manifest(s)); }
    private List<ManifestPart> manifest(Snapshot s) {
        return s.registered().stream().map(p -> new ManifestPart(p.partNumber(), p.length(), p.sha256(), p.registrationId(), "opaque-" + p.partNumber())).toList();
    }
    private CompletedObject evidence(Session s) {
        return new CompletedObject(s.declaredSize(), composite(), "opaque-final", "text/plain",
                Map.of("vaultdrive-session", s.id().toString(), "vaultdrive-initiation", s.initiationId().toString()));
    }
    private String composite() {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(new byte[64])); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private String shaDifferent() { byte[] other = new byte[32]; other[0] = 1; return Base64.getEncoder().encodeToString(other); }
    private void assertReady(Session result) {
        assertThat(result.completion().disposition()).isEqualTo("READY"); assertThat(result.completion().receiptRecorded()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE id = ?", String.class, result.fileId())).isEqualTo("READY");
    }
    private void failReceipt(boolean install) {
        if (install) { jdbc.execute("CREATE FUNCTION test_receipt_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture receipt failure'; END $$");
            jdbc.execute("CREATE TRIGGER test_receipt_failure BEFORE INSERT ON multipart_completion_receipts FOR EACH ROW EXECUTE FUNCTION test_receipt_failure()"); }
        else { jdbc.execute("DROP TRIGGER test_receipt_failure ON multipart_completion_receipts"); jdbc.execute("DROP FUNCTION test_receipt_failure()"); }
    }
    private void failReady(boolean install) {
        if (install) { jdbc.execute("CREATE FUNCTION test_ready_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status = 'READY' THEN RAISE EXCEPTION 'fixture READY failure'; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER test_ready_failure BEFORE UPDATE ON files FOR EACH ROW EXECUTE FUNCTION test_ready_failure()"); }
        else { jdbc.execute("DROP TRIGGER test_ready_failure ON files"); jdbc.execute("DROP FUNCTION test_ready_failure()"); }
    }
    private void expire() {
        tx.executeWithoutResult(status -> { jdbc.execute("ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session");
            jdbc.update("UPDATE upload_sessions SET created_at = created_at - interval '25 hours', expires_at = expires_at - interval '25 hours' WHERE id = ?", session.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"); jdbc.execute("ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session"); });
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
