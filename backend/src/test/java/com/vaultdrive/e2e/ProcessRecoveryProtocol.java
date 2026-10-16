package com.vaultdrive.e2e;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Test-classpath only. Journals contain UUIDs, process identities and scalar evidence, never keys or secrets. */
final class ProcessRecoveryProtocol {
    enum Point {
        INTENT, CONFIRM, ACK, CLAIM, DISCOVERY, DELETE, RECEIPT, CHILD_REMOVAL, FINALIZED, MISSING_JOB;
        boolean folderOnly() { return this == DISCOVERY || this == CHILD_REMOVAL || this == FINALIZED; }
    }

    record Identity(long pid, Instant started, UUID token, String schema) {
        String permit() { return pid + "|" + started + "|" + token + "|" + schema; }
        void requireOwned(ProcessHandle handle, Path directory) {
            var info = handle.info();
            if (!handle.isAlive() || handle.pid() != pid || !info.startInstant().orElseThrow().equals(started)
                    || !schema.matches("async_e2e_[0-9a-f]{32}")) {
                throw new IllegalStateException("Test process ownership cannot be proven; refusing termination");
            }
            boolean argumentsMatch = info.arguments().map(arguments -> java.util.Arrays.asList(arguments).contains(ProcessRecoveryApplication.class.getName())
                    && java.util.Arrays.asList(arguments).contains(directory.toAbsolutePath().toString())
                    && java.util.Arrays.asList(arguments).contains(token.toString())).orElseGet(() -> journalOwnership(info, directory));
            if (!argumentsMatch || !handle.isAlive() || !handle.info().startInstant().orElseThrow().equals(started)) {
                throw new IllegalStateException("Test process ownership cannot be proven; refusing termination");
            }
        }

        private boolean journalOwnership(ProcessHandle.Info info, Path directory) {
            // Windows Java does not expose arguments. Prove the launcher/child handshake and
            // require a NEW live challenge response, in addition to the immutable PID/start time.
            if (info.command().isEmpty()) { return false; }
            try {
                if (!Path.of(info.command().orElseThrow()).toRealPath().equals(
                        Path.of(System.getProperty("java.home"), "bin", "java.exe").toRealPath())) { return false; }
                var run = read(directory.resolve("run.properties"));
                if (!token.toString().equals(run.getProperty("token")) || !schema.equals(run.getProperty("schema"))) { return false; }
                boolean handshake = false;
                for (String mode : java.util.List.of("initial", "restart")) {
                    Path launched = directory.resolve(mode + "-launched");
                    Path child = directory.resolve(mode + "-identity");
                    if (Files.exists(launched) && Files.exists(child) && Files.readString(launched).equals(permit())
                            && Files.readString(child).equals(permit())) { handshake = true; }
                }
                if (!handshake) { return false; }
                String challenge = UUID.randomUUID().toString();
                Path response = directory.resolve("response-" + challenge);
                write(directory.resolve("challenge-" + challenge), permit());
                await("fresh live test-child ownership response", Duration.ofSeconds(10), () -> Files.exists(response));
                return Files.readString(response).equals(permit());
            } catch (IOException | IllegalStateException failure) { return false; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }

    }

    static void write(Path path, String value) throws IOException {
        // Publish only fully written/forced records. A non-replacing same-directory rename
        // makes accidental journal adoption fail closed and readers never see partial values.
        Path temporary = path.resolveSibling(".writing-" + UUID.randomUUID());
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var bytes = java.nio.ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) { channel.write(bytes); }
                channel.force(true);
            }
            Files.move(temporary, path);
        } finally { Files.deleteIfExists(temporary); }
    }

    static Properties read(Path path) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
        return properties;
    }

    static void consumePermit(Path permit, Identity identity) throws IOException {
        if (!Files.readString(permit, StandardCharsets.UTF_8).strip().equals(identity.permit())) {
            throw new IllegalStateException("Approval permit does not match the reported live test identity");
        }
        Files.move(permit, permit.resolveSibling("permit-consumed"));
    }

    static void await(String label, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) { throw new IllegalStateException("Timed out: " + label); }
            Thread.sleep(50);
        }
    }

    static final class Gate {
        private final Path directory;
        private final Point selected;
        private final AtomicBoolean reached = new AtomicBoolean();
        Gate(Path directory, Point selected) { this.directory = directory; this.selected = selected; }
        boolean held() { return reached.get() && !Files.exists(directory.resolve("release")); }
        void hit(Point point) throws Exception {
            if (point != selected) { return; }
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Failpoint must follow transaction completion");
            }
            if (reached.compareAndSet(false, true)) { write(directory.resolve("hit"), point.name()); }
            if (held()) { waitForRelease(); }
        }
        void waitForRelease() throws InterruptedException {
            // Cancellation cannot accidentally release the boundary. A bounded self-stop owns shutdown.
            boolean interrupted = false;
            long end = System.nanoTime() + Duration.ofMinutes(15).toNanos();
            while (held() && !Files.exists(directory.resolve("stop")) && System.nanoTime() < end) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted || held()) { throw new InterruptedException("Test boundary cancelled"); }
        }
    }

    private ProcessRecoveryProtocol() { }
}
