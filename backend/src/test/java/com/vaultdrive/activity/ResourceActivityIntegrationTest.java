package com.vaultdrive.activity;

import com.vaultdrive.activity.ResourceActivityService.*;
import com.vaultdrive.file.*;
import com.vaultdrive.folder.*;
import com.vaultdrive.sharing.*;
import com.vaultdrive.user.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static com.vaultdrive.activity.ResourceActivityService.EventType.*;
import static com.vaultdrive.activity.ResourceActivityService.ResourceType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fresh schema and committed UUID fixtures; immutable synthetic evidence is retained. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceActivityIntegrationTest {
    static final String SCHEMA = "activity_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @MockitoSpyBean ResourceActivityService activity;
    @Autowired FileService fileService;
    @Autowired FolderService folderService;
    @Autowired GrantService grants;
    @Autowired ResourceReadService reads;
    @Autowired PermissionActivityService permissionActivity;
    @Autowired UserRepository users;
    @Autowired FolderRepository folders;
    @Autowired StoredFileRepository files;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    UUID owner, editor, stranger, parent, child, destination, file;
    String email;

    @BeforeEach void fixture() {
        tx(() -> {
            owner = user("Owner"); editor = user("Editor then"); stranger = user("Stranger");
            email = users.findById(editor).orElseThrow().getEmail();
            parent = folders.saveAndFlush(new Folder(owner, null, "private parent")).getId();
            child = folders.saveAndFlush(new Folder(owner, parent, "child")).getId();
            destination = folders.saveAndFlush(new Folder(owner, null, "destination")).getId();
            StoredFile f = new StoredFile(UUID.randomUUID(), owner, child, "original.txt", UUID.randomUUID().toString(), "text/plain", 3);
            f.markReady(); file = files.saveAndFlush(f).getId();
        });
    }
    @AfterEach void resetSpy() { reset(AopTestUtils.<ResourceActivityService>getUltimateTargetObject(activity)); }

    @Test void allMetadataMutationsPreserveBeforeAfterIdentityAndActor() {
        UUID created = folderService.createFolder(owner, parent, "created");
        folderService.renameFolder(owner, created, "renamed");
        folderService.moveFolder(owner, created, destination);
        folderService.deleteFolder(owner, created);
        folderService.restoreFolder(owner, created);
        fileService.renameFile(owner, file, "renamed");
        fileService.moveFile(owner, file, destination);
        fileService.trashFile(owner, file);
        fileService.restoreFile(owner, file);
        assertThat(history()).extracting(Activity::eventType).containsExactly(
                RESTORED, TRASHED, MOVED, RENAMED, RESTORED, TRASHED, MOVED, RENAMED, FOLDER_CREATED);
        assertThat(history()).allSatisfy(e -> {
            assertThat(e.actorId()).isEqualTo(owner); assertThat(e.ownerId()).isEqualTo(owner);
            assertThat(e.actorDisplayName()).isEqualTo("Owner"); assertThat(e.occurredAt()).isNotNull();
        });
        var moved = history().get(2);
        assertThat(moved.beforeParentId()).isEqualTo(child); assertThat(moved.afterParentId()).isEqualTo(destination);
        var renamed = history().get(3);
        assertThat(renamed.beforeName()).isEqualTo("original.txt"); assertThat(renamed.afterName()).isEqualTo("renamed.txt");
        assertThat(history().getLast().beforeName()).isNull();
        assertThat(permissionActivity.list(owner, null, null, null, 0, 100).content()).isEmpty();
    }

    @Test void editorAttributionPrivateParentRedactionAndRevocationNeverConferHistoryAccess() throws Exception {
        var grant = grants.create(owner, true, child, email, ResourceRole.EDITOR);
        assertThat(fileService.renameFile(editor, file, "edited").folderId()).isEqualTo(child);
        assertThat(folderService.renameFolder(editor, child, "edited child").parentFolderId()).isNull();
        UUID created = folderService.createFolder(editor, child, "created by Editor");
        fileService.trashFile(editor, file);
        folderService.deleteFolder(editor, created);
        var retained = history();
        assertThat(retained).hasSize(5).allSatisfy(e -> {
            assertThat(e.actorId()).isEqualTo(editor); assertThat(e.ownerId()).isEqualTo(owner);
            assertThat(e.actorDisplayName()).isEqualTo("Editor then"); assertThat(e.actorAttribution()).isEqualTo(ActorAttribution.CALLER);
        });
        jdbc.update("UPDATE users SET display_name = 'Editor now' WHERE id = ?", editor);
        grants.revoke(owner, true, child, grant.id());
        assertThatThrownBy(() -> reads.folder(editor, child)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fileService.renameFile(editor, file, "denied")).isInstanceOf(RuntimeException.class);
        assertThat(history()).isEqualTo(retained);
        for (UUID actor : List.of(editor, stranger)) {
            mvc.perform(get("/api/v1/activity").with(as(actor)).param("actorId", editor.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content").isEmpty());
        }
        assertThat(permissionActivity.list(owner, null, null, null, 0, 100).totalElements()).isEqualTo(2);
    }

    @Test void historySurvivesDeletionAndAccountRemovalWithoutLiveDisplayLookups() {
        fileService.renameFile(owner, file, "renamed");
        Activity event = history().getFirst();
        jdbc.update("UPDATE users SET display_name = 'new owner' WHERE id = ?", owner);
        jdbc.update("DELETE FROM files WHERE id = ?", file);
        jdbc.update("DELETE FROM folders WHERE id = ?", child);
        jdbc.update("DELETE FROM folders WHERE owner_id = ?", owner);
        jdbc.update("DELETE FROM users WHERE id = ?", owner);
        assertThat(history()).containsExactly(event);
        // Retention matches permission history; no account-erasure mechanism is invented.
    }

    @Test void noOpsFailuresRetriesAndPurgeAcceptanceDoNotEmitExtraEvents() {
        folderService.renameFolder(owner, child, "child"); folderService.moveFolder(owner, child, parent);
        fileService.renameFile(owner, file, "original"); fileService.moveFile(owner, file, child);
        assertThat(history()).isEmpty();
        assertThatThrownBy(() -> folderService.createFolder(owner, parent, "child")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fileService.renameFile(stranger, file, "denied")).isInstanceOf(RuntimeException.class);
        fileService.renameFile(owner, file, "once"); fileService.renameFile(owner, file, "once");
        fileService.trashFile(owner, file);
        assertThatThrownBy(() -> fileService.trashFile(owner, file)).isInstanceOf(RuntimeException.class);
        fileService.requestPermanentDeletion(owner, file);
        assertThatThrownBy(() -> fileService.requestPermanentDeletion(owner, file)).isInstanceOf(RuntimeException.class);
        assertThat(history()).extracting(Activity::eventType).containsExactly(TRASHED, RENAMED);
    }

    enum Mutation { FILE_RENAME, FILE_MOVE, FILE_TRASH, FILE_RESTORE, FOLDER_CREATE, FOLDER_RENAME, FOLDER_MOVE, FOLDER_TRASH, FOLDER_RESTORE }
    @ParameterizedTest @EnumSource(Mutation.class)
    void rollbackAfterFlushRemovesTransitionAndEventThenRetryCommitsOnce(Mutation kind) {
        Runnable change = mutation(kind);
        var before = state(); int count = history().size();
        assertThatThrownBy(() -> tx(() -> {
            change.run(); assertThat(history()).hasSize(count + 1); throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo(before); assertThat(history()).hasSize(count);
        change.run(); assertThat(history()).hasSize(count + 1);
    }

    @ParameterizedTest @EnumSource(Mutation.class)
    void activityFailureRollsBackEveryMetadataTransition(Mutation kind) {
        Runnable change = mutation(kind);
        var before = state(); int count = history().size();
        doAnswer(i -> { i.callRealMethod(); throw new IllegalStateException("after activity insert"); })
                .when(AopTestUtils.<ResourceActivityService>getUltimateTargetObject(activity)).append(any(), any(), any(), any());
        assertThatThrownBy(change::run).isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo(before); assertThat(history()).hasSize(count);
        resetSpy(); change.run(); assertThat(history()).hasSize(count + 1);
    }

    @Test void restoreSnapshotsIncludeCollisionNamesAndRootFallback() {
        fileService.trashFile(owner, file); folderService.deleteFolder(owner, child);
        tx(() -> { var f = new StoredFile(UUID.randomUUID(), owner, null, "original.txt", UUID.randomUUID().toString(), "text/plain", 1);
            f.markReady(); files.saveAndFlush(f); });
        fileService.restoreFile(owner, file);
        var event = history().getFirst();
        assertThat(event.eventType()).isEqualTo(RESTORED); assertThat(event.beforeParentId()).isEqualTo(child);
        assertThat(event.afterParentId()).isNull(); assertThat(event.afterName()).isEqualTo("original (restored).txt");
        assertThat(event.beforeTrashed()).isTrue(); assertThat(event.afterTrashed()).isFalse();
        folderService.deleteFolder(owner, parent); folderService.restoreFolder(owner, child);
        assertThat(history().getFirst().afterParentId()).isNull();
    }

    @Test void uncommittedHistoryIsInvisibleAndWriterRejectsStandaloneCalls() throws Exception {
        assertThatThrownBy(() -> activity.append(owner, FOLDER_CREATED, null, Snapshot.of(folders.findById(child).orElseThrow())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        try (var executor = Executors.newSingleThreadExecutor()) {
            tx(() -> {
                folderService.renameFolder(owner, child, "pending");
                try { executor.submit(() -> assertThat(history()).isEmpty()).get(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
            });
        }
        assertThat(history()).hasSize(1);
    }

    @Test void immutableDatabaseConstraintsRejectUpdatesDeletesDuplicateTerminalEventsAndBadShapes() {
        UUID created = folderService.createFolder(owner, parent, "new");
        UUID event = history().getFirst().id();
        assertThatThrownBy(() -> jdbc.update("UPDATE resource_activity_events SET resource_name = 'changed' WHERE id = ?", event))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM resource_activity_events WHERE id = ?", event))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx(() -> activity.append(owner, FOLDER_CREATED, null, Snapshot.of(folders.findById(created).orElseThrow()))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tx(() -> activity.append(owner, MOVED, Snapshot.of(files.findById(file).orElseThrow()),
                Snapshot.of(files.findById(file).orElseThrow())))).isInstanceOf(RuntimeException.class);
        assertThat(history()).hasSize(1);
    }

    @Test void authenticatedBoundedFiltersAndPagesUseOwnerScopeBeforeCount() throws Exception {
        fileService.renameFile(owner, file, "one"); folderService.renameFolder(owner, child, "two");
        Activity latest = history().getFirst();
        mvc.perform(get("/api/v1/activity")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/activity").with(as(owner)).param("size", "1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].resourceType").value("FOLDER"));
        mvc.perform(get("/api/v1/activity").with(as(owner)).param("resourceType", "FOLDER")
                .param("resourceId", child.toString()).param("eventType", "RENAMED").param("actorId", owner.toString())
                .param("from", latest.occurredAt().toString()).param("to", latest.occurredAt().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].beforeName").value("child"));
        for (UUID resource : List.of(file, UUID.randomUUID())) {
            mvc.perform(get("/api/v1/activity").with(as(stranger)).param("resourceType", "FILE").param("resourceId", resource.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        }
        assertThat(activity.list(owner, null, null, null, stranger, null, null, 0, 100).totalElements()).isZero();
        var outside = activity.list(owner, null, null, null, null, null, null, Integer.MAX_VALUE, 100);
        assertThat(outside.content()).isEmpty(); assertThat(outside.totalElements()).isEqualTo(2);
        for (String[] pair : List.of(new String[]{"page", "-1"}, new String[]{"page", "2147483648"},
                new String[]{"size", "0"}, new String[]{"size", "101"}, new String[]{"resourceType", "bad"},
                new String[]{"eventType", "GRANT_CREATED"}, new String[]{"actorId", "bad"},
                new String[]{"resourceId", file.toString()}, new String[]{"from", "bad"},
                new String[]{"from", "+10000-01-01T00:00:00Z"})) {
            mvc.perform(get("/api/v1/activity").with(as(owner)).param(pair[0], pair[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/activity").with(as(owner)).param("from", "2026-02-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test void concurrentIdenticalFolderRenameCommitsOneChangeAndOneEvent() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Object> rename = () -> { start.await(5, TimeUnit.SECONDS); return folderService.renameFolder(owner, child, "same result"); };
        try {
            Future<?> first = pool.submit(rename), second = pool.submit(rename);
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertThat(folders.findById(child).orElseThrow().getName()).isEqualTo("same result");
            assertThat(history()).hasSize(1); assertThat(history().getFirst().eventType()).isEqualTo(RENAMED);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void boundedTableLockTimeoutReturnsSanitized503() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var blocker = pool.submit(() -> tx(() -> {
                em.createNativeQuery("LOCK TABLE resource_activity_events IN ACCESS EXCLUSIVE MODE").executeUpdate();
                locked.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/api/v1/activity").with(as(owner))).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "2")).andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
            release.countDown(); blocker.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void equalTimestampsUseUuidTieBreakerWithoutMutatingHistoricalRows() {
        Instant at = Instant.parse("2020-01-01T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO resource_activity_events(id,actor_id,actor_display_name,actor_attribution,owner_id,resource_type,resource_id,
                        resource_name,event_type,before_name,after_name,before_trashed,after_trashed,occurred_at)
                    VALUES (?,?,'Snapshot','CALLER',?,'FOLDER',?,'after','RENAMED','before','after',false,false,?)
                    """, UUID.randomUUID(), owner, owner, child, java.sql.Timestamp.from(at));
        }
        var all = history();
        assertThat(all.stream().map(a -> a.id().toString()).toList()).isSortedAccordingTo(Comparator.reverseOrder());
        for (int page = 0; page < 3; page++) {
            var result = activity.list(owner, null, null, null, null, at, at, page, 1);
            assertThat(result.content()).containsExactly(all.get(page)); assertThat(result.totalElements()).isEqualTo(3);
        }
    }

    private Runnable mutation(Mutation kind) {
        if (kind == Mutation.FILE_RESTORE) fileService.trashFile(owner, file);
        if (kind == Mutation.FOLDER_RESTORE) folderService.deleteFolder(owner, child);
        return switch (kind) {
            case FILE_RENAME -> () -> fileService.renameFile(owner, file, "changed");
            case FILE_MOVE -> () -> fileService.moveFile(owner, file, destination);
            case FILE_TRASH -> () -> fileService.trashFile(owner, file);
            case FILE_RESTORE -> () -> fileService.restoreFile(owner, file);
            case FOLDER_CREATE -> () -> folderService.createFolder(owner, parent, "created");
            case FOLDER_RENAME -> () -> folderService.renameFolder(owner, child, "changed");
            case FOLDER_MOVE -> () -> folderService.moveFolder(owner, child, destination);
            case FOLDER_TRASH -> () -> folderService.deleteFolder(owner, child);
            case FOLDER_RESTORE -> () -> folderService.restoreFolder(owner, child);
        };
    }
    private List<?> state() {
        return List.of(jdbc.queryForList("SELECT row_to_json(f)::text FROM files f WHERE owner_id = ? ORDER BY id", String.class, owner),
                jdbc.queryForList("SELECT row_to_json(f)::text FROM folders f WHERE owner_id = ? ORDER BY id", String.class, owner));
    }
    private List<Activity> history() { return activity.list(owner, null, null, null, null, null, null, 0, 100).content(); }
    private UUID user(String name) { return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", name)).getId(); }
    private void tx(Runnable action) { new TransactionTemplate(manager).executeWithoutResult(s -> action.run()); }
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor as(UUID actor) {
        return jwt().jwt(j -> j.subject(actor.toString()));
    }
}
