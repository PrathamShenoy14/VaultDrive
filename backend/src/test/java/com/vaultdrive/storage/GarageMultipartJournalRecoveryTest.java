package com.vaultdrive.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.nio.file.*;
import java.util.*;
import static com.vaultdrive.storage.GarageMultipartFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit reviewed-run disposal only. Never scans a bucket, discovers journals or repairs unknown writes. */
@EnabledIfEnvironmentVariable(named = "GARAGE_MULTIPART_REVIEWED_RUN", matches = "[0-9a-f-]{36}")
class GarageMultipartJournalRecoveryTest {
    @Test @Timeout(60) void disposeOnlyAcknowledgedAllocationsFromOneReviewedRun() throws Exception {
        String run = System.getenv("GARAGE_MULTIPART_REVIEWED_RUN");
        assertThat(UUID.fromString(run).toString()).isEqualTo(run);
        Path root = Path.of("target", "garage-multipart").toAbsolutePath().normalize();
        Path journal = root.resolve(run).resolve("evidence.tsv").normalize();
        assertThat(journal.startsWith(root)).isTrue();
        List<String> lines = Files.readAllLines(journal);
        String retainedKey = System.getenv("GARAGE_MULTIPART_RETAIN_KEY");
        if (retainedKey != null) { assertThat(retainedKey).startsWith("integration-tests/multipart-m0/" + run + "/"); }
        assertThat(lines).noneMatch(s -> s.contains("\tCREATE_UNKNOWN\t") || s.contains("\tCOMPLETE_UNKNOWN\t")
                || s.contains("\tABORT_UNKNOWN\t") || s.contains("\tPART_SDK_UNKNOWN\t")
                || (s.contains("\tPART_HTTP_UNKNOWN\t") && retainedKey == null) || s.contains("\tREVIEWED_DISPOSAL\t"));
        assertThat(lines.stream().filter(s -> s.contains("\tGATE_PREFIX_FLUSHED\t")).count())
                .isEqualTo(lines.stream().filter(s -> s.contains("\tLATE_PART_AFTER_COMPLETE\t")
                        || s.contains("\tLATE_PART_AFTER_ABORT\t")).count());
        assertThat(lines.stream().filter(s -> s.contains("\tCOMPLETE_CLIENT_UNKNOWN\t")).count())
                .isEqualTo(lines.stream().filter(s -> s.contains("\tCOMPLETE_UPSTREAM_ACK_DROPPED\t")).count());
        Map<String, Allocation> allocations = new LinkedHashMap<>();
        Allocation completing = null;
        String lastAbortInExperiment = null;
        int retainedUnknowns = 0;
        int dispatches = 0, acknowledgements = 0;
        for (String line : lines) {
            String[] fields = line.split("\t", 3); assertThat(fields).hasSize(3);
            String kind = fields[1], detail = fields[2];
            if (kind.equals("EXPERIMENT")) { lastAbortInExperiment = null; }
            else if (kind.equals("PART_HTTP_UNKNOWN")) {
                // Review may retain an already-aborted replay target while disposing only OTHER
                // acknowledged allocations. No storage operation or absence claim for this target.
                assertThat(lastAbortInExperiment).isEqualTo(retainedKey);
                retainedUnknowns++;
            } else if (kind.equals("ALLOCATED")) {
                assertThat(detail).startsWith("integration-tests/multipart-m0/" + run + "/");
                assertThat(allocations.put(detail, new Allocation(detail))).isNull();
            } else if (kind.startsWith("CREATE_HTTP_200")) {
                String[] target = detail.split(" "); assertThat(target).hasSize(2);
                assertThat(target[1]).matches("[0-9a-f]{64}");
                allocations.get(target[0]).uploadId = target[1];
            } else if (kind.equals("COMPLETE_DISPATCH")) {
                completing = allocations.get(detail); assertThat(completing).isNotNull(); dispatches++;
            } else if (kind.equals("COMPLETE_HTTP_200") || kind.equals("COMPLETE_UPSTREAM_ACK_DROPPED")) {
                assertThat(completing).isNotNull(); completing.completed = true;
                completing.completionDispatched = true; completing = null; acknowledgements++;
            } else if (kind.equals("ABORT_HTTP_204")) {
                allocations.get(detail).aborted = true; lastAbortInExperiment = detail;
            }
        }
        assertThat(dispatches).isEqualTo(acknowledgements);
        assertThat(allocations).hasSizeBetween(1, 50);
        assertThat(allocations.values()).allSatisfy(a -> assertThat(a.uploadId).isNotNull());
        if (retainedKey != null) {
            assertThat(retainedUnknowns).isEqualTo(1);
            assertThat(allocations.get(retainedKey).aborted).isTrue();
            assertThat(allocations.get(retainedKey).completionDispatched).isFalse();
        }
        try (var f = new GarageMultipartFixture()) {
            assertThat(lines).anyMatch(s -> s.endsWith("\tBINDING\t" + bindingEvidence(f.properties)));
            // This operation requires review of the complete journal and request-join evidence.
            // Missing objects and elapsed time did not authorize any disposal above.
            f.event("REVIEWED_RUN", run);
            for (var a : allocations.values()) {
                if (a.key.equals(retainedKey)) { f.event("RETAINED", a.key); continue; }
                if (!a.completed && !a.aborted) { f.abort(a); }
                if (a.completed) {
                    var head = f.s3.headObject(b -> b.bucket(f.properties.bucket()).key(a.key));
                    assertThat(head.metadata()).containsEntry("vaultdrive-m0-run", run);
                    f.s3.deleteObject(b -> b.bucket(f.properties.bucket()).key(a.key)); f.noObject(a);
                }
                try { f.s3.listParts(b -> b.bucket(f.properties.bucket()).key(a.key).uploadId(a.uploadId));
                    throw new AssertionError("Reviewed upload remains open");
                } catch (S3Exception e) { assertThat(code(e)).isEqualTo("NoSuchUpload"); }
            }
            Files.writeString(journal, java.time.Instant.now() + "\tREVIEWED_DISPOSAL\tacknowledged allocations="
                    + allocations.size() + " retained=" + (retainedKey == null ? 0 : 1) + "\n", StandardOpenOption.APPEND);
        }
    }
}
