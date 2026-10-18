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

/** One test-only completion request: relay to real Garage, consume response, drop downstream response. */
final class MultipartCompletionDropProxy implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final S3Client client;
    final Future<?> handler;
    final GarageMultipartFixture f;
    MultipartCompletionDropProxy(GarageMultipartFixture f) throws IOException {
        this.f = f;
        URI endpoint = URI.create(f.properties.endpoint());
        assertThat(endpoint.getScheme()).isEqualTo("http");
        var p = f.properties;
        client = new S3StorageConfig().s3Client(new S3StorageProperties("http://localhost:" + server.getLocalPort(),
                p.region(), p.bucket(), p.accessKey(), p.secretKey()));
        handler = executor.submit(() -> {
            try (Socket downstream = server.accept(); Socket upstream = new Socket()) {
                downstream.setSoTimeout(10000);
                String requestLine = line(downstream.getInputStream());
                assertThat(requestLine).startsWith("POST ");
                List<String> headers = new ArrayList<>(); int length = -1; boolean expect = false;
                for (String h; !(h = line(downstream.getInputStream())).isEmpty();) {
                    String lower = h.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("content-length:")) { length = Integer.parseInt(h.substring(h.indexOf(':') + 1).trim()); }
                    if (lower.startsWith("expect:")) { expect = true; continue; }
                    if (!lower.startsWith("connection:")) { headers.add(h); }
                }
                assertThat(length).isBetween(1, 65536);
                if (expect) { downstream.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); downstream.getOutputStream().flush(); }
                byte[] body = downstream.getInputStream().readNBytes(length);
                assertThat(body).hasSize(length);
                upstream.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), 2000);
                upstream.setSoTimeout(10000);
                upstream.getOutputStream().write((requestLine + "\r\n" + String.join("\r\n", headers)
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                upstream.getOutputStream().write(body); upstream.getOutputStream().flush();
                byte[] response = upstream.getInputStream().readNBytes(65536);
                String wire = new String(response, StandardCharsets.UTF_8);
                assertThat(wire.startsWith("HTTP/1.1 200") && wire.contains("CompleteMultipartUploadResult")
                        && !wire.contains("<Error>")).as("valid upstream completion response").isTrue();
                f.event("COMPLETE_UPSTREAM_ACK_DROPPED", "http=200 valid_result=true requests=1");
                // Deliberately send no status/headers/body to the caller.
            } catch (Exception e) { throw new IllegalStateException("Completion proxy failed: " + e.getClass().getSimpleName()); }
        });
    }
    void verifyAccepted() throws Exception { handler.get(15, TimeUnit.SECONDS); }
    @Override public void close() throws Exception {
        client.close(); server.close(); executor.shutdownNow();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
}
