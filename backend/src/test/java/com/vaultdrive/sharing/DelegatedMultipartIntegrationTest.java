package com.vaultdrive.sharing;

import com.vaultdrive.file.*;
import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.file.MultipartCompletionTransactions.*;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.storage.*;
import com.vaultdrive.storage.MultipartStorage.*;
import com.vaultdrive.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(40)
class DelegatedMultipartIntegrationTest {
    static final String SCHEMA = "sharing_m3_" + UUID.randomUUID().toString().replace("-", "");
    static final String SHA = Base64.getEncoder().encodeToString(new byte[32]);
    static final List<String> GETS = List.of("", "/parts", "/cancellation");
    static final List<String> POSTS = List.of("/initiate", "/part-urls", "/complete", "/recover", "/cancel", "/cancel/recover");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartTransferService transfers;
    @Autowired MultipartCompletionService completions;
    @Autowired MultipartCompletionTransactions completionTx;
    @Autowired MultipartCancellationService cancellations;
    @Autowired MultipartCancellationTransactions cancellationTx;
    @Autowired FolderService folders;
    @Autowired GrantService grants;
    @Autowired ResourceReadService reads;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @Autowired HierarchyCoordinator hierarchy;
    @MockitoBean ObjectStorageService storage;
    UUID owner, editor, otherEditor, viewer, stranger, parent, destination, grant;
    TransactionTemplate tx;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = user(); editor = user(); otherEditor = user(); viewer = user(); stranger = user();
        parent = folders.createFolder(owner, null, "private");
        destination = folders.createFolder(owner, parent, "shared");
        grant = grant(editor, ResourceRole.EDITOR);
        grant(otherEditor, ResourceRole.EDITOR); grant(viewer, ResourceRole.VIEWER);
        reset(storage);
        when(storage.initiateMultipart(any(), any(), any(), any())).thenAnswer(c -> { outside(); return "upload-" + c.getArgument(1); });
        when(storage.signPart(any(), any(), anyInt(), anyLong(), any(), any())).thenAnswer(c -> {
            outside(); return new SignedPart("https://fixture.invalid/redacted", Map.of(), c.getArgument(5));
        });
        when(storage.listParts(any(), any(), anyInt(), anyInt())).thenAnswer(c -> { outside(); return new Page(List.of(new Part(1, 1, "part", SHA)), null); });
        when(storage.completeMultipart(any(), any(), anyList(), any())).thenAnswer(c -> { outside(); return new CompletionAck("final", c.getArgument(3), "COMPOSITE"); });
        doAnswer(c -> { outside(); return null; }).when(storage).abortMultipart(any(), any());
    }
    @AfterEach void fences() {
        verify(storage, never()).delete(any(StorageTarget.class)); verify(storage, never()).delete(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_storage_objects WHERE owner_id = ? AND "
                + "(put_succeeded_at IS NOT NULL OR admitted_file_version IS NOT NULL OR write_precluded_at IS NOT NULL)", Integer.class, owner)).isZero();
    }
    @AfterAll static void dispose(@Autowired DataSource source) throws Exception {
        assertThat(SCHEMA).matches("sharing_m3_[0-9a-f]{32}");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test"); s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void reservationAssignsOwnerAndInitiatorAndReplaysWithoutExtendingIdentity() throws Exception {
        var request = request("delegated");
        var s = sessions.reserve(editor, "same-key", request);
        assertThat(s.ownerId()).isEqualTo(owner); assertThat(s.initiatorUserId()).isEqualTo(editor);
        assertThat(sessions.reserve(editor, "same-key", request)).isEqualTo(s);
        assertThat(sessions.get(owner, s.id())).isEqualTo(s);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM files WHERE id = ?", UUID.class, s.fileId())).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM file_storage_objects WHERE file_id = ?", UUID.class, s.fileId())).isEqualTo(owner);
        String key = jdbc.queryForObject("SELECT storage_key FROM upload_sessions WHERE id = ?", String.class, s.id());
        assertThat(key).contains(owner.toString()).doesNotContain(editor.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_grants WHERE file_id = ?", Integer.class, s.fileId())).isZero();
        assertThatThrownBy(() -> sessions.reserve(editor, "same-key", request("different"))).isInstanceOf(MultipartConflictException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE upload_sessions SET initiator_user_id = ? WHERE id = ?", otherEditor, s.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE upload_sessions SET initiator_user_id = NULL WHERE id = ?", s.id())).isInstanceOf(RuntimeException.class);
        mvc.perform(get("/api/v1/upload-sessions/{id}", s.id()).with(as(editor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(owner.toString())).andExpect(jsonPath("$.initiatorUserId").value(editor.toString()));
        revoke();
        assertThatThrownBy(() -> sessions.reserve(editor, "same-key", request)).isInstanceOf(RuntimeException.class);
        assertThat(sessions.get(owner, s.id())).isEqualTo(s);
        grant(editor, ResourceRole.EDITOR);
        assertThat(sessions.reserve(editor, "same-key", request)).isEqualTo(s);
    }

    @Test void reservationMatrixAndRootOwnership() throws Exception {
        for (UUID actor : List.of(owner, editor, otherEditor)) {
            mvc.perform(post("/api/v1/upload-sessions").with(as(actor)).header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json").content(body(UUID.randomUUID().toString())))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerId").value(owner.toString()));
        }
        mvc.perform(post("/api/v1/upload-sessions").with(as(viewer)).header("Idempotency-Key", "view")
                .contentType("application/json").content(body("view"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/upload-sessions").with(as(stranger)).header("Idempotency-Key", "stranger")
                .contentType("application/json").content(body("stranger"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/upload-sessions").header("Idempotency-Key", "anonymous")
                .contentType("application/json").content(body("anonymous"))).andExpect(status().isUnauthorized());
        var root = sessions.reserve(editor, "root", new Request(null, "root", 1, "text/plain"));
        assertThat(root.ownerId()).isEqualTo(editor);
        assertThatThrownBy(() -> sessions.get(owner, root.id())).isInstanceOf(FileNotFoundException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"VIEWER", "STRANGER", "OTHER_EDITOR", "REVOKED", "DOWNGRADED", "ANONYMOUS"})
    void everySessionEndpointHidesUnauthorizedMembership(String caller) throws Exception {
        var s = open();
        UUID actor = switch (caller) { case "VIEWER" -> viewer; case "STRANGER" -> stranger;
            case "OTHER_EDITOR" -> otherEditor; default -> editor; };
        if (caller.equals("REVOKED")) revoke();
        if (caller.equals("DOWNGRADED")) downgrade();
        clearInvocations(storage);
        for (String suffix : GETS) {
            var req = get("/api/v1/upload-sessions/" + s.id() + suffix);
            if (!caller.equals("ANONYMOUS")) req.with(as(actor));
            mvc.perform(req).andExpect(status().is(caller.equals("ANONYMOUS") ? 401 : 404));
        }
        for (String suffix : POSTS) {
            var req = post("/api/v1/upload-sessions/" + s.id() + suffix).contentType("application/json").content(partsBody());
            if (!caller.equals("ANONYMOUS")) req.with(as(actor));
            mvc.perform(req).andExpect(status().is(caller.equals("ANONYMOUS") ? 401 : 404));
        }
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownerAndInitiatorCanUseAllTransferCompletionAndRecoveryEndpoints(boolean ownerActs) throws Exception {
        UUID actor = ownerActs ? owner : editor;
        var s = reserve();
        mvc.perform(post("/api/v1/upload-sessions/{id}/initiate", s.id()).with(as(actor))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/upload-sessions/{id}/part-urls", s.id()).with(as(actor))
                .contentType("application/json").content(partsBody())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/upload-sessions/{id}/part-urls", s.id()).with(as(actor))
                .contentType("application/json").content(partsBody())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/upload-sessions/{id}/parts", s.id()).with(as(actor))).andExpect(status().isOk());
        var opened = sessions.get(actor, s.id()); evidence(opened);
        mvc.perform(post("/api/v1/upload-sessions/{id}/complete", s.id()).with(as(actor))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/upload-sessions/{id}/recover", s.id()).with(as(actor))).andExpect(status().isOk());
        assertThat(reads.file(editor, s.fileId()).ownerId()).isEqualTo(owner);
        revoke();
        assertThatThrownBy(() -> reads.file(editor, s.fileId())).isInstanceOf(FileNotFoundException.class);
        assertThat(reads.file(owner, s.fileId()).ownerId()).isEqualTo(owner);
        verify(storage, times(1)).completeMultipart(any(), any(), anyList(), any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownerAndInitiatorCanCancelAndRecoverWithoutRepeatedAbort(boolean ownerActs) throws Exception {
        UUID actor = ownerActs ? owner : editor;
        var s = open();
        mvc.perform(post("/api/v1/upload-sessions/{id}/cancel", s.id()).with(as(actor))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/upload-sessions/{id}/cancellation", s.id()).with(as(actor))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/upload-sessions/{id}/cancel/recover", s.id()).with(as(actor))).andExpect(status().isOk());
        assertThatThrownBy(() -> completions.complete(actor, s.id())).isInstanceOf(MultipartConflictException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE id = ?", String.class, s.fileId())).isEqualTo("FAILED");
        verify(storage, times(1)).abortMultipart(any(), any());
    }

    @ParameterizedTest @CsvSource({"SIGN,false", "SIGN,true", "LIST,false", "CREATE,false", "COMPLETE,false", "HEAD,false", "ABORT,false"})
    void revocationDuringStorageRejectsResponseButPreservesAdmittedFacts(String operation, boolean downgrade) {
        var s = operation.equals("CREATE") ? reserve() : open();
        evidence(s);
        if (operation.equals("HEAD")) { freeze(s); completionTx.claim(editor, s.id()); }
        switch (operation) {
            case "SIGN" -> doAnswer(c -> { outside(); if (downgrade) downgrade(); else revoke(); return new SignedPart("https://fixture.invalid", Map.of(), c.getArgument(5)); })
                    .when(storage).signPart(any(), any(), anyInt(), anyLong(), any(), any());
            case "LIST" -> doAnswer(c -> { outside(); revoke(); return new Page(List.of(), null); }).when(storage).listParts(any(), any(), anyInt(), anyInt());
            case "CREATE" -> doAnswer(c -> { outside(); revoke(); return "known-upload"; }).when(storage).initiateMultipart(any(), any(), any(), any());
            case "COMPLETE" -> doAnswer(c -> { outside(); revoke(); return new CompletionAck("final", c.getArgument(3), "COMPOSITE"); })
                    .when(storage).completeMultipart(any(), any(), anyList(), any());
            case "ABORT" -> doAnswer(c -> { outside(); revoke(); return null; }).when(storage).abortMultipart(any(), any());
            case "HEAD" -> doAnswer(c -> { outside(); revoke(); return observed(s); }).when(storage).inspectMultipartObject(any());
        }
        assertThatThrownBy(() -> {
            switch (operation) {
                case "SIGN" -> transfers.partUrls(editor, s.id(), parts());
                case "LIST" -> transfers.list(editor, s.id(), 0, 100);
                case "CREATE" -> transfers.initiate(editor, s.id());
                case "COMPLETE" -> completions.complete(editor, s.id());
                case "ABORT" -> cancellations.cancel(editor, s.id());
                case "HEAD" -> completions.recover(editor, s.id());
            }
        }).isInstanceOf(FileNotFoundException.class);
        var retained = sessions.get(owner, s.id());
        if (operation.equals("CREATE")) assertThat(retained.uploadId()).isEqualTo("known-upload");
        if (operation.equals("COMPLETE") || operation.equals("HEAD")) {
            assertThat(retained.completion().ready()).isTrue(); assertThat(retained.completion().receiptRecorded()).isTrue();
            assertThat(completions.recover(owner, s.id()).completion().ready()).isTrue();
        }
        if (operation.equals("ABORT")) assertThat(cancellations.recover(owner, s.id()).cancelled()).isTrue();
    }

    @Test void frozenManifestDoesNotAuthorizeDispatchAfterRevokeAndOwnerCanRecover() {
        var s = open(); var frozen = freeze(s); revoke();
        assertThatThrownBy(() -> completionTx.claim(editor, s.id())).isInstanceOf(FileNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT dispatch_id FROM multipart_completions WHERE session_id = ?", UUID.class, s.id())).isNull();
        assertThat(sessions.get(owner, s.id()).decisionId()).isEqualTo(frozen.session().decisionId());
        assertThatThrownBy(() -> cancellations.cancel(owner, s.id())).isInstanceOf(MultipartConflictException.class);
        evidence(s);
        assertThat(completions.recover(owner, s.id()).completion().ready()).isTrue();
    }

    @Test void uncertainCallsRemainSingleDispatchAfterRevokeAndRegrant() {
        var create = reserve();
        doThrow(new MultipartStorageException()).when(storage).initiateMultipart(any(), any(), any(), any());
        assertThat(transfers.initiate(editor, create.id()).state()).isEqualTo("INITIATING");
        revoke(); assertThatThrownBy(() -> transfers.initiate(editor, create.id())).isInstanceOf(FileNotFoundException.class);
        grant = grant(editor, ResourceRole.EDITOR);
        assertThat(transfers.initiate(editor, create.id()).state()).isEqualTo("INITIATING");
        verify(storage, times(1)).initiateMultipart(any(), any(), any(), any());
        reset(storage); // New sessions use direct transaction fixture initiation below.
        var complete = open();
        when(storage.listParts(any(), any(), anyInt(), anyInt())).thenReturn(new Page(List.of(new Part(1, 1, "part", SHA)), null));
        doThrow(new MultipartStorageException()).when(storage).completeMultipart(any(), any(), anyList(), any());
        completions.complete(editor, complete.id()); revoke();
        assertThatThrownBy(() -> completions.recover(editor, complete.id())).isInstanceOf(FileNotFoundException.class);
        grant = grant(editor, ResourceRole.EDITOR); completions.recover(editor, complete.id());
        verify(storage, times(1)).completeMultipart(any(), any(), anyList(), any());
        var abort = open(); doThrow(new MultipartStorageException()).when(storage).abortMultipart(any(), any());
        cancellations.cancel(editor, abort.id()); revoke();
        assertThatThrownBy(() -> cancellations.recover(editor, abort.id())).isInstanceOf(FileNotFoundException.class);
        grant(editor, ResourceRole.EDITOR); cancellations.recover(editor, abort.id());
        verify(storage, times(1)).abortMultipart(any(), any());
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void concurrentEditorReservationsScopeKeyToInitiator(boolean sameInitiator, boolean sameName) throws Exception {
        String name = UUID.randomUUID().toString(); UUID actor = sameInitiator ? editor : otherEditor;
        var result = race(() -> sessions.reserve(editor, "collision", request(name)),
                () -> sessions.reserve(actor, "collision", request(sameName ? name : name + "2")));
        var first = (Session) result.getFirst();
        if (sameInitiator && sameName) assertThat(result.getLast()).isEqualTo(first);
        else if (sameInitiator) assertThat(result.getLast()).isInstanceOf(MultipartConflictException.class);
        else if (sameName) assertThat(result.getLast()).isInstanceOf(com.vaultdrive.file.exception.DuplicateFileNameException.class);
        else {
            var second = (Session) result.getLast(); assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(second.initiatorUserId()).isEqualTo(otherEditor);
        }
    }

    @ParameterizedTest
    @CsvSource({"RESERVE", "INITIATE", "PARTS", "LIST", "RESPONSE", "FREEZE", "CLAIM", "PROBE", "CANCEL", "ABORT"})
    void revocationAndDowngradeSerializeAtEveryDurableAdmission(String stage) throws Exception {
        // Both winner orders, and both permission removal forms, use actual PostgreSQL lock waits.
        for (boolean downgrade : List.of(false, true)) for (boolean revokeFirst : List.of(false, true)) {
            var s = reserve();
            if (!List.of("RESERVE", "INITIATE").contains(stage)) s = open(s);
            if (List.of("CLAIM", "PROBE").contains(stage)) freeze(s);
            if (stage.equals("PROBE")) completionTx.claim(editor, s.id());
            if (stage.equals("ABORT")) cancellationTx.cancel(editor, s.id());
            Session current = s;
            Snapshot snapshot = stage.equals("FREEZE") ? completionTx.authorize(editor, s.id()) : null;
            Supplier<?> admission = () -> switch (stage) {
                case "RESERVE" -> sessions.reserve(editor, UUID.randomUUID().toString(), request(UUID.randomUUID().toString()));
                case "INITIATE" -> sessions.claimInitiation(editor, current.id());
                case "PARTS" -> sessions.authorizeParts(editor, current.id(), parts());
                case "LIST" -> sessions.authorizeListing(editor, current.id(), 0, 100);
                case "RESPONSE" -> { sessions.validatePartAuthorization(editor, current.id(), current.revision()); yield "authorized response"; }
                case "FREEZE" -> completionTx.freeze(editor, snapshot, manifest(snapshot));
                case "CLAIM" -> completionTx.claim(editor, current.id());
                case "PROBE" -> completionTx.probe(editor, current.id());
                case "CANCEL" -> cancellationTx.cancel(editor, current.id());
                case "ABORT" -> cancellationTx.claim(editor, current.id());
                default -> throw new AssertionError();
            };
            Supplier<?> removal = () -> { if (downgrade) downgrade(); else revoke(); return "removed"; };
            var result = race(revokeFirst ? removal : admission, revokeFirst ? admission : removal);
            if (revokeFirst) assertThat(result.getLast()).isInstanceOf(RuntimeException.class);
            else assertThat(result.getFirst()).isNotInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sessions.get(editor, current.id())).isInstanceOf(FileNotFoundException.class);
            assertThat(sessions.get(owner, current.id()).id()).isEqualTo(current.id());
            if (downgrade) grants.update(owner, true, destination, grant, ResourceRole.EDITOR, grantVersion());
            else grant = grant(editor, ResourceRole.EDITOR);
        }
    }

    @Test void destinationMoveTrashAndPurgeSuspendInitiatorWithoutChangingStorageIdentity() {
        var s = open(); String before = jdbc.queryForObject("SELECT to_jsonb(a)::text FROM file_storage_objects a WHERE file_id = ?", String.class, s.fileId());
        folders.deleteFolder(owner, parent);
        assertThatThrownBy(() -> sessions.get(editor, s.id())).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> sessions.claimInitiation(owner, s.id())).isInstanceOf(FileNotFoundException.class);
        folders.restoreFolder(owner, parent);
        assertThat(sessions.get(editor, s.id()).id()).isEqualTo(s.id());
        folders.deleteFolder(owner, parent); folders.requestPermanentDeletion(owner, parent);
        assertThatThrownBy(() -> cancellations.cancel(editor, s.id())).isInstanceOf(FileNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(a)::text FROM file_storage_objects a WHERE file_id = ?", String.class, s.fileId())).isEqualTo(before);
    }

    @Test void ownerMoveRecomputesDestinationInheritanceAndCreatorGetsNoIndependentAccess() {
        revoke();
        var inherited = grants.create(owner, true, parent, users.findById(editor).orElseThrow().getEmail(), ResourceRole.EDITOR);
        var s = open();
        UUID privateFolder = folders.createFolder(owner, null, "private-destination");
        folders.moveFolder(owner, destination, privateFolder);
        assertThatThrownBy(() -> transfers.partUrls(editor, s.id(), parts())).isInstanceOf(FileNotFoundException.class);
        assertThat(sessions.get(owner, s.id()).id()).isEqualTo(s.id());
        folders.moveFolder(owner, destination, parent);
        assertThat(transfers.partUrls(editor, s.id(), parts())).hasSize(1);
        grants.revoke(owner, true, parent, inherited.id());
        assertThatThrownBy(() -> sessions.get(editor, s.id())).isInstanceOf(FileNotFoundException.class);
    }

    @Test void sameInitiatorKeyIsIndependentAcrossDestinationOwners() {
        var first = sessions.reserve(editor, "owner-scoped", request("first"));
        UUID secondFolder = folders.createFolder(otherEditor, null, "other-owner");
        grants.create(otherEditor, true, secondFolder, users.findById(editor).orElseThrow().getEmail(), ResourceRole.EDITOR);
        var second = sessions.reserve(editor, "owner-scoped", new Request(secondFolder, "first", 1, "text/plain"));
        assertThat(second.ownerId()).isEqualTo(otherEditor); assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(second.initiatorUserId()).isEqualTo(first.initiatorUserId());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void delegatedCompletionAndOwnerCancellationRetainOneExclusiveDecision(boolean completeFirst) throws Exception {
        var s = open(); var snapshot = completionTx.authorize(editor, s.id());
        Supplier<?> complete = () -> completionTx.freeze(editor, snapshot, manifest(snapshot));
        Supplier<?> cancel = () -> cancellationTx.cancel(owner, s.id());
        var result = race(completeFirst ? complete : cancel, completeFirst ? cancel : complete);
        assertThat(result.getFirst()).isNotInstanceOf(RuntimeException.class);
        assertThat(result.getLast()).isInstanceOf(MultipartConflictException.class);
        assertThat(sessions.get(owner, s.id()).state()).isEqualTo(completeFirst ? "COMPLETION_DECIDED" : "ABANDONMENT_DECIDED");
        verifyNoInteractions(storage);
    }

    @Test void preM3FingerprintReplaysWithoutRewritingOrRenewingSession() {
        var request = new Request(null, "legacy", 1, "text/plain");
        var s = sessions.reserve(owner, "legacy", request);
        tx.executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session");
            // Exact V27 fingerprint for null/legacy/1/text/plain, without owner prefix.
            jdbc.update("UPDATE upload_sessions SET request_fingerprint = ? WHERE id = ?",
                    "f95dfc9618da9dae5a61e9d4529f6392a30eccbadab2e862fcad45b88606fd3b", s.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session");
        });
        assertThat(sessions.reserve(owner, "legacy", request)).isEqualTo(s);
    }

    @Test void v32UpgradeBackfillsAllStatesWithoutChangingAnyRetainedEvidence(@Autowired DataSource source) throws Exception {
        for (String state : List.of("RESERVED", "INITIATING", "OPEN", "FROZEN", "READY", "CANCELLED")) {
            var s = sessions.reserve(owner, state, new Request(null, state, 1, "text/plain"));
            if (state.equals("RESERVED")) continue;
            var d = sessions.claimInitiation(owner, s.id());
            if (state.equals("INITIATING")) continue;
            s = sessions.recordUploadId(owner, s.id(), 1, d.transfer().session().initiationId(), "old-" + s.id());
            sessions.authorizeParts(owner, s.id(), parts());
            if (state.equals("FROZEN")) {
                var a = completionTx.authorize(owner, s.id()); completionTx.freeze(owner, a, manifest(a));
            } else if (state.equals("READY")) { evidence(s); completions.complete(owner, s.id()); }
            else if (state.equals("CANCELLED")) cancellations.cancel(owner, s.id());
        }
        String schema = "sharing_m3_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var old = org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("32").load();
        List<String> tables = List.of("users", "files", "file_storage_objects", "upload_sessions", "multipart_initiation_dispatches",
                "upload_session_parts", "multipart_completions", "multipart_completion_receipts", "multipart_cancellations");
        try {
            old.migrate();
            Map<String, List<String>> before = new LinkedHashMap<>();
            try (var c = source.getConnection()) {
                c.setAutoCommit(false);
                for (String table : tables) {
                    String predicate = table.equals("users") ? "t.id = ?" : List.of("files", "file_storage_objects", "upload_sessions").contains(table)
                            ? "t.owner_id = ?" : "t.session_id IN (SELECT id FROM upload_sessions WHERE owner_id = ?)";
                    var rows = jdbc.queryForList("SELECT (to_jsonb(t)" + (table.equals("upload_sessions") ? " - 'initiator_user_id'" : "")
                            + ")::text FROM " + table + " t WHERE " + predicate + " ORDER BY 1", String.class, owner);
                    before.put(table, rows);
                    // Copy valid production-created states into the pre-M3 fixture, including READY
                    // and immutable evidence. Disable only user guards while importing exact rows;
                    // FK/check constraints remain active. Restore guards before the actual upgrade.
                    try (var statement = c.createStatement()) { statement.execute("ALTER TABLE " + schema + "." + table + " DISABLE TRIGGER USER"); }
                    for (String row : rows) try (var insert = c.prepareStatement("INSERT INTO " + schema + "." + table
                            + " SELECT (jsonb_populate_record(NULL::" + schema + "." + table + ", cast(? AS jsonb))).*")) {
                        insert.setString(1, row); insert.executeUpdate();
                    }
                    try (var statement = c.createStatement()) { statement.execute("ALTER TABLE " + schema + "." + table + " ENABLE TRIGGER USER"); }
                }
                c.commit();
            }
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            for (String table : tables) {
                assertThat(jdbc.queryForList("SELECT (to_jsonb(t)" + (table.equals("upload_sessions") ? " - 'initiator_user_id'" : "")
                        + ")::text FROM " + schema + "." + table + " t ORDER BY 1", String.class)).isEqualTo(before.get(table));
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".upload_sessions WHERE initiator_user_id = owner_id", Integer.class)).isEqualTo(6);
            assertThatThrownBy(() -> jdbc.update("UPDATE " + schema + ".upload_sessions SET initiator_user_id = ?", editor)).isInstanceOf(RuntimeException.class);
        } finally {
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(false).load().clean();
        }
    }

    private UUID user() { return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "M3")).getId(); }
    private UUID grant(UUID actor, ResourceRole role) { return grants.create(owner, true, destination, users.findById(actor).orElseThrow().getEmail(), role).id(); }
    private void revoke() { grants.revoke(owner, true, destination, grant); }
    private long grantVersion() { return jdbc.queryForObject("SELECT version FROM resource_grants WHERE id = ?", Long.class, grant); }
    private void downgrade() { grants.update(owner, true, destination, grant, ResourceRole.VIEWER, grantVersion()); }
    private Request request(String name) { return new Request(destination, name, 1, "text/plain"); }
    private Session reserve() { return sessions.reserve(editor, UUID.randomUUID().toString(), request(UUID.randomUUID().toString())); }
    private Session open() { return open(reserve()); }
    private Session open(Session s) {
        var d = sessions.claimInitiation(editor, s.id());
        var result = sessions.recordUploadId(owner, s.id(), 1, d.transfer().session().initiationId(), "fixture-" + s.id());
        sessions.authorizeParts(editor, s.id(), parts()); return result;
    }
    private List<PartExpectation> parts() { return List.of(new PartExpectation(1, 1, SHA)); }
    private String body(String name) { return "{\"folderId\":\"" + destination + "\",\"name\":\"" + name + "\",\"size\":1,\"contentType\":\"text/plain\"}"; }
    private String partsBody() { return "{\"parts\":[{\"partNumber\":1,\"length\":1,\"sha256\":\"" + SHA + "\"}]}"; }
    private Snapshot freeze(Session s) { var a = completionTx.authorize(editor, s.id()); return completionTx.freeze(editor, a, manifest(a)); }
    private List<ManifestPart> manifest(Snapshot s) { return s.registered().stream().map(p -> new ManifestPart(p.partNumber(), p.length(), p.sha256(), p.registrationId(), "part")).toList(); }
    private void evidence(Session s) {
        when(storage.inspectMultipartObject(any())).thenAnswer(c -> { outside(); return observed(s); });
    }
    private CompletedObject observed(Session s) {
        try {
            String composite = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(new byte[32]));
            return new CompletedObject(1, composite, "final", "text/plain",
                    Map.of("vaultdrive-session", s.id().toString(), "vaultdrive-initiation", s.initiationId().toString()));
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor as(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()));
    }
    private void outside() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        tx.executeWithoutResult(s -> hierarchy.acquireExclusive(owner)); // No owner hierarchy lock spans storage.
    }
    private static Object capture(Supplier<?> task) { try { return task.get(); } catch (RuntimeException e) { return e; } }
    private List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var pid = new AtomicInteger();
        try {
            var winner = pool.submit(() -> capture(() -> tx.execute(status -> {
                Object result = capture(first); held.countDown(); await(release); if (result instanceof RuntimeException) status.setRollbackOnly(); return result;
            })));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var loser = pool.submit(() -> capture(() -> tx.execute(status -> { pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); entered.countDown(); return second.get(); })));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); boolean blocked = false;
            while (System.nanoTime() < deadline) { blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid.get())); if (blocked) break; Thread.yield(); }
            assertThat(blocked).isTrue(); assertThat(loser.isDone()).isFalse(); release.countDown();
            return List.of(winner.get(8, TimeUnit.SECONDS), loser.get(8, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
