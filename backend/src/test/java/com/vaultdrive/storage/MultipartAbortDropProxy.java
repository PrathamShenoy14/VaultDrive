package com.vaultdrive.storage;

import com.vaultdrive.storage.config.S3StorageConfig;
import com.vaultdrive.storage.config.S3StorageProperties;
import software.amazon.awssdk.services.s3.S3Client;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static com.vaultdrive.storage.GarageMultipartFixture.line;
import static org.assertj.core.api.Assertions.assertThat;

/** Exactly one DELETE ?uploadId request; independently observe Garage's 204, drop the entire response. */
final class MultipartAbortDropProxy implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final S3Client client;
    final Future<?> handler;
    MultipartAbortDropProxy(GarageMultipartFixture f) throws IOException {
        URI endpoint = URI.create(f.properties.endpoint()); assertThat(endpoint.getScheme()).isEqualTo("http");
        var p = f.properties;
        client = new S3StorageConfig().s3Client(new S3StorageProperties("http://localhost:" + server.getLocalPort(),
                p.region(), p.bucket(), p.accessKey(), p.secretKey()));
        handler = executor.submit(() -> {
            try (Socket downstream = server.accept(); Socket upstream = new Socket()) {
                downstream.setSoTimeout(10000);
                String requestLine = line(downstream.getInputStream());
                assertThat(requestLine).startsWith("DELETE ").contains("uploadId=");
                List<String> headers = new ArrayList<>();
                for (String h; !(h = line(downstream.getInputStream())).isEmpty();) {
                    String lower = h.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("content-length:")) assertThat(h.substring(h.indexOf(':') + 1).trim()).isEqualTo("0");
                    assertThat(lower.startsWith("transfer-encoding:")).isFalse();
                    if (!lower.startsWith("connection:")) headers.add(h);
                }
                upstream.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), 2000); upstream.setSoTimeout(10000);
                upstream.getOutputStream().write((requestLine + "\r\n" + String.join("\r\n", headers)
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                upstream.getOutputStream().flush();
                String response = new String(upstream.getInputStream().readNBytes(65536), StandardCharsets.UTF_8);
                assertThat(response).startsWith("HTTP/1.1 204");
                f.event("ABORT_UPSTREAM_ACK_DROPPED", "http=204 requests=1; verifier fact only");
            } catch (Exception e) { throw new IllegalStateException("Abort proxy failed: " + e.getClass().getSimpleName()); }
        });
    }
    void verifyAccepted() throws Exception { handler.get(15, TimeUnit.SECONDS); }
    @Override public void close() throws Exception {
        client.close(); server.close(); executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
}
