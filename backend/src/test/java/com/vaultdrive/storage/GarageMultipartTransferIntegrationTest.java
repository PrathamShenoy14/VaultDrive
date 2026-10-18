package com.vaultdrive.storage;

import com.vaultdrive.file.MultipartSessionService;
import com.vaultdrive.file.MultipartTransferService;
import com.vaultdrive.file.MultipartConflictException;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;

import javax.sql.DataSource;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static com.vaultdrive.storage.GarageMultipartFixture.sha;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real adapter + database; only journalled disposable MPUs, never production complete/abort. */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GARAGE_MULTIPART_TRANSFER_TEST", matches = "true")
@Timeout(90)
class GarageMultipartTransferIntegrationTest {
    static final String SCHEMA = "multipart_transfer_live_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartTransferService transfers;
    @Autowired UserRepository users;
    @Autowired DataSource source;
    @MockitoBean StorageKeyGenerator keys;
    @MockitoSpyBean S3Client s3;
    GarageMultipartFixture f;
    UUID owner;

    @BeforeAll void fixture() throws Exception {
        f = new GarageMultipartFixture();
        f.event("M2A", "production adapter with isolated PostgreSQL; no completion/abort dispatch");
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "M2A live")).getId();
    }
    @BeforeEach void stub() {
        clearInvocations(s3);
        when(keys.generateFileKey(any(), any())).thenAnswer(call -> "integration-tests/multipart-m2a/" + f.run + "/" + call.getArgument(1));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            CreateMultipartUploadRequest request = call.getArgument(0);
            var a = new GarageMultipartFixture.Allocation(request.key());
            f.allocations.add(a); f.event("ALLOCATED", a.key);
            assertThat(request.checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256);
            assertThat(request.metadata()).containsKeys("vaultdrive-session", "vaultdrive-initiation");
            try {
                var result = (CreateMultipartUploadResponse) call.callRealMethod();
                a.uploadId = result.uploadId(); f.event("CREATE_HTTP_200", a.key + " " + a.uploadId);
                return result;
            } catch (Throwable failure) { a.uncertain = true; f.event("CREATE_UNKNOWN", a.key); throw failure; }
        }).when(s3).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }
    @AfterEach void controlAndNoTerminalDispatch() throws Exception {
        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(s3, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        f.assertBytes(f.control, GarageMultipartFixture.CONTROL);
    }
    @AfterAll void dispose() throws Exception {
        if (f == null) { return; }
        f.close(); // Test-only cleanup of known open allocations after all HTTP tasks join.
        if (f.retain || f.allocations.stream().anyMatch(a -> a.uncertain)) { return; }
        assertThat(SCHEMA).matches("multipart_transfer_live_[0-9a-f]{32}");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test");
            s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void productionUrlBindsIdentityLengthAndChecksumAndSupportsIdenticalReplay() throws Exception {
        byte[] bytes = bytes(4096, 1);
        var session = open(bytes.length);
        var url = url(session, 1, bytes);
        assertThat(url.upload().requiredHeaders().keySet()).contains("content-length", "host", "x-amz-checksum-sha256");
        assertThat(url.upload().requiredHeaders().get("content-length")).containsExactly("4096");
        assertThat(url.upload().requiredHeaders().get("x-amz-checksum-sha256")).containsExactly(sha(bytes));
        assertThat(url.upload().expiresAt()).isAfter(Instant.now()).isBefore(Instant.now().plusSeconds(301));
        var signed = wire(url.upload());
        assertThat(f.send(signed, signed.url().toURI(), bytes).status()).isEqualTo(200);
        assertThat(f.send(signed, signed.url().toURI(), bytes).status()).isEqualTo(200);
        assertThat(f.send(signed, signed.url().toURI(), bytes(4096, 2)).code()).isEqualTo("InvalidDigest");
        for (int length : new int[]{4095, 4097}) {
            assertThat(f.send(signed, signed.url().toURI(), bytes(length, 1)).status()).isEqualTo(403);
        }
        String original = url.upload().url();
        for (String tampered : List.of(original.replace("partNumber=1", "partNumber=2"),
                original.replace(session.uploadId(), "0".repeat(session.uploadId().length())),
                original.replaceFirst("X-Amz-Signature=.", "X-Amz-Signature=z"),
                original.replace("/integration-tests/", "/altered-integration-tests/"),
                original.replace("/" + f.properties.bucket() + "/", "/altered-bucket/"))) {
            assertThat(!original.equals(tampered)).as("tamper changes request").isTrue();
            assertThat(f.send(signed, URI.create(tampered), bytes).status()).isEqualTo(403);
        }
        var headers = new HashMap<>(signed.signedHeaders()); headers.put("x-amz-checksum-sha256", List.of(sha(bytes(4096, 2))));
        var changed = PresignedUploadPartRequest.builder().expiration(signed.expiration()).httpRequest(signed.httpRequest())
                .signedHeaders(headers).isBrowserExecutable(false).build();
        assertThat(f.send(changed, signed.url().toURI(), bytes).status()).isEqualTo(403);
        headers.remove("x-amz-checksum-sha256");
        var missing = PresignedUploadPartRequest.builder().expiration(signed.expiration()).httpRequest(signed.httpRequest())
                .signedHeaders(headers).isBrowserExecutable(false).build();
        assertThat(f.send(missing, signed.url().toURI(), bytes).status()).isEqualTo(400);
        var listed = transfers.list(owner, session.id(), 0, 1).parts().getFirst();
        assertThat(listed.size()).isEqualTo(bytes.length); assertThat(listed.sha256()).isEqualTo(sha(bytes));
        f.event("M2A_SIGNATURE", "exact identity length checksum tampering rejected; identical replay accepted");
    }

    @Test void competingSamePartCanOnlyWriteRegisteredContent() throws Exception {
        byte[] payload = bytes(32768, 3);
        var session = open(payload.length);
        var first = url(session, 1, payload);
        var refresh = url(session, 1, payload);
        assertThat(refresh.part()).isEqualTo(first.part());
        assertThatThrownBy(() -> url(session, 1, bytes(payload.length, 4))).isInstanceOf(MultipartConflictException.class);
        var pool = Executors.newFixedThreadPool(2); var gate = new CyclicBarrier(2);
        try {
            var left = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); var signed = wire(first.upload()); return f.send(signed, signed.url().toURI(), payload).status(); });
            var right = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); var signed = wire(refresh.upload()); return f.send(signed, signed.url().toURI(), bytes(payload.length, 4)).status(); });
            assertThat(left.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(right.get(30, TimeUnit.SECONDS)).isEqualTo(400);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        assertThat(transfers.list(owner, session.id(), 0, 1).parts().getFirst().sha256()).isEqualTo(sha(payload));
    }

    @Test void authoritativeResumePaginationAndRefreshUseDurableId() throws Exception {
        var session = open(2L * MultipartSessionService.PART_SIZE + 7);
        for (int number = 1; number <= 3; number++) {
            byte[] payload = bytes(number == 3 ? 7 : MultipartSessionService.PART_SIZE, number);
            var signed = wire(url(session, number, payload).upload());
            assertThat(f.send(signed, signed.url().toURI(), payload).status()).isEqualTo(200);
        }
        int marker = 0; var numbers = new ArrayList<Integer>();
        do {
            var page = transfers.list(owner, session.id(), marker, 1);
            assertThat(page.parts()).hasSize(1);
            var part = page.parts().getFirst(); numbers.add(part.partNumber());
            assertThat(part.size()).isEqualTo(part.partNumber() == 3 ? 7 : MultipartSessionService.PART_SIZE);
            assertThat(part.etag()).isNotBlank(); assertThat(part.sha256()).isNotBlank();
            if (page.nextMarker() == null) { break; }
            assertThat(page.nextMarker()).isGreaterThan(marker); marker = page.nextMarker();
        } while (numbers.size() < 4);
        assertThat(numbers).containsExactly(1, 2, 3);
        assertThat(transfers.initiate(owner, session.id())).isEqualTo(session);
        verify(s3, times(1)).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test void expiryFixtureResignsProductionRequestWithoutChangingItsRestrictions() throws Exception {
        byte[] payload = bytes(1024, 5);
        var session = open(payload.length);
        var original = wire(url(session, 1, payload).upload());
        // Same documented M0 test-only clock override. No production or machine clock change.
        var expired = withClock(original, Clock.offset(f.garageClock, Duration.ofMinutes(-2)), Duration.ofSeconds(30));
        assertThat(f.send(expired, expired.url().toURI(), payload).status()).isBetween(400, 403);
        var refreshed = url(session, 1, payload);
        var signed = wire(refreshed.upload());
        assertThat(f.send(signed, signed.url().toURI(), payload).status()).isEqualTo(200);
        f.event("M2A_EXPIRY", "provider-aligned expired request rejected; production refresh accepted; no writer termination inference");
    }

    private MultipartSessionService.Session open(long size) {
        var reserved = sessions.reserve(owner, UUID.randomUUID().toString(),
                new MultipartSessionService.Request(null, UUID.randomUUID().toString(), size, "application/octet-stream"));
        var result = transfers.initiate(owner, reserved.id());
        assertThat(result.state()).isEqualTo("OPEN"); return result;
    }
    private MultipartTransferService.PartUrl url(MultipartSessionService.Session session, int number, byte[] payload) {
        return transfers.partUrls(owner, session.id(), List.of(new MultipartSessionService.PartExpectation(number, payload.length, sha(payload)))).getFirst();
    }
    private static byte[] bytes(int length, int seed) { var b = new byte[length]; new Random(seed).nextBytes(b); return b; }
    private static PresignedUploadPartRequest wire(MultipartStorage.SignedPart signed) {
        return PresignedUploadPartRequest.builder().expiration(signed.expiresAt()).signedHeaders(signed.requiredHeaders())
                .httpRequest(SdkHttpFullRequest.builder().uri(URI.create(signed.url())).method(SdkHttpMethod.PUT).headers(signed.requiredHeaders()).build())
                .isBrowserExecutable(false).build();
    }
    private PresignedUploadPartRequest withClock(PresignedUploadPartRequest signed, Clock clock, Duration duration) {
        var query = new HashMap<>(signed.httpRequest().rawQueryParameters());
        List.of("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires", "X-Amz-SignedHeaders", "X-Amz-Signature").forEach(query::remove);
        var unsigned = SdkHttpFullRequest.builder().uri(signed.httpRequest().getUri()).method(signed.httpRequest().method())
                .headers(signed.httpRequest().headers()).rawQueryParameters(query).build();
        var adjusted = AwsS3V4Signer.create().presign(unsigned, Aws4PresignerParams.builder()
                .awsCredentials(AwsBasicCredentials.create(f.properties.accessKey(), f.properties.secretKey()))
                .signingRegion(Region.of(f.properties.region())).signingName("s3").doubleUrlEncode(false)
                .signingClockOverride(clock).expirationTime(clock.instant().plus(duration)).build());
        return PresignedUploadPartRequest.builder().expiration(clock.instant().plus(duration)).httpRequest(adjusted)
                .signedHeaders(signed.signedHeaders()).isBrowserExecutable(false).build();
    }
}
