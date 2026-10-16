package com.vaultdrive.e2e;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.vaultdrive.e2e.ProcessRecoveryProtocol.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Safe release mode by default. Kill mode additionally needs a fresh, exact, live-process permit. */
@EnabledIfEnvironmentVariable(named = "ASYNC_PROCESS_TEST", matches = "true")
class AsyncPurgeProcessRecoveryTest {
    record Scenario(boolean folder, Point point) {
        @Override public String toString() { return (folder ? "FOLDER_" : "FILE_") + point; }
    }

    static Stream<Scenario> scenarios() {
        String selected = System.getProperty("processRecovery.scenarios", "");
        var scenarios = new ArrayList<Scenario>();
        for (boolean folder : List.of(false, true)) {
            for (Point point : Point.values()) {
                if (folder || !point.folderOnly()) { scenarios.add(new Scenario(folder, point)); }
            }
        }
        if (!selected.isBlank()) {
            var names = Arrays.asList(selected.split(","));
            if (!names.stream().allMatch(name -> scenarios.stream().anyMatch(s -> s.toString().equals(name)))) {
                throw new IllegalArgumentException("Unknown process recovery scenario");
            }
            return scenarios.stream().filter(s -> names.contains(s.toString()));
        }
        return scenarios.stream();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("scenarios") @Timeout(1100)
    void recoversOriginalIdentityWithoutEditingDurableState(Scenario scenario) throws Exception {
        String mode = System.getProperty("processRecovery.mode", "release");
        assertThat(List.of("release", "kill")).contains(mode);
        var isolation = new AsyncPurgeEndToEndTest.Isolation();
        Path directory = Path.of("target", "process-recovery", isolation.schema).toAbsolutePath();
        Files.createDirectories(directory.getParent());
        Files.createDirectory(directory);
        UUID token = UUID.randomUUID();
        write(directory.resolve("run.properties"), "schema=" + isolation.schema + "\ntoken=" + token
                + "\npoint=" + scenario.point + "\nfolder=" + scenario.folder + "\n");
        Process initial = null, restarted = null;
        boolean objectsCleaned = false;
        try {
            isolation.establish();
            write(directory.resolve("resources-owned"), "true");
            // Polling reuses the verifier's owned autocommit connection for each fresh observation.
            isolation.database.setSchema(isolation.schema);
            assertThat(isolation.database.getAutoCommit()).isTrue();
            var db = new JdbcTemplate(new SingleConnectionDataSource(isolation.database, true));
            db.setQueryTimeout(3);
            initial = launch(directory, token, "initial");
            awaitChild(initial, directory.resolve("initial-ready"), Duration.ofSeconds(90));
            awaitChild(initial, directory.resolve("hit"), Duration.ofSeconds(90));
            var fixture = read(directory.resolve("fixture.properties"));
            UUID event = UUID.fromString(fixture.getProperty("event"));
            assertBoundary(db, scenario, event);
            if (scenario.point == Point.DELETE || scenario.point == Point.RECEIPT) {
                write(directory.resolve("boundary-request"), "true");
                awaitChild(initial, directory.resolve("boundary-verified"), Duration.ofSeconds(15));
            }
            var immutableTargets = targets(db);
            var retained = retainedProof(db);
            var beforeOutbox = db.queryForMap("SELECT claim_token, attempt_count, next_attempt_at, claim_expires_at FROM outbox_events WHERE id = ?", event);
            if (beforeOutbox.get("claim_token") != null) {
                write(directory.resolve("stale-outbox.properties"), "claimToken=" + beforeOutbox.get("claim_token")
                        + "\nattempt=" + beforeOutbox.get("attempt_count") + "\n");
            }
            var frontier = db.queryForList("SELECT folder_id, child_cursor, file_cursor, children_complete, files_complete FROM folder_purge_frontier ORDER BY folder_id");
            int deletesBefore = markers(directory, "deleted-");
            var beforeJob = db.queryForList("SELECT attempt_count, failure_count, progress_version, claim_token, created_at FROM background_jobs WHERE id = ?", event);
            if (!beforeJob.isEmpty() && beforeJob.getFirst().get("claim_token") != null) {
                var row = db.queryForMap("SELECT job_type, aggregate_type, claim_token, claim_expires_at, attempt_count FROM background_jobs WHERE id = ?", event);
                write(directory.resolve("stale.properties"), "type=" + row.get("job_type") + "\naggregateType=" + row.get("aggregate_type")
                        + "\nclaimToken=" + row.get("claim_token") + "\nexpires=" + ((java.sql.Timestamp) row.get("claim_expires_at")).toInstant()
                        + "\nattempt=" + row.get("attempt_count") + "\n");
            }
            // Capture deadlines; no UPDATE/clock manipulation/reset exists anywhere in this harness.
            var deadlines = db.queryForList("SELECT next_attempt_at, claim_expires_at FROM background_jobs WHERE id = ?", event);
            var identity = identity(initial, token, isolation.schema);
            assertThat(Files.readString(directory.resolve("initial-identity"))).isEqualTo(identity.permit());
            identity.requireOwned(initial.toHandle(), directory);
            write(directory.resolve("termination-plan"), identity.permit());
            if (mode.equals("kill")) {
                Path permit = directory.resolve("permit-" + token + ".txt");
                System.out.printf("PROCESS_RECOVERY isolation database=localhost:5432/vaultdrive_test schema=%s vhost=vaultdrive_outbox_test exchange=%s queue=%s disposableAllocations=%d%n",
                        isolation.schema, isolation.exchange, isolation.queue, markers(directory, "allocation-"));
                System.out.println("PROCESS_RECOVERY cleanup=stop-owned-children,delete-journaled-disposable-allocations,delete-owned-queue-and-exchange,drop-owned-schema retain-on-failure=true normalApplicationAndVmServicesUnaffected=true");
                System.out.printf("PROCESS_RECOVERY AWAITING_EXPLICIT_APPROVAL scenario=%s identity=%s directory=%s permit=%s impact=isolated-child-only%n",
                        scenario, identity.permit(), directory, permit);
                awaitChild(initial, permit, Duration.ofMinutes(10));
                consumePermit(permit, identity);
                identity.requireOwned(initial.toHandle(), directory); // Recheck immediately before the ONLY forced termination.
                initial.destroyForcibly();
                assertThat(initial.waitFor(15, TimeUnit.SECONDS)).as("test process exited after approved kill").isTrue();
                write(directory.resolve("killed"), identity.permit());
                deadlines = db.queryForList("SELECT next_attempt_at, claim_expires_at FROM background_jobs WHERE id = ?", event);
            } else {
                write(directory.resolve("release"), "true");
                // Missing-job has no consumer: it must survive restart and age naturally before reconciliation.
                if (scenario.point == Point.CONFIRM || scenario.point == Point.MISSING_JOB) {
                    await("released publication outcome", Duration.ofSeconds(15), () -> db.queryForObject(
                            "SELECT delivery_status = 'PUBLISHED' FROM outbox_events WHERE id = ?", Boolean.class, event));
                } else if (scenario.point == Point.ACK) {
                    await("released consumer ACK", Duration.ofSeconds(15), () -> markers(directory, "ack-") > 0);
                } else { awaitSuccess(db, event, Duration.ofSeconds(100)); }
                stop(initial, directory.resolve("initial-stop"));
                assertThat(initial.exitValue()).as("safe child exit").isZero();
            }
            restarted = launch(directory, token, "restart");
            awaitChild(restarted, directory.resolve("restart-ready"), Duration.ofSeconds(90));
            awaitSuccess(db, event, scenario.point == Point.MISSING_JOB ? Duration.ofSeconds(420) : Duration.ofSeconds(100));
            assertThat(targets(db)).as("original immutable allocation and target hashes").isEqualTo(immutableTargets);
            assertThat(retainedProof(db)).containsAll(retained);
            for (var row : frontier) {
                assertThat(db.queryForObject("SELECT children_complete AND files_complete FROM folder_purge_frontier WHERE folder_id = ?",
                        Boolean.class, row.get("folder_id"))).isTrue();
                for (String cursor : List.of("child_cursor", "file_cursor")) {
                    if (row.get(cursor) != null) {
                        assertThat(db.queryForObject("SELECT " + cursor + " >= ? FROM folder_purge_frontier WHERE folder_id = ?",
                                Boolean.class, row.get(cursor), row.get("folder_id"))).isTrue();
                    }
                }
            }
            verifyFinal(db, scenario, fixture, event);
            boolean abandoned = mode.equals("kill") && !beforeJob.isEmpty() && beforeJob.getFirst().get("claim_token") != null;
            assertThat(db.queryForObject("SELECT failure_count FROM background_jobs WHERE id = ?", Integer.class, event)).isEqualTo(abandoned ? 1 : 0);
            if (scenario.point == Point.MISSING_JOB) {
                assertThat(db.queryForObject("SELECT j.created_at >= e.published_at + interval '5 minutes' FROM background_jobs j"
                        + " JOIN outbox_events e ON j.id = e.id WHERE j.id = ?", Boolean.class, event)).isTrue();
            }
            if (mode.equals("kill") && scenario.point == Point.CONFIRM) {
                assertThat(db.queryForObject("SELECT attempt_count FROM outbox_events WHERE id = ?", Integer.class, event)).isEqualTo(2);
                assertThat(db.queryForObject("SELECT last_attempt_at >= ? AND last_attempt_at >= ? FROM outbox_events WHERE id = ?",
                        Boolean.class, beforeOutbox.get("next_attempt_at"), beforeOutbox.get("claim_expires_at"), event)).isTrue();
            }
            write(directory.resolve("verify-request"), "true");
            awaitChild(restarted, directory.resolve("verified"), Duration.ofSeconds(30));
            if (!scenario.folder && scenario.point == Point.RECEIPT) {
                assertThat(markers(directory, "deleted-")).as("retained byte receipt skips DELETE").isEqualTo(deletesBefore);
            }
            if (mode.equals("kill") && scenario.point == Point.DELETE) {
                assertThat(markers(directory, "deleted-")).as("uncertain DELETE replays the original immutable target").isGreaterThan(deletesBefore);
            }
            if (!beforeJob.isEmpty()) {
                var before = beforeJob.getFirst();
                assertThat(db.queryForObject("SELECT created_at FROM background_jobs WHERE id = ?", java.sql.Timestamp.class, event)).isEqualTo(before.get("created_at"));
                assertThat(db.queryForObject("SELECT progress_version FROM background_jobs WHERE id = ?", Long.class, event))
                        .isGreaterThanOrEqualTo(((Number) before.get("progress_version")).longValue());
                if (mode.equals("kill") && before.get("claim_token") != null) {
                    assertThat(markers(directory, "claimed-restart-")).isPositive();
                    assertThat(markers(directory, "claimed-restart-" + before.get("claim_token") + "-")).isZero();
                    assertThat(db.queryForObject("SELECT failure_count FROM background_jobs WHERE id = ?", Integer.class, event))
                            .isEqualTo(((Number) before.get("failure_count")).intValue() + 1);
                    assertThat(db.queryForObject("SELECT attempt_count FROM background_jobs WHERE id = ?", Long.class, event))
                            .isGreaterThan(((Number) before.get("attempt_count")).longValue());
                    // Fresh claim must not precede either persisted recovery deadline from the killed claim.
                    var due = deadlines.getFirst();
                    assertThat(db.queryForObject("SELECT last_attempt_at >= ? AND last_attempt_at >= ? FROM background_jobs WHERE id = ?",
                            Boolean.class, due.get("next_attempt_at"), due.get("claim_expires_at"), event)).isTrue();
                }
            }
            if (scenario.point != Point.MISSING_JOB) {
                if (mode.equals("kill") && scenario.point == Point.ACK) {
                    assertThat(markers(directory, "redelivery-")).as("original unacknowledged notification redelivered after process loss").isPositive();
                }
                var terminal = terminal(db);
                int acks = markers(directory, "ack-");
                write(directory.resolve("replay-request"), "true");
                awaitChild(restarted, directory.resolve("replay-done"), Duration.ofSeconds(30));
                await("duplicate ACKs and real redelivery", Duration.ofSeconds(30), () -> markers(directory, "ack-") >= acks + 2
                        && markers(directory, "redelivery-") > 0);
                assertThat(terminal(db)).as("duplicates preserve terminal jobs and receipts").isEqualTo(terminal);
            }
            stop(restarted, directory.resolve("restart-stop"));
            assertThat(restarted.exitValue()).isZero();
            try (var channel = isolation.broker.createChannel()) {
                if (scenario.point != Point.MISSING_JOB) { assertThat(channel.queueDeclarePassive(isolation.queue).getMessageCount()).isZero(); }
                assertThat(channel.queueDeclarePassive(isolation.queue).getConsumerCount()).isZero();
            }
            var cleanup = launch(directory, token, "cleanup");
            assertThat(cleanup.waitFor(90, TimeUnit.SECONDS)).as("bounded cleanup process exit").isTrue();
            assertThat(cleanup.exitValue()).isZero();
            objectsCleaned = Files.exists(directory.resolve("objects-cleaned"));
            assertThat(objectsCleaned).isTrue();
            System.out.printf("PROCESS_RECOVERY scenario=%s mode=%s event=%s originalIdentity=true retainedProof=true controlsReadable=true cleanup=true%n",
                    scenario, mode, event);
        } finally {
            // Never force-kill on timeout/failure. Cooperative stop or retain EVERYTHING for inspection.
            boolean stopped = requestStop(initial, directory) & requestStop(restarted, directory);
            if (stopped && objectsCleaned) {
                isolation.close();
                write(directory.resolve("resources-cleaned"), "true");
            } else {
                if (isolation.broker != null) { isolation.broker.close(1000); }
                if (isolation.database != null) { isolation.database.close(); }
                System.out.printf("PROCESS_RECOVERY retained=true allKnownChildrenStopped=%s journal=%s%n", stopped, directory);
            }
        }
    }

    static Process launch(Path directory, UUID token, String mode) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path classpathFile = directory.resolve("test-classpath.txt");
        if (!Files.exists(classpathFile)) { write(classpathFile, classpath); }
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx384m", "-Duser.timezone=UTC", "-cp", classpath,
                ProcessRecoveryApplication.class.getName(), directory.toString(), token.toString(), mode)
                .redirectErrorStream(true).redirectOutput(directory.resolve(mode + ".log").toFile());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) { builder.environment().remove(variable); }
        var process = builder.start();
        var identity = identity(process, token, read(directory.resolve("run.properties")).getProperty("schema"));
        write(directory.resolve(mode + "-launched"), identity.permit());
        return process;
    }

    static Identity identity(Process process, UUID token, String schema) {
        return new Identity(process.pid(), process.info().startInstant().orElseThrow(), token, schema);
    }
    static void awaitChild(Process process, Path marker, Duration timeout) throws Exception {
        await("child marker " + marker.getFileName(), timeout, () -> {
            assertThat(process.isAlive()).as("isolated child alive; inspect sanitized child log").isTrue();
            return Files.exists(marker);
        });
    }
    static void stop(Process process, Path marker) throws Exception {
        write(marker, "true");
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("cooperative child shutdown").isTrue();
    }
    private static boolean requestStop(Process process, Path directory) throws Exception {
        if (process == null || !process.isAlive()) { return true; }
        Path marker = directory.resolve("stop");
        if (!Files.exists(marker)) { write(marker, "true"); }
        return process.waitFor(30, TimeUnit.SECONDS);
    }
    private static int markers(Path directory, String prefix) {
        try (var paths = Files.list(directory)) { return (int) paths.filter(p -> p.getFileName().toString().startsWith(prefix)).count(); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot inspect sanitized markers"); }
    }
    private static void awaitSuccess(JdbcTemplate db, UUID event, Duration timeout) throws Exception {
        await("original event published and job succeeded", timeout, () -> db.queryForObject("""
                SELECT count(*) FROM outbox_events e JOIN background_jobs j ON j.id = e.id
                WHERE e.id = ? AND e.delivery_status = 'PUBLISHED' AND j.status = 'SUCCEEDED'
                """, Long.class, event) == 1);
    }
    private static List<Map<String, Object>> targets(JdbcTemplate db) {
        var result = new ArrayList<>(db.queryForList("SELECT file_id, md5(a::text) AS allocation_identity FROM file_storage_objects a ORDER BY file_id"));
        result.addAll(db.queryForList("SELECT event_id, md5(t::text) AS target_identity FROM file_purge_targets t ORDER BY event_id"));
        return result;
    }
    private static List<Map<String, Object>> retainedProof(JdbcTemplate db) {
        var proof = new ArrayList<Map<String, Object>>();
        proof.addAll(db.queryForList("SELECT file_id, bytes_deleted_at, byte_receipt_job_id FROM file_purge_work WHERE bytes_deleted_at IS NOT NULL ORDER BY file_id"));
        proof.addAll(db.queryForList("SELECT file_id, metadata_finalized_at FROM file_purge_work WHERE metadata_finalized_at IS NOT NULL ORDER BY file_id"));
        proof.addAll(db.queryForList("SELECT event_id, folder_id, removed_at FROM folder_purge_removals ORDER BY folder_id"));
        proof.addAll(db.queryForList("SELECT event_id, finalized_at FROM folder_purge_runs WHERE finalized_at IS NOT NULL"));
        return proof;
    }
    private static List<Map<String, Object>> terminal(JdbcTemplate db) {
        var proof = new ArrayList<>(retainedProof(db));
        proof.addAll(db.queryForList("SELECT id, status, attempt_count, failure_count, progress_version, completed_at FROM background_jobs"));
        proof.addAll(db.queryForList("SELECT id, delivery_status, attempt_count, published_at FROM outbox_events"));
        return proof;
    }
    private static void assertBoundary(JdbcTemplate db, Scenario scenario, UUID event) {
        String condition = switch (scenario.point) {
            case INTENT -> "EXISTS (SELECT 1 FROM outbox_events WHERE delivery_status = 'PENDING' AND attempt_count = 0)";
            case CONFIRM -> "EXISTS (SELECT 1 FROM outbox_events WHERE delivery_status = 'PUBLISHING' AND published_at IS NULL) AND NOT EXISTS (SELECT 1 FROM background_jobs)";
            case ACK -> "EXISTS (SELECT 1 FROM background_jobs WHERE status = 'PENDING' AND attempt_count = 0)";
            case CLAIM -> "EXISTS (SELECT 1 FROM background_jobs WHERE status = 'RUNNING' AND attempt_count = 1) AND NOT EXISTS (SELECT 1 FROM file_purge_work)";
            case DISCOVERY -> "EXISTS (SELECT 1 FROM background_jobs WHERE status = 'RUNNING' AND progress_version > claim_progress_version) AND EXISTS (SELECT 1 FROM folder_purge_frontier)";
            case DELETE -> "EXISTS (SELECT 1 FROM file_purge_work WHERE bytes_deleted_at IS NULL AND metadata_finalized_at IS NULL)";
            case RECEIPT -> "EXISTS (SELECT 1 FROM file_purge_work WHERE bytes_deleted_at IS NOT NULL AND metadata_finalized_at IS NULL)";
            case CHILD_REMOVAL -> "EXISTS (SELECT 1 FROM folder_purge_removals) AND EXISTS (SELECT 1 FROM folder_purge_runs WHERE phase = 'DELETE_FOLDERS')";
            case FINALIZED -> "EXISTS (SELECT 1 FROM folder_purge_runs WHERE phase = 'FINALIZED') AND EXISTS (SELECT 1 FROM background_jobs WHERE status = 'RUNNING')";
            case MISSING_JOB -> "EXISTS (SELECT 1 FROM outbox_events WHERE delivery_status = 'PUBLISHED') AND NOT EXISTS (SELECT 1 FROM background_jobs)";
        };
        assertThat(db.queryForObject("SELECT " + condition, Boolean.class)).as("durable boundary " + scenario).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM outbox_events WHERE id = ?", Long.class, event)).isOne();
    }
    private static void verifyFinal(JdbcTemplate db, Scenario scenario, Properties fixture, UUID event) {
        int files = scenario.folder ? 3 : 1;
        assertThat(db.queryForObject("SELECT count(*) FROM background_jobs WHERE id = ? AND claim_token IS NULL AND claim_expires_at IS NULL", Long.class, event)).isOne();
        assertThat(db.queryForObject("SELECT count(*) FROM background_jobs", Long.class)).isOne();
        assertThat(db.queryForObject("SELECT count(*) FROM file_purge_work w JOIN file_storage_objects a USING(file_id) WHERE w.bytes_deleted_at IS NOT NULL"
                + " AND w.byte_receipt_job_id = ? AND w.metadata_finalized_at >= w.bytes_deleted_at"
                + " AND ROW(w.storage_key,w.owner_id,w.storage_namespace,w.endpoint,w.region,w.bucket) = ROW(a.storage_key,a.owner_id,a.storage_namespace,a.endpoint,a.region,a.bucket)", Long.class, event)).isEqualTo(files);
        assertThat(db.queryForObject("SELECT count(*) FROM files", Long.class)).isOne(); // only the control
        assertThat(db.queryForObject("SELECT count(*) FROM files WHERE id = ? AND status = 'READY' AND purge_requested_at IS NULL", Long.class,
                UUID.fromString(fixture.getProperty("control")))).isOne();
        assertThat(db.queryForObject("SELECT count(*) FROM notification_quarantine", Long.class)).isZero();
        if (scenario.folder) {
            assertThat(db.queryForObject("SELECT count(*) FROM folders", Long.class)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM folder_purge_runs WHERE event_id = ? AND phase = 'FINALIZED'"
                    + " AND manifest_folder_count = 3 AND manifest_file_count = 3 AND finalized_at IS NOT NULL", Long.class, event)).isOne();
            assertThat(db.queryForObject("SELECT count(*) FROM folder_purge_removals WHERE event_id = ?", Long.class, event)).isEqualTo(3);
            assertThat(db.queryForObject("SELECT count(*) FROM folder_purge_frontier m JOIN folder_purge_removals c USING(event_id,folder_id)"
                    + " JOIN folder_purge_removals p ON p.event_id=m.event_id AND p.folder_id=m.parent_folder_id WHERE c.removed_at >= p.removed_at", Long.class)).isZero();
        } else {
            assertThat(db.queryForObject("SELECT count(*) FROM file_purge_targets t JOIN file_purge_work w USING(file_id)"
                    + " WHERE t.event_id = ? AND ROW(t.storage_key,t.owner_id,t.storage_namespace,t.endpoint,t.region,t.bucket)"
                    + " = ROW(w.storage_key,w.owner_id,w.storage_namespace,w.endpoint,w.region,w.bucket)", Long.class, event)).isOne();
        }
    }
}
