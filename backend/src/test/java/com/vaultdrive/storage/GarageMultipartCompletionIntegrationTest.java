package com.vaultdrive.storage;

import com.vaultdrive.file.*;
import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.file.MultipartCompletionTransactions.*;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;

import javax.sql.DataSource;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

import static com.vaultdrive.storage.GarageMultipartFixture.sha;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GARAGE_MULTIPART_COMPLETION_TEST", matches = "true")
@Timeout(90)
class GarageMultipartCompletionIntegrationTest {
    static final String SCHEMA = "multipart_completion_live_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartTransferService transfers;
    @Autowired MultipartCompletionService completions;
    @Autowired MultipartCompletionTransactions transactions;
    @Autowired FileService files;
    @Autowired FolderService folders;
    @Autowired com.vaultdrive.sharing.GrantService grants;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @MockitoBean StorageKeyGenerator keys;
    @MockitoSpyBean S3Client s3;
    GarageMultipartFixture f;
    UUID owner;
    final byte[] first = bytes(8388608, 11), last = bytes(1234, 12);

    @BeforeAll void initialize() throws Exception {
        f = new GarageMultipartFixture(); f.event("M3", "isolated PostgreSQL production multipart completion adapter");
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "M3 live")).getId();
    }
    @BeforeEach void stubs() {
        clearInvocations(s3);
        when(keys.generateFileKey(any(), any())).thenAnswer(call -> "integration-tests/multipart-m3/" + f.run + "/" + call.getArgument(1));
        doAnswer(call -> {
            assertNoTransaction(); CreateMultipartUploadRequest request = call.getArgument(0);
            var a = new GarageMultipartFixture.Allocation(request.key()); f.allocations.add(a); f.event("ALLOCATED", a.key);
            try {
                var result = (CreateMultipartUploadResponse) call.callRealMethod();
                a.uploadId = result.uploadId(); f.event("CREATE_HTTP_200", a.key + " " + a.uploadId); return result;
            } catch (Throwable failure) { a.uncertain = true; f.event("CREATE_UNKNOWN", a.key); throw failure; }
        }).when(s3).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        doAnswer(call -> realComplete(call)).when(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        doAnswer(call -> { assertNoTransaction(); return call.callRealMethod(); }).when(s3).headObject(any(HeadObjectRequest.class));
        doAnswer(call -> { assertNoTransaction(); return call.callRealMethod(); }).when(s3).listParts(any(ListPartsRequest.class));
    }
    private Object realComplete(org.mockito.invocation.InvocationOnMock call) throws Throwable {
        assertNoTransaction(); CompleteMultipartUploadRequest request = call.getArgument(0);
        var a = allocation(request.key()); a.completionDispatched = true; f.event("COMPLETE_DISPATCH", a.key);
        assertThat(request.uploadId()).isEqualTo(a.uploadId);
        assertThat(request.checksumType()).isEqualTo(ChecksumType.COMPOSITE);
        assertThat(request.multipartUpload().parts()).allSatisfy(p -> assertThat(p.checksumSHA256()).isNotBlank());
        try {
            var result = (CompleteMultipartUploadResponse) call.callRealMethod();
            a.completed = true; f.event("COMPLETE_HTTP_200", "checksumType=" + result.checksumTypeAsString()); return result;
        } catch (Throwable failure) { a.uncertain = true; f.event("COMPLETE_UNKNOWN", a.key); throw failure; }
    }
    @AfterEach void controlsAndNoDestructiveProductionIo() throws Exception {
        verify(s3, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        f.assertBytes(f.control, GarageMultipartFixture.CONTROL);
    }
    @AfterAll void dispose() throws Exception {
        if (f == null) return;
        f.close(); // Only known complete objects or known never-dispatched open test MPUs, after task joins.
        if (f.retain || f.allocations.stream().anyMatch(a -> a.uncertain)) return;
        assertThat(SCHEMA).matches("multipart_completion_live_[0-9a-f]{32}");
        try (var c = source.getConnection(); var statement = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test"); statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void realPartsCompleteReceiptReadyAndExistingDownloadReturnExactBytesAndMetadata() throws Exception {
        var session = uploaded(null);
        var ready = completions.complete(owner, session.id()); assertReady(ready); assertCompletedBytes(session);
        var receipt = jdbc.queryForMap("SELECT * FROM multipart_completion_receipts WHERE session_id = ?", session.id());
        assertThat(receipt.get("garage_upload_id")).isEqualTo(session.uploadId());
        assertThat(receipt.get("evidence_kind")).isEqualTo("COMPLETE_HEAD");
        assertThat(receipt.get("size_bytes")).isEqualTo((long) first.length + last.length);
        assertThat(jdbc.queryForObject("SELECT storage_identity = multipart_storage_identity(s) AND r.manifest_hash = c.manifest_hash "
                + "AND r.sha256 = c.expected_sha256 FROM upload_sessions s JOIN multipart_completions c ON c.session_id = s.id "
                + "JOIN multipart_completion_receipts r ON r.session_id = s.id WHERE s.id = ?", Boolean.class, session.id())).isTrue();
        try (var stream = files.downloadFile(owner, session.fileId()).inputStream()) { assertThat(Arrays.equals(stream.readAllBytes(), expected())).isTrue(); }
        clearInvocations(s3);
        assertReady(completions.complete(owner, session.id())); assertReady(completions.recover(owner, session.id()));
        verifyNoInteractions(s3);
        assertThat(jdbc.queryForMap("SELECT * FROM multipart_completion_receipts WHERE session_id = ?", session.id())).isEqualTo(receipt);
    }

    @Test void delegatedEditorUsesRealSignedPartsAndOwnerRecoversAfterRevocation() throws Exception {
        var editor = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "Editor"));
        UUID folder = folders.createFolder(owner, null, UUID.randomUUID().toString());
        var grant = grants.create(owner, true, folder, editor.getEmail(), com.vaultdrive.sharing.ResourceRole.EDITOR);
        var reserved = sessions.reserve(editor.getId(), UUID.randomUUID().toString(),
                new Request(folder, UUID.randomUUID().toString(), first.length + last.length, "application/octet-stream"));
        var session = transfers.initiate(editor.getId(), reserved.id());
        assertThat(session.ownerId()).isEqualTo(owner);
        assertThat(session.initiatorUserId()).isEqualTo(editor.getId());
        var urls = transfers.partUrls(editor.getId(), session.id(), List.of(
                new PartExpectation(1, first.length, sha(first)), new PartExpectation(2, last.length, sha(last))));
        grants.revoke(owner, true, folder, grant.id());
        // Outstanding Garage URLs remain bearer capabilities after revocation.
        for (int number = 0; number < 2; number++) {
            var signed = wire(urls.get(number).upload());
            assertThat(f.send(signed, signed.url().toURI(), number == 0 ? first : last).status()).isEqualTo(200);
        }
        assertThatThrownBy(() -> completions.complete(editor.getId(), session.id()))
                .isInstanceOf(com.vaultdrive.file.exception.FileNotFoundException.class);
        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        assertReady(completions.complete(owner, session.id()));
        assertCompletedBytes(session);
        try (var stream = files.downloadFile(owner, session.fileId()).inputStream()) {
            assertThat(Arrays.equals(stream.readAllBytes(), expected())).isTrue();
        }
        assertThatThrownBy(() -> files.downloadFile(editor.getId(), session.fileId()))
                .isInstanceOf(com.vaultdrive.file.exception.FileNotFoundException.class);
        verify(s3, times(1)).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        f.event("SHARING_M3", "delegated signed parts accepted after revoke; new Editor authority denied; owner completed exact bytes");
    }

    @Test void realPaginatedListPartsProvidesTheOrderedCompletionManifest() throws Exception {
        var session = uploaded(null);
        doAnswer(call -> { assertNoTransaction(); ListPartsRequest request = call.getArgument(0);
            return f.s3.listParts(request.toBuilder().maxParts(1).build()); })
                .when(s3).listParts(any(ListPartsRequest.class)); // Real provider pages, narrowed only by the fixture transport.
        assertReady(completions.complete(owner, session.id())); assertCompletedBytes(session);
        verify(s3, times(2)).listParts(any(ListPartsRequest.class));
    }

    @Test void actualCompleteResponseLossRecoversOnlyFromExactPositiveHeadEvidence() throws Exception {
        var session = uploaded(null); var a = allocation(session);
        try (var proxy = new MultipartCompletionDropProxy(f)) {
            doAnswer(call -> {
                assertNoTransaction(); a.completionDispatched = true; f.event("COMPLETE_DISPATCH", a.key);
                // Only the test transport changes; the production adapter supplies the exact frozen request.
                return proxy.client.completeMultipartUpload((CompleteMultipartUploadRequest) call.getArgument(0));
            }).when(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
            var unknown = completions.complete(owner, session.id());
            assertThat(unknown.completion().disposition()).isEqualTo("UNKNOWN");
            proxy.verifyAccepted(); a.completed = true; f.event("COMPLETE_KNOWN_BY_VERIFIER", "dropped_response=true");
            assertCompletedBytes(session);
            assertThat(completions.complete(owner, session.id()).completion().receiptRecorded()).isFalse();
            assertReady(completions.recover(owner, session.id()));
            assertThat(jdbc.queryForObject("SELECT evidence_kind FROM multipart_completion_receipts WHERE session_id = ?", String.class, session.id()))
                    .isEqualTo("HEAD_RECOVERY");
            verify(s3, times(1)).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
            assertCompletedBytes(session);
        } catch (Exception | AssertionError failure) {
            if (!a.completed) a.uncertain = true;
            throw failure;
        }
    }

    @Test void successfulGarageCompleteWithFailedReceiptCommitRemainsRecoverableWithoutRedispatch() throws Exception {
        var session = uploaded(null);
        jdbc.execute("CREATE FUNCTION test_receipt_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture receipt failure'; END $$");
        jdbc.execute("CREATE TRIGGER test_receipt_failure BEFORE INSERT ON multipart_completion_receipts FOR EACH ROW EXECUTE FUNCTION test_receipt_failure()");
        try {
            var result = completions.complete(owner, session.id());
            assertThat(result.completion().receiptRecorded()).isFalse(); assertCompletedBytes(session);
        } finally { jdbc.execute("DROP TRIGGER test_receipt_failure ON multipart_completion_receipts"); jdbc.execute("DROP FUNCTION test_receipt_failure()"); }
        assertReady(completions.recover(owner, session.id())); assertCompletedBytes(session);
        verify(s3, times(1)).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }

    @Test void committedReceiptWithFailedReadyCommitRecoversWithoutStorageIo() throws Exception {
        var session = uploaded(null);
        jdbc.execute("CREATE FUNCTION test_ready_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status = 'READY' THEN RAISE EXCEPTION 'fixture READY failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_ready_failure BEFORE UPDATE ON files FOR EACH ROW EXECUTE FUNCTION test_ready_failure()");
        try {
            var result = completions.complete(owner, session.id());
            assertThat(result.completion().receiptRecorded()).isTrue(); assertThat(result.completion().disposition()).isEqualTo("BLOCKED");
            assertCompletedBytes(session);
        } finally { jdbc.execute("DROP TRIGGER test_ready_failure ON files"); jdbc.execute("DROP FUNCTION test_ready_failure()"); }
        clearInvocations(s3); assertReady(completions.recover(owner, session.id())); verifyNoInteractions(s3);
    }

    @Test void folderBarrierAfterGarageSuccessPreservesReceiptAndBlocksReadyUntilRestore() throws Exception {
        UUID folder = folders.createFolder(owner, null, "destination"); var session = uploaded(folder);
        doAnswer(call -> { Object result = realComplete(call); folders.deleteFolder(owner, folder); return result; })
                .when(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        var blocked = completions.complete(owner, session.id());
        assertThat(blocked.completion().receiptRecorded()).isTrue(); assertThat(blocked.completion().disposition()).isEqualTo("BLOCKED");
        assertCompletedBytes(session);
        folders.restoreFolder(owner, folder); clearInvocations(s3);
        assertReady(completions.recover(owner, session.id())); verifyNoInteractions(s3);
    }

    @Test void acceptedIdenticalPartHandlerAcrossCompletionLeavesReceiptAndBytesIntact() throws Exception {
        var session = uploaded(null); var a = allocation(session);
        var signed = wire(transfers.partUrls(owner, session.id(), List.of(new PartExpectation(2, last.length, sha(last)))).getFirst().upload());
        try (var gate = new GarageMultipartFixture.GatedPart(signed, last)) {
            f.event("M3_IDENTICAL_PART_GATE", "accepted_prefix=1 before_complete=true");
            assertReady(completions.complete(owner, session.id())); assertCompletedBytes(session);
            int status = gate.finish(); assertThat(status).isIn(200, 404);
            assertCompletedBytes(session);
            f.event("M3_LATE_IDENTICAL_PART", "http=" + status + " bytesUnchanged=true noQuiescenceClaim=true");
        } catch (Exception | AssertionError failure) { a.uncertain = true; throw failure; }
    }

    @Test void restartAfterDispatchPermissionBeforeCallRetainsUnknownAndNeverCompletes() throws Exception {
        var session = uploaded(null); var snapshot = transactions.authorize(owner, session.id());
        var parts = transfers.list(owner, session.id(), 0, 100).parts();
        var manifest = parts.stream().map(p -> {
            var expected = snapshot.registered().get(p.partNumber() - 1);
            return new MultipartStorage.ManifestPart(p.partNumber(), p.size(), p.sha256(), expected.registrationId(), p.etag());
        }).toList();
        transactions.freeze(owner, snapshot, manifest); transactions.claim(owner, session.id());
        f.event("M3_PERMISSION_ONLY_NO_CALL", allocation(session).key);
        var restarted = new MultipartCompletionService(transactions, storage());
        assertThat(restarted.recover(owner, session.id()).completion().disposition()).isEqualTo("UNKNOWN");
        assertThat(restarted.complete(owner, session.id()).completion().receiptRecorded()).isFalse();
        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        // Cleanup is allowed by this test's direct no-call boundary, never by HEAD absence or dispatch age.
    }

    @Test void concurrentRequestsUseOneDurableDispatcher() throws Exception {
        var session = uploaded(null); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { assertNoTransaction(); entered.countDown(); await(release); return realComplete(call); })
                .when(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> completions.complete(owner, session.id()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> completions.complete(owner, session.id()));
            assertThat(second.get(5, TimeUnit.SECONDS).completion().disposition()).isEqualTo("UNKNOWN");
            release.countDown(); assertReady(first.get(15, TimeUnit.SECONDS));
            assertCompletedBytes(session); verify(s3, times(1)).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void mismatchedPositiveMetadataIsBlockedUntilAnExactRecoveryObservation() throws Exception {
        var session = uploaded(null);
        doAnswer(call -> {
            var real = (HeadObjectResponse) call.callRealMethod();
            return real.toBuilder().metadata(Map.of("vaultdrive-session", UUID.randomUUID().toString(),
                    "vaultdrive-initiation", session.initiationId().toString())).build();
        }).when(s3).headObject(any(HeadObjectRequest.class));
        var blocked = completions.complete(owner, session.id());
        assertThat(blocked.completion().reason()).isEqualTo("EVIDENCE_MISMATCH"); assertThat(blocked.completion().receiptRecorded()).isFalse();
        assertCompletedBytes(session);
        doAnswer(call -> { assertNoTransaction(); return call.callRealMethod(); }).when(s3).headObject(any(HeadObjectRequest.class));
        assertReady(completions.recover(owner, session.id())); verify(s3, times(1)).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }

    @Autowired ObjectStorageService boundary;
    private ObjectStorageService storage() { return boundary; }
    private Session uploaded(UUID folder) throws Exception {
        var r = sessions.reserve(owner, UUID.randomUUID().toString(), new Request(folder, UUID.randomUUID().toString(), first.length + last.length, "application/octet-stream"));
        var session = transfers.initiate(owner, r.id()); assertThat(session.state()).isEqualTo("OPEN");
        for (int number = 1; number <= 2; number++) {
            byte[] payload = number == 1 ? first : last;
            var url = transfers.partUrls(owner, r.id(), List.of(new PartExpectation(number, payload.length, sha(payload)))).getFirst();
            var signed = wire(url.upload()); assertThat(f.send(signed, signed.url().toURI(), payload).status()).isEqualTo(200);
        }
        return session;
    }
    private GarageMultipartFixture.Allocation allocation(Session s) { return f.allocations.stream().filter(a -> Objects.equals(a.uploadId, s.uploadId())).findFirst().orElseThrow(); }
    private GarageMultipartFixture.Allocation allocation(String key) { return f.allocations.stream().filter(a -> a.key.equals(key)).findFirst().orElseThrow(); }
    private void assertCompletedBytes(Session session) throws Exception {
        var a = allocation(session);
        var result = f.s3.getObjectAsBytes(b -> b.bucket(f.properties.bucket()).key(a.key));
        assertThat(Arrays.equals(result.asByteArray(), expected())).as("actual completed bytes match every registered part").isTrue();
        assertThat(result.response().metadata()).containsEntry("vaultdrive-session", session.id().toString())
                .containsEntry("vaultdrive-initiation", session.initiationId().toString());
        f.event("M3_BYTES_METADATA", "size=" + result.asByteArray().length + " actual_sha256=" + sha(result.asByteArray()) + " metadata=true");
    }
    private void assertReady(Session s) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id = ? AND event_type = 'UPLOAD_COMPLETED'", Integer.class, s.fileId())).isEqualTo(1);
        assertThat(s.completion().disposition()).isEqualTo("READY"); assertThat(s.completion().receiptRecorded()).isTrue();
        assertThat(jdbc.queryForObject("SELECT put_succeeded_at IS NULL AND admitted_file_version IS NULL FROM file_storage_objects WHERE file_id = ?", Boolean.class, s.fileId())).isTrue();
    }
    private byte[] expected() { byte[] result = Arrays.copyOf(first, first.length + last.length); System.arraycopy(last, 0, result, first.length, last.length); return result; }
    private static byte[] bytes(int length, int seed) { byte[] result = new byte[length]; new Random(seed).nextBytes(result); return result; }
    private static void assertNoTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private static PresignedUploadPartRequest wire(MultipartStorage.SignedPart signed) {
        return PresignedUploadPartRequest.builder().expiration(signed.expiresAt()).signedHeaders(signed.requiredHeaders())
                .httpRequest(SdkHttpFullRequest.builder().uri(URI.create(signed.url())).method(SdkHttpMethod.PUT).headers(signed.requiredHeaders()).build())
                .isBrowserExecutable(false).build();
    }
    private static void await(CountDownLatch latch) throws InterruptedException { if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
}
