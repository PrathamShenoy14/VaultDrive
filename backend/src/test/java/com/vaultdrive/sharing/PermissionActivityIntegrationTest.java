package com.vaultdrive.sharing;

import com.vaultdrive.file.*;
import com.vaultdrive.folder.*;
import com.vaultdrive.outbox.*;
import com.vaultdrive.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

import static com.vaultdrive.sharing.PermissionActivityService.ResourceType.*;
import static com.vaultdrive.sharing.PermissionActivityType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** UUID-scoped committed fixtures in the externally selected isolated schema.
 * Immutable synthetic activity remains retained, including after fixture deletion. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionActivityIntegrationTest {
    @Autowired GrantService grants;
    @Autowired ShareLinkService links;
    @Autowired PermissionActivityService activity;
    @Autowired ResourceReadService reads;
    @Autowired FileService fileService;
    @Autowired FolderService folderService;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired FolderRepository folders;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean OutboxWriter outbox;
    UUID owner, recipient, stranger, parent, file;
    String email;

    @BeforeEach void fixture() {
        owner = user("Historical actor"); recipient = user("Recipient"); stranger = user("Stranger");
        email = users.findById(recipient).orElseThrow().getEmail();
        parent = folders.saveAndFlush(new Folder(owner, null, "private-parent")).getId();
        var f = new StoredFile(UUID.randomUUID(), owner, parent, "original.txt", UUID.randomUUID().toString(), "text/plain", 3);
        f.markReady(); file = files.saveAndFlush(f).getId();
    }

    @AfterEach void cleanup() {
        tx(() -> {
            jdbc.update("DELETE FROM outbox_events WHERE id IN (SELECT id FROM permission_activity_events WHERE owner_id = ?) OR aggregate_id IN (?,?)",
                    owner, file, parent);
            jdbc.update("DELETE FROM files WHERE id = ?", file);
            jdbc.update("DELETE FROM folders WHERE id = ?", parent);
            for (UUID id : List.of(owner, recipient, stranger)) jdbc.update("DELETE FROM users WHERE id = ?", id);
        });
    }

    @Test void allMutationKindsRecordSourceSnapshotsAndAtomicOutboxPointers() {
        var fileBefore = jdbc.queryForMap("SELECT version, updated_at FROM files WHERE id = ?", file);
        var grant = createGrant(false);
        grants.update(owner, false, file, grant.id(), ResourceRole.EDITOR, 0L);
        grants.revoke(owner, false, file, grant.id());
        var link = links.create(owner, file, null);
        links.revoke(owner, file, link.link().id());
        var rows = history();
        assertThat(rows).extracting(PermissionActivityService.Activity::eventType)
                .containsExactly(LINK_REVOKED, LINK_CREATED, GRANT_REVOKED, GRANT_ROLE_CHANGED, GRANT_CREATED);
        assertThat(rows).allSatisfy(e -> {
            assertThat(e.actorId()).isEqualTo(owner); assertThat(e.ownerId()).isEqualTo(owner);
            assertThat(e.actorDisplayName()).isEqualTo("Historical actor");
            assertThat(e.resourceId()).isEqualTo(file); assertThat(e.resourceName()).isEqualTo("original.txt");
            var event = jdbc.queryForMap("SELECT * FROM outbox_events WHERE id = ?", e.id());
            assertThat(event.get("event_type")).isEqualTo("PERMISSION_ACTIVITY_RECORDED");
            assertThat(event.get("aggregate_type")).isEqualTo("PERMISSION_ACTIVITY");
            assertThat(event.get("aggregate_id")).isEqualTo(e.id());
            assertThat(event.get("payload_version")).isEqualTo(1);
            assertThat(event.get("delivery_status")).isEqualTo("PENDING");
            assertThat(event.get("payload").toString()).contains(e.id().toString()).doesNotContain(email, link.secret(), "original.txt");
            assertThat(jdbc.queryForObject("SELECT created_at = ? AND next_attempt_at = ? FROM outbox_events WHERE id = ?",
                    Boolean.class, java.sql.Timestamp.from(e.occurredAt()), java.sql.Timestamp.from(e.occurredAt()), e.id())).isTrue();
        });
        assertThat(rows.get(2).subjectVersion()).isEqualTo(2);
        assertThat(rows.get(2).oldRole()).isEqualTo(ResourceRole.EDITOR);
        assertThat(rows.get(3).newRole()).isEqualTo(ResourceRole.EDITOR);
        assertThat(rows.get(4).oldRole()).isNull();
        assertThat(rows.get(4).recipientEmail()).isEqualTo(email);
        assertThat(rows.getFirst().linkExpiresAt()).isEqualTo(link.link().expiresAt());
        assertThat(jdbc.queryForMap("SELECT version, updated_at FROM files WHERE id = ?", file)).isEqualTo(fileBefore);
        var folderGrant = createGrant(true);
        grants.update(owner, true, parent, folderGrant.id(), ResourceRole.EDITOR, 0L);
        grants.revoke(owner, true, parent, folderGrant.id());
        assertThat(activity.list(owner, FOLDER, parent, null, 0, 50).content()).hasSize(3);
        assertThat(history()).hasSize(8); // no inherited descendant fan-out
    }

    @ParameterizedTest @EnumSource(PermissionActivityType.class)
    void outerRollbackRemovesMutationActivityAndOutboxThenRetryRecordsOnce(PermissionActivityType type) {
        Runnable change = mutation(type);
        int before = history().size();
        var state = permissionState();
        assertThatThrownBy(() -> tx(() -> {
            change.run();
            assertThat(history()).hasSize(before + 1);
            throw new IllegalStateException("rollback after flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(history()).hasSize(before);
        assertThat(permissionState()).isEqualTo(state);
        assertOutboxCount(before);
        change.run();
        assertThat(history()).hasSize(before + 1);
        assertOutboxCount(before + 1);
    }

    @ParameterizedTest @EnumSource(PermissionActivityType.class)
    void outboxFailureRollsBackEachMutationAndItsActivity(PermissionActivityType type) {
        Runnable change = mutation(type);
        int before = history().size();
        var state = permissionState();
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(event, "payloadVersion", 0);
            return invocation.callRealMethod();
        }).doCallRealMethod().when(org.springframework.test.util.AopTestUtils.<OutboxWriter>getUltimateTargetObject(outbox))
                .append(any());
        assertThatThrownBy(change::run).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(history()).hasSize(before); assertThat(permissionState()).isEqualTo(state);
        assertOutboxCount(before);
        change.run(); assertThat(history()).hasSize(before + 1); assertOutboxCount(before + 1);
    }

    @Test void uncommittedActivityAndOutboxAreInvisibleToOtherTransactions() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(manager).executeWithoutResult(s -> {
                createGrant(false);
                try {
                    executor.submit(() -> { assertThat(history()).isEmpty(); assertOutboxCount(0); })
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception e) { throw new AssertionError(e); }
                s.setRollbackOnly();
            });
        }
        assertThat(history()).isEmpty(); assertOutboxCount(0);
    }

    @Test void noOpsFailuresAndReplaysDoNotDuplicateHistoryWhileRegrantHasNewIdentity() {
        var grant = createGrant(false);
        grants.update(owner, false, file, grant.id(), ResourceRole.VIEWER, 0L);
        assertThatThrownBy(() -> createGrant(false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        grants.update(owner, false, file, grant.id(), ResourceRole.EDITOR, 0L);
        assertThatThrownBy(() -> grants.update(owner, false, file, grant.id(), ResourceRole.VIEWER, 0L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        grants.revoke(owner, false, file, UUID.randomUUID());
        grants.revoke(owner, false, file, grant.id()); grants.revoke(owner, false, file, grant.id());
        var regrant = createGrant(false); assertThat(regrant.id()).isNotEqualTo(grant.id());
        var link = links.create(owner, file, null);
        links.revoke(owner, file, UUID.randomUUID());
        links.revoke(owner, file, link.link().id()); links.revoke(owner, file, link.link().id());
        assertThat(history()).hasSize(6); assertOutboxCount(6);
    }

    @Test void deniedMutationsCannotEmitActivityOrExposeHistoricalPermission() throws Exception {
        var grant = createGrant(false);
        var link = links.create(owner, file, null);
        for (UUID actor : List.of(recipient, stranger)) {
            assertThatThrownBy(() -> grants.create(actor, false, file, email, ResourceRole.EDITOR)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> grants.update(actor, false, file, grant.id(), ResourceRole.EDITOR, 0L)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> grants.revoke(actor, false, file, grant.id())).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> links.create(actor, file, null)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> links.revoke(actor, file, link.link().id())).isInstanceOf(RuntimeException.class);
        }
        assertThat(history()).hasSize(2); assertOutboxCount(2);
        mvc.perform(get("/api/v1/permission-activity").header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret()))
                .andExpect(status().isUnauthorized());
        grants.revoke(owner, false, file, grant.id());
        assertThatThrownBy(() -> reads.file(recipient, file)).isInstanceOf(com.vaultdrive.file.exception.FileNotFoundException.class);
        assertThat(activity.list(recipient, FILE, file, null, 0, 50).totalElements()).isZero();
    }

    @Test void historicalSnapshotsSurviveRenameRecipientChangesRevocationAndPhysicalRemoval() {
        var grant = createGrant(false);
        fileService.renameFile(owner, file, "renamed.txt");
        jdbc.update("UPDATE users SET email = ?, display_name = 'New actor' WHERE id = ?", "changed-" + email, owner);
        jdbc.update("UPDATE users SET email = ? WHERE id = ?", "new-" + email, recipient);
        grants.revoke(owner, false, file, grant.id());
        var created = history().getLast();
        assertThat(created.resourceName()).isEqualTo("original.txt");
        assertThat(created.actorDisplayName()).isEqualTo("Historical actor");
        assertThat(created.recipientEmail()).isEqualTo(email);
        assertThat(history().getFirst().resourceName()).isEqualTo("renamed.txt");
        assertThat(history().getFirst().recipientEmail()).isEqualTo("new-" + email);
        jdbc.update("DELETE FROM files WHERE id = ?", file);
        assertThat(history()).hasSize(2); assertThat(history().getLast()).isEqualTo(created);
        assertThat(activity.list(recipient, FILE, file, null, 0, 50).content()).isEmpty();
    }

    @Test void databaseRejectsHistoricalMutationDeletionDuplicateRevisionsAndMalformedShapes() {
        createGrant(false); UUID event = history().getFirst().id();
        assertThatThrownBy(() -> jdbc.update("UPDATE permission_activity_events SET resource_name = 'changed' WHERE id = ?", event))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM permission_activity_events WHERE id = ?", event))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO permission_activity_events SELECT ?::uuid, actor_id, actor_display_name, owner_id, resource_type, resource_id, resource_name, event_type, subject_id, subject_version, recipient_id, recipient_email, old_role, new_role, link_expires_at, occurred_at FROM permission_activity_events WHERE id = ?", UUID.randomUUID(), event))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for (String invalid : List.of("'OWNER'", "NULL")) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO permission_activity_events SELECT ?::uuid, actor_id, actor_display_name, owner_id, resource_type, resource_id, resource_name, event_type, ?::uuid, subject_version, recipient_id, recipient_email, old_role, " + invalid + ", link_expires_at, occurred_at FROM permission_activity_events WHERE id = ?", UUID.randomUUID(), UUID.randomUUID(), event))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(history()).hasSize(1);
    }

    @Test void ownerScopeFiltersBeforePaginationAndCollaboratorsNeverSeeHistory() throws Exception {
        var grant = createGrant(false);
        createGrant(true); links.create(owner, file, null);
        for (UUID actor : List.of(recipient, stranger)) {
            for (UUID target : List.of(file, UUID.randomUUID())) {
                mvc.perform(get("/api/v1/permission-activity").with(as(actor)).param("resourceType", "FILE")
                        .param("resourceId", target.toString()).param("page", "100").param("size", "1"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
                        .andExpect(jsonPath("$.content").isEmpty());
            }
        }
        mvc.perform(get("/api/v1/permission-activity")).andExpect(status().isUnauthorized());
        String body = mvc.perform(get("/api/v1/permission-activity").with(as(owner)).param("size", "1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].eventType").value("LINK_CREATED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private-parent", "parentId", "folderPath", "tokenHash", "secret", "storageKey");
        mvc.perform(get("/api/v1/permission-activity").with(as(owner)).param("resourceType", "FILE")
                .param("resourceId", file.toString()).param("eventType", "GRANT_CREATED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].subjectId").value(grant.id().toString()));
        grants.revoke(owner, false, file, grant.id());
        grants.revoke(owner, true, parent, grants.list(owner, true, parent).direct().getFirst().id());
        mvc.perform(get("/api/v1/permission-activity").with(as(recipient)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void deterministicOrderingHandlesTimestampTiesEmptyAndOutOfRangePagesAndValidatesFilters() throws Exception {
        // Copy immutable rows with a common timestamp to test the UUID tie break
        // without updates to existing history.
        createGrant(false); var link = links.create(owner, file, null); links.revoke(owner, file, link.link().id());
        jdbc.update("INSERT INTO permission_activity_events SELECT gen_random_uuid(), actor_id, actor_display_name, owner_id, resource_type, resource_id, resource_name, event_type, gen_random_uuid(), subject_version, recipient_id, recipient_email, old_role, new_role, link_expires_at, '2020-01-01'::timestamptz FROM permission_activity_events WHERE owner_id = ?", owner);
        var all = history(); assertThat(all).hasSize(6);
        var ids = all.stream().map(PermissionActivityService.Activity::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        for (int page = 0; page < 6; page++) {
            var result = activity.list(owner, null, null, null, page, 1);
            assertThat(result.content()).containsExactly(all.get(page)); assertThat(result.totalElements()).isEqualTo(6);
        }
        var tied = all.subList(3, 6).stream().map(e -> e.id().toString()).toList();
        assertThat(tied).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(activity.list(owner, null, null, null, Integer.MAX_VALUE, 100).content()).isEmpty();
        assertThat(activity.list(owner, FILE, UUID.randomUUID(), null, 0, 50).totalElements()).isZero();
        for (var pair : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"}, new String[]{"size", "101"},
                new String[]{"eventType", "UNKNOWN"}, new String[]{"resourceType", "UNKNOWN"},
                new String[]{"resourceId", file.toString()})) {
            mvc.perform(get("/api/v1/permission-activity").with(as(owner)).param(pair[0], pair[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void lifecycleBarriersDoNotEraseOwnerHistoryOrRestoreRecipientAuthority() {
        var grant = createGrant(false); var link = links.create(owner, file, null);
        folderService.deleteFolder(owner, parent);
        assertThat(history()).hasSize(2);
        assertThatThrownBy(() -> links.authorize(link.link().id(), link.secret())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> links.create(owner, file, null)).isInstanceOf(RuntimeException.class);
        grants.revoke(owner, false, file, grant.id()); links.revoke(owner, file, link.link().id());
        assertThat(history()).hasSize(4);
        folderService.restoreFolder(owner, parent);
        assertThatThrownBy(() -> links.authorize(link.link().id(), link.secret())).isInstanceOf(RuntimeException.class);
        assertThat(grants.list(owner, false, file).direct()).isEmpty();
        fileService.moveFile(owner, file, null); // inherited access never comes from history
        assertThat(history()).hasSize(4);
        fileService.trashFile(owner, file); fileService.requestPermanentDeletion(owner, file);
        assertThatThrownBy(() -> grants.revoke(owner, false, file, grant.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> links.revoke(owner, file, link.link().id())).isInstanceOf(RuntimeException.class);
        assertThat(history()).hasSize(4);
    }

    private Runnable mutation(PermissionActivityType type) {
        return switch (type) {
            case GRANT_CREATED -> () -> createGrant(false);
            case GRANT_ROLE_CHANGED -> {
                var grant = createGrant(false);
                yield () -> grants.update(owner, false, file, grant.id(), ResourceRole.EDITOR, 0L);
            }
            case GRANT_REVOKED -> {
                var grant = createGrant(false); yield () -> grants.revoke(owner, false, file, grant.id());
            }
            case LINK_CREATED -> () -> links.create(owner, file, null);
            case LINK_REVOKED -> {
                var link = links.create(owner, file, null); yield () -> links.revoke(owner, file, link.link().id());
            }
        };
    }
    private List<?> permissionState() {
        return List.of(jdbc.queryForList("SELECT row_to_json(g)::text FROM resource_grants g WHERE owner_id = ? ORDER BY id", String.class, owner),
                jdbc.queryForList("SELECT row_to_json(l)::text FROM share_links l WHERE owner_id = ? ORDER BY id", String.class, owner));
    }
    private GrantService.Grant createGrant(boolean folder) {
        return grants.create(owner, folder, folder ? parent : file, email, ResourceRole.VIEWER);
    }
    private List<PermissionActivityService.Activity> history() { return activity.list(owner, null, null, null, 0, 100).content(); }
    private void assertOutboxCount(int count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE id IN (SELECT id FROM permission_activity_events WHERE owner_id = ?)", Integer.class, owner)).isEqualTo(count);
    }
    private UUID user(String name) { return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", name)).getId(); }
    private void tx(Runnable action) { new TransactionTemplate(manager).executeWithoutResult(s -> action.run()); }
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor as(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()));
    }
}
