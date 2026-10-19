package com.vaultdrive.e2e;

import com.rabbitmq.client.Channel;
import com.vaultdrive.VaultDriveApplication;
import com.vaultdrive.outbox.ClaimedOutboxEvent;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.outbox.OutboxTransport;
import com.vaultdrive.outbox.PublishResult;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageKeyGenerator;
import com.vaultdrive.storage.config.S3StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
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
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Opt-in, real HTTP -> outbox -> RabbitMQ -> job -> Garage; no test transaction or mocks. */
@EnabledIfEnvironmentVariable(named = "ASYNC_E2E_TEST", matches = "true")
class AsyncPurgeEndToEndTest {
    private static final byte[] CONTENT = "disposable continuous purge verification".getBytes(StandardCharsets.UTF_8);
    JdbcTemplate jdbc;
    ObjectMapper mapper;
    HttpClient http;
    String base;
    String token;
    private Probe probe;

    @Test
    @Timeout(180)
    void continuouslyPurgesFileAndNestedFolderThroughHttpAndRetainsProof() throws Exception {
        // No Spring context, migrations, uploads or async beans exist until this preflight succeeds.
        try (var isolation = new Isolation()) {
            isolation.establish();
            probe = new Probe(isolation.schema);
            var context = new AtomicReference<ConfigurableApplicationContext>();
            try {
                var app = new SpringApplication(VaultDriveApplication.class, DisposableKeys.class);
                app.setAdditionalProfiles("test");
                app.addInitializers(ctx -> {
                    context.set(ctx); // Also close a partially initialized context on startup failure.
                    ctx.getBeanFactory().registerSingleton("e2eProbe", probe);
                    ctx.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                        @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                            if (Set.of("outboxPublisher", "purgeNotificationContainer", "jobWorker").contains(name)) {
                                var db = ctx.getBean(JdbcTemplate.class);
                                db.setQueryTimeout(3);
                                assertThat(db.queryForObject("SELECT current_database()", String.class)).isEqualTo("vaultdrive_test");
                                assertThat(db.queryForObject("SELECT current_schema()", String.class)).isEqualTo(isolation.schema);
                                assertThat(db.queryForList("SELECT unnest(current_schemas(false))", String.class))
                                        .containsExactly(isolation.schema);
                                for (String table : List.of("outbox_events", "background_jobs", "files", "folders", "users")) {
                                    assertThat(db.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
                                }
                                probe.gates.add(name);
                            }
                            return bean;
                        }
                        @Override public Object postProcessAfterInitialization(Object bean, String name) {
                            if (name.equals("purgeNotificationContainer")) {
                                var container = (SimpleMessageListenerContainer) bean;
                                var listener = (ChannelAwareMessageListener) container.getMessageListener();
                                container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
                                    UUID event = UUID.fromString(message.getMessageProperties().getMessageId());
                                    Channel observed = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                                            new Class<?>[]{Channel.class}, (proxy, method, args) -> {
                                                try {
                                                    if (method.getName().equals("basicAck")) {
                                                        // Verify commit-before-ACK using a separate DB connection.
                                                        assertThat(ctx.getBean(JdbcTemplate.class).queryForObject(
                                                                "SELECT count(*) FROM background_jobs WHERE id = ?", Long.class, event)).isOne();
                                                    }
                                                    Object result = method.invoke(channel, args);
                                                    if (method.getName().equals("basicAck")) {
                                                        probe.acks.merge(event, 1, Integer::sum);
                                                        if (Boolean.TRUE.equals(message.getMessageProperties().isRedelivered())) {
                                                            probe.redeliveries.add(event);
                                                        }
                                                    }
                                                    return result;
                                                } catch (InvocationTargetException failure) { throw failure.getCause(); }
                                            });
                                    listener.onMessage(message, observed); // Unmodified production listener/ingestor.
                                });
                            }
                            return bean;
                        }
                    });
                });
                var ctx = app.run(isolation.arguments());
                jdbc = ctx.getBean(JdbcTemplate.class);
                jdbc.setQueryTimeout(3);
                mapper = ctx.getBean(ObjectMapper.class);
                base = "http://127.0.0.1:" + ((WebServerApplicationContext) ctx).getWebServer().getPort() + "/api/v1";
                assertThat(probe.gates).containsExactlyInAnyOrder("outboxPublisher", "purgeNotificationContainer", "jobWorker");
                assertThat(ctx.getEnvironment().getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
                try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
                    http = client;
                    registerAndLogin();
                    UUID controlRoot = folder(null, "control");
                    UUID controlChild = folder(controlRoot, "control-child");
                    UUID control = upload(controlChild, "control.txt");
                    UUID file = upload(null, "standalone.txt");
                    request("DELETE", "/files/" + file, null, 204);
                    request("DELETE", "/files/" + file + "/permanent", null, 202);
                    UUID fileEvent = event(file);
                    awaitSuccess(fileEvent, null);
                    assertFileProof(file, fileEvent);
                    assertThat(count("file_purge_targets", "event_id", fileEvent)).isOne();
                    assertThat(count("files", "id", file)).isZero();
                    absent(file, ctx);
                    readable(control);
                    evidence("FILE", fileEvent);

                    UUID root = folder(null, "purge-root");
                    UUID child = folder(root, "child");
                    UUID grandchild = folder(child, "grandchild");
                    UUID separateTrash = folder(root, "separately-trashed");
                    var members = List.of(upload(root, "root.txt"), upload(child, "child.txt"),
                            upload(grandchild, "grandchild.txt"), upload(separateTrash, "trash-folder.txt"),
                            upload(grandchild, "trashed-file.txt"));
                    request("DELETE", "/files/" + members.getLast(), null, 204);
                    request("DELETE", "/folders/" + separateTrash, null, 204);
                    request("DELETE", "/folders/" + root, null, 204);
                    request("DELETE", "/trash/folders/" + root + "/permanent", null, 202);
                    UUID folderEvent = event(root);
                    var phases = new ArrayList<String>();
                    awaitSuccess(folderEvent, phases);
                    assertThat(phases).containsExactly("DISCOVER", "DELETE_FILES", "DELETE_FOLDERS", "FINALIZED");
                    assertThat(jdbc.queryForObject("SELECT manifest_folder_count = 4 AND manifest_file_count = 5"
                            + " AND finalized_at IS NOT NULL FROM folder_purge_runs WHERE event_id = ?", Boolean.class, folderEvent)).isTrue();
                    assertThat(count("folder_purge_frontier", "event_id", folderEvent)).isEqualTo(4);
                    assertThat(count("folder_purge_files", "event_id", folderEvent)).isEqualTo(5);
                    assertThat(count("folder_purge_removals", "event_id", folderEvent)).isEqualTo(4);
                    assertThat(jdbc.queryForObject("""
                            SELECT count(*) FROM folder_purge_frontier child
                            JOIN folder_purge_removals c ON c.event_id = child.event_id AND c.folder_id = child.folder_id
                            JOIN folder_purge_removals p ON p.event_id = child.event_id AND p.folder_id = child.parent_folder_id
                            WHERE child.event_id = ? AND c.removed_at >= p.removed_at
                            """, Long.class, folderEvent)).isZero();
                    for (UUID id : List.of(root, child, grandchild, separateTrash)) {
                        assertThat(count("folders", "id", id)).isZero();
                    }
                    for (UUID id : members) {
                        assertFileProof(id, folderEvent);
                        assertThat(count("files", "id", id)).isZero();
                        absent(id, ctx);
                    }
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM folder_purge_files WHERE event_id = ?"
                            + " AND deleted_at IS NOT NULL", Long.class, folderEvent)).isOne();
                    evidence("FOLDER phases=" + String.join(">", phases), folderEvent);

                    // Repeat actual production serialization/publication, then force broker redelivery
                    // by closing an unacknowledged basic.get channel on this run's queue only.
                    var consumer = ctx.getBean("purgeNotificationContainer", SimpleMessageListenerContainer.class);
                    consumer.stop();
                    var beforeFile = receipts(fileEvent);
                    var beforeFolder = receipts(folderEvent);
                    for (UUID id : List.of(fileEvent, folderEvent)) {
                        var e = ctx.getBean(OutboxEventRepository.class).findById(id).orElseThrow();
                        var replay = new ClaimedOutboxEvent(e.getId(), e.getEventType(), e.getAggregateType(),
                                e.getAggregateId(), e.getPayloadVersion(), e.getPayload(), e.getCreatedAt(), UUID.randomUUID(), 1);
                        for (int n = 0; n < 2; n++) {
                            assertThat(ctx.getBean(OutboxTransport.class).publish(replay)).isEqualTo(PublishResult.CONFIRMED);
                        }
                    }
                    try (var channel = isolation.broker.createChannel()) {
                        var delivery = channel.basicGet(isolation.queue, false);
                        assertThat(delivery).isNotNull();
                        // No ACK; channel close causes real broker redelivery with redelivered=true.
                    }
                    consumer.start();
                    await("duplicate ACKs and broker redelivery", () -> probe.acks.getOrDefault(fileEvent, 0) == 3
                            && probe.acks.getOrDefault(folderEvent, 0) == 3 && !probe.redeliveries.isEmpty());
                    consumer.stop(); // Stop before basic.get so ready=0 is also drained, not prefetched.
                    try (var channel = isolation.broker.createChannel()) {
                        assertThat(channel.queueDeclarePassive(isolation.queue).getMessageCount()).isZero();
                        assertThat(channel.basicGet(isolation.queue, false)).isNull();
                    }
                    assertThat(receipts(fileEvent)).isEqualTo(beforeFile);
                    assertThat(receipts(folderEvent)).isEqualTo(beforeFolder);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs", Long.class)).isEqualTo(2);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_quarantine", Long.class)).isZero();
                    assertImmutable("UPDATE file_purge_work SET bytes_deleted_at = bytes_deleted_at + interval '1 second' WHERE file_id = ?", file);
                    assertImmutable("UPDATE folder_purge_removals SET removed_at = removed_at + interval '1 second' WHERE event_id = ?", folderEvent);
                    request("GET", "/folders/" + controlRoot, null, 200);
                    request("GET", "/folders/" + controlChild, null, 200);
                    readable(control);
                    assertThat(count("file_purge_work", "file_id", control)).isZero();
                    System.out.printf("ASYNC_E2E duplicatePublications=4 totalACKs=6 redelivery=%s queueDrained=true receiptsUnchanged=true controlsReadable=true%n",
                            !probe.redeliveries.isEmpty());
                }
            } finally {
                var ctx = context.get();
                // Retain allocation identities for bounded recovery if owned-byte cleanup cannot finish.
                isolation.preserveSchema = !probe.keys.isEmpty();
                // Stop all autonomous work before deleting this run's remaining control bytes.
                if (ctx != null) {
                    try {
                        if (ctx.isActive()) {
                            var storage = ctx.getBean(ObjectStorageService.class);
                            ctx.getBean("purgeNotificationContainer", SimpleMessageListenerContainer.class).stop();
                            ctx.getBean(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).shutdown();
                            ctx.getBean(com.vaultdrive.worker.JobWorker.class).close();
                            assertThat(probe.keys.size()).isLessThanOrEqualTo(16);
                            boolean cleaned = true;
                            for (String key : probe.keys.values()) {
                                assertThat(key.startsWith("integration-tests/" + isolation.schema + "/")).isTrue();
                                try { storage.delete(key); }
                                catch (RuntimeException failure) { cleaned = false; } // Attempt every owned key; no raw exceptions.
                            }
                            assertThat(cleaned).as("all owned disposable objects cleaned").isTrue();
                            isolation.preserveSchema = false;
                            System.out.printf("ASYNC_E2E cleanup ownedObjectsDeleted=%d%n", probe.keys.size());
                        }
                    } finally { ctx.close(); }
                }
            }
        }
    }

    void registerAndLogin() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        String password = UUID.randomUUID() + "aA1!";
        request("POST", "/auth/register", mapper.writeValueAsString(Map.of("email", email,
                "password", password, "displayName", "Disposable E2E")), 201);
        token = mapper.readTree(request("POST", "/auth/login", mapper.writeValueAsString(
                Map.of("email", email, "password", password)), 200)).get("accessToken").asText();
    }

    UUID folder(UUID parent, String name) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("parentFolderId", parent);
        return UUID.fromString(mapper.readTree(request("POST", "/folders", mapper.writeValueAsString(body), 201))
                .get("folderId").asText());
    }

    UUID upload(UUID folder, String name) throws Exception {
        String boundary = "e2e-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: text/plain\r\n\r\n" + new String(CONTENT, StandardCharsets.UTF_8)
                + "\r\n--" + boundary + "--\r\n";
        var response = http.send(builder("/files" + (folder == null ? "" : "?folderId=" + folder))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP upload status (body suppressed)").isEqualTo(201);
        UUID id = UUID.fromString(mapper.readTree(response.body()).get("fileId").asText());
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE id = ?", String.class, id)).isEqualTo("READY");
        readable(id);
        return id;
    }

    private HttpRequest.Builder builder(String path) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return builder;
    }

    String request(String method, String path, String body, int status) throws Exception {
        var builder = builder(path);
        if (path.equals("/auth/login")) builder.header("X-VaultDrive-Client", "1");
        if (body != null) { builder.header("Content-Type", "application/json"); }
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP status (body suppressed)").isEqualTo(status);
        return response.body();
    }

    private void readable(UUID file) throws Exception {
        var response = http.send(builder("/files/" + file + "/download").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(CONTENT);
    }

    UUID event(UUID resource) {
        UUID id = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ? LIMIT 2", UUID.class, resource);
        if ("true".equals(System.getenv("ASYNC_E2E_ALIGN_INITIAL_DUE"))) {
            // Opt-in fixture scheduling only, after the fresh-schema gates and real HTTP intent commit.
            // Keep original intent/payload/time and every claim/retry/worker transition unchanged.
            int aligned = jdbc.update("""
                    UPDATE outbox_events SET next_attempt_at = clock_timestamp() - interval '1 second'
                    WHERE id = ? AND current_schema() = ? AND delivery_status = 'PENDING' AND attempt_count = 0
                        AND next_attempt_at > clock_timestamp() + interval '5 seconds'
                    """, id, probe.schema);
            System.out.println("ASYNC_E2E scheduling initialDueAligned=" + (aligned == 1));
        }
        return id;
    }

    private long count(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id);
    }

    private void awaitSuccess(UUID event, List<String> phases) throws Exception {
        await("published event, ACK and successful durable job", () -> {
            if (phases != null) {
                String phase = jdbc.queryForObject("SELECT phase FROM folder_purge_runs WHERE event_id = ?", String.class, event);
                if (phases.isEmpty() || !phases.getLast().equals(phase)) { phases.add(phase); }
            }
            return jdbc.queryForObject("SELECT count(*) FROM outbox_events e JOIN background_jobs j ON j.id = e.id"
                    + " WHERE e.id = ? AND e.delivery_status = 'PUBLISHED' AND e.published_at IS NOT NULL"
                    + " AND j.status = 'SUCCEEDED' AND j.completed_at IS NOT NULL AND j.failure_count = 0",
                    Long.class, event) == 1 && probe.acks.getOrDefault(event, 0) == 1;
        });
    }

    private void assertFileProof(UUID file, UUID job) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_purge_work w JOIN file_storage_objects a USING(file_id)"
                + " WHERE w.file_id = ? AND w.byte_receipt_job_id = ? AND w.bytes_deleted_at IS NOT NULL"
                + " AND w.metadata_finalized_at >= w.bytes_deleted_at AND a.write_started"
                + " AND a.storage_key = w.storage_key AND a.owner_id = w.owner_id", Long.class, file, job)).isOne();
    }

    private void absent(UUID file, ConfigurableApplicationContext ctx) {
        String key = probe.keys.get(file);
        assertThat(key != null).as("owned object allocation exists").isTrue();
        var failure = assertThrows(S3Exception.class, () -> ctx.getBean(S3Client.class).headObject(b -> b
                .bucket(ctx.getBean(S3StorageProperties.class).bucket()).key(key)));
        assertThat(failure.statusCode()).isEqualTo(404);
    }

    /** Only fixed scalar/timestamp proof columns; never serialize a target, payload, or storage key. */
    private List<Map<String, Object>> receipts(UUID event) {
        var result = new ArrayList<>(jdbc.queryForList("SELECT id, status, attempt_count, failure_count, progress_version,"
                + " completed_at, updated_at FROM background_jobs WHERE id = ?", event));
        result.addAll(jdbc.queryForList("SELECT id, delivery_status, attempt_count, published_at FROM outbox_events WHERE id = ?", event));
        result.addAll(jdbc.queryForList("SELECT file_id, bytes_deleted_at, byte_receipt_job_id, metadata_finalized_at"
                + " FROM file_purge_work WHERE byte_receipt_job_id = ? ORDER BY file_id", event));
        result.addAll(jdbc.queryForList("SELECT folder_id, removed_at FROM folder_purge_removals WHERE event_id = ? ORDER BY folder_id", event));
        result.addAll(jdbc.queryForList("SELECT phase, discovery_sealed_at, finalized_at FROM folder_purge_runs WHERE event_id = ?", event));
        return result;
    }

    private void assertImmutable(String sql, UUID id) throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(3);
                statement.setObject(1, id);
                SQLException rejected = assertThrows(SQLException.class, statement::executeUpdate);
                assertThat(rejected.getSQLState()).isEqualTo("23514");
            } finally { connection.rollback(); }
        }
    }

    private void evidence(String kind, UUID event) {
        System.out.printf("ASYNC_E2E %s event=%s published=true ACKs=%d job=SUCCEEDED attempts=%d failures=0 bytesAbsent=true metadataFinalized=true%n",
                kind, event, probe.acks.get(event), jdbc.queryForObject(
                        "SELECT attempt_count FROM background_jobs WHERE id = ?", Long.class, event));
    }

    private static void await(String label, BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(65).toNanos();
        while (!done.getAsBoolean()) {
            assertThat(System.nanoTime() < deadline).as(label + " within 65 seconds").isTrue();
            Thread.sleep(50);
        }
    }

    static final class Probe {
        final String schema;
        final Map<UUID, String> keys = new ConcurrentHashMap<>();
        final Map<UUID, Integer> acks = new ConcurrentHashMap<>();
        final Set<UUID> redeliveries = ConcurrentHashMap.newKeySet();
        final Set<String> gates = ConcurrentHashMap.newKeySet();
        Probe(String schema) { this.schema = schema; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DisposableKeys {
        @Bean @Primary
        StorageKeyGenerator disposableKeyGenerator(Probe probe, S3Client s3, S3StorageProperties properties) {
            s3.headBucket(b -> b.bucket(properties.bucket()));
            return new StorageKeyGenerator() {
                @Override public String generateFileKey(UUID owner, UUID file) {
                    String key = "integration-tests/" + probe.schema + "/" + owner + "/" + file;
                    // HEAD before admitting a write. Only a provider 404 allows this run to own cleanup.
                    try {
                        s3.headObject(b -> b.bucket(properties.bucket()).key(key));
                        throw new IllegalStateException("Disposable allocation already exists; refusing mutation");
                    } catch (S3Exception missing) {
                        if (missing.statusCode() != 404) { throw new IllegalStateException("Disposable allocation preflight failed"); }
                    }
                    probe.keys.put(file, key);
                    return key;
                }
            };
        }
    }

    /** Owns only a newly CREATED schema and newly declared UUID topology in the approved test vhost. */
    static final class Isolation implements AutoCloseable {
        private static final String URL = "jdbc:postgresql://localhost:5432/vaultdrive_test";
        final String schema;
        Isolation() { this("async_e2e_" + UUID.randomUUID().toString().replace("-", "")); }
        Isolation(String schema) {
            if (!schema.matches("async_e2e_[0-9a-f]{32}")) { throw new IllegalArgumentException("Invalid isolated schema"); }
            this.schema = schema;
            this.exchange = "vaultdrive.outbox.events.test." + schema;
            this.queue = "vaultdrive.purge.requests.test." + schema;
        }
        final String exchange;
        final String queue;
        Connection database;
        com.rabbitmq.client.Connection broker;
        boolean schemaOwned, exchangeOwned, queueOwned, preserveSchema;

        void establish() throws Exception {
            requirePrivateTestEnvironment(System.getenv());
            database = DriverManager.getConnection(URL + "?connectTimeout=3&socketTimeout=5",
                    System.getenv().getOrDefault("DB_USERNAME", "vaultdrive"), System.getenv("DB_PASSWORD"));
            try (var statement = database.createStatement()) {
                statement.setQueryTimeout(3);
                try (var result = statement.executeQuery("SELECT current_database()")) {
                    result.next();
                    assertThat(result.getString(1)).isEqualTo("vaultdrive_test");
                }
                statement.execute("CREATE SCHEMA " + schema); // No IF NOT EXISTS: never adopt an existing schema.
                schemaOwned = true;
            }
            var factory = new com.rabbitmq.client.ConnectionFactory();
            factory.setHost("127.0.0.1");
            factory.setPort(5672);
            factory.setVirtualHost("vaultdrive_outbox_test");
            factory.setUsername(System.getenv("RABBITMQ_USERNAME"));
            factory.setPassword(System.getenv("RABBITMQ_PASSWORD"));
            factory.setConnectionTimeout(3000);
            factory.setHandshakeTimeout(5000);
            factory.setChannelRpcTimeout(5000);
            factory.setAutomaticRecoveryEnabled(false);
            broker = factory.newConnection("isolated-async-e2e");
            // Passive collision checks use separate channels because a 404 closes the channel.
            requireAbsent(true);
            requireAbsent(false);
            try (var channel = broker.createChannel()) {
                channel.exchangeDeclare(exchange, "direct", true);
                exchangeOwned = true;
                channel.queueDeclare(queue, true, false, false, Map.of("x-queue-type", "quorum"));
                queueOwned = true;
                channel.queueBind(queue, exchange, "purge.requested");
                assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isZero();
                assertThat(channel.queueDeclarePassive(queue).getConsumerCount()).isZero();
            }
            System.out.printf("ASYNC_E2E isolation database=vaultdrive_test schema=%s vhost=vaultdrive_outbox_test freshQuorumQueue=true%n", schema);
        }

        static void requirePrivateTestEnvironment(Map<String, String> environment) {
            if (!"vaultdrive_outbox_test".equals(environment.get("RABBITMQ_TEST_VHOST"))) {
                throw new IllegalStateException("Isolation requires RABBITMQ_TEST_VHOST=vaultdrive_outbox_test");
            }
            for (String variable : List.of("DB_PASSWORD", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD", "S3_ACCESS_KEY", "S3_SECRET_KEY", "JWT_SECRET")) {
                if (environment.get(variable) == null || environment.get(variable).isBlank()) {
                    throw new IllegalStateException("Isolation requires private environment variable " + variable);
                }
            }
        }

        private void requireAbsent(boolean isExchange) throws Exception {
            var channel = broker.createChannel();
            try {
                try {
                    if (isExchange) { channel.exchangeDeclarePassive(exchange); }
                    else { channel.queueDeclarePassive(queue); }
                } catch (java.io.IOException missing) {
                    if (missing.getCause() instanceof com.rabbitmq.client.ShutdownSignalException signal
                            && signal.getReason() instanceof com.rabbitmq.client.AMQP.Channel.Close close
                            && close.getReplyCode() == 404) { return; }
                    throw new IllegalStateException("Cannot verify topology isolation");
                }
                throw new IllegalStateException("Topology collision; refusing to adopt existing resource");
            } finally { channel.abort(); } // Passive 404 already closed it; close() would mask verified absence.
        }

        String[] arguments() {
            return new String[]{"--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                    "--logging.level.root=WARN", "--spring.datasource.url=" + URL + "?currentSchema=" + schema + "&connectTimeout=3&socketTimeout=15",
                    "--spring.datasource.hikari.schema=" + schema,
                    "--spring.flyway.url=" + URL + "?currentSchema=" + schema + "&connectTimeout=3&socketTimeout=15",
                    "--spring.flyway.user=${spring.datasource.username}", "--spring.flyway.password=${spring.datasource.password}",
                    "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                    "--spring.jpa.properties.hibernate.default_schema=" + schema,
                    "--spring.flyway.create-schemas=false", "--spring.jpa.open-in-view=false",
                    "--spring.jpa.show-sql=false", "--logging.level.org.hibernate.SQL=OFF",
                    "--logging.level.org.hibernate.orm.jdbc.bind=OFF", "--logging.level.org.springframework.amqp=OFF",
                    "--logging.level.com.rabbitmq.client=OFF", "--spring.rabbitmq.host=127.0.0.1",
                    "--spring.rabbitmq.port=5672", "--spring.rabbitmq.virtual-host=vaultdrive_outbox_test",
                    "--vaultdrive.outbox.publisher.exchange=" + exchange, "--vaultdrive.outbox.publisher.queue=" + queue,
                    "--vaultdrive.outbox.publisher.routing-key=purge.requested", "--vaultdrive.outbox.publisher.enabled=true",
                    "--vaultdrive.purge.consumer.enabled=true", "--vaultdrive.jobs.worker.enabled=true",
                    "--vaultdrive.outbox.publisher.poll-delay=100ms", "--vaultdrive.jobs.worker.poll-delay=100ms",
                    "--vaultdrive.jobs.worker.concurrency=1"};
        }

        @Override public void close() throws Exception {
            try {
                if (broker != null) {
                    try {
                        try (var channel = broker.createChannel()) {
                            if (queueOwned) { channel.queueDelete(queue, false, false); }
                            if (exchangeOwned) { channel.exchangeDelete(exchange, true); }
                        }
                    } finally { broker.close(1000); }
                }
            } finally {
                if (database != null) {
                    try {
                        if (schemaOwned && !preserveSchema) {
                            try (var statement = database.createStatement()) {
                                statement.setQueryTimeout(5);
                                statement.execute("DROP SCHEMA " + schema + " CASCADE");
                            }
                            try (var check = database.prepareStatement("SELECT count(*) FROM pg_namespace WHERE nspname = ?")) {
                                check.setQueryTimeout(3);
                                check.setString(1, schema);
                                try (var result = check.executeQuery()) {
                                    result.next();
                                    assertThat(result.getLong(1)).isZero();
                                }
                            }
                            System.out.printf("ASYNC_E2E cleanup ownedSchemaRemoved=true ownedQueueRemoved=%s ownedExchangeRemoved=%s%n",
                                    queueOwned, exchangeOwned);
                        }
                        if (schemaOwned && preserveSchema) {
                            System.out.println("ASYNC_E2E cleanup ownedSchemaRetainedForObjectRecovery=true");
                        }
                    } finally { database.close(); }
                }
            }
        }
    }
}
