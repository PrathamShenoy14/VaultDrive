package com.vaultdrive.sharing;

import com.vaultdrive.file.*;
import com.vaultdrive.folder.*;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.vaultdrive.sharing.PermissionActivityType.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PermissionActivityConcurrencyIntegrationTest {
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired FolderRepository folders;
    @Autowired GrantService grants;
    @Autowired ShareLinkService links;
    @Autowired PermissionActivityService activity;
    @Autowired FileService fileService;
    @Autowired FolderService folderService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    UUID owner, recipient, parent, file;
    String email;

    @BeforeEach void fixture() {
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Owner")).getId();
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Recipient"));
        recipient = user.getId(); email = user.getEmail();
        parent = folders.saveAndFlush(new Folder(owner, null, "private")).getId();
        var f = new StoredFile(UUID.randomUUID(), owner, parent, "original.txt", UUID.randomUUID().toString(), "text/plain", 3);
        f.markReady(); file = files.saveAndFlush(f).getId();
    }

    @AfterEach void cleanup() {
        tx(() -> {
            em.createNativeQuery("DELETE FROM outbox_events WHERE id IN (SELECT id FROM permission_activity_events WHERE owner_id = :owner) OR aggregate_id IN (:file, :folder)")
                    .setParameter("owner", owner).setParameter("file", file).setParameter("folder", parent).executeUpdate();
            files.deleteById(file); files.flush(); folders.deleteById(parent); folders.flush();
            users.deleteById(recipient); users.deleteById(owner);
        });
    }

    @Test void concurrentDuplicateGrantsCommitOneCreationAndOneEvent() throws Exception {
        assertThat(ordered(this::createGrant, this::createGrant)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(history()).singleElement().satisfies(e -> assertThat(e.eventType()).isEqualTo(GRANT_CREATED));
        assertConsistentOutbox();
    }

    @Test void competingRoleChangesWithSameVersionCommitOnlyOneRevision() throws Exception {
        var grant = createGrant();
        Runnable promote = () -> grants.update(owner, false, file, grant.id(), ResourceRole.EDITOR, 0L);
        assertThat(ordered(promote, promote)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(history()).hasSize(2);
        assertThat(history().getFirst().eventType()).isEqualTo(GRANT_ROLE_CHANGED);
        assertThat(history().getFirst().subjectVersion()).isEqualTo(1);
        assertConsistentOutbox();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void staleRoleUpdatesNeverRecordLosingOrRetriedVersions(boolean promoteFirst) throws Exception {
        var grant = createGrant();
        Runnable promote = () -> grants.update(owner, false, file, grant.id(), ResourceRole.EDITOR, 0L);
        Runnable noOp = () -> grants.update(owner, false, file, grant.id(), ResourceRole.VIEWER, 0L);
        Throwable loser = ordered(promoteFirst ? promote : noOp, promoteFirst ? noOp : promote);
        if (promoteFirst) assertThat(loser).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        else assertThat(loser).isNull();
        assertThat(history()).hasSize(2);
        assertThat(history().getFirst().newRole()).isEqualTo(ResourceRole.EDITOR);
        assertThat(history().getFirst().subjectVersion()).isEqualTo(1);
        assertConsistentOutbox();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void roleChangeAndRevokeCaptureExactlyTheCommittedPrecedingRole(boolean updateFirst) throws Exception {
        var grant = createGrant();
        Runnable update = () -> grants.update(owner, false, file, grant.id(), ResourceRole.EDITOR, 0L);
        Runnable revoke = () -> grants.revoke(owner, false, file, grant.id());
        Throwable loser = ordered(updateFirst ? update : revoke, updateFirst ? revoke : update);
        if (updateFirst) assertThat(loser).isNull(); else assertThat(loser).isInstanceOf(RuntimeException.class);
        assertThat(history()).hasSize(updateFirst ? 3 : 2);
        assertThat(history().getFirst().eventType()).isEqualTo(GRANT_REVOKED);
        assertThat(history().getFirst().oldRole()).isEqualTo(updateFirst ? ResourceRole.EDITOR : ResourceRole.VIEWER);
        assertThat(history().getFirst().subjectVersion()).isEqualTo(updateFirst ? 2 : 1);
        assertThat(grants.list(owner, false, file).direct()).isEmpty(); assertConsistentOutbox();
    }

    @Test void concurrentRepeatedRevokesRecordOneTerminalRevisionForGrantsAndLinks() throws Exception {
        var grant = createGrant();
        Runnable revoke = () -> grants.revoke(owner, false, file, grant.id());
        assertThat(ordered(revoke, revoke)).isNull();
        var link = links.create(owner, file, null);
        Runnable revokeLink = () -> links.revoke(owner, file, link.link().id());
        assertThat(ordered(revokeLink, revokeLink)).isNull();
        assertThat(history()).hasSize(4); assertConsistentOutbox();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void snapshotNameFollowsSerializedRenameOrder(boolean renameFirst) throws Exception {
        Runnable rename = () -> fileService.renameFile(owner, file, "renamed.txt");
        assertThat(ordered(renameFirst ? rename : this::createGrant, renameFirst ? this::createGrant : rename)).isNull();
        assertThat(history()).singleElement().satisfies(e ->
                assertThat(e.resourceName()).isEqualTo(renameFirst ? "renamed.txt" : "original.txt"));
        assertConsistentOutbox();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void grantCreationAndAncestorTrashRecordOnlyAnAcceptedMutation(boolean createFirst) throws Exception {
        Runnable trash = () -> folderService.deleteFolder(owner, parent);
        Throwable loser = ordered(createFirst ? this::createGrant : trash, createFirst ? trash : this::createGrant);
        if (createFirst) assertThat(loser).isNull(); else assertThat(loser).isInstanceOf(RuntimeException.class);
        assertThat(history()).hasSize(createFirst ? 1 : 0); assertConsistentOutbox();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void linkCreationAndAncestorTrashRecordOnlyAnAcceptedMutation(boolean createFirst) throws Exception {
        Runnable create = () -> links.create(owner, file, null);
        Runnable trash = () -> folderService.deleteFolder(owner, parent);
        Throwable loser = ordered(createFirst ? create : trash, createFirst ? trash : create);
        if (createFirst) assertThat(loser).isNull(); else assertThat(loser).isInstanceOf(RuntimeException.class);
        assertThat(history()).hasSize(createFirst ? 1 : 0); assertConsistentOutbox();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void revokeAndPurgePreserveHistoryAndPendingPurgeFreeze(boolean revokeFirst) throws Exception {
        var grant = createGrant(); fileService.trashFile(owner, file);
        Runnable revoke = () -> grants.revoke(owner, false, file, grant.id());
        Runnable purge = () -> fileService.requestPermanentDeletion(owner, file);
        Throwable loser = ordered(revokeFirst ? revoke : purge, revokeFirst ? purge : revoke);
        if (revokeFirst) assertThat(loser).isNull(); else assertThat(loser).isInstanceOf(RuntimeException.class);
        assertThat(history()).hasSize(revokeFirst ? 2 : 1); assertConsistentOutbox();
    }

    private GrantService.Grant createGrant() { return grants.create(owner, false, file, email, ResourceRole.VIEWER); }
    private List<PermissionActivityService.Activity> history() { return activity.list(owner, null, null, null, 0, 100).content(); }
    private void assertConsistentOutbox() {
        tx(() -> {
            Number count = (Number) em.createNativeQuery("SELECT count(*) FROM outbox_events WHERE id IN (SELECT id FROM permission_activity_events WHERE owner_id = :owner)")
                    .setParameter("owner", owner).getSingleResult();
            assertThat(count.intValue()).isEqualTo(history().size());
        });
    }

    // The winner pauses after the mutation, event and outbox flush, but before
    // commit. Observe a real PostgreSQL advisory wait rather than timing a sleep.
    private Throwable ordered(Runnable first, Runnable second) throws Exception {
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var started = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        AtomicInteger pid = new AtomicInteger();
        try {
            Future<?> winner = workers.submit(() -> tx(() -> { first.run(); em.flush(); held.countDown(); await(release); }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> contender = workers.submit(() -> tx(() -> {
                pid.set(((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue());
                started.countDown(); second.run();
            }));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (System.nanoTime() < deadline && !contender.isDone()) {
                waiting = new TransactionTemplate(manager).execute(s -> ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM pg_locks WHERE pid = :pid AND NOT granted AND locktype = 'advisory'")
                        .setParameter("pid", pid.get()).getSingleResult()).intValue() > 0);
                if (waiting) break;
                Thread.yield();
            }
            assertThat(waiting).as("contender waits on owner hierarchy coordination").isTrue();
            release.countDown(); winner.get(10, TimeUnit.SECONDS);
            try { contender.get(10, TimeUnit.SECONDS); return null; }
            catch (ExecutionException e) { return e.getCause(); }
        } finally {
            release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
    private void tx(Runnable action) { new TransactionTemplate(manager).executeWithoutResult(s -> action.run()); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Lock release timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
