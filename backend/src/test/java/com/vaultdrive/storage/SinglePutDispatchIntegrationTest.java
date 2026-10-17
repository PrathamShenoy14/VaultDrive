package com.vaultdrive.storage;

import com.vaultdrive.file.FileMetadataService;
import com.vaultdrive.file.FileService;
import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.UploadFinalizationRecovery;
import com.vaultdrive.file.UploadFinalizationTransactions;
import com.vaultdrive.file.exception.FileUploadException;
import com.vaultdrive.security.JwtService;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.vaultdrive.storage.ControlledPutServer.Mode.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real production SDK/Apache client, adapter, admission, receipt transactions and HTTP upload API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "vaultdrive.outbox.publisher.enabled=false",
        "vaultdrive.purge.consumer.enabled=false", "vaultdrive.jobs.worker.enabled=false",
        "vaultdrive.jobs.reconciliation.enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(40)
class SinglePutDispatchIntegrationTest {
    private static final String SCHEMA = "single_put_" + UUID.randomUUID().toString().replace("-", "");
    private static final String BUCKET = "controlled-fixture";
    private static final byte[] BODY = "controlled upload body".repeat(4096).getBytes(StandardCharsets.UTF_8);
    private static final ControlledPutServer receiver = ControlledPutServer.start();
    private static final ControlledPutServer redirect = ControlledPutServer.start();

    @DynamicPropertySource static void isolate(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("storage.s3.endpoint", receiver::endpoint);
        registry.add("storage.s3.namespace", () -> "single-put-controlled");
        registry.add("storage.s3.region", () -> "garage");
        registry.add("storage.s3.bucket", () -> BUCKET);
        registry.add("storage.s3.access-key", () -> "fixture-access");
        registry.add("storage.s3.secret-key", () -> "fixture-secret");
    }

    @Autowired FileService files;
    @Autowired FileMetadataService metadata;
    @Autowired ObjectStorageService storage;
    @Autowired S3Client s3;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired Environment environment;
    @MockitoSpyBean UploadFinalizationTransactions transactions;
    @MockitoSpyBean UploadFinalizationRecovery completion;
    @LocalServerPort int port;
    private UUID owner;

    @BeforeEach void freshScenario() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("vaultdrive_test");
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        for (String flag : List.of("vaultdrive.outbox.publisher.enabled", "vaultdrive.purge.consumer.enabled",
                "vaultdrive.jobs.worker.enabled", "vaultdrive.jobs.reconciliation.enabled")) {
            assertThat(environment.getProperty(flag, Boolean.class)).isFalse();
        }
        receiver.scenario(SUCCESS, 1, redirect.endpoint());
        redirect.scenario(SUCCESS, 0, null);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture-hash", "Dispatch")).getId();
    }

    @AfterAll static void disposeOwnedSchema(@Autowired DataSource source) throws Exception {
        try {
            receiver.close();
        } finally {
            try { redirect.close(); }
            finally {
                assertThat(SCHEMA).matches("single_put_[0-9a-f]{32}");
                try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                    assertThat(connection.getCatalog()).isEqualTo("vaultdrive_test");
                    statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                }
            }
        }
    }

    @Test void successfulPutHasOneReceivedRequestAndCannotDispatchAgain() throws Exception {
        var response = files.uploadFile(owner, null, multipart());
        assertEvidence("READY", 1);
        assertThat(receiver.completeBodies()).isEqualTo(1);
        assertThat(receiver.effects()).isEqualTo(1);
        String key = jdbc.queryForObject("SELECT storage_key FROM files WHERE id = ?", String.class, response.fileId());
        assertThat(receiver.received()).extracting(ControlledPutServer.Received::target).containsExactly("/" + BUCKET + "/" + key);
        assertThatThrownBy(() -> storage.upload(key, new ByteArrayInputStream(BODY), BODY.length, "text/plain"))
                .isInstanceOf(IllegalStateException.class);
        evidence("SUCCESS_AND_REUSE_REJECTED", 1, 1);
    }

    @Test void productionClientHasOneAttemptAndNoCrossRegionReplay() throws Exception {
        receiver.scenario(SUCCESS, 0, redirect.endpoint());
        assertThat(s3.serviceClientConfiguration().overrideConfiguration().retryStrategy().orElseThrow().maxAttempts()).isEqualTo(1);
        assertThat(s3.serviceClientConfiguration().crossRegionAccessEnabled()).isNotEqualTo(Boolean.TRUE);
        evidence("PRODUCTION_CLIENT_CONFIGURATION", 0, 0);
    }

    @ParameterizedTest
    @EnumSource(value = ControlledPutServer.Mode.class, names = {"HTTP_500", "HTTP_503", "DISCONNECT_DURING_BODY",
            "READ_TIMEOUT", "REDIRECT_301", "REDIRECT_302", "REDIRECT_307", "REDIRECT_308", "DROP_RESPONSE"})
    void failedOrUncertainPutNeverReplaysOrCreatesReceipt(ControlledPutServer.Mode fault) throws Exception {
        receiver.scenario(fault, 1, redirect.endpoint());
        Throwable failure = catchThrowable(() -> files.uploadFile(owner, null, multipart()));
        assertThat(failure).isInstanceOf(FileUploadException.class);
        int status = switch (fault) {
            case HTTP_500 -> 500; case HTTP_503 -> 503;
            case REDIRECT_301 -> 301; case REDIRECT_302 -> 302;
            case REDIRECT_307 -> 307; case REDIRECT_308 -> 308;
            default -> 0;
        };
        if (status != 0) {
            assertThat(failure.getCause()).isInstanceOfSatisfying(S3Exception.class,
                    error -> assertThat(error.statusCode()).isEqualTo(status));
        } else if (fault == READ_TIMEOUT) {
            assertThat(failure).hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
        } else {
            assertThat(failure).hasRootCauseInstanceOf(IOException.class);
        }
        assertEvidence("FAILED", 0);
        // A client failure can coexist with the server's fully accepted body/effect.
        if (fault == READ_TIMEOUT || fault == DROP_RESPONSE) { assertThat(receiver.effects()).isEqualTo(1); }
        evidence(fault.name(), 1, 0);
        String key = jdbc.queryForObject("SELECT storage_key FROM files WHERE owner_id = ?", String.class, owner);
        assertThatThrownBy(() -> storage.upload(key, new ByteArrayInputStream(BODY), BODY.length, "text/plain"))
                .isInstanceOf(IllegalStateException.class);
        receiver.assertCounts(1);
    }

    @Test void unallocatedKeyCannotReachHttpReceiver() throws Exception {
        receiver.scenario(SUCCESS, 0, redirect.endpoint());
        assertThatThrownBy(() -> storage.upload("unallocated", new ByteArrayInputStream(BODY), BODY.length, "text/plain"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(allocationCount()).isZero();
        evidence("NO_ADMISSION", 0, 0);
    }

    @Test void concurrentAdmissionOfSameAllocationAllowsExactlyOneReceivedPut() throws Exception {
        receiver.scenario(HOLD_SUCCESS, 1, redirect.endpoint());
        UUID id = UUID.randomUUID();
        String key = "users/" + owner + "/files/" + id;
        metadata.createUploading(new StoredFile(id, owner, null, "concurrent.txt", key, "text/plain", BODY.length));
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = CompletableFuture.supplyAsync(() -> attempt(start, key), executor);
            var two = CompletableFuture.supplyAsync(() -> attempt(start, key), executor);
            assertThat(receiver.awaitEntered()).isTrue();
            // Winner is held at the receiver. The other admission must fail without another PUT.
            assertThat(CompletableFuture.anyOf(one, two).get(10, TimeUnit.SECONDS)).isEqualTo(false);
            assertEvidence("UPLOADING", 0);
            receiver.releaseResponse();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            evidence("CONCURRENT_ADMISSION", 1, 0);
        } finally {
            receiver.releaseResponse(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void repeatedHttpUploadAfterFailureUsesDistinctPermanentAllocations() throws Exception {
        receiver.scenario(HTTP_500, 2, redirect.endpoint());
        byte[] request = httpMultipart();
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            assertThat(post(client, request).statusCode()).isEqualTo(500);
            receiver.nextResponse(SUCCESS);
            assertThat(post(client, request).statusCode()).isEqualTo(201);
        }
        assertThat(allocationCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT storage_key) FROM file_storage_objects WHERE owner_id = ?",
                Long.class, owner)).isEqualTo(2);
        List<String> paths = jdbc.queryForList("SELECT storage_key FROM file_storage_objects WHERE owner_id = ?", String.class, owner)
                .stream().map(key -> "/" + BUCKET + "/" + key).toList();
        assertThat(receiver.received()).extracting(ControlledPutServer.Received::target).containsExactlyInAnyOrderElementsOf(paths);
        assertThat(jdbc.queryForList("SELECT status FROM files WHERE owner_id = ?", String.class, owner))
                .containsExactlyInAnyOrder("FAILED", "READY");
        assertThat(receipts()).isEqualTo(1);
        evidence("REPEATED_HTTP_AFTER_FAILURE", 2, 1);
    }

    @Test void repeatingSuccessfulHttpUploadConflictsBeforeAnotherPut() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            byte[] request = httpMultipart();
            assertThat(post(client, request).statusCode()).isEqualTo(201);
            assertThat(post(client, request).statusCode()).isEqualTo(409);
        }
        assertEvidence("READY", 1);
        evidence("REPEATED_HTTP_NAME_CONFLICT", 1, 1);
    }

    @Test void receiptFollowsSuccessfulPutReturnAndSuccessfulStreamClosure() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        doAnswer(call -> {
            assertThat(closed).isTrue();
            assertThat(receiver.completeBodies()).isEqualTo(1);
            assertThat(receipts()).isZero();
            return call.callRealMethod();
        }).when(transactions).recordSuccess(any());
        doAnswer(call -> {
            assertThat(receipts()).as("receipt independently committed before READY").isEqualTo(1);
            return call.callRealMethod();
        }).when(transactions).recover(any());
        files.uploadFile(owner, null, new MockMultipartFile("file", "dispatch.txt", "text/plain", BODY) {
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(BODY) {
                    @Override public void close() {
                        assertThat(receiver.effects()).isEqualTo(1);
                        assertEvidence("UPLOADING", 0);
                        closed.set(true);
                    }
                };
            }
        });
        assertEvidence("READY", 1);
        evidence("RECEIPT_AFTER_CLOSE", 1, 1);
    }

    @Test void successfulPutWithFailedCloseHasNoSuccessReceipt() throws Exception {
        var multipart = new MockMultipartFile("file", "dispatch.txt", "text/plain", BODY) {
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(BODY) {
                    @Override public void close() throws IOException { throw new IOException("Controlled close failure"); }
                };
            }
        };
        assertThatThrownBy(() -> files.uploadFile(owner, null, multipart)).isInstanceOf(FileUploadException.class);
        assertEvidence("FAILED", 0);
        assertThat(receiver.effects()).isEqualTo(1);
        evidence("FAILED_CLOSE", 1, 0);
    }

    @Test void rolledBackReceiptRetainsUploadingAndNeverRepeatsPut() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("Controlled receipt transaction rollback");
        }).when(transactions).recordSuccess(any());
        assertThatThrownBy(() -> files.uploadFile(owner, null, multipart())).isInstanceOf(FileUploadException.class);
        assertEvidence("UPLOADING", 0);
        assertThat(receiver.effects()).isEqualTo(1);
        evidence("RECEIPT_ROLLBACK", 1, 0);
    }

    @Test void unavailableReceiptAndReloadLeaveUnresolvedAllocationWithoutReplay() throws Exception {
        doThrow(new IllegalStateException("Controlled receipt boundary unavailable")).when(transactions).recordSuccess(any());
        doThrow(new IllegalStateException("Controlled authoritative reload unavailable")).when(transactions).hasSuccess(any());
        assertThatThrownBy(() -> files.uploadFile(owner, null, multipart())).isInstanceOf(FileUploadException.class);
        assertEvidence("UPLOADING", 0);
        evidence("RECEIPT_AND_RELOAD_UNAVAILABLE", 1, 0);
    }

    @Test void lostReceiptAcknowledgementReloadsCommittedEvidenceWithoutReplay() throws Exception {
        // Throw outside the real transaction: its successful return has already committed.
        var boundary = mock(UploadFinalizationTransactions.class);
        doAnswer(call -> {
            transactions.recordSuccess(call.getArgument(0));
            throw new IllegalStateException("Controlled lost receipt acknowledgement");
        }).when(boundary).recordSuccess(any());
        when(boundary.hasSuccess(any())).thenAnswer(call -> transactions.hasSuccess(call.getArgument(0)));
        when(boundary.recover(any())).thenAnswer(call -> transactions.recover(call.getArgument(0)));
        doAnswer(call -> {
            new UploadFinalizationRecovery(boundary).complete(call.getArgument(0));
            return null;
        }).when(completion).complete(any());
        files.uploadFile(owner, null, multipart());
        assertEvidence("READY", 1);
        evidence("RECEIPT_ACK_LOST", 1, 1);
    }

    private boolean attempt(CyclicBarrier barrier, String key) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            storage.upload(key, new ByteArrayInputStream(BODY), BODY.length, "text/plain");
            return true;
        } catch (IllegalStateException rejected) { return false; }
        catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
    }

    private void evidence(String scenario, int expectedPuts, int expectedReceipts) throws InterruptedException {
        receiver.assertCounts(expectedPuts); redirect.assertCounts(0);
        assertThat(receipts()).isEqualTo(expectedReceipts);
        System.out.printf("PUT_DISPATCH scenario=%s received=%d completeBodies=%d effects=%d redirectRequests=0 receipts=%d%n",
                scenario, receiver.received().size(), receiver.completeBodies(), receiver.effects(), expectedReceipts);
    }

    private void assertEvidence(String status, int receiptCount) {
        assertThat(allocationCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT write_started FROM file_storage_objects WHERE owner_id = ?", Boolean.class, owner)).isTrue();
        assertThat(jdbc.queryForObject("SELECT admitted_file_version FROM file_storage_objects WHERE owner_id = ?", Long.class, owner))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE owner_id = ?", String.class, owner)).isEqualTo(status);
        assertThat(receipts()).isEqualTo(receiptCount);
    }

    private long allocationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM file_storage_objects WHERE owner_id = ?", Long.class, owner);
    }
    private long receipts() {
        return jdbc.queryForObject("SELECT count(*) FROM file_storage_objects WHERE owner_id = ? AND put_succeeded_at IS NOT NULL",
                Long.class, owner);
    }
    private MockMultipartFile multipart() { return new MockMultipartFile("file", "dispatch.txt", "text/plain", BODY); }

    private byte[] httpMultipart() throws IOException {
        var bytes = new ByteArrayOutputStream();
        bytes.write(("--dispatch-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"dispatch.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(BODY); bytes.write("\r\n--dispatch-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }
    private HttpResponse<Void> post(HttpClient client, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/files"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + jwt.generateAccessToken(owner))
                .header("Content-Type", "multipart/form-data; boundary=dispatch-boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding());
    }
}
