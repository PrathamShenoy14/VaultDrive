package com.vaultdrive.storage;

import com.vaultdrive.file.*;
import com.vaultdrive.file.MultipartSessionService.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;

import javax.sql.DataSource;
import java.net.URI;
import java.util.*;

import static com.vaultdrive.storage.GarageMultipartFixture.sha;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GARAGE_MULTIPART_CANCELLATION_TEST", matches = "true")
@Timeout(90)
class GarageMultipartCancellationIntegrationTest {
    static final String SCHEMA = "multipart_cancel_live_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartTransferService transfers;
    @Autowired MultipartCancellationService cancellations;
    @Autowired MultipartCancellationTransactions transactions;
    @Autowired MultipartCompletionService completions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean StorageKeyGenerator keys;
    @MockitoSpyBean S3Client s3;
    GarageMultipartFixture f;
    UUID owner;

    @BeforeAll void initialize() throws Exception {
        f = new GarageMultipartFixture(); f.event("M4", "production multipart cancellation adapter; isolated PostgreSQL");
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "M4 live")).getId();
    }
    @BeforeEach void stubs() {
        clearInvocations(s3);
        when(keys.generateFileKey(any(), any())).thenAnswer(call -> "integration-tests/multipart-m4/" + f.run + "/" + call.getArgument(1));
        doAnswer(call -> {
            assertNoTransaction(); CreateMultipartUploadRequest request = call.getArgument(0);
            var a = new GarageMultipartFixture.Allocation(request.key()); f.allocations.add(a); f.event("ALLOCATED", a.key);
            try { var result = (CreateMultipartUploadResponse) call.callRealMethod();
                a.uploadId = result.uploadId(); f.event("CREATE_HTTP_200", a.key + " " + a.uploadId); return result;
            } catch (Throwable failure) { a.uncertain = true; f.event("CREATE_UNKNOWN", a.key); throw failure; }
        }).when(s3).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        doAnswer(call -> {
            assertNoTransaction(); AbortMultipartUploadRequest request = call.getArgument(0); var a = allocation(request.key());
            assertThat(request.uploadId()).isEqualTo(a.uploadId); f.event("ABORT_DISPATCH", a.key);
            try { var response = (AbortMultipartUploadResponse) call.callRealMethod();
                assertThat(response.sdkHttpResponse().statusCode()).isEqualTo(204); a.aborted = true;
                f.event("ABORT_HTTP_204", a.key); return response;
            } catch (Throwable failure) { a.uncertain = true; f.event("ABORT_UNKNOWN", a.key); throw failure; }
        }).when(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        doAnswer(call -> {
            assertNoTransaction(); CompleteMultipartUploadRequest request = call.getArgument(0); var a = allocation(request.key());
            a.completionDispatched = true; f.event("COMPLETE_DISPATCH", a.key);
            try { var result = call.callRealMethod(); a.completed = true; f.event("COMPLETE_HTTP_200", a.key); return result; }
            catch (Throwable failure) { a.uncertain = true; throw failure; }
        }).when(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }
    @AfterEach void controlsAndNoObjectDeletion() throws Exception {
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class)); f.assertBytes(f.control, GarageMultipartFixture.CONTROL);
    }
    @AfterAll void dispose() throws Exception {
        if (f == null) return;
        f.close();
        if (f.retain || f.allocations.stream().anyMatch(a -> a.uncertain)) return;
        assertThat(SCHEMA).matches("multipart_cancel_live_[0-9a-f]{32}");
        try (var c = source.getConnection(); var statement = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test"); statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void cancelBeforeAnyPartsUsesOneRealAbortAndDuplicatesHaveNoIo() {
        var s = open(4096); var result = cancellations.cancel(owner, s.id());
        assertThat(result.cancelled()).isTrue(); assertThat(result.evidenceKind()).isEqualTo("ABORT_ACK");
        verify(s3, times(1)).abortMultipartUpload(any(AbortMultipartUploadRequest.class)); clearInvocations(s3);
        assertThat(cancellations.cancel(owner, s.id())).isEqualTo(result);
        assertThat(cancellations.recover(owner, s.id())).isEqualTo(result); verifyNoInteractions(s3);
    }

    @Test void partialUploadCancellationRejectsFreshSmallPartReplayWithoutCompletingOrDeleting() throws Exception {
        var s = open(8388609); byte[] first = bytes(8388608), last = new byte[] {42};
        var firstUrl = signed(s, 1, first); assertThat(f.send(firstUrl, firstUrl.url().toURI(), first).status()).isEqualTo(200);
        var lastUrl = signed(s, 2, last);
        assertThat(cancellations.cancel(owner, s.id()).cancelled()).isTrue();
        assertThat(f.send(lastUrl, lastUrl.url().toURI(), last).status()).isEqualTo(404);
        assertThatThrownBy(() -> transfers.partUrls(owner, s.id(), List.of(new PartExpectation(2, 1, sha(last)))))
                .isInstanceOf(MultipartConflictException.class);
        verify(s3, times(1)).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        f.noObject(allocation(s)); // Observation only; acknowledged abort and no completer determine fixture disposal.
    }

    @Test void acceptedPartCanReturnAfterProductionAbortWithoutGrantingRemovalProof() throws Exception {
        byte[] bytes = bytes(3 * 1024 * 1024); var s = open(bytes.length); var a = allocation(s);
        var signed = signed(s, 1, bytes); a.activeRequests.incrementAndGet();
        try (var gate = new GarageMultipartFixture.GatedPart(signed, bytes)) {
            f.event("GATE_PREFIX_FLUSHED", "100-continue prefix=1 internal-read-point-unproven");
            assertThat(cancellations.cancel(owner, s.id()).cancelled()).isTrue();
            int status = gate.finish(); f.event("LATE_PART_AFTER_M4_ABORT", "http=" + status);
            assertThat(status).isIn(200, 404);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM file_purge_work WHERE file_id = ? AND bytes_deleted_at IS NOT NULL", Integer.class, s.fileId())).isZero();
        } catch (Exception | AssertionError failure) { a.uncertain = true; throw failure; }
        finally { a.activeRequests.decrementAndGet(); }
        verify(s3, times(1)).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test void actualLost204ResponseRemainsUnknownAcrossRecoveryWithoutSecondAbort() throws Exception {
        var s = open(4096); var a = allocation(s);
        try (var proxy = new MultipartAbortDropProxy(f)) {
            doAnswer(call -> { assertNoTransaction(); f.event("ABORT_DISPATCH", a.key);
                return proxy.client.abortMultipartUpload((AbortMultipartUploadRequest) call.getArgument(0));
            }).when(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
            var unknown = cancellations.cancel(owner, s.id()); assertThat(unknown.disposition()).isEqualTo("UNKNOWN");
            proxy.verifyAccepted(); a.aborted = true; f.event("ABORT_KNOWN_BY_VERIFIER", "application remains UNKNOWN; no repair authority");
            assertThat(cancellations.cancel(owner, s.id())).isEqualTo(unknown);
            assertThat(cancellations.recover(owner, s.id())).isEqualTo(unknown);
            verify(s3, times(1)).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
            verify(s3, never()).headObject(any(HeadObjectRequest.class)); verify(s3, never()).listParts(any(ListPartsRequest.class));
        } catch (Exception | AssertionError failure) { if (!a.aborted) a.uncertain = true; throw failure; }
    }

    @Test void successfulAbortWithDatabaseAcknowledgementFailureStaysUnknown() {
        var s = open(4096);
        jdbc.execute("CREATE FUNCTION test_abort_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.disposition = 'CANCELLED' THEN RAISE EXCEPTION 'fixture failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_abort_failure BEFORE UPDATE ON multipart_cancellations FOR EACH ROW EXECUTE FUNCTION test_abort_failure()");
        try { assertThat(cancellations.cancel(owner, s.id()).disposition()).isEqualTo("UNKNOWN"); }
        finally { jdbc.execute("DROP TRIGGER test_abort_failure ON multipart_cancellations"); jdbc.execute("DROP FUNCTION test_abort_failure()"); }
        assertThat(cancellations.recover(owner, s.id()).disposition()).isEqualTo("UNKNOWN");
        verify(s3, times(1)).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test void explicitExpiryStopsProductionSigningAndOnlyExplicitRecoveryAborts() {
        var s = open(4096); age(s); clearInvocations(s3);
        assertThat(transactions.expire(new MultipartCancellationTransactions.Candidate(owner, s.id())).disposition()).isEqualTo("PENDING");
        verifyNoInteractions(s3);
        assertThatThrownBy(() -> transfers.partUrls(owner, s.id(), List.of(new PartExpectation(1, 4096, sha(bytes(4096))))))
                .isInstanceOf(MultipartConflictException.class);
        assertThat(cancellations.recover(owner, s.id()).cancelled()).isTrue();
        verify(s3, times(1)).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test void readyCannotBeCancelledOrExpiredAndCompletedBytesRemainExact() throws Exception {
        byte[] bytes = bytes(4096); var s = open(bytes.length); var signed = signed(s, 1, bytes);
        assertThat(f.send(signed, signed.url().toURI(), bytes).status()).isEqualTo(200);
        assertThat(completions.complete(owner, s.id()).completion().ready()).isTrue(); age(s);
        assertThatThrownBy(() -> cancellations.cancel(owner, s.id())).isInstanceOf(MultipartConflictException.class);
        assertThatThrownBy(() -> cancellations.recover(owner, s.id())).isInstanceOf(MultipartConflictException.class);
        assertThat(transactions.expire(new MultipartCancellationTransactions.Candidate(owner, s.id())).reason()).isEqualTo("DECISION_EXISTS");
        assertThat(f.s3.getObjectAsBytes(b -> b.bucket(f.properties.bucket()).key(allocation(s).key)).asByteArray()).isEqualTo(bytes);
        verify(s3, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        assertThat(completions.recover(owner, s.id()).completion().ready()).isTrue();
    }

    private Session open(long size) {
        var s = sessions.reserve(owner, UUID.randomUUID().toString(), new Request(null, UUID.randomUUID().toString(), size, "application/octet-stream"));
        var opened = transfers.initiate(owner, s.id()); assertThat(opened.state()).isEqualTo("OPEN"); return opened;
    }
    private PresignedUploadPartRequest signed(Session s, int number, byte[] bytes) {
        var signed = transfers.partUrls(owner, s.id(), List.of(new PartExpectation(number, bytes.length, sha(bytes)))).getFirst().upload();
        return PresignedUploadPartRequest.builder().expiration(signed.expiresAt()).signedHeaders(signed.requiredHeaders())
                .httpRequest(SdkHttpFullRequest.builder().uri(URI.create(signed.url())).method(SdkHttpMethod.PUT).headers(signed.requiredHeaders()).build())
                .isBrowserExecutable(false).build();
    }
    private void age(Session s) {
        new TransactionTemplate(manager).executeWithoutResult(status -> { jdbc.execute("ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session");
            jdbc.update("UPDATE upload_sessions SET created_at = created_at - interval '25 hours', expires_at = expires_at - interval '25 hours' WHERE id = ?", s.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"); jdbc.execute("ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session"); });
    }
    private GarageMultipartFixture.Allocation allocation(Session s) { return f.allocations.stream().filter(a -> Objects.equals(a.uploadId, s.uploadId())).findFirst().orElseThrow(); }
    private GarageMultipartFixture.Allocation allocation(String key) { return f.allocations.stream().filter(a -> a.key.equals(key)).findFirst().orElseThrow(); }
    private static byte[] bytes(int length) { byte[] result = new byte[length]; new Random(44).nextBytes(result); return result; }
    private static void assertNoTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
}
