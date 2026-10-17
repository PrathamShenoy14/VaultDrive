package com.vaultdrive.hierarchy;

import com.vaultdrive.file.FileStatus;
import com.vaultdrive.outbox.OutboxWriter;
import com.vaultdrive.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.mockito.stubbing.Answer;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Deliberately does not override OSIV: regressions must exercise the production default. */
class HierarchyHttpTransactionTest extends HierarchyHttpTestSupport {
    @MockitoSpyBean OutboxWriter writer;
    @Autowired ObjectStorageService storage;

    @Test
    void folderLifecycleAndDtoReadsUseServiceTransactions() throws Exception {
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        var created = request("POST", "/folders", "{\"name\":\"Created\"}", 201);
        UUID id = UUID.fromString(mapper.readTree(created.body()).get("folderId").asText());
        request("PATCH", "/folders/" + id, "{\"name\":\"Renamed\"}", 200);
        request("PATCH", "/folders/" + id + "/move", "{\"destinationFolderId\":\"" + root + "\"}", 200);
        request("GET", "/folders/" + id, null, 200);
        request("GET", "/folders/" + root + "/children", null, 200);
        request("GET", "/folders", null, 200);
        request("DELETE", "/folders/" + id, null, 204);
        request("GET", "/trash/folders", null, 200);
        var restored = request("POST", restore(id), null, 200);
        assertThat(mapper.readTree(restored.body()).get("folderId").asText()).isEqualTo(id.toString());
        var folder = folders.findById(id).orElseThrow();
        assertThat(folder.getName()).isEqualTo("Renamed");
        assertThat(folder.getParentFolderId()).isEqualTo(root);
        assertThat(folder.getDeletedAt()).isNull();
        assertThat(events()).isZero();
        assertJoinedRequests();
    }

    @Test
    void folderRestoreCollisionAndPurgePreserveHttpContractAndAtomicIntent() throws Exception {
        tx.executeWithoutResult(s -> folder("Restore", false, false));
        var restored = request("POST", restore(trashed), null, 200);
        assertThat(mapper.readTree(restored.body()).get("restoredName").asText()).isEqualTo("Restore (restored)");
        request("DELETE", "/folders/" + trashed, null, 204);
        var accepted = request("DELETE", purge(trashed), null, 202);
        assertThat(accepted.body()).isEmpty();
        assertThat(folders.findById(trashed).orElseThrow().getPurgeRequestedAt()).isNotNull();
        assertThat(events()).isEqualTo(1);
        assertThat(count("folder_purge_runs")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs", Long.class)).isZero();
        var before = snapshot();
        request("DELETE", purge(trashed), null, 404);
        request("POST", restore(trashed), null, 404);
        assertThat(snapshot()).isEqualTo(before);
        assertJoinedRequests();
    }

    @Test
    void fileLifecycleUsesSharedAndDestinationLocksAndCommitsPurgeTarget() throws Exception {
        request("PATCH", "/files/" + activeFile + "/name", "{\"name\":\"renamed\"}", 200);
        request("PATCH", "/files/" + activeFile + "/move", "{\"folderId\":\"" + root + "\"}", 200);
        request("GET", "/files?folderId=" + root, null, 200);
        request("DELETE", "/files/" + activeFile, null, 204);
        request("GET", "/files/trash", null, 200);
        request("POST", "/files/" + activeFile + "/restore", null, 200);
        var restored = files.findById(activeFile).orElseThrow();
        assertThat(restored.getName()).isEqualTo("renamed.txt");
        assertThat(restored.getFolderId()).isEqualTo(root);
        assertThat(restored.getDeletedAt()).isNull();
        request("DELETE", "/files/" + activeFile, null, 204);
        request("DELETE", "/files/" + activeFile + "/permanent", null, 202);
        assertThat(files.findById(activeFile).orElseThrow().getPurgeRequestedAt()).isNotNull();
        assertThat(events()).isEqualTo(1);
        assertThat(count("file_purge_targets")).isEqualTo(1);
        var before = snapshot();
        request("POST", "/files/" + activeFile + "/restore", null, 404);
        request("DELETE", "/files/" + activeFile + "/permanent", null, 404);
        assertThat(snapshot()).isEqualTo(before);
        assertJoinedRequests();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedRestoreAndPurgeLeaveAllFixtureRowsUnchanged(boolean file) throws Exception {
        UUID trashId = file ? trashedFile : trashed;
        for (UUID id : List.of(file ? pendingFile : pending, file ? activeFile : root, UUID.randomUUID())) {
            assertRejected(file, id, owner, 404);
        }
        assertRejected(file, trashId, otherOwner, 404);
        assertRejected(file, trashId, null, 401);
        assertThat(events()).isZero();
        assertJoinedRequests();
    }

    private void assertRejected(boolean file, UUID id, UUID principal, int status) throws Exception {
        var before = snapshot();
        String restorePath = file ? "/files/" + id + "/restore" : restore(id);
        String purgePath = file ? "/files/" + id + "/permanent" : purge(id);
        assertThat(request("POST", restorePath, null, principal).statusCode()).isEqualTo(status);
        assertThat(request("DELETE", purgePath, null, principal).statusCode()).isEqualTo(status);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void inaccessibleAncestorsAndConflictsLeaveStateUnchanged() throws Exception {
        UUID child = tx.execute(s -> {
            UUID id = folder("Child", true, false);
            var folder = folders.findById(id).orElseThrow();
            folder.move(pending);
            folders.flush();
            var file = files.findById(trashedFile).orElseThrow();
            file.move(pending);
            files.flush();
            return id;
        });
        assertRejected(false, child, owner, 404);
        assertRejected(true, trashedFile, owner, 404);
        var before = snapshot();
        request("POST", "/folders", "{\"name\":\"Destination\"}", 409);
        request("PATCH", "/folders/" + root + "/move", "{\"destinationFolderId\":\"" + root + "\"}", 400);
        request("PATCH", "/files/" + activeFile + "/move", "{\"folderId\":\"" + pending + "\"}", 404);
        assertThat(snapshot()).isEqualTo(before);
        assertJoinedRequests();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outboxFailureRollsBackResourceAndAllIntentRows(boolean file) throws Exception {
        OutboxWriter target = AopTestUtils.getUltimateTargetObject(writer);
        doAnswer(call -> {
            call.callRealMethod(); // Includes a real flush in the existing REQUIRED transaction.
            throw new IllegalStateException("Injected test rollback");
        }).when(target).append(any());
        var before = snapshot();
        request("DELETE", file ? "/files/" + trashedFile + "/permanent" : purge(trashed), null, 500);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(events()).isZero();
        assertJoinedRequests();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentFolderRestoreAndPurgeHaveExactlyOneCommittedWinner(boolean purgeFirst) throws Exception {
        var acquired = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var waiterPid = new AtomicInteger();
        doAnswer(call -> {
            recordState();
            boolean winner = first.getAndSet(false);
            if (!winner) {
                waiterPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                attempted.countDown();
            }
            call.callRealMethod();
            if (winner) {
                acquired.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock release timed out");
            }
            return null;
        }).when(hierarchy).acquireExclusive(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var winner = executor.submit(() -> request(purgeFirst ? "DELETE" : "POST",
                    purgeFirst ? purge(trashed) : restore(trashed), null, owner));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            var loser = executor.submit(() -> request(purgeFirst ? "POST" : "DELETE",
                    purgeFirst ? restore(trashed) : purge(trashed), null, owner));
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> loser.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"
                    + " AND NOT granted AND pid = ?", Long.class, waiterPid.get()))
                    .isGreaterThanOrEqualTo(1);
            release.countDown();
            assertThat(winner.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(purgeFirst ? 202 : 200);
            assertThat(loser.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(404);
            var folder = folders.findById(trashed).orElseThrow();
            assertThat(folder.getDeletedAt() != null).isEqualTo(purgeFirst);
            assertThat(folder.getPurgeRequestedAt() != null).isEqualTo(purgeFirst);
            assertThat(events()).isEqualTo(purgeFirst ? 1 : 0);
            assertThat(count("folder_purge_runs")).isEqualTo(purgeFirst ? 1 : 0);
            assertJoinedRequests();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sharedFileMoveAndExclusiveFolderTrashRevalidateAfterWaiting(boolean folderFirst) throws Exception {
        tx.executeWithoutResult(s -> {
            files.findById(activeFile).orElseThrow().move(root);
            files.flush();
        });
        var acquired = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var waiterPid = new AtomicInteger();
        Answer<Object> gate = call -> {
            recordState();
            boolean winner = first.getAndSet(false);
            if (!winner) {
                waiterPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                attempted.countDown();
            }
            call.callRealMethod();
            if (winner) {
                acquired.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock release timed out");
            }
            return null;
        };
        doAnswer(gate).when(hierarchy).acquireShared(any());
        doAnswer(gate).when(hierarchy).acquireExclusive(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var winner = executor.submit(() -> folderFirst
                    ? request("DELETE", "/folders/" + root, null, owner)
                    : request("PATCH", "/files/" + activeFile + "/move", "{\"folderId\":null}", owner));
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            var loser = executor.submit(() -> folderFirst
                    ? request("PATCH", "/files/" + activeFile + "/move", "{\"folderId\":null}", owner)
                    : request("DELETE", "/folders/" + root, null, owner));
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> loser.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"
                    + " AND NOT granted AND pid = ?", Long.class, waiterPid.get())).isGreaterThanOrEqualTo(1);
            release.countDown();
            assertThat(winner.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(folderFirst ? 204 : 200);
            assertThat(loser.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(folderFirst ? 404 : 204);
            assertThat(folders.findById(root).orElseThrow().getDeletedAt()).isNotNull();
            var file = files.findById(activeFile).orElseThrow();
            assertThat(file.getFolderId()).isEqualTo(folderFirst ? root : null);
            assertThat(file.getVersion()).isEqualTo(folderFirst ? 1 : 2);
            assertThat(events()).isZero();
            assertJoinedRequests();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void realUploadReservationAdmissionReceiptAndFinalizationUseSeparateJoinedTransactions() throws Exception {
        String boundary = "vaultdrive-http-test";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"http-upload.txt\""
                + "\r\nContent-Type: text/plain\r\n\r\ndisposable HTTP bytes\r\n--" + boundary + "--\r\n";
        try {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + jwt.generateAccessToken(owner))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            var id = UUID.fromString(mapper.readTree(response.body()).get("fileId").asText());
            assertThat(files.findById(id).orElseThrow().getStatus()).isEqualTo(FileStatus.READY);
            assertThat(states).hasSize(4); // reservation, admission, success receipt, finalization
            assertThat(jdbc.queryForObject("""
                    SELECT a.write_started AND a.put_succeeded_at IS NOT NULL
                        AND a.admitted_file_version + 1 = f.version
                        AND a.admitted_folder_id IS NOT DISTINCT FROM f.folder_id
                    FROM file_storage_objects a JOIN files f ON f.id = a.file_id WHERE f.id = ?
                    """, Boolean.class, id)).isTrue();
            assertJoinedRequests();
            var download = request("GET", "/files/" + id + "/download", null, 200);
            assertThat(download.body()).isEqualTo("disposable HTTP bytes");
        } finally {
            for (String key : jdbc.queryForList("SELECT storage_key FROM files WHERE owner_id = ? AND name = 'http-upload.txt'",
                    String.class, owner)) {
                assertThat(key).startsWith("users/" + owner + "/files/");
                storage.delete(key);
            }
        }
    }
}
