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

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ShareLinkConcurrencyIntegrationTest {
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired FolderRepository folders;
    @Autowired ShareLinkService links;
    @Autowired FolderService folderService;
    @Autowired FileService fileService;
    @Autowired EntityManager em;
    @Autowired com.vaultdrive.hierarchy.HierarchyCoordinator hierarchy;
    @Autowired PlatformTransactionManager manager;
    UUID owner, folder, file;
    ShareLinkService.CreatedLink link;

    @BeforeEach void fixture() {
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Owner")).getId();
        folder = folders.saveAndFlush(new Folder(owner, null, "private")).getId();
        var f = new StoredFile(UUID.randomUUID(), owner, folder, "public.txt", UUID.randomUUID().toString(), "text/plain", 3);
        f.markReady(); file = files.saveAndFlush(f).getId();
        link = links.create(owner, file, null);
    }

    @AfterEach void cleanup() {
        tx(() -> {
            em.createNativeQuery("DELETE FROM outbox_events WHERE aggregate_id IN (:file, :folder)")
                    .setParameter("file", file).setParameter("folder", folder).executeUpdate();
            files.deleteById(file); files.flush(); folders.deleteById(folder); folders.flush(); users.deleteById(owner);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void revocationAndAuthorizationSerializeInBothWinnerOrders(boolean revokeFirst) throws Exception {
        Runnable revoke = () -> links.revoke(owner, file, link.link().id());
        Runnable read = () -> assertThat(links.authorize(link.link().id(), link.secret()).metadata().name()).isEqualTo("public.txt");
        Throwable loser = ordered(revokeFirst ? revoke : read, revokeFirst ? read : revoke);
        if (revokeFirst) assertThat(loser).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        else assertThat(loser).isNull();
        assertThat(links.list(owner, file)).singleElement().satisfies(l -> {
            assertThat(l.revokedAt()).isNotNull(); assertThat(l.version()).isEqualTo(1);
        });
        assertThatThrownBy(() -> links.authorize(link.link().id(), link.secret()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void linkCreationAndAncestorTrashSerializeInBothOrders(boolean createFirst) throws Exception {
        Runnable create = () -> links.create(owner, file, null);
        Runnable trash = () -> folderService.deleteFolder(owner, folder);
        Throwable loser = ordered(createFirst ? create : trash, createFirst ? trash : create);
        if (createFirst) assertThat(loser).isNull();
        else assertThat(loser).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(links.list(owner, file)).hasSize(createFirst ? 2 : 1);
        assertThatThrownBy(() -> links.authorize(link.link().id(), link.secret()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        folderService.restoreFolder(owner, folder);
        assertThat(links.authorize(link.link().id(), link.secret())).isNotNull();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void revokeAndFilePurgeRespectTheExistingBarrier(boolean revokeFirst) throws Exception {
        fileService.trashFile(owner, file);
        Runnable revoke = () -> links.revoke(owner, file, link.link().id());
        Runnable purge = () -> fileService.requestPermanentDeletion(owner, file);
        Throwable loser = ordered(revokeFirst ? revoke : purge, revokeFirst ? purge : revoke);
        if (revokeFirst) assertThat(loser).isNull();
        else assertThat(loser).isInstanceOf(com.vaultdrive.file.exception.FileNotFoundException.class);
        assertThat(files.findById(file).orElseThrow().getPurgeRequestedAt()).isNotNull();
    }

    @Test void expiryIsRecheckedAfterWaitingForHierarchyCoordination() throws Exception {
        UUID id = UUID.randomUUID(); String token = ShareLinkTokens.generate();
        tx(() -> em.createNativeQuery("""
                INSERT INTO share_links(id, owner_id, file_id, token_hash, expires_at)
                VALUES (:id, :owner, :file, :hash, statement_timestamp() + interval '2 seconds')
                """).setParameter("id", id).setParameter("owner", owner).setParameter("file", file)
                .setParameter("hash", ShareLinkTokens.hash(token)).executeUpdate());
        Throwable denied = ordered(() -> hierarchy.acquireExclusive(owner), () -> links.authorize(id, token), () -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean expired = false;
            while (System.nanoTime() < deadline) {
                expired = new TransactionTemplate(manager).execute(s -> (Boolean) em.createNativeQuery(
                        "SELECT clock_timestamp() >= expires_at FROM share_links WHERE id = :id")
                        .setParameter("id", id).getSingleResult());
                if (expired) break;
                Thread.yield();
            }
            assertThat(expired).isTrue();
        });
        assertThat(denied).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private Throwable ordered(Runnable first, Runnable second) throws Exception {
        return ordered(first, second, () -> {});
    }

    private Throwable ordered(Runnable first, Runnable second, Runnable afterWaiting) throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), started = new CountDownLatch(1);
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
            assertThat(waiting).as("contender waits on real owner hierarchy coordination").isTrue();
            afterWaiting.run();
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
