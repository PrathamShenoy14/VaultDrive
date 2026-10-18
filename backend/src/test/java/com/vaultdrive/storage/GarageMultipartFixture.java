package com.vaultdrive.storage;

import com.vaultdrive.storage.config.S3StorageConfig;
import com.vaultdrive.storage.config.S3StorageProperties;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Test-only allocations. No Spring context, database, automatic recovery or production admission. */
final class GarageMultipartFixture implements AutoCloseable {
    static final byte[] CONTROL = "M0 unrelated control bytes".getBytes(StandardCharsets.UTF_8);
    final String run = UUID.randomUUID().toString();
    final Path journal = Path.of("target", "garage-multipart", run, "evidence.tsv");
    final S3StorageProperties properties;
    final S3Client s3;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    final Clock garageClock;
    final List<Allocation> allocations = new CopyOnWriteArrayList<>();
    final Allocation control;
    volatile boolean retain;

    static final class Allocation {
        final String key;
        String uploadId;
        boolean completionDispatched, completed, aborted, uncertain;
        final AtomicInteger activeRequests = new AtomicInteger();
        Allocation(String key) { this.key = key; }
    }
    record Wire(int status, String code, String etag, String checksum) { }

    GarageMultipartFixture() throws Exception {
        properties = new S3StorageProperties(env("S3_ENDPOINT", "http://localhost:3900"),
                env("S3_REGION", "garage"), env("S3_BUCKET", "vaultdrive-files"),
                required("S3_ACCESS_KEY"), required("S3_SECRET_KEY"));
        assertThat(System.getenv("GARAGE_MULTIPART_VERSION")).isEqualTo("2.3.0");
        // The value is a separately inspected binary/image version attestation, not endpoint detection.
        Files.createDirectories(journal.getParent());
        event("RUN", "garage=2.3.0 sdk=2.48.0 no-database");
        URI endpoint = URI.create(properties.endpoint());
        assertThat(endpoint.getUserInfo() == null).as("endpoint contains no credentials").isTrue();
        assertThat(endpoint.getQuery() == null && endpoint.getFragment() == null)
                .as("endpoint has no query or fragment").isTrue();
        event("BINDING", bindingEvidence(properties));
        s3 = new S3StorageConfig().s3Client(properties);
        var dateProbe = http.send(HttpRequest.newBuilder(URI.create(properties.endpoint()))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
        Instant server = ZonedDateTime.parse(dateProbe.headers().firstValue("date").orElseThrow(),
                DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        Duration offset = Duration.between(Instant.now(), server);
        garageClock = Clock.offset(Clock.systemUTC(), offset);
        event("CLOCK", "garage_minus_jvm_seconds=" + offset.toSeconds());
        control = create(false);
        var part = upload(control, 1, CONTROL, false);
        complete(control, List.of(part));
        assertBytes(control, CONTROL);
    }

    Allocation create(boolean checksum) throws Exception {
        Allocation a = new Allocation("integration-tests/multipart-m0/" + run + "/" + UUID.randomUUID());
        allocations.add(a);
        event("ALLOCATED", a.key); // Force journal before sending Create, including unknown outcomes.
        var request = CreateMultipartUploadRequest.builder().bucket(properties.bucket()).key(a.key)
                .metadata(Map.of("vaultdrive-m0-run", run)).contentType("application/octet-stream");
        if (checksum) { request.checksumAlgorithm(ChecksumAlgorithm.SHA256); }
        try {
            var result = s3.createMultipartUpload(request.build());
            a.uploadId = result.uploadId();
            event("CREATE_HTTP_" + result.sdkHttpResponse().statusCode(), a.key + " " + a.uploadId);
            return a;
        } catch (RuntimeException e) { a.uncertain = true; event("CREATE_UNKNOWN", a.key); throw sanitized(e); }
    }

    CompletedPart upload(Allocation a, int number, byte[] bytes, boolean checksum) throws Exception {
        var request = UploadPartRequest.builder().bucket(properties.bucket()).key(a.key)
                .uploadId(a.uploadId).partNumber(number);
        if (checksum) { request.checksumSHA256(sha(bytes)); }
        a.activeRequests.incrementAndGet();
        try {
            var result = s3.uploadPart(request.build(), RequestBody.fromBytes(bytes));
            event("PART_HTTP_" + result.sdkHttpResponse().statusCode(),
                    "part=" + number + " size=" + bytes.length + " etag=" + result.eTag()
                            + " sha256=" + result.checksumSHA256());
            return CompletedPart.builder().partNumber(number).eTag(result.eTag())
                    .checksumSHA256(result.checksumSHA256()).build();
        } catch (RuntimeException e) { a.uncertain = true; event("PART_SDK_UNKNOWN", a.key); throw sanitized(e); }
        finally { a.activeRequests.decrementAndGet(); }
    }

    ListPartsResponse list(Allocation a, int marker, int max) throws IOException {
        try {
            var result = s3.listParts(b -> b.bucket(properties.bucket()).key(a.key).uploadId(a.uploadId)
                    .partNumberMarker(marker).maxParts(max));
            for (var p : result.parts()) {
                event("LIST_PART", "part=" + p.partNumber() + " size=" + p.size()
                        + " etag=" + p.eTag() + " sha256=" + p.checksumSHA256());
            }
            event("LIST_HTTP_" + result.sdkHttpResponse().statusCode(), "truncated=" + result.isTruncated());
            return result;
        } catch (RuntimeException e) { throw sanitized(e); }
    }

    CompleteMultipartUploadRequest completion(Allocation a, List<CompletedPart> parts) {
        return CompleteMultipartUploadRequest.builder().bucket(properties.bucket()).key(a.key)
                .uploadId(a.uploadId).multipartUpload(b -> b.parts(parts)).build();
    }

    void complete(Allocation a, List<CompletedPart> parts) throws Exception {
        a.completionDispatched = true;
        event("COMPLETE_DISPATCH", a.key);
        try {
            var result = s3.completeMultipartUpload(completion(a, parts));
            a.completed = true;
            event("COMPLETE_HTTP_" + result.sdkHttpResponse().statusCode(), "etag=" + result.eTag());
        } catch (RuntimeException e) { a.uncertain = true; event("COMPLETE_UNKNOWN", a.key); throw sanitized(e); }
    }

    void abort(Allocation a) throws Exception {
        assertThat(a.completionDispatched).as("never abort an uncertain completer").isFalse();
        try {
            var result = s3.abortMultipartUpload(b -> b.bucket(properties.bucket()).key(a.key).uploadId(a.uploadId));
            a.aborted = true;
            event("ABORT_HTTP_" + result.sdkHttpResponse().statusCode(), a.key);
        } catch (RuntimeException e) { a.uncertain = true; event("ABORT_UNKNOWN", a.key); throw sanitized(e); }
    }

    S3Presigner signer(Clock clock) {
        return S3Presigner.builder().endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    PresignedUploadPartRequest sign(Allocation a, int number, byte[] bytes, boolean length,
                                    boolean checksum, Clock clock, Duration duration) throws IOException {
        var request = UploadPartRequest.builder().bucket(properties.bucket()).key(a.key)
                .uploadId(a.uploadId).partNumber(number);
        if (length) { request.contentLength((long) bytes.length); }
        if (checksum) { request.checksumSHA256(sha(bytes)); }
        try (var presigner = signer(clock)) {
            var signed = presigner.presignUploadPart(b -> b.signatureDuration(duration).uploadPartRequest(request.build()));
            // SDK 2.48.0's public S3Presigner has no clock override. Only the characterization
            // request is re-signed with the SDK's S3 SigV4 signer; no machine clock is changed.
            if (!clock.equals(Clock.systemUTC())) {
                var unsignedQuery = new HashMap<>(signed.httpRequest().rawQueryParameters());
                List.of("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires",
                        "X-Amz-SignedHeaders", "X-Amz-Signature").forEach(unsignedQuery::remove);
                var unsigned = SdkHttpFullRequest.builder().uri(signed.httpRequest().getUri())
                        .method(signed.httpRequest().method()).headers(signed.httpRequest().headers())
                        .rawQueryParameters(unsignedQuery).build();
                var adjusted = AwsS3V4Signer.create().presign(unsigned, Aws4PresignerParams.builder()
                        .awsCredentials(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()))
                        .signingRegion(Region.of(properties.region())).signingName("s3")
                        .doubleUrlEncode(false).signingClockOverride(clock)
                        .expirationTime(clock.instant().plus(duration)).build());
                signed = PresignedUploadPartRequest.builder().expiration(clock.instant().plus(duration))
                        .httpRequest(adjusted).signedHeaders(signed.signedHeaders()).isBrowserExecutable(false).build();
            }
            event("SIGNED_HEADER_NAMES", String.join(",", new TreeSet<>(signed.signedHeaders().keySet())));
            return signed;
        }
    }

    PresignedUploadPartRequest sign(Allocation a, int number, byte[] bytes, boolean length, boolean checksum)
            throws IOException {
        return sign(a, number, bytes, length, checksum, garageClock, Duration.ofMinutes(2));
    }

    Wire send(PresignedUploadPartRequest signed, URI uri, byte[] bytes) throws Exception {
        // Blob of empty MIME type: Origin + checksum; browser supplies Host and Content-Length.
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Origin", "http://localhost:3000");
        signed.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                values.forEach(value -> request.header(name, value));
            }
        });
        HttpResponse<String> response;
        var target = allocations.stream().filter(a -> uri.getPath().endsWith("/" + a.key)).findFirst();
        target.ifPresent(a -> a.activeRequests.incrementAndGet());
        try {
            response = http.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) {
            if (target.isPresent()) { target.get().uncertain = true; }
            else { retain = true; }
            event("PART_HTTP_UNKNOWN", target.map(a -> a.key).orElse("UNMAPPED"));
            if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            return new Wire(0, "TRANSPORT_UNKNOWN", "", "");
        } finally { target.ifPresent(a -> a.activeRequests.decrementAndGet()); }
        Wire wire = new Wire(response.statusCode(), xmlCode(response.body()),
                response.headers().firstValue("etag").orElse(""),
                response.headers().firstValue("x-amz-checksum-sha256").orElse(""));
        event("BROWSER_SHAPED_HTTP_" + wire.status(), "code=" + wire.code() + " size=" + bytes.length
                + " etag=" + wire.etag() + " sha256=" + wire.checksum());
        return wire;
    }

    void assertBytes(Allocation a, byte[] expected) throws IOException {
        try {
            var response = s3.getObjectAsBytes(b -> b.bucket(properties.bucket()).key(a.key));
            event("GET_OBSERVED", "size=" + response.asByteArray().length + " sha256=" + sha(response.asByteArray()));
            assertThat(Arrays.equals(response.asByteArray(), expected)).as("GET bytes equal expected payload").isTrue();
            assertThat(response.response().metadata()).containsEntry("vaultdrive-m0-run", run);
            event("GET_HTTP_" + response.response().sdkHttpResponse().statusCode(),
                    "size=" + expected.length + " sha256=" + sha(expected));
        } catch (RuntimeException e) { throw sanitized(e); }
    }

    void noObject(Allocation a) throws IOException {
        try {
            s3.getObjectAsBytes(b -> b.bucket(properties.bucket()).key(a.key));
            throw new AssertionError("Unexpected completed test object");
        } catch (S3Exception e) {
            assertThat(e.statusCode()).isEqualTo(404);
            assertThat(code(e)).isEqualTo("NoSuchKey");
            event("GET_HTTP_404", "NoSuchKey; observation only, not writer proof");
        }
    }

    synchronized void event(String event, String detail) throws IOException {
        byte[] line = (Instant.now() + "\t" + event + "\t" + detail.replace('\n', ' ').replace('\r', ' ') + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try (var channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) { channel.write(buffer); }
            channel.force(true);
        }
    }

    @Override public void close() throws Exception {
        try {
            if (Thread.currentThread().isInterrupted()) {
                event("RETAINED", "interrupted verifier; no cleanup"); return;
            }
            assertBytes(control, CONTROL);
            // A failed/uncertain run deliberately retains every allocation and its journal.
            if (retain) { event("RETAINED", "explicit review required; no automatic cleanup"); return; }
            for (Allocation a : allocations) {
                if (a.uncertain || a.activeRequests.get() != 0 || a.uploadId == null
                        || (a.completionDispatched && !a.completed)) {
                    a.uncertain = true;
                    event("RETAINED", a.key); continue;
                }
                if (!a.completed && !a.aborted) { abort(a); }
                if (a.completed) {
                    // Only confirmed completed test objects; no issued part handlers remain unjoined.
                    s3.deleteObject(b -> b.bucket(properties.bucket()).key(a.key));
                    event("DELETE_ACK", a.key);
                    noObject(a); // Postflight observation, not the authority for DELETE.
                }
            }
            event("POSTFLIGHT", "retained=" + allocations.stream().filter(a -> a.uncertain).count()
                    + " allocations=" + allocations.size());
        } finally { s3.close(); http.close(); }
    }

    static String sha(byte[] bytes) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static String code(S3Exception e) { return e.awsErrorDetails() == null ? "UNKNOWN" : e.awsErrorDetails().errorCode(); }
    static RuntimeException sanitized(RuntimeException e) {
        return new IllegalStateException(e instanceof S3Exception s ? "Garage HTTP " + s.statusCode() + " " + code(s)
                : "Storage outcome unresolved: " + e.getClass().getSimpleName());
    }
    static String xmlCode(String xml) {
        int start = xml.indexOf("<Code>"), end = xml.indexOf("</Code>");
        return start < 0 || end < start ? "" : xml.substring(start + 6, end).replaceAll("[^A-Za-z0-9]", "");
    }
    static String env(String name, String fallback) { String v = System.getenv(name); return v == null ? fallback : v; }
    static String bindingEvidence(S3StorageProperties p) {
        return p.endpoint() + " region=" + p.region() + " bucket=" + p.bucket();
    }
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException("Missing " + name); }
        return value;
    }

    /** Real HTTP request held after a nonempty prefix; no claim about Garage's internal read point. */
    static final class GatedPart implements AutoCloseable {
        final Socket socket;
        final OutputStream output;
        final InputStream input;
        final byte[] bytes;
        final int prefix;
        boolean finished;
        GatedPart(PresignedUploadPartRequest signed, byte[] bytes) throws Exception {
            URI uri = signed.url().toURI();
            assertThat(uri.getScheme()).as("raw gate deliberately supports current HTTP test endpoint only").isEqualTo("http");
            this.bytes = bytes;
            // Hold before the first complete storage block. This explores a handler that
            // may have captured Uploading state but has not installed a replacement part.
            prefix = 1;
            socket = new Socket(); socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 2000);
            socket.setSoTimeout(15000); output = socket.getOutputStream(); input = socket.getInputStream();
            StringBuilder headers = new StringBuilder("PUT " + uri.getRawPath() + "?" + uri.getRawQuery() + " HTTP/1.1\r\n");
            signed.signedHeaders().forEach((k, values) -> values.forEach(v -> headers.append(k).append(": ").append(v).append("\r\n")));
            if (!signed.signedHeaders().containsKey("content-length")) { headers.append("Content-Length: ").append(bytes.length).append("\r\n"); }
            headers.append("Origin: http://localhost:3000\r\nConnection: close\r\nExpect: 100-continue\r\n\r\n");
            output.write(headers.toString().getBytes(StandardCharsets.US_ASCII)); output.flush();
            assertThat(line(input)).as("Garage acknowledged request headers").contains("100 Continue");
            while (!line(input).isEmpty()) { /* consume interim headers */ }
            output.write(bytes, 0, prefix); output.flush();
        }
        int finish() throws Exception {
            try {
                output.write(bytes, prefix, bytes.length - prefix); output.flush();
                String status = line(input);
                assertThat(status).startsWith("HTTP/1.1 ");
                int code = Integer.parseInt(status.split(" ")[1]);
                input.readAllBytes(); finished = true;
                return code;
            } finally { socket.close(); }
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1;) {
            if (b == '\n') { return bytes.toString(StandardCharsets.US_ASCII).replace("\r", ""); }
            if (bytes.size() > 65536) { throw new IOException("Bounded HTTP line exceeded"); }
            bytes.write(b);
        }
        throw new EOFException("HTTP connection ended");
    }
}
