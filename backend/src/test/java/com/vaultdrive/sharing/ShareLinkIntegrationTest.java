package com.vaultdrive.sharing;

import com.vaultdrive.file.*;
import com.vaultdrive.folder.*;
import com.vaultdrive.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Committed disposable fixtures; never clears shared schemas or retained storage allocations. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShareLinkIntegrationTest {
    @Autowired ShareLinkService links;
    @Autowired GrantService grants;
    @Autowired FileService fileService;
    @Autowired FolderService folderService;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired FolderRepository folders;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    UUID owner, viewer, editor, stranger, parent, child, file, second;
    String ownerPath;

    @BeforeEach void fixture() {
        owner = user(); viewer = user(); editor = user(); stranger = user();
        parent = folders.saveAndFlush(new Folder(owner, null, "private-parent")).getId();
        child = folders.saveAndFlush(new Folder(owner, parent, "private-child")).getId();
        file = ready(child, "public.txt"); second = ready(child, "private-sibling.txt");
        ownerPath = "/api/v1/files/" + file + "/share-links";
        grants.create(owner, false, file, users.findById(viewer).orElseThrow().getEmail(), ResourceRole.VIEWER);
        grants.create(owner, false, file, users.findById(editor).orElseThrow().getEmail(), ResourceRole.EDITOR);
    }

    @AfterEach void cleanupOnlyFixtureRows() {
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id IN (?, ?, ?, ?)", file, second, parent, child);
            files.deleteById(file); files.deleteById(second); files.flush();
            folders.deleteById(child); folders.flush(); folders.deleteById(parent); folders.flush();
            for (UUID user : List.of(viewer, editor, stranger, owner)) users.deleteById(user);
        });
    }

    @Test void creationReturnsSecretOnceWithDefaultExpiryAndStoresOnlyHash() throws Exception {
        var result = mvc.perform(post(ownerPath).with(as(owner)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.secret").isString()).andExpect(jsonPath("$.link.tokenHash").doesNotExist()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        String token = body.get("secret").asString(); UUID id = UUID.fromString(body.get("link").get("id").asString());
        var link = links.list(owner, file).getFirst();
        assertThat(link.expiresAt()).isEqualTo(link.createdAt().plus(24, ChronoUnit.HOURS));
        assertThat(jdbc.queryForObject("SELECT token_hash FROM share_links WHERE id = ?", byte[].class, id))
                .isEqualTo(ShareLinkTokens.hash(token));
        assertThat(jdbc.queryForObject("SELECT row_to_json(l)::text FROM share_links l WHERE id = ?", String.class, id))
                .doesNotContain(token);
        mvc.perform(get(ownerPath).with(as(owner))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))))
                .andExpect(jsonPath("$[0].secret").doesNotExist()).andExpect(jsonPath("$[0].tokenHash").doesNotExist());
        assertThat(new ShareLinkService.CreatedLink(link, token).toString()).doesNotContain(token);
        mvc.perform(get(publicPath(id)).header(ShareLinkHttpFilter.TOKEN_HEADER, token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("public.txt"));
        mvc.perform(get(publicPath(id)).header(ShareLinkHttpFilter.TOKEN_HEADER, token).header("Authorization", "Bearer invalid-jwt"))
                .andExpect(status().isOk());
    }

    @Test void expiryValidationAndExactSevenDayDatabaseBoundary() throws Exception {
        // Expiry admission is defined by PostgreSQL time. Do not assume that the
        // test JVM shares its clock (the Windows/VM launch gate remains separate).
        Instant now = jdbc.queryForObject("SELECT statement_timestamp()",
                (rs, row) -> rs.getTimestamp(1).toInstant());
        for (Instant expiry : List.of(now.minusSeconds(1), now.plus(8, ChronoUnit.DAYS))) {
            mvc.perform(post(ownerPath).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expiresAt\":\"" + expiry + "\"}")).andExpect(status().isBadRequest());
        }
        var custom = links.create(owner, file, now.plus(2, ChronoUnit.DAYS));
        assertThat(custom.link().expiresAt()).isEqualTo(now.plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS));
        UUID max = insert("statement_timestamp()", "statement_timestamp() + interval '7 days'", ShareLinkTokens.generate());
        assertThat(max).isNotNull();
        assertThatThrownBy(() -> insert("statement_timestamp()", "statement_timestamp() + interval '7 days 1 microsecond'", ShareLinkTokens.generate()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("statement_timestamp()", "statement_timestamp()", ShareLinkTokens.generate()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void expiredAtDatabaseTimeAndBeforeBoundaryAreDeniedWhileFutureIsAllowed() throws Exception {
        String token = ShareLinkTokens.generate();
        UUID at = insert("statement_timestamp() - interval '1 day'", "statement_timestamp()", token);
        denied(at, token);
        String expiredToken = ShareLinkTokens.generate();
        UUID before = insert("statement_timestamp() - interval '2 days'", "statement_timestamp() - interval '1 microsecond'", expiredToken);
        denied(before, expiredToken);
        var future = links.create(owner, file, null);
        assertThat(links.authorize(future.link().id(), future.secret()).metadata().name()).isEqualTo("public.txt");
    }

    @Test void ownerOnlyManagementDoesNotGrantAuthorityThroughTheSecret() throws Exception {
        var link = links.create(owner, file, null);
        for (UUID actor : new UUID[]{viewer, editor, stranger, null}) {
            int expected = actor == null ? 401 : actor.equals(stranger) ? 404 : 403;
            var list = get(ownerPath).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret());
            var create = post(ownerPath).contentType(MediaType.APPLICATION_JSON).content("{}");
            var revoke = delete(ownerPath + "/" + link.link().id());
            if (actor != null) { list.with(as(actor)); create.with(as(actor)); revoke.with(as(actor)); }
            mvc.perform(list).andExpect(status().is(expected));
            mvc.perform(create).andExpect(status().is(expected));
            mvc.perform(revoke).andExpect(status().is(expected));
        }
        for (String path : List.of("/api/v1/files/" + file, "/api/v1/folders/" + child,
                "/api/v1/files", "/api/v1/upload-sessions", "/api/v1/shared-with-me", ownerPath)) {
            mvc.perform(get(path).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret())).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/upload-sessions").header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test void metadataDisclosesOnlyFileFieldsAndNeverPrivateHierarchyOrIdentity() throws Exception {
        var link = links.create(owner, file, null);
        var result = mvc.perform(get(publicPath(link.link().id())).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("name", "contentType", "sizeBytes");
        assertThat(body.toString()).doesNotContain(owner.toString(), parent.toString(), child.toString(),
                second.toString(), link.secret(), "private", "storage", "grants");
        denied(UUID.randomUUID(), link.secret());
        mvc.perform(get(publicPath(link.link().id()) + "/files/" + second)
                .header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret())).andExpect(status().isNotFound());
    }

    @Test void allInvalidLinkReasonsHaveIdentical404BodiesAndNoStore() throws Exception {
        var valid = links.create(owner, file, null);
        String expected = denied(valid.link().id(), ShareLinkTokens.generate());
        assertThat(denied(UUID.randomUUID(), valid.secret())).isEqualTo(expected);
        assertThat(denied(valid.link().id(), "bad")).isEqualTo(expected);
        assertThat(denied(valid.link().id(), "A".repeat(42) + "B")).isEqualTo(expected);
        mvc.perform(get(publicPath(valid.link().id()))).andExpect(status().isNotFound()).andExpect(content().json(expected));
        mvc.perform(get(publicPath(valid.link().id())).queryParam("secret", valid.secret()))
                .andExpect(status().isNotFound()).andExpect(content().json(expected));
        mvc.perform(get(publicPath(valid.link().id())).queryParam("secret", valid.secret())
                .header(ShareLinkHttpFilter.TOKEN_HEADER, valid.secret()))
                .andExpect(status().isNotFound()).andExpect(content().json(expected));
        mvc.perform(get(publicPath(valid.link().id())).header(ShareLinkHttpFilter.TOKEN_HEADER, valid.secret(), valid.secret()))
                .andExpect(status().isNotFound()).andExpect(content().json(expected));
        mvc.perform(get("/api/v1/public/share-links/not-a-uuid").header(ShareLinkHttpFilter.TOKEN_HEADER, valid.secret()))
                .andExpect(status().isNotFound()).andExpect(content().json(expected));
        mvc.perform(get(publicPath(valid.link().id())).header("Authorization", "Bearer " + valid.secret()))
                .andExpect(status().isNotFound()).andExpect(content().json(expected));
        links.revoke(owner, file, valid.link().id());
        assertThat(denied(valid.link().id(), valid.secret())).isEqualTo(expected);
        var trashed = links.create(owner, file, null);
        folderService.deleteFolder(owner, parent);
        assertThat(denied(trashed.link().id(), trashed.secret())).isEqualTo(expected);
    }

    @Test void revokeIsPermanentIdempotentAndBoundToFileWithoutChangingLifecycle() throws Exception {
        var link = links.create(owner, file, null);
        var before = files.findById(file).orElseThrow();
        links.revoke(owner, second, link.link().id());
        assertThat(links.authorize(link.link().id(), link.secret())).isNotNull();
        mvc.perform(delete(ownerPath + "/" + link.link().id()).with(as(owner))).andExpect(status().isNoContent());
        var revoked = links.list(owner, file).getFirst();
        links.revoke(owner, file, link.link().id());
        assertThat(links.list(owner, file).getFirst()).isEqualTo(revoked);
        assertThat(revoked.version()).isEqualTo(1); assertThat(revoked.revokedAt()).isNotNull();
        denied(link.link().id(), link.secret());
        var renewal = links.create(owner, file, null);
        assertThat(renewal.link().id()).isNotEqualTo(link.link().id());
        assertThat(renewal.secret()).isNotEqualTo(link.secret());
        var after = files.findById(file).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    @Test void trashRestoreAndMovesRevalidateCurrentAncestryAndRetainOnlyEligibleLinks() throws Exception {
        var active = links.create(owner, file, null);
        var revoked = links.create(owner, file, null); links.revoke(owner, file, revoked.link().id());
        String expiredToken = ShareLinkTokens.generate();
        UUID expired = insert("statement_timestamp() - interval '2 days'", "statement_timestamp() - interval '1 day'", expiredToken);
        fileService.trashFile(owner, file);
        denied(active.link().id(), active.secret());
        assertThat(links.list(owner, file)).hasSize(3);
        assertThatThrownBy(() -> links.create(owner, file, null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        fileService.restoreFile(owner, file);
        assertThat(links.authorize(active.link().id(), active.secret())).isNotNull();
        denied(revoked.link().id(), revoked.secret()); denied(expired, expiredToken);
        folderService.deleteFolder(owner, parent);
        denied(active.link().id(), active.secret());
        folderService.restoreFolder(owner, parent);
        assertThat(links.authorize(active.link().id(), active.secret())).isNotNull();
        fileService.moveFile(owner, file, null);
        folderService.deleteFolder(owner, parent);
        assertThat(links.authorize(active.link().id(), active.secret())).isNotNull();
    }

    @Test void ownerCanRevokeDuringTrashButPendingPurgeFreezesManagement() throws Exception {
        var link = links.create(owner, file, null);
        fileService.trashFile(owner, file); links.revoke(owner, file, link.link().id());
        fileService.restoreFile(owner, file); denied(link.link().id(), link.secret());
        var other = links.create(owner, file, null);
        folderService.deleteFolder(owner, parent); folderService.requestPermanentDeletion(owner, parent);
        denied(other.link().id(), other.secret());
        mvc.perform(get(ownerPath).with(as(owner))).andExpect(status().isNotFound());
        mvc.perform(post(ownerPath).with(as(owner)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(ownerPath + "/" + other.link().id()).with(as(owner))).andExpect(status().isNotFound());
    }

    @Test void cyclesAndNonReadyFilesFailClosedOnEveryPublicRead() throws Exception {
        var link = links.create(owner, file, null);
        for (String state : List.of("UPLOADING", "FAILED")) {
            jdbc.update("UPDATE files SET status = ? WHERE id = ?", state, file);
            denied(link.link().id(), link.secret());
            mvc.perform(post(ownerPath).with(as(owner)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
        jdbc.update("UPDATE files SET status = 'READY' WHERE id = ?", file);
        jdbc.update("UPDATE folders SET parent_folder_id = ? WHERE id = ?", child, parent);
        try { denied(link.link().id(), link.secret()); }
        finally { jdbc.update("UPDATE folders SET parent_folder_id = NULL WHERE id = ?", parent); }
    }

    @Test void directFilePurgeBlocksAccessAndManagementAndPhysicalRemovalCascadesLinks() throws Exception {
        var link = links.create(owner, file, null);
        fileService.trashFile(owner, file); fileService.requestPermanentDeletion(owner, file);
        denied(link.link().id(), link.secret());
        mvc.perform(get(ownerPath).with(as(owner))).andExpect(status().isNotFound());
        mvc.perform(delete(ownerPath + "/" + link.link().id()).with(as(owner))).andExpect(status().isNotFound());
        // This fixture has no storage allocation or bytes; remove only its own metadata.
        files.deleteById(file); files.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM share_links WHERE id = ?", Integer.class, link.link().id())).isZero();
    }

    @Test void databaseGuardsBindingHashExpiryAndPermanentRevocation() {
        var link = links.create(owner, file, null);
        for (String change : List.of("owner_id = '" + stranger + "'", "file_id = '" + second + "'",
                "id = '" + UUID.randomUUID() + "'", "token_hash = decode(repeat('ab',32),'hex')",
                "expires_at = expires_at + interval '1 hour'", "created_at = created_at - interval '1 hour'", "version = 1")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE share_links SET " + change + " WHERE id = ?", link.link().id()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insert("statement_timestamp()", "'infinity'::timestamptz", ShareLinkTokens.generate()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO share_links(id,owner_id,file_id,token_hash,expires_at) VALUES (?,?,?,?,statement_timestamp()+interval '1 day')",
                UUID.randomUUID(), stranger, file, ShareLinkTokens.hash(ShareLinkTokens.generate())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("statement_timestamp()", "statement_timestamp()+interval '1 day'", link.secret()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO share_links(id,owner_id,file_id,token_hash,expires_at) VALUES (?,?,?,?,statement_timestamp()+interval '1 day')",
                UUID.randomUUID(), owner, file, new byte[31])).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        links.revoke(owner, file, link.link().id());
        assertThatThrownBy(() -> jdbc.update("UPDATE share_links SET revoked_at = NULL, version = 0 WHERE id = ?", link.link().id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private UUID insert(String created, String expiry, String token) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO share_links(id,owner_id,file_id,token_hash,created_at,expires_at) VALUES (?,?,?,?," + created + "," + expiry + ")",
                id, owner, file, ShareLinkTokens.hash(token)); return id;
    }
    private String denied(UUID id, String token) throws Exception {
        return mvc.perform(get(publicPath(id)).header(ShareLinkHttpFilter.TOKEN_HEADER, token))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
    }
    static String publicPath(UUID id) { return "/api/v1/public/share-links/" + id; }
    private UUID user() { return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Private owner")).getId(); }
    private UUID ready(UUID folder, String name) {
        var f = new StoredFile(UUID.randomUUID(), owner, folder, name, UUID.randomUUID().toString(), "text/plain", 3);
        f.markReady(); return files.saveAndFlush(f).getId();
    }
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor as(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()));
    }
}
