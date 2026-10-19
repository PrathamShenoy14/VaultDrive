package com.vaultdrive.sharing;

import com.vaultdrive.file.*;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.*;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.user.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class SharingConcurrencyIntegrationTest {
    @Autowired com.vaultdrive.storage.StorageBinding binding;
    @Autowired UserRepository users;
    @Autowired FolderRepository folders;
    @Autowired StoredFileRepository files;
    @Autowired GrantService grants;
    @Autowired ResourceReadService reads;
    @Autowired FileService fileService;
    @Autowired FolderService folderService;
    @Autowired HierarchyCoordinator hierarchy;
    @Autowired OutboxEventRepository events;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.vaultdrive.storage.ObjectStorageService storage;
    @Autowired software.amazon.awssdk.services.s3.S3Client s3;
    @Autowired com.vaultdrive.storage.config.S3StorageProperties storageProperties;
    UUID owner, viewer, root, child, file, created;
    String email;

    @BeforeEach
    void committedFixture() {
        tx(() -> {
            owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Owner")).getId();
            var recipient = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Viewer"));
            viewer = recipient.getId(); email = recipient.getEmail();
            root = folders.saveAndFlush(new Folder(owner, null, "shared")).getId();
            child = folders.saveAndFlush(new Folder(owner, root, "child")).getId();
            StoredFile f = new StoredFile(UUID.randomUUID(), owner, child, "file.txt", UUID.randomUUID().toString(), "text/plain", 1);
            f.markReady(); file = files.saveAndFlush(f).getId();
        });
    }

    @AfterEach
    void cleanupOnlyOwnedFixture() {
        tx(() -> {
            events.deleteAll(events.findByAggregateTypeAndAggregateId("FILE", file));
            events.flush();
            files.deleteById(file); files.flush();
            if (created != null) { folders.deleteById(created); folders.flush(); }
            folders.deleteById(child); folders.flush();
            folders.deleteById(root); folders.flush();
            users.deleteById(viewer); users.deleteById(owner);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void grantAndFolderTrashSerializeInBothOrders(boolean grantFirst) throws Exception {
        Runnable grant = () -> grants.create(owner, false, file, email, ResourceRole.VIEWER);
        Runnable trash = () -> folderService.deleteFolder(owner, root);
        Throwable loser = ordered(grantFirst ? grant : trash, grantFirst ? trash : grant);
        if (grantFirst) {
            assertThat(loser).isNull();
            assertThat(grants.list(owner, false, file).direct()).hasSize(1);
        } else {
            assertThat(loser).isInstanceOf(ResponseStatusException.class);
            assertThat(grants.list(owner, false, file).direct()).isEmpty();
        }
        assertThatThrownBy(() -> reads.file(viewer, file)).isInstanceOf(FileNotFoundException.class);
        folderService.restoreFolder(owner, root);
        if (grantFirst) assertThat(reads.file(viewer, file).role()).isEqualTo(ResourceRole.VIEWER);
        else assertThatThrownBy(() -> reads.file(viewer, file)).isInstanceOf(FileNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void grantAndFileMoveSerializeAndRetainDirectGrant(boolean grantFirst) throws Exception {
        Runnable grant = () -> grants.create(owner, false, file, email, ResourceRole.VIEWER);
        Runnable move = () -> fileService.moveFile(owner, file, null);
        assertThat(ordered(grantFirst ? grant : move, grantFirst ? move : grant)).isNull();
        assertThat(reads.file(viewer, file).parentId()).isNull();
        assertThat(grants.list(owner, false, file).direct()).hasSize(1);
        assertThat(files.findById(file).orElseThrow().getVersion()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revocationAndReadSerializeInBothOrders(boolean revokeFirst) throws Exception {
        var grant = grants.create(owner, false, file, email, ResourceRole.VIEWER);
        Runnable revoke = () -> grants.revoke(owner, false, file, grant.id());
        Runnable read = () -> assertThat(reads.file(viewer, file).role()).isEqualTo(ResourceRole.VIEWER);
        Throwable loser = ordered(revokeFirst ? revoke : read, revokeFirst ? read : revoke);
        if (revokeFirst) assertThat(loser).isInstanceOf(FileNotFoundException.class);
        else assertThat(loser).isNull();
        assertThat(grants.list(owner, false, file).direct()).isEmpty();
        assertThatThrownBy(() -> reads.file(viewer, file)).isInstanceOf(FileNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revokeAndFilePurgeSerializeInBothOrders(boolean revokeFirst) throws Exception {
        var grant = grants.create(owner, false, file, email, ResourceRole.VIEWER);
        fileService.trashFile(owner, file);
        Runnable revoke = () -> grants.revoke(owner, false, file, grant.id());
        Runnable purge = () -> fileService.requestPermanentDeletion(owner, file);
        Throwable loser = ordered(revokeFirst ? revoke : purge, revokeFirst ? purge : revoke);
        if (revokeFirst) assertThat(loser).isNull();
        else assertThat(loser).isInstanceOf(FileNotFoundException.class);
        assertThat(files.findById(file).orElseThrow().getPurgeRequestedAt()).isNotNull();
        long count = new TransactionTemplate(manager).execute(s -> ((Number)em.createNativeQuery(
                "SELECT count(*) FROM resource_grants WHERE file_id = :file")
                .setParameter("file", file).getSingleResult()).longValue());
        assertThat(count).isEqualTo(revokeFirst ? 0 : 1);
        assertThatThrownBy(() -> reads.file(viewer, file)).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void competingRoleUpdatesHaveOneWinnerAndOneStaleVersion() throws Exception {
        var grant = grants.create(owner, false, file, email, ResourceRole.VIEWER);
        Runnable update = () -> grants.update(owner, false, file, grant.id(), ResourceRole.EDITOR, 0L);
        Throwable loser = ordered(update, update);
        assertThat(loser).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException)loser).getStatusCode().value()).isEqualTo(412);
        assertThat(grants.list(owner, false, file).direct()).singleElement().satisfies(g -> {
            assertThat(g.version()).isEqualTo(1); assertThat(g.role()).isEqualTo(ResourceRole.EDITOR);
        });
        assertThat(files.findById(file).orElseThrow().getVersion()).isZero();
    }

    @Test
    void fileMoveAndReadUseOneStatementSnapshotDespiteCompatibleHierarchyLocks() throws Exception {
        grants.create(owner, true, root, email, ResourceRole.EDITOR);
        CountDownLatch moved = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> tx(() -> {
                fileService.moveFile(owner, file, null);
                moved.countDown(); await(release);
            }));
            assertThat(moved.await(10, TimeUnit.SECONDS)).isTrue();
            var beforeCommit = reads.file(viewer, file);
            assertThat(beforeCommit.parentId()).isEqualTo(child);
            assertThat(beforeCommit.role()).isEqualTo(ResourceRole.EDITOR);
            release.countDown(); writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> reads.file(viewer, file)).isInstanceOf(FileNotFoundException.class);
            assertThat(reads.sharedWithMe(viewer, 0, 10).totalElements()).isEqualTo(1);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void postLockReadsDoNotReuseCachedFileLocationOrGrantState() throws Exception {
        grants.create(owner, true, root, email, ResourceRole.VIEWER);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                assertThat(em.find(StoredFile.class, file).getFolderId()).isEqualTo(child);
                try { executor.submit(() -> fileService.moveFile(owner, file, null)).get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new RuntimeException(e); }
                assertThatThrownBy(() -> reads.file(viewer, file)).isInstanceOf(FileNotFoundException.class);
                status.setRollbackOnly();
            });
        } finally {
            executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void competingDuplicateCreatesProduceOneGrant() throws Exception {
        Runnable create = () -> grants.create(owner, false, file, email, ResourceRole.VIEWER);
        Throwable loser = ordered(create, create);
        assertThat(loser).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException)loser).getStatusCode().value()).isEqualTo(409);
        assertThat(grants.list(owner, false, file).direct()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revokeAndRestoreSerializeWithoutRecreatingAccess(boolean revokeFirst) throws Exception {
        var grant = grants.create(owner, false, file, email, ResourceRole.VIEWER);
        fileService.trashFile(owner, file);
        Runnable revoke = () -> grants.revoke(owner, false, file, grant.id());
        Runnable restore = () -> fileService.restoreFile(owner, file);
        assertThat(ordered(revokeFirst ? revoke : restore, revokeFirst ? restore : revoke)).isNull();
        assertThat(files.findById(file).orElseThrow().getDeletedAt()).isNull();
        assertThat(grants.list(owner, false, file).direct()).isEmpty();
        assertThatThrownBy(() -> reads.file(viewer, file)).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void sharedViewerDownloadsRealGarageBytesAndRevocationPreservesOwnerBytes() throws Exception {
        String key = files.findById(file).orElseThrow().getStorageKey();
        grants.create(owner, true, root, email, ResourceRole.VIEWER);
        var grant = grants.list(owner, true, root).direct().getFirst();
        // Only this fixture's UUID key is written/deleted; no application workers are enabled.
        try {
            s3.putObject(b -> b.bucket(storageProperties.bucket()).key(key).contentType("text/plain"),
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[]{42}));
            try (var bytes = fileService.downloadFile(viewer, file).inputStream()) {
                assertThat(bytes.readAllBytes()).containsExactly((byte)42);
            }
            grants.revoke(owner, true, root, grant.id());
            assertThatThrownBy(() -> fileService.downloadFile(viewer, file)).isInstanceOf(FileNotFoundException.class);
            try (var bytes = fileService.downloadFile(owner, file).inputStream()) {
                assertThat(bytes.readAllBytes()).containsExactly((byte)42);
            }
        } finally {
            storage.delete(binding.target(key));
        }
    }

    enum EditorMutation { CREATE, FOLDER_RENAME, FOLDER_TRASH, FILE_RENAME, FILE_TRASH }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> editorPermissionRaces() {
        return java.util.Arrays.stream(EditorMutation.values()).flatMap(operation ->
                java.util.stream.Stream.of(true, false).flatMap(revoke ->
                        java.util.stream.Stream.of(true, false).map(changeFirst ->
                                org.junit.jupiter.params.provider.Arguments.of(operation, revoke, changeFirst, false))))
                .flatMap(args -> {
                    Object[] values = args.get();
                    EditorMutation operation = (EditorMutation) values[0];
                    return operation == EditorMutation.FILE_RENAME || operation == EditorMutation.FOLDER_RENAME
                            ? java.util.stream.Stream.of(args, org.junit.jupiter.params.provider.Arguments.of(operation, values[1], values[2], true))
                            : java.util.stream.Stream.of(args);
                });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("editorPermissionRaces")
    void editorMutationsSerializeWithRevocationAndDowngradeInBothOrders(
            EditorMutation operation, boolean revoke, boolean changeFirst, boolean direct) throws Exception {
        boolean sourceFolder = !direct || operation == EditorMutation.FOLDER_RENAME;
        UUID sourceId = direct ? (sourceFolder ? child : file) : root;
        var source = grants.create(owner, sourceFolder, sourceId, email, ResourceRole.EDITOR);
        Runnable permission = () -> {
            if (revoke) grants.revoke(owner, sourceFolder, sourceId, source.id());
            else grants.update(owner, sourceFolder, sourceId, source.id(), ResourceRole.VIEWER, 0L);
        };
        Runnable mutation = () -> {
            switch (operation) {
                case CREATE -> created = folderService.createFolder(viewer, child, "created-by-editor");
                case FOLDER_RENAME -> folderService.renameFolder(viewer, child, "edited");
                case FOLDER_TRASH -> folderService.deleteFolder(viewer, child);
                case FILE_RENAME -> fileService.renameFile(viewer, file, "edited");
                case FILE_TRASH -> fileService.trashFile(viewer, file);
            }
        };
        Throwable loser = ordered(changeFirst ? permission : mutation, changeFirst ? mutation : permission);
        if (changeFirst) {
            if (revoke) assertThat(loser).isInstanceOf(operation == EditorMutation.FILE_RENAME || operation == EditorMutation.FILE_TRASH
                    ? FileNotFoundException.class : com.vaultdrive.folder.exception.FolderNotFoundException.class);
            else assertThat(loser).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } else assertThat(loser).isNull();
        var persistedFolder = folders.findById(child).orElseThrow();
        var persistedFile = files.findById(file).orElseThrow();
        assertThat(persistedFolder.getName()).isEqualTo(!changeFirst && operation == EditorMutation.FOLDER_RENAME ? "edited" : "child");
        assertThat(persistedFolder.getDeletedAt() != null).isEqualTo(!changeFirst && operation == EditorMutation.FOLDER_TRASH);
        assertThat(persistedFile.getName()).isEqualTo(!changeFirst && operation == EditorMutation.FILE_RENAME ? "edited.txt" : "file.txt");
        assertThat(persistedFile.getDeletedAt() != null).isEqualTo(!changeFirst && operation == EditorMutation.FILE_TRASH);
        assertThat(persistedFile.getVersion()).isEqualTo(!changeFirst && (operation == EditorMutation.FILE_RENAME || operation == EditorMutation.FILE_TRASH) ? 1 : 0);
        assertThat(created != null).isEqualTo(!changeFirst && operation == EditorMutation.CREATE);
        if (created != null) assertThat(folders.findById(created).orElseThrow().getOwnerId()).isEqualTo(owner);
        assertThat(grants.list(owner, sourceFolder, sourceId).direct()).hasSize(revoke ? 0 : 1);
        if (!revoke) assertThat(grants.list(owner, sourceFolder, sourceId).direct().getFirst().role()).isEqualTo(ResourceRole.VIEWER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void inheritedEditorMutationAndOwnerFolderMoveUseCurrentAncestry(boolean moveFirst) throws Exception {
        grants.create(owner, true, root, email, ResourceRole.EDITOR);
        Runnable move = () -> folderService.moveFolder(owner, child, null);
        Runnable edit = () -> fileService.renameFile(viewer, file, "edited");
        Throwable loser = ordered(moveFirst ? move : edit, moveFirst ? edit : move);
        if (moveFirst) assertThat(loser).isInstanceOf(FileNotFoundException.class);
        else assertThat(loser).isNull();
        assertThat(folders.findById(child).orElseThrow().getParentFolderId()).isNull();
        assertThat(files.findById(file).orElseThrow().getName()).isEqualTo(moveFirst ? "file.txt" : "edited.txt");
        assertThatThrownBy(() -> reads.file(viewer, file)).isInstanceOf(FileNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void inheritedEditorMutationAndOwnerFolderTrashHonorBarrier(boolean trashFirst) throws Exception {
        grants.create(owner, true, root, email, ResourceRole.EDITOR);
        Runnable trash = () -> folderService.deleteFolder(owner, root);
        Runnable edit = () -> fileService.renameFile(viewer, file, "edited");
        Throwable loser = ordered(trashFirst ? trash : edit, trashFirst ? edit : trash);
        if (trashFirst) assertThat(loser).isInstanceOf(FileNotFoundException.class);
        else assertThat(loser).isNull();
        assertThat(folders.findById(root).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(files.findById(file).orElseThrow().getName()).isEqualTo(trashFirst ? "file.txt" : "edited.txt");
    }

    @Test
    void editorCannotReuseCachedFileAfterOwnerMovesItOutsideSharedAncestry() throws Exception {
        grants.create(owner, true, root, email, ResourceRole.EDITOR);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                assertThat(em.find(StoredFile.class, file).getFolderId()).isEqualTo(child);
                try { executor.submit(() -> fileService.moveFile(owner, file, null)).get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new RuntimeException(e); }
                assertThatThrownBy(() -> fileService.renameFile(viewer, file, "denied"))
                        .isInstanceOf(FileNotFoundException.class);
                status.setRollbackOnly();
            });
            assertThat(files.findById(file).orElseThrow().getName()).isEqualTo("file.txt");
            assertThat(files.findById(file).orElseThrow().getFolderId()).isNull();
        } finally {
            executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void editorFileMutationAndOwnerFileMoveKeepOneOptimisticWinner(boolean rename, boolean editorFirst) throws Exception {
        grants.create(owner, true, root, email, ResourceRole.EDITOR);
        Runnable edit = () -> {
            if (rename) fileService.renameFile(viewer, file, "edited");
            else fileService.trashFile(viewer, file);
        };
        Runnable move = () -> fileService.moveFile(owner, file, null);
        Throwable loser = ordered(editorFirst ? edit : move, editorFirst ? move : edit, false);
        assertThat(loser).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        StoredFile persisted = files.findById(file).orElseThrow();
        assertThat(persisted.getFolderId()).isEqualTo(editorFirst ? child : null);
        assertThat(persisted.getName()).isEqualTo(editorFirst && rename ? "edited.txt" : "file.txt");
        assertThat(persisted.getDeletedAt() != null).isEqualTo(editorFirst && !rename);
        assertThat(persisted.getVersion()).isEqualTo(1);
    }

    /** First operation flushes under its real transaction/locks before the competitor starts. */
    private Throwable ordered(Runnable first, Runnable second) throws Exception {
        return ordered(first, second, true);
    }

    private Throwable ordered(Runnable first, Runnable second, boolean hierarchyWait) throws Exception {
        CountDownLatch flushed = new CountDownLatch(1), release = new CountDownLatch(1), started = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicInteger competitorPid = new java.util.concurrent.atomic.AtomicInteger();
        try {
            Future<?> winner = executor.submit(() -> tx(() -> {
                first.run(); em.flush(); flushed.countDown(); await(release);
            }));
            assertThat(flushed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> competitor = executor.submit(() -> tx(() -> {
                competitorPid.set(((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue());
                started.countDown(); second.run();
            }));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (System.nanoTime() < deadline && !competitor.isDone()) {
                waiting = new TransactionTemplate(manager).execute(s -> ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM pg_locks WHERE pid = :pid AND NOT granted"
                                + (hierarchyWait ? " AND locktype = 'advisory'" : ""))
                        .setParameter("pid", competitorPid.get()).getSingleResult()).intValue() > 0);
                if (waiting) break;
                Thread.yield();
            }
            assertThat(waiting).as("competitor waits on the expected PostgreSQL lock").isTrue();
            assertThat(competitor.isDone()).isFalse();
            release.countDown(); winner.get(10, TimeUnit.SECONDS);
            try { competitor.get(10, TimeUnit.SECONDS); return null; }
            catch (ExecutionException e) { return e.getCause(); }
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void tx(Runnable action) { new TransactionTemplate(manager).executeWithoutResult(s -> action.run()); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for release"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
