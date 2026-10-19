package com.vaultdrive.e2e;

import com.rabbitmq.client.Channel;
import com.vaultdrive.VaultDriveApplication;
import com.vaultdrive.outbox.OutboxTransport;
import com.vaultdrive.outbox.PublishResult;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageKeyGenerator;
import com.vaultdrive.storage.StorageTarget;
import com.vaultdrive.storage.config.S3StorageProperties;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.vaultdrive.e2e.ProcessRecoveryProtocol.*;

/** Separately launched JVM. Never packaged in the application artifact or component-scanned. */
public final class ProcessRecoveryApplication {
    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable failure) {
            // Framework/SQL/storage exception messages can contain sensitive values.
            System.err.println("PROCESS_RECOVERY childFailed=" + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    private static void run(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath();
        UUID token = UUID.fromString(args[1]);
        var run = read(directory.resolve("run.properties"));
        if (!token.toString().equals(run.getProperty("token"))) { throw new IllegalStateException("Journal token mismatch"); }
        String schema = run.getProperty("schema");
        require(directory.toRealPath().getParent().equals(Path.of("target", "process-recovery").toRealPath())
                && directory.getFileName().toString().equals(schema) && Files.exists(directory.resolve("resources-owned")),
                "Child requires a fresh parent-owned isolation journal");
        String mode = args[2];
        if (!Set.of("initial", "restart", "cleanup").contains(mode)) { throw new IllegalArgumentException("Invalid child mode"); }
        boolean initial = mode.equals("initial"), cleanup = mode.equals("cleanup");
        Point point = Point.valueOf(run.getProperty("point"));
        var gate = new Gate(directory, initial ? point : null);
        AsyncPurgeEndToEndTest.Isolation.requirePrivateTestEnvironment(System.getenv());
        var boundary = new AsyncPurgeEndToEndTest.Isolation(schema);
        var app = new SpringApplication(VaultDriveApplication.class, DisposableKeys.class);
        app.setAdditionalProfiles("test");
        var reference = new AtomicReference<ConfigurableApplicationContext>();
        var gates = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        app.addInitializers(ctx -> {
            reference.set(ctx);
            ctx.getBeanFactory().registerSingleton("processJournal", directory);
            ctx.getBeanFactory().registerSingleton("processSchema", schema);
            // Added after Spring's auto-proxy creator: our outer advice runs AFTER transaction commit.
            ctx.getBeanFactory().registerSingleton("processBoundaryPostProcessor", new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (Set.of("outboxPublisher", "purgeNotificationContainer", "jobWorker", "missingJobPoller").contains(name)) {
                        var db = ctx.getBean(JdbcTemplate.class);
                        db.setQueryTimeout(3);
                        require("vaultdrive_test".equals(db.queryForObject("SELECT current_database()", String.class)), "Wrong database");
                        require(schema.equals(db.queryForObject("SELECT current_schema()", String.class)), "Wrong schema");
                        require(db.queryForList("SELECT unnest(current_schemas(false))", String.class).equals(List.of(schema)), "Wrong search path");
                        if (initial) {
                            for (String table : List.of("outbox_events", "background_jobs", "users", "files", "folders")) {
                                require(db.queryForObject("SELECT count(*) FROM " + table, Long.class) == 0, "Nonempty initial boundary");
                            }
                        } else {
                            require(Files.exists(directory.resolve("fixture.properties")), "Restart requires fixture journal");
                            require(db.queryForObject("SELECT count(*) FROM outbox_events", Long.class) == 1, "Unexpected restart intent");
                            try {
                                UUID event = UUID.fromString(read(directory.resolve("fixture.properties")).getProperty("event"));
                                require(db.queryForObject("SELECT count(*) FROM outbox_events WHERE id = ?", Long.class, event) == 1, "Restart event identity mismatch");
                            } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot verify restart identity"); }
                        }
                        gates.add(name);
                    }
                    return bean;
                }
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (name.equals("purgeNotificationContainer")) {
                        var container = (SimpleMessageListenerContainer) bean;
                        var listener = (ChannelAwareMessageListener) container.getMessageListener();
                        container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
                            Channel observed = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[]{Channel.class},
                                    (proxy, method, parameters) -> {
                                        try {
                                            if (method.getName().equals("basicAck")) {
                                                UUID event = UUID.fromString(message.getMessageProperties().getMessageId());
                                                require(ctx.getBean(JdbcTemplate.class).queryForObject(
                                                        "SELECT count(*) FROM background_jobs WHERE id = ?", Long.class, event) == 1,
                                                        "ACK before independently visible job commit");
                                                gate.hit(Point.ACK);
                                            }
                                            Object result = method.invoke(channel, parameters);
                                            if (method.getName().equals("basicAck")) {
                                                Files.createTempFile(directory, "ack-", ".marker");
                                                if (Boolean.TRUE.equals(message.getMessageProperties().isRedelivered())) {
                                                    Files.createTempFile(directory, "redelivery-", ".marker");
                                                }
                                            }
                                            return result;
                                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    });
                            listener.onMessage(message, observed);
                        });
                        return bean;
                    }
                    if (!Set.of("outboxClaimService", "outboxTransport", "backgroundJobService", "s3ObjectStorageService",
                            "filePurgeProgressService", "folderPurgeFileService", "folderPurgeDiscoveryService", "folderPurgeMetadataService").contains(name)) { return bean; }
                    var factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(true);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        String method = invocation.getMethod().getName();
                        if (name.equals("backgroundJobService") && method.equals("renew") && gate.held()) { gate.waitForRelease(); }
                        if (initial && point == Point.INTENT && name.equals("outboxClaimService") && method.equals("claimNext")) {
                            // Do not let an empty observation race the first committed intent into claimNext.
                            // Hold the first claim outside any transaction until the HTTP fixture commits.
                            await("committed intent before first publication claim", Duration.ofSeconds(90),
                                    () -> Files.exists(directory.resolve("stop")) || ctx.getBean(JdbcTemplate.class)
                                            .queryForObject("SELECT count(*) FROM outbox_events", Long.class) == 1);
                            gate.hit(Point.INTENT);
                        }
                        Object result = invocation.proceed();
                        if (name.equals("outboxTransport") && method.equals("publish") && result == PublishResult.CONFIRMED) { gate.hit(Point.CONFIRM); }
                        if (name.equals("outboxClaimService") && method.equals("confirmed") && Boolean.TRUE.equals(result)) { gate.hit(Point.MISSING_JOB); }
                        if (name.equals("backgroundJobService") && method.equals("claimNext") && ((java.util.Optional<?>) result).isPresent()) {
                            var claim = (com.vaultdrive.job.ClaimedJob) ((java.util.Optional<?>) result).orElseThrow();
                            Files.createTempFile(directory, "claimed-" + mode + "-" + claim.claimToken() + "-", ".marker");
                            gate.hit(Point.CLAIM);
                        }
                        if (name.equals("s3ObjectStorageService") && method.equals("delete") && invocation.getArguments()[0] instanceof StorageTarget target) {
                            require(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(), "Storage delete inside transaction");
                            UUID file = ctx.getBean(JdbcTemplate.class).queryForObject("SELECT file_id FROM file_storage_objects WHERE storage_key = ?", UUID.class, target.key());
                            Files.createTempFile(directory, "deleted-" + file + "-", ".marker");
                            gate.hit(Point.DELETE);
                        }
                        if (name.equals("filePurgeProgressService") && method.equals("recordBytesDeleted")) { gate.hit(Point.RECEIPT); }
                        if (name.equals("folderPurgeFileService") && method.equals("completeFile") && Boolean.TRUE.equals(result)) { gate.hit(Point.RECEIPT); }
                        if (name.equals("folderPurgeDiscoveryService") && method.equals("discover")) { gate.hit(Point.DISCOVERY); }
                        if (name.equals("folderPurgeMetadataService") && method.equals("removeBatch")) {
                            var db = ctx.getBean(JdbcTemplate.class);
                            if (db.queryForObject("SELECT count(*) FROM folder_purge_runs WHERE phase = 'FINALIZED'", Long.class) == 1) { gate.hit(Point.FINALIZED); }
                            else if (db.queryForObject("SELECT count(*) FROM folder_purge_removals", Long.class) > 0) { gate.hit(Point.CHILD_REMOVAL); }
                        }
                        return result;
                    });
                    return factory.getProxy();
                }
            });
        });
        var properties = new ArrayList<>(List.of(boundary.arguments()));
        // Explicit overrides prevent inherited development enablement/timing values from widening scope.
        properties.add("--vaultdrive.outbox.publisher.enabled=" + !cleanup);
        properties.add("--vaultdrive.purge.consumer.enabled=" + (!cleanup && point != Point.MISSING_JOB && (!initial || point != Point.CONFIRM)));
        properties.add("--vaultdrive.jobs.worker.enabled=" + (!cleanup && (!initial || point != Point.ACK)));
        properties.add("--vaultdrive.jobs.reconciliation.enabled=" + (!cleanup && !initial && point == Point.MISSING_JOB));
        properties.add("--vaultdrive.jobs.reconciliation.eligibility-delay=5m");
        properties.add("--vaultdrive.jobs.reconciliation.poll-delay=1s");
        properties.add("--vaultdrive.outbox.publisher.lease=30s");
        properties.add("--vaultdrive.outbox.publisher.initial-backoff=1s");
        properties.add("--vaultdrive.outbox.publisher.max-backoff=60s");
        properties.add("--logging.level.root=OFF");
        try {
            var uniqueProperties = new java.util.LinkedHashMap<String, String>();
            for (String property : properties) { uniqueProperties.put(property.substring(0, property.indexOf('=')), property); }
            var ctx = app.run(uniqueProperties.values().toArray(String[]::new));
            require(cleanup || gates.contains("outboxPublisher"), "Publisher isolation gate missing");
            if (cleanup) {
                cleanupObjects(ctx, directory, schema);
                write(directory.resolve("objects-cleaned"), "true");
                return;
            }
            var identity = new Identity(ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant().orElseThrow(), token, schema);
            write(directory.resolve(mode + "-identity"), identity.permit());
            if (initial) { fixture(ctx, directory, Boolean.parseBoolean(run.getProperty("folder"))); }
            write(directory.resolve(mode + "-ready"), "true");
            long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
            while (!Files.exists(directory.resolve(mode + "-stop")) && !Files.exists(directory.resolve("stop"))) {
                require(System.nanoTime() < deadline, "Test process safety lifetime elapsed");
                try (var challenges = Files.newDirectoryStream(directory, "challenge-*")) {
                    for (Path challenge : challenges) {
                        Path response = directory.resolve(challenge.getFileName().toString().replace("challenge-", "response-"));
                        if (!Files.exists(response) && Files.readString(challenge).equals(identity.permit())) {
                            write(response, identity.permit());
                        }
                    }
                }
                if (Files.exists(directory.resolve("replay-request")) && !Files.exists(directory.resolve("replay-done"))) {
                    replay(ctx, boundary);
                    write(directory.resolve("replay-done"), "true");
                }
                if (Files.exists(directory.resolve("verify-request")) && !Files.exists(directory.resolve("verified"))) {
                    verifyStorageAndFences(ctx, directory, schema);
                    write(directory.resolve("verified"), "true");
                }
                if (Files.exists(directory.resolve("boundary-request")) && !Files.exists(directory.resolve("boundary-verified"))) {
                    verifyDeletedObjects(ctx, directory, schema, true);
                    write(directory.resolve("boundary-verified"), "true");
                }
                Thread.sleep(50);
            }
        } finally {
            var ctx = reference.get();
            if (ctx != null) {
                try {
                    if (ctx.isActive()) {
                        if (ctx.containsBean("jobWorker")) { ctx.getBean(com.vaultdrive.worker.JobWorker.class).stopClaiming(); }
                        if (ctx.containsBean("taskScheduler")) { ctx.getBean(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).shutdown(); }
                        if (ctx.containsBean("purgeNotificationContainer")) { ctx.getBean("purgeNotificationContainer", SimpleMessageListenerContainer.class).stop(); }
                    }
                } finally { ctx.close(); }
            }
        }
    }

    private static void fixture(ConfigurableApplicationContext ctx, Path directory, boolean folder) throws Exception {
        var api = new AsyncPurgeEndToEndTest();
        api.jdbc = ctx.getBean(JdbcTemplate.class);
        api.mapper = ctx.getBean(tools.jackson.databind.ObjectMapper.class);
        api.base = "http://127.0.0.1:" + ((WebServerApplicationContext) ctx).getWebServer().getPort() + "/api/v1";
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            api.http = http;
            api.registerAndLogin();
            UUID control = api.upload(null, "control.txt");
            var members = new ArrayList<UUID>();
            UUID resource;
            if (folder) {
                resource = api.folder(null, "root");
                UUID child = api.folder(resource, "child");
                UUID grandchild = api.folder(child, "grandchild");
                members.add(api.upload(resource, "root.txt"));
                members.add(api.upload(child, "child.txt"));
                members.add(api.upload(grandchild, "grandchild.txt"));
                api.request("DELETE", "/folders/" + resource, null, 204);
                api.request("DELETE", "/trash/folders/" + resource + "/permanent", null, 202);
            } else {
                resource = api.upload(null, "standalone.txt");
                members.add(resource);
                api.request("DELETE", "/files/" + resource, null, 204);
                api.request("DELETE", "/files/" + resource + "/permanent", null, 202);
            }
            StringBuilder journal = new StringBuilder("event=" + api.event(resource) + "\nresource=" + resource + "\ncontrol=" + control + "\n");
            for (int i = 0; i < members.size(); i++) { journal.append("file.").append(i).append('=').append(members.get(i)).append('\n'); }
            write(directory.resolve("fixture.properties"), journal.toString());
        }
    }

    static void require(boolean valid, String message) { if (!valid) { throw new IllegalStateException(message); } }

    private static void replay(ConfigurableApplicationContext ctx, AsyncPurgeEndToEndTest.Isolation boundary) throws Exception {
        var consumer = ctx.getBean("purgeNotificationContainer", SimpleMessageListenerContainer.class);
        consumer.stop();
        var event = ctx.getBean(com.vaultdrive.outbox.OutboxEventRepository.class).findAll().getFirst();
        var claim = new com.vaultdrive.outbox.ClaimedOutboxEvent(event.getId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getPayloadVersion(), event.getPayload(), event.getCreatedAt(), UUID.randomUUID(), 1);
        for (int n = 0; n < 2; n++) {
            require(ctx.getBean(OutboxTransport.class).publish(claim) == PublishResult.CONFIRMED, "Duplicate publication unconfirmed");
        }
        var factory = ctx.getBean(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.class).getRabbitConnectionFactory();
        var connection = factory.newConnection("isolated-process-recovery-redelivery");
        try {
            try (var channel = connection.createChannel()) {
                require(channel.basicGet(boundary.queue, false) != null, "No duplicate available for redelivery");
            } // Native channel, not a Spring cached proxy: close truly requeues the unacknowledged delivery.
        } finally { connection.close(1000); }
        consumer.start();
    }

    private static void verifyStorageAndFences(ConfigurableApplicationContext ctx, Path directory, String schema) throws Exception {
        var fixture = read(directory.resolve("fixture.properties"));
        var db = ctx.getBean(JdbcTemplate.class);
        var storage = ctx.getBean(ObjectStorageService.class);
        UUID control = UUID.fromString(fixture.getProperty("control"));
        UUID owner = UUID.fromString(Files.readString(directory.resolve("allocation-" + control)));
        try (var bytes = storage.download(key(schema, owner, control)).inputStream()) {
            require(java.util.Arrays.equals(bytes.readAllBytes(), "disposable continuous purge verification".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "Control bytes changed");
        }
        verifyDeletedObjects(ctx, directory, schema, false);
        Path stale = directory.resolve("stale.properties");
        if (Files.exists(stale)) {
            var values = read(stale);
            var claim = new com.vaultdrive.job.ClaimedJob(UUID.fromString(fixture.getProperty("event")),
                    com.vaultdrive.job.JobType.valueOf(values.getProperty("type")), values.getProperty("aggregateType"),
                    UUID.fromString(fixture.getProperty("resource")), UUID.fromString(values.getProperty("claimToken")),
                    java.time.Instant.parse(values.getProperty("expires")), Long.parseLong(values.getProperty("attempt")));
            var jobs = ctx.getBean(com.vaultdrive.job.BackgroundJobService.class);
            require(!jobs.complete(claim) && !jobs.retry(claim) && !jobs.continueJob(claim) && !jobs.renew(claim), "Stale claim was accepted");
        }
        Path staleOutbox = directory.resolve("stale-outbox.properties");
        if (Files.exists(staleOutbox)) {
            var values = read(staleOutbox);
            var event = ctx.getBean(com.vaultdrive.outbox.OutboxEventRepository.class)
                    .findById(UUID.fromString(fixture.getProperty("event"))).orElseThrow();
            var claim = new com.vaultdrive.outbox.ClaimedOutboxEvent(event.getId(), event.getEventType(), event.getAggregateType(),
                    event.getAggregateId(), event.getPayloadVersion(), event.getPayload(), event.getCreatedAt(),
                    UUID.fromString(values.getProperty("claimToken")), Integer.parseInt(values.getProperty("attempt")));
            var claims = ctx.getBean(com.vaultdrive.outbox.OutboxClaimService.class);
            require(!claims.owns(claim) && !claims.confirmed(claim) && !claims.failed(claim, PublishResult.BROKER_ERROR), "Stale publisher token was accepted");
        }
        require(db.queryForObject("SELECT count(*) FROM background_jobs WHERE status = 'SUCCEEDED'", Long.class) == 1, "Terminal proof changed");
    }

    private static void verifyDeletedObjects(ConfigurableApplicationContext ctx, Path directory, String schema, boolean onlyObserved) throws Exception {
        var fixture = read(directory.resolve("fixture.properties"));
        var s3 = ctx.getBean(S3Client.class);
        var properties = ctx.getBean(S3StorageProperties.class);
        for (String name : fixture.stringPropertyNames()) {
            if (!name.startsWith("file.")) { continue; }
            UUID file = UUID.fromString(fixture.getProperty(name));
            if (onlyObserved) {
                try (var paths = Files.list(directory)) {
                    if (paths.noneMatch(path -> path.getFileName().toString().startsWith("deleted-" + file + "-"))) { continue; }
                }
            }
            UUID fileOwner = UUID.fromString(Files.readString(directory.resolve("allocation-" + file)));
            try {
                s3.headObject(b -> b.bucket(properties.bucket()).key(key(schema, fileOwner, file)));
                throw new IllegalStateException("Original object still present");
            } catch (S3Exception missing) { require(missing.statusCode() == 404, "Cannot confirm original object absence"); }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DisposableKeys {
        @Bean @Primary StorageKeyGenerator processKeys(Path processJournal, String processSchema, S3Client s3, S3StorageProperties properties,
                                                       org.springframework.core.env.Environment environment) throws Exception {
            s3.headBucket(b -> b.bucket(properties.bucket()));
            String binding = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (environment.getRequiredProperty("storage.s3.namespace") + "\n" + properties.endpoint() + "\n" + properties.region() + "\n" + properties.bucket())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Path marker = processJournal.resolve("binding");
            if (Files.exists(marker)) { require(Files.readString(marker).equals(binding), "Storage binding changed; retaining allocations"); }
            else { write(marker, binding); }
            return new StorageKeyGenerator() {
                @Override public String generateFileKey(UUID owner, UUID file) {
                    try {
                        String key = key(processSchema, owner, file);
                        try {
                            s3.headObject(b -> b.bucket(properties.bucket()).key(key));
                            throw new IllegalStateException("Disposable allocation collision");
                        } catch (S3Exception missing) { require(missing.statusCode() == 404, "Cannot prove allocation absence"); }
                        // Force journal to disk BEFORE admitting any PUT, including uncertain/rolled-back uploads.
                        write(processJournal.resolve("allocation-" + file), owner.toString());
                        return key;
                    } catch (Exception failure) { throw new IllegalStateException("Cannot admit disposable allocation"); }
                }
            };
        }
    }

    static String key(String schema, UUID owner, UUID file) { return "integration-tests/" + schema + "/" + owner + "/" + file; }

    private static void cleanupObjects(ConfigurableApplicationContext ctx, Path directory, String schema) throws Exception {
        try (var paths = Files.list(directory)) {
            var allocations = paths.filter(path -> path.getFileName().toString().startsWith("allocation-")).toList();
            require(allocations.size() <= 16, "Allocation cleanup bound exceeded");
            boolean clean = true;
            for (Path allocation : allocations) {
                UUID file = UUID.fromString(allocation.getFileName().toString().substring("allocation-".length()));
                UUID owner = UUID.fromString(Files.readString(allocation));
                try { ctx.getBean(ObjectStorageService.class).delete(ctx.getBean(com.vaultdrive.storage.StorageBinding.class).target(key(schema, owner, file))); }
                catch (RuntimeException failure) { clean = false; }
            }
            require(clean, "Owned object cleanup failed; preserve schema and journal");
        }
    }
}
