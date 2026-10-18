package com.vaultdrive.storage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static com.vaultdrive.storage.GarageMultipartFixture.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real Garage characterization. Deliberately never boots the application or Flyway. */
@EnabledIfEnvironmentVariable(named = "GARAGE_MULTIPART_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(60)
class GarageMultipartCapabilityTest {
    GarageMultipartFixture f;
    @RegisterExtension final TestWatcher retainFailures = new TestWatcher() {
        @Override public void testFailed(ExtensionContext context, Throwable cause) {
            if (f != null) {
                try { f.event("ASSERTION_FAILED", context.getRequiredTestMethod().getName()); }
                catch (Exception e) { f.retain = true; }
            }
        }
    };
    @BeforeAll void initialize() throws Exception { f = new GarageMultipartFixture(); }
    @BeforeEach void boundary(TestInfo info) throws Exception { f.event("EXPERIMENT", info.getTestMethod().orElseThrow().getName()); }
    @AfterEach void controlIntact() throws Exception { f.assertBytes(f.control, CONTROL); }
    @AfterAll void disposeOnlyRegisteredAllocations() throws Exception {
        if (f != null) { f.close(); }
    }

    @Test @Order(1) void basicMultipartAndCompletedMetadata() throws Exception {
        var a = f.create(true);
        byte[] bytes = bytes(8192, 1);
        var part = f.upload(a, 1, bytes, true);
        var listed = f.list(a, 0, 10).parts().getFirst();
        assertThat(listed.eTag()).isEqualTo(part.eTag());
        assertThat(listed.size()).isEqualTo(bytes.length);
        assertThat(listed.checksumSHA256()).isEqualTo(sha(bytes));
        f.complete(a, List.of(part)); f.assertBytes(a, bytes);
        var head = f.s3.headObject(b -> b.bucket(f.properties.bucket()).key(a.key).checksumMode(ChecksumMode.ENABLED));
        assertThat(head.metadata()).containsEntry("vaultdrive-m0-run", f.run);
        assertThat(head.contentLength()).isEqualTo(bytes.length);
        f.event("HEAD_COMPLETE", "etag=" + head.eTag() + " checksum=" + head.checksumSHA256()
                + " versionId=" + head.versionId());
    }

    @Test @Order(2) void browserShapedPresignAndSignedLengthAndChecksum() throws Exception {
        var a = f.create(true); byte[] bytes = bytes(4096, 2);
        var signed = f.sign(a, 1, bytes, true, true);
        assertThat(signed.signedHeaders()).containsKey("content-length");
        var good = f.send(signed, signed.url().toURI(), bytes);
        assertThat(good.status()).isEqualTo(200);
        assertThat(good.checksum()).isEqualTo(sha(bytes));
        var part = f.list(a, 0, 10).parts().getFirst();
        assertThat(part.size()).isEqualTo(bytes.length);
        assertThat(part.checksumSHA256()).isEqualTo(sha(bytes));
        f.complete(a, List.of(CompletedPart.builder().partNumber(1).eTag(good.etag())
                .checksumSHA256(good.checksum()).build()));
        f.assertBytes(a, bytes);
    }

    @Test @Order(3) void wrongPayloadRejectedByGarageChecksum() throws Exception {
        var a = f.create(true); byte[] bytes = bytes(4096, 3);
        var signed = f.sign(a, 1, bytes, true, true);
        var bad = f.send(signed, signed.url().toURI(), bytes(4096, 4));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.code()).isEqualTo("InvalidDigest");
        assertThat(f.list(a, 0, 10).parts()).isEmpty();
    }

    @Test @Order(4) void signedLengthRejectsSmallerAndLargerBodies() throws Exception {
        var a = f.create(false); byte[] bytes = bytes(4096, 5);
        var signed = f.sign(a, 1, bytes, true, false);
        for (int size : new int[]{4095, 4097}) {
            var bad = f.send(signed, signed.url().toURI(), bytes(size, 5));
            assertThat(bad.status()).isEqualTo(403);
            assertThat(bad.code()).isEqualTo("AccessDenied");
        }
        assertThat(f.list(a, 0, 10).parts()).isEmpty();
    }

    @Test @Order(5) void unsignedLengthIsOnlyAClientDeclaration() throws Exception {
        var a = f.create(false); byte[] bytes = bytes(4096, 6);
        var signed = f.sign(a, 1, bytes, false, false);
        assertThat(signed.signedHeaders()).doesNotContainKey("content-length");
        var result = f.send(signed, signed.url().toURI(), bytes(17, 6));
        assertThat(result.status()).isEqualTo(200);
        assertThat(f.list(a, 0, 10).parts().getFirst().size()).isEqualTo(17);
    }

    @Test @Order(6) void expiryAndTamperedSignaturePartAndUploadIdentity() throws Exception {
        var a = f.create(false); byte[] bytes = bytes(1024, 7);
        var signed = f.sign(a, 1, bytes, true, false);
        String url = signed.url().toExternalForm();
        List<String> altered = List.of(url.replace("partNumber=1", "partNumber=2"),
                url.replace(a.uploadId, "0".repeat(a.uploadId.length())),
                url.replaceFirst("X-Amz-Signature=.", "X-Amz-Signature=z"));
        for (String tampered : altered) {
            assertThat(!tampered.equals(url)).as("tampering changed the signed request").isTrue();
            var response = f.send(signed, URI.create(tampered), bytes);
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.code()).isEqualTo("AccessDenied");
        }
        var expired = f.sign(a, 1, bytes, true, false,
                Clock.offset(f.garageClock, Duration.ofMinutes(-2)), Duration.ofSeconds(30));
        var denied = f.send(expired, expired.url().toURI(), bytes);
        assertThat(denied.status()).isBetween(400, 403);
        f.event("EXPIRED_DENIED", "http=" + denied.status() + " code=" + denied.code());
        var missing = new Allocation(a.key); missing.uploadId = "0".repeat(a.uploadId.length());
        var wrong = f.sign(missing, 1, bytes, true, false);
        var noUpload = f.send(wrong, wrong.url().toURI(), bytes);
        assertThat(noUpload.status()).isEqualTo(404);
        assertThat(noUpload.code()).isEqualTo("NoSuchUpload");
        var invalidPart = f.sign(a, 0, bytes, true, false);
        var invalid = f.send(invalidPart, invalidPart.url().toURI(), bytes);
        assertThat(invalid.status()).isEqualTo(200);
        f.event("PART_ZERO_ACCEPTED", "Garage does not enforce the proposed 1-based application range");
        // Marker 0 excludes part zero. Do not confuse an empty page with absence of this accepted part.
    }

    @Test @Order(7) void replayAndReplacementAndChecksumBoundReplay() throws Exception {
        var a = f.create(false); byte[] old = bytes(2048, 8), replacement = bytes(2048, 9);
        var signed = f.sign(a, 1, old, true, false);
        var first = f.send(signed, signed.url().toURI(), old);
        var replay = f.send(signed, signed.url().toURI(), old);
        var replaced = f.send(signed, signed.url().toURI(), replacement);
        assertThat(List.of(first.status(), replay.status(), replaced.status())).containsOnly(200);
        assertThat(replay.etag()).isEqualTo(first.etag());
        assertThat(replaced.etag()).isNotEqualTo(first.etag());
        assertThat(f.list(a, 0, 10).parts()).singleElement().satisfies(p -> assertThat(p.eTag()).isEqualTo(replaced.etag()));
        f.complete(a, List.of(CompletedPart.builder().partNumber(1).eTag(replaced.etag()).build()));
        f.assertBytes(a, replacement);
        var b = f.create(true); var bound = f.sign(b, 1, old, true, true);
        assertThat(f.send(bound, bound.url().toURI(), old).status()).isEqualTo(200);
        assertThat(f.send(bound, bound.url().toURI(), old).status()).isEqualTo(200);
        assertThat(f.send(bound, bound.url().toURI(), replacement).code()).isEqualTo("InvalidDigest");
    }

    @Test @Order(8) void concurrentDifferentPartsAndCompetingSamePart() throws Exception {
        var a = f.create(false); byte[] one = bytes(5 * 1024 * 1024, 10), two = bytes(8192, 11);
        var parts = parallel(() -> f.upload(a, 1, one, false), () -> f.upload(a, 2, two, false));
        assertThat(f.list(a, 0, 10).parts()).hasSize(2);
        f.complete(a, parts); f.assertBytes(a, concat(one, two));
        byte[] left = bytes(32768, 12), right = bytes(32768, 13); int mismatches = 0;
        for (int round = 0; round < 20; round++) {
            var b = f.create(false);
            var competing = parallel(() -> f.upload(b, 1, left, false), () -> f.upload(b, 1, right, false));
            var selected = f.list(b, 0, 10).parts().getFirst();
            assertThat(selected.eTag()).isIn(competing.get(0).eTag(), competing.get(1).eTag());
            f.complete(b, List.of(CompletedPart.builder().partNumber(1).eTag(selected.eTag()).build()));
            byte[] actual = f.s3.getObjectAsBytes(r -> r.bucket(f.properties.bucket()).key(b.key)).asByteArray();
            assertThat(Arrays.equals(actual, left) || Arrays.equals(actual, right)).isTrue();
            byte[] selectedBytes = selected.eTag().equals(competing.get(0).eTag()) ? left : right;
            boolean matches = Arrays.equals(actual, selectedBytes);
            if (!matches) { mismatches++; }
            f.event("COMPETING_DIFFERENT_BYTES", "round=" + round + " selected_etag=" + selected.eTag()
                    + " selected_etag_matches_bytes=" + matches + " expected_sha256=" + sha(selectedBytes)
                    + " actual_sha256=" + sha(actual));
        }
        f.event("COMPETING_DIFFERENT_SUMMARY", "rounds=20 mismatches=" + mismatches);
        // Characterize the observed Garage metadata/content disagreement; never treat it as supported safety.
        // The proposed protocol instead binds every capability for a part to the same checksum and size.
    }

    @Test @Order(17) void competingSamePartWithImmutableChecksumHasIdenticalBytes() throws Exception {
        byte[] payload = bytes(32768, 21);
        for (int i = 0; i < 10; i++) {
            var a = f.create(true);
            var signed = f.sign(a, 1, payload, true, true);
            var executor = Executors.newFixedThreadPool(2); var start = new CyclicBarrier(2);
            try {
                var one = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return f.send(signed, signed.url().toURI(), payload); });
                var two = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return f.send(signed, signed.url().toURI(), payload); });
                assertThat(one.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
                assertThat(two.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
            } catch (Exception | AssertionError e) { f.retain = true; throw e; }
            finally { executor.shutdownNow(); assertThat(executor.awaitTermination(25, TimeUnit.SECONDS)).isTrue(); }
            var p = f.list(a, 0, 10).parts().getFirst();
            assertThat(p.checksumSHA256()).isEqualTo(sha(payload));
            f.complete(a, List.of(CompletedPart.builder().partNumber(1).eTag(p.eTag()).checksumSHA256(p.checksumSHA256()).build()));
            f.assertBytes(a, payload);
        }
        f.event("COMPETING_IMMUTABLE_CHECKSUM", "rounds=10 bytes_verified=true");
    }

    @Test @Order(18) void explicitInvalidPartRejectionThenCorrectCompletion() throws Exception {
        var a = f.create(false); byte[] payload = bytes(4096, 22); var good = f.upload(a, 1, payload, false);
        var bad = CompletedPart.builder().partNumber(1).eTag("00000000000000000000000000000000").build();
        f.event("REJECTED_COMPLETE_DISPATCH", a.key);
        try {
            var unexpected = f.s3.completeMultipartUpload(f.completion(a, List.of(bad)));
            a.completed = true; a.completionDispatched = true;
            fail("Wrong ETag unexpectedly completed: HTTP " + unexpected.sdkHttpResponse().statusCode());
        } catch (S3Exception rejected) {
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(code(rejected)).isEqualTo("InvalidPart");
            f.event("COMPLETE_INVALID_PART_REJECTED", "http=400 code=InvalidPart");
        } catch (RuntimeException unknown) {
            f.retain = true; f.event("COMPLETE_UNKNOWN", a.key); throw sanitized(unknown);
        }
        assertThat(f.list(a, 0, 10).parts()).hasSize(1);
        f.complete(a, List.of(good)); f.assertBytes(a, payload);
    }

    @Test @Order(9) void listPartsPaginationAndChecksums() throws Exception {
        var a = f.create(true);
        for (int n = 1; n <= 5; n++) { f.upload(a, n, bytes(1000 + n, n), true); }
        List<Part> all = new ArrayList<>(); int marker = 0, pages = 0;
        while (true) {
            var page = f.list(a, marker, 2); pages++; all.addAll(page.parts());
            if (!page.isTruncated()) { break; }
            assertThat(page.nextPartNumberMarker()).isGreaterThan(marker);
            marker = page.nextPartNumberMarker(); assertThat(pages).isLessThan(5);
        }
        assertThat(pages).isEqualTo(3);
        assertThat(all).extracting(Part::partNumber).containsExactly(1, 2, 3, 4, 5);
        for (var part : all) {
            assertThat(part.size()).isEqualTo(1000 + part.partNumber());
            assertThat(part.checksumSHA256()).isEqualTo(sha(bytes(1000 + part.partNumber(), part.partNumber())));
        }
    }

    @Test @Order(10) void inFlightNewPartCannotChangeCompletedBytes() throws Exception { completeRace(false); }
    @Test @Order(11) void inFlightReplacementCannotChangeCompletedBytes() throws Exception { completeRace(true); }
    void completeRace(boolean replacement) throws Exception {
        var a = f.create(false); byte[] original = bytes(8192, 14), late = bytes(3 * 1024 * 1024, 15);
        var part = f.upload(a, 1, original, false);
        var signed = f.sign(a, replacement ? 1 : 2, late, true, false);
        try (var gate = new GatedPart(signed, late)) {
            f.event("GATE_PREFIX_FLUSHED", "100-continue prefix=" + gate.prefix + " internal-read-point-unproven");
            f.complete(a, List.of(part)); f.assertBytes(a, original);
            int lateStatus = gate.finish();
            f.event("LATE_PART_AFTER_COMPLETE", "http=" + lateStatus + " replacement=" + replacement);
            assertThat(lateStatus).isIn(200, 404);
            f.assertBytes(a, original);
            assertThat(f.send(signed, signed.url().toURI(), late).status()).isIn(0, 404);
            f.assertBytes(a, original);
        } catch (Exception | AssertionError e) { a.uncertain = true; throw e; }
    }

    @Test @Order(12) void inFlightPartAcrossAbortAndOldUrlReplay() throws Exception {
        var a = f.create(false); byte[] late = bytes(3 * 1024 * 1024, 16);
        var signed = f.sign(a, 1, late, true, false);
        try (var gate = new GatedPart(signed, late)) {
            f.event("GATE_PREFIX_FLUSHED", "100-continue prefix=" + gate.prefix + " internal-read-point-unproven");
            f.abort(a);
            int lateStatus = gate.finish();
            f.event("LATE_PART_AFTER_ABORT", "http=" + lateStatus);
            assertThat(lateStatus).isIn(200, 404);
            // A peer can reject/close before HttpClient obtains the response. Preserve that unknown.
            assertThat(f.send(signed, signed.url().toURI(), late).status()).isIn(0, 404);
            try { f.s3.listParts(b -> b.bucket(f.properties.bucket()).key(a.key).uploadId(a.uploadId));
                fail("Aborted upload unexpectedly listed");
            } catch (S3Exception e) { assertThat(code(e)).isEqualTo("NoSuchUpload"); }
            f.noObject(a); // Does not assert physical part-block reclamation or terminate writers.
        } catch (Exception | AssertionError e) { a.uncertain = true; throw e; }
    }

    @Test @Order(13) void completionResponseLossAndDuplicateAttempt() throws Exception {
        var a = f.create(false); byte[] bytes = bytes(4096, 17); var part = f.upload(a, 1, bytes, false);
        try (var proxy = new MultipartCompletionDropProxy(f)) {
            a.completionDispatched = true; f.event("COMPLETE_DISPATCH", a.key);
            try { proxy.client.completeMultipartUpload(f.completion(a, List.of(part)));
                fail("Client must lose completion response");
            } catch (software.amazon.awssdk.core.exception.SdkClientException expected) {
                f.event("COMPLETE_CLIENT_UNKNOWN", expected.getClass().getSimpleName());
            }
            proxy.verifyAccepted(); // Independent verifier saw real upstream success before dropping it.
            a.completed = true;
            f.assertBytes(a, bytes);
            try { f.s3.completeMultipartUpload(f.completion(a, List.of(part))); fail("Duplicate completion unexpectedly succeeded");
            } catch (S3Exception e) {
                assertThat(e.statusCode()).isEqualTo(404); assertThat(code(e)).isEqualTo("NoSuchUpload");
                f.event("DUPLICATE_COMPLETE", "http=404 code=NoSuchUpload");
            }
            f.assertBytes(a, bytes);
        } catch (Exception | AssertionError e) { f.retain = true; throw e; }
    }

    @Test @Order(14) void readOnlyCorsAndHttpsInspection() throws Exception {
        var a = f.create(true); byte[] bytes = bytes(4096, 18); var signed = f.sign(a, 1, bytes, true, true);
        var response = f.http.send(HttpRequest.newBuilder(signed.url().toURI()).timeout(Duration.ofSeconds(10))
                .header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "x-amz-checksum-sha256")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        f.event("CORS_PREFLIGHT", "http=" + response.statusCode() + " code=" + xmlCode(response.body())
                + " allowOrigin=" + response.headers().firstValue("access-control-allow-origin").orElse("ABSENT")
                + " allowHeaders=" + response.headers().firstValue("access-control-allow-headers").orElse("ABSENT")
                + " expose=" + response.headers().firstValue("access-control-expose-headers").orElse("ABSENT"));
        try {
            var cors = f.s3.getBucketCors(b -> b.bucket(f.properties.bucket()));
            f.event("BUCKET_CORS", "http=" + cors.sdkHttpResponse().statusCode() + " rules=" + cors.corsRules().size());
        } catch (S3Exception e) { f.event("BUCKET_CORS", "http=" + e.statusCode() + " code=" + code(e)); }
        f.event("TLS_PREREQUISITE", "configured_scheme=" + URI.create(f.properties.endpoint()).getScheme()
                + " production_origin_and_https_not_supplied");
        URI tls = URI.create(f.properties.endpoint().replaceFirst("^http:", "https:"));
        try {
            var tlsResponse = f.http.send(HttpRequest.newBuilder(tls).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            f.event("HTTPS_PROBE", "http=" + tlsResponse.statusCode());
        } catch (java.io.IOException failure) {
            f.event("HTTPS_PROBE_FAILED", failure.getClass().getSimpleName());
        }
        // Inspection is characterization; an absent CORS configuration is not disguised as storage failure.
        assertThat(response.statusCode()).isBetween(200, 499);
    }

    @Test @Order(15) void publicSdkPresignerWithoutClockAdjustment() throws Exception {
        var a = f.create(true); byte[] bytes = bytes(4096, 19);
        var signed = f.sign(a, 1, bytes, true, true, Clock.systemUTC(), Duration.ofMinutes(2));
        assertThat(f.send(signed, signed.url().toURI(), bytes).status()).isEqualTo(200);
        f.event("PUBLIC_PRESIGNER", "accepted_with_unadjusted_jvm_date; expiry_follows_signed_date");
    }

    @Test @Order(16) void acceptedPartMayFinishAfterUrlExpiry() throws Exception {
        var a = f.create(false); byte[] bytes = bytes(3 * 1024 * 1024, 20);
        var signed = f.sign(a, 1, bytes, true, false, f.garageClock, Duration.ofSeconds(3));
        try (var gate = new GatedPart(signed, bytes)) {
            // This wait deliberately crosses an authorization deadline; it is never writer proof.
            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            assertThat(f.garageClock.instant()).isAfter(signed.expiration());
            int result = gate.finish(); f.event("INFLIGHT_AFTER_EXPIRY", "http=" + result);
            assertThat(result).isEqualTo(200);
            assertThat(f.list(a, 0, 10).parts().getFirst().size()).isEqualTo(bytes.length);
            assertThat(f.send(signed, signed.url().toURI(), bytes).status()).isEqualTo(400);
        } catch (Exception | AssertionError e) { f.retain = true; throw e; }
    }

    static List<CompletedPart> parallel(Callable<CompletedPart> one, Callable<CompletedPart> two) throws Exception {
        var executor = Executors.newFixedThreadPool(2); var start = new CyclicBarrier(2);
        try {
            var a = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return one.call(); });
            var b = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return two.call(); });
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).as("actual tasks joined before cleanup").isTrue();
        }
    }
    static byte[] bytes(int length, int seed) { byte[] bytes = new byte[length]; new Random(seed).nextBytes(bytes); return bytes; }
    static byte[] concat(byte[] a, byte[] b) { byte[] out = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, out, a.length, b.length); return out; }
}
