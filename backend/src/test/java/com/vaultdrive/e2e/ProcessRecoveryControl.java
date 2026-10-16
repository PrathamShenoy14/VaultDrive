package com.vaultdrive.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.vaultdrive.e2e.ProcessRecoveryProtocol.*;
import static com.vaultdrive.e2e.ProcessRecoveryApplication.require;

/** Inspection/cooperative-stop/owned-cleanup only; this command has no forced termination capability. */
public final class ProcessRecoveryControl {
    public static void main(String[] args) throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.OFF);
        try { run(args); }
        catch (Throwable failure) {
            System.err.println("PROCESS_RECOVERY controlFailed=" + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    private static void run(String[] args) throws Exception {
        require(args.length == 2 && List.of("inspect", "stop", "cleanup").contains(args[0]), "Usage: inspect|stop|cleanup journal-directory");
        Path root = Path.of("target", "process-recovery").toRealPath();
        Path directory = Path.of(args[1]).toRealPath();
        require(directory.getParent().equals(root), "Journal must be directly inside target/process-recovery");
        var run = read(directory.resolve("run.properties"));
        String schema = run.getProperty("schema");
        UUID token = UUID.fromString(run.getProperty("token"));
        require(schema.matches("async_e2e_[0-9a-f]{32}") && directory.getFileName().toString().equals(schema), "Invalid run boundary");
        require(Files.exists(directory.resolve("resources-owned")), "No durable resource ownership proof; retain for private inspection");
        boolean inspect = args[0].equals("inspect");
        for (String mode : List.of("initial", "restart", "cleanup")) {
            Path marker = directory.resolve(mode + "-launched");
            if (!Files.exists(marker)) { continue; }
            String[] parts = Files.readString(marker).split("\\|");
            var identity = new Identity(Long.parseLong(parts[0]), Instant.parse(parts[1]), UUID.fromString(parts[2]), parts[3]);
            require(identity.token().equals(token) && identity.schema().equals(schema), "Process journal identity mismatch");
            var process = ProcessHandle.of(identity.pid());
            boolean alive = process.isPresent() && process.orElseThrow().isAlive();
            System.out.printf("PROCESS_RECOVERY mode=%s identity=%s alive=%s%n", mode, identity.permit(), alive);
            if (!alive) { continue; }
            identity.requireOwned(process.orElseThrow(), directory);
            if (!inspect) {
                if (!Files.exists(directory.resolve("stop"))) { write(directory.resolve("stop"), "true"); }
                await("cooperative test process stop", Duration.ofSeconds(30), () -> !process.orElseThrow().isAlive());
            }
        }
        System.out.printf("PROCESS_RECOVERY schema=%s vhost=vaultdrive_outbox_test point=%s hit=%s cleaned=%s%n",
                schema, run.getProperty("point"), Files.exists(directory.resolve("hit")), Files.exists(directory.resolve("resources-cleaned")));
        if (!args[0].equals("cleanup") || Files.exists(directory.resolve("resources-cleaned"))) { return; }
        AsyncPurgeEndToEndTest.Isolation.requirePrivateTestEnvironment(System.getenv());
        if (!Files.exists(directory.resolve("objects-cleaned"))) {
            // A previous uncertain cleanup process is never silently replaced or reaped.
            require(!Files.exists(directory.resolve("cleanup-launched")), "Previous cleanup needs private inspection; refusing to overwrite its identity");
            var cleanup = AsyncPurgeProcessRecoveryTest.launch(directory, token, "cleanup");
            require(cleanup.waitFor(90, TimeUnit.SECONDS) && cleanup.exitValue() == 0
                    && Files.exists(directory.resolve("objects-cleaned")), "Cleanup failed; retain schema/topology/journal");
        }
        var isolation = new AsyncPurgeEndToEndTest.Isolation(schema);
        isolation.database = DriverManager.getConnection("jdbc:postgresql://localhost:5432/vaultdrive_test?connectTimeout=3&socketTimeout=5",
                System.getenv().getOrDefault("DB_USERNAME", "vaultdrive"), System.getenv("DB_PASSWORD"));
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost("127.0.0.1"); factory.setPort(5672); factory.setVirtualHost("vaultdrive_outbox_test");
        factory.setUsername(System.getenv("RABBITMQ_USERNAME")); factory.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        factory.setConnectionTimeout(3000); factory.setHandshakeTimeout(5000); factory.setChannelRpcTimeout(5000);
        factory.setAutomaticRecoveryEnabled(false);
        try {
            isolation.broker = factory.newConnection("isolated-process-recovery-cleanup");
            try (var channel = isolation.broker.createChannel()) {
                require(channel.queueDeclarePassive(isolation.queue).getConsumerCount() == 0, "Owned queue still has consumers; refusing cleanup");
            }
            isolation.schemaOwned = true; isolation.queueOwned = true; isolation.exchangeOwned = true;
            isolation.close();
            write(directory.resolve("resources-cleaned"), "true");
        } finally {
            if (isolation.broker != null && isolation.broker.isOpen()) { isolation.broker.close(1000); }
            if (isolation.database != null && !isolation.database.isClosed()) { isolation.database.close(); }
        }
    }
}
