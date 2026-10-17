package com.vaultdrive.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Loopback-only HTTP/1.1 receiver. Counts parsed requests, never SDK method calls. */
final class ControlledPutServer implements AutoCloseable {
    enum Mode { SUCCESS, HTTP_500, HTTP_503, DISCONNECT_DURING_BODY, READ_TIMEOUT,
        REDIRECT_301, REDIRECT_302, REDIRECT_307, REDIRECT_308, DROP_RESPONSE, HOLD_SUCCESS }
    record Received(String method, String target) { }

    private final ServerSocket listener;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> effects = new ConcurrentHashMap<>();
    private final List<String> completeBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SUCCESS);
    private volatile boolean closed;
    private volatile int expected = 1;
    private volatile CountDownLatch handled = new CountDownLatch(1);
    private volatile CountDownLatch entered = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);
    private volatile CountDownLatch extraRequest = new CountDownLatch(1);
    private volatile String redirectEndpoint;

    static ControlledPutServer start() {
        try { return new ControlledPutServer(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private ControlledPutServer() throws IOException {
        listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        executor.submit(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept();
                    sockets.add(socket);
                    executor.submit(() -> handle(socket));
                } catch (IOException failure) {
                    if (!closed) { failures.add(failure); }
                }
            }
        });
    }

    String endpoint() { return "http://127.0.0.1:" + listener.getLocalPort(); }

    void scenario(Mode next, int expectedRequests, String redirect) {
        assertThat(sockets).as("previous handlers finished before resetting evidence").isEmpty();
        received.clear(); completeBodies.clear(); effects.clear(); failures.clear();
        expected = expectedRequests;
        handled = new CountDownLatch(expectedRequests);
        entered = new CountDownLatch(1); release = new CountDownLatch(1); extraRequest = new CountDownLatch(1);
        redirectEndpoint = redirect;
        mode.set(next);
    }

    void nextResponse(Mode next) { mode.set(next); }
    void releaseResponse() { release.countDown(); }
    boolean awaitEntered() throws InterruptedException { return entered.await(10, TimeUnit.SECONDS); }
    List<Received> received() { return List.copyOf(received); }
    int completeBodies() { return completeBodies.size(); }
    int effects() { return effects.size(); }

    void assertCounts(int puts) throws InterruptedException {
        releaseResponse();
        assertThat(handled.await(10, TimeUnit.SECONDS)).as("receiver handlers finished").isTrue();
        // Synchronous retries must finish before the SDK returns. Also observe delayed arrivals.
        assertThat(extraRequest.await(300, TimeUnit.MILLISECONDS)).as("unexpected extra HTTP request").isFalse();
        assertThat(failures).as("controlled server failures").isEmpty();
        assertThat(received).hasSize(puts).allSatisfy(request -> assertThat(request.method()).isEqualTo("PUT"));
    }

    private void handle(Socket socket) {
        Mode selected = mode.get();
        try (socket) {
            socket.setSoTimeout(15_000);
            InputStream input = socket.getInputStream();
            String[] start = line(input).split(" ", 3);
            if (start.length != 3) { throw new IOException("Incomplete HTTP request line"); }
            Map<String, String> headers = new HashMap<>();
            for (String header; !(header = line(input)).isEmpty();) {
                int colon = header.indexOf(':');
                if (colon <= 0) { throw new IOException("Invalid HTTP header"); }
                headers.put(header.substring(0, colon).toLowerCase(java.util.Locale.ROOT), header.substring(colon + 1).trim());
            }
            // Only method/target survive parsing. Never retain or print authentication headers.
            received.add(new Received(start[0], start[1]));
            if (received.size() > expected) { extraRequest.countDown(); }
            entered.countDown();
            if (!"PUT".equals(start[0])) { respond(socket, 405, "", null); return; }
            if ("100-continue".equalsIgnoreCase(headers.get("expect"))) {
                socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            if (selected == Mode.DISCONNECT_DURING_BODY) {
                if (input.read() < 0) { throw new IOException("Missing request body prefix"); }
                socket.setSoLinger(true, 0); // Reset after receiving headers and a body prefix.
                return;
            }
            byte[] body = body(input, headers);
            completeBodies.add(start[1]);
            if (List.of(Mode.SUCCESS, Mode.DROP_RESPONSE, Mode.READ_TIMEOUT, Mode.HOLD_SUCCESS).contains(selected)) {
                effects.put(start[1], body); // Simulated acceptance survives a missing client response.
            }
            if (selected == Mode.DROP_RESPONSE) { return; }
            if (selected == Mode.READ_TIMEOUT || selected == Mode.HOLD_SUCCESS) {
                if (!release.await(15, TimeUnit.SECONDS)) { throw new IOException("Response gate was not released"); }
                if (selected == Mode.READ_TIMEOUT) { return; }
            }
            int status = switch (selected) {
                case HTTP_500 -> 500; case HTTP_503 -> 503;
                case REDIRECT_301 -> 301; case REDIRECT_302 -> 302;
                case REDIRECT_307 -> 307; case REDIRECT_308 -> 308;
                default -> 200;
            };
            String error = status == 200 ? "" : "<Error><Code>" + switch (status) {
                case 500 -> "InternalError"; case 503 -> "SlowDown";
                case 301 -> "PermanentRedirect"; default -> "TemporaryRedirect";
            } + "</Code><Message>Controlled fault</Message></Error>";
            respond(socket, status, error, status >= 300 && status < 400 ? redirectEndpoint : null);
        } catch (Throwable failure) {
            failures.add(failure);
        } finally {
            sockets.remove(socket);
            handled.countDown();
        }
    }

    private static byte[] body(InputStream input, Map<String, String> headers) throws IOException {
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            var bytes = new ByteArrayOutputStream();
            for (;;) {
                int length = Integer.parseInt(line(input).split(";", 2)[0].trim(), 16);
                if (length == 0) {
                    while (!line(input).isEmpty()) { /* Consume trailers. */ }
                    return bytes.toByteArray();
                }
                bytes.write(exact(input, length));
                if (!line(input).isEmpty()) { throw new IOException("Invalid chunk terminator"); }
            }
        }
        return exact(input, Integer.parseInt(headers.getOrDefault("content-length", "0")));
    }

    private static byte[] exact(InputStream input, int length) throws IOException {
        if (length < 0 || length > 2_000_000) { throw new IOException("Unexpected test request size"); }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) { throw new IOException("Truncated request body"); }
        return bytes;
    }

    private static String line(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int next; (next = input.read()) != -1;) {
            if (next == '\n') { return bytes.toString(StandardCharsets.US_ASCII).stripTrailing(); }
            if (bytes.size() >= 16_384) { throw new IOException("HTTP line too long"); }
            bytes.write(next);
        }
        throw new IOException("Unexpected end of HTTP headers");
    }

    private static void respond(Socket socket, int status, String body, String redirect) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " Controlled\r\nConnection: close\r\nContent-Type: application/xml\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + (redirect == null ? "ETag: \"controlled\"\r\n" : "Location: " + redirect + "/redirected\r\nx-amz-bucket-region: other-region\r\n")
                + "\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    @Override public void close() throws Exception {
        closed = true;
        releaseResponse();
        listener.close();
        for (Socket socket : new ArrayList<>(sockets)) { socket.close(); }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
