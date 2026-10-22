package com.vaultdrive.search;

import com.vaultdrive.file.*;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.folder.*;
import com.vaultdrive.folder.dto.FolderResponse;
import com.vaultdrive.sharing.*;
import com.vaultdrive.user.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchIntegrationTest {
    private static final String SCHEMA = "search_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired SearchService search;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired FolderRepository folders;
    @Autowired StoredFileRepository files;
    @Autowired GrantService grants;
    @Autowired FolderService folderService;
    @Autowired FileService fileService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    UUID owner, viewer, stranger, privateParent, shared, child, sibling, file;
    String viewerEmail;

    @BeforeEach void fixture() {
        tx(() -> {
            owner = user(); viewer = user(); stranger = user();
            viewerEmail = users.findById(viewer).orElseThrow().getEmail();
            privateParent = folder(owner, null, "private Report");
            shared = folder(owner, privateParent, "Report");
            child = folder(owner, shared, "Report child");
            sibling = folder(owner, privateParent, "Report sibling");
            file = ready(owner, child, "Report.txt", "text/plain", 100);
        });
    }

    @Test void ownersViewersEditorsAndStrangersHaveOnlyCurrentAccess() {
        var folderGrant = grants.create(owner, true, shared, viewerEmail, ResourceRole.VIEWER);
        assertThat(find(owner, "report").totalElements()).isEqualTo(5);
        var page = find(viewer, "rEpOrT");
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.content()).extracting(SearchService.Entry::resourceType)
                .containsExactly(SearchService.ResourceType.FOLDER, SearchService.ResourceType.FOLDER, SearchService.ResourceType.FILE);
        assertThat((FolderResponse) page.content().getFirst().resource()).satisfies(r -> {
            assertThat(r.parentFolderId()).isNull();
            assertThat(r.effectiveRole()).isEqualTo(ResourceRole.VIEWER);
        });
        assertThat((FileResponse) page.content().getLast().resource()).satisfies(r -> {
            assertThat(r.folderId()).isEqualTo(child);
            assertThat(r.allowedCapabilities()).containsExactly("READ_METADATA", "DOWNLOAD");
        });
        var direct = grants.create(owner, false, file, viewerEmail, ResourceRole.EDITOR);
        assertThat(file(find(viewer, "Report.txt")).effectiveRole()).isEqualTo(ResourceRole.EDITOR);
        assertThat(file(find(viewer, "Report.txt")).allowedCapabilities()).doesNotContain("TRASH");
        grants.update(owner, true, shared, folderGrant.id(), ResourceRole.EDITOR, folderGrant.version());
        assertThat(file(find(viewer, "Report.txt")).allowedCapabilities()).contains("TRASH");
        grants.revoke(owner, false, file, direct.id());
        assertThat(file(find(viewer, "Report.txt")).effectiveRole()).isEqualTo(ResourceRole.EDITOR);
        assertThat(find(stranger, "Report").totalElements()).isZero();
        assertThat(find(viewer, "private").totalElements()).isZero();
        assertThat(find(viewer, "sibling").content()).isEmpty();
    }

    @Test void directFileAndFolderSharesDoNotExposePrivateAncestryOrSiblings() throws Exception {
        grants.create(owner, false, file, viewerEmail, ResourceRole.EDITOR);
        assertThat(find(viewer, "Report").totalElements()).isEqualTo(1);
        assertThat(file(find(viewer, "Report")).folderId()).isNull();
        mvc.perform(get("/api/v1/search").param("q", "Report").with(as(viewer)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content[0].resourceType").value("FILE"))
                .andExpect(jsonPath("$.content[0].resource.folderId").isEmpty())
                .andExpect(jsonPath("$.content[0].resource.storageKey").doesNotExist())
                .andExpect(jsonPath("$.content[0].resource.ownerId").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
        grants.create(owner, true, child, viewerEmail, ResourceRole.VIEWER);
        assertThat(find(viewer, "Report").totalElements()).isEqualTo(2);
        assertThat(file(find(viewer, "Report.txt")).folderId()).isEqualTo(child);
    }

    @ParameterizedTest
    @CsvSource({"100%_done!,100%_DONE!", "a_b,a_b", "a%b,a%b", "東京資料,東京", "Résumé,RÉSUMÉ", "emoji😀,😀", "a!b,a!b", "a\\b,a\\b"})
    void matchingIsLiteralAndUnicodeAware(String name, String term) {
        tx(() -> { ready(owner, null, name, null, 0); ready(owner, null, "axb", null, 0); });
        assertThat(find(owner, term).content()).singleElement()
                .satisfies(e -> assertThat(((FileResponse)e.resource()).name()).isEqualTo(name));
    }

    @Test void boundedFiltersCombineAndFileFiltersExcludeFolders() throws Exception {
        grants.create(owner, true, shared, viewerEmail, ResourceRole.VIEWER);
        tx(() -> ready(viewer, null, "Report own", "image/png", 200));
        mvc.perform(get("/api/v1/search").param("q", "Report").param("scope", "OWNED").with(as(viewer)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/search").param("q", "Report").param("scope", "SHARED").with(as(viewer)))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/v1/search").param("q", "Report").param("resourceType", "FOLDER").with(as(viewer)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/search").param("q", "Report").param("resourceType", "FILE")
                .param("mimeType", "TEXT/PLAIN").param("minSizeBytes", "100").param("maxSizeBytes", "100").with(as(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/search").param("q", "Report").param("minSizeBytes", "101").with(as(viewer)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/search").param("q", "Report").param("maxSizeBytes", "99").with(as(viewer)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/search").param("q", "Report").param("mimeType", "image/jpeg").with(as(viewer)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void dateBoundsAreInclusiveOnCreationAndModification() throws Exception {
        tx(() -> {
            em.createNativeQuery("UPDATE files SET created_at = :created, updated_at = :modified WHERE id = :id")
                    .setParameter("created", Instant.parse("2026-01-02T00:00:00Z"))
                    .setParameter("modified", Instant.parse("2026-02-03T00:00:00Z"))
                    .setParameter("id", file).executeUpdate();
            em.createNativeQuery("UPDATE folders SET created_at = :created, updated_at = :modified WHERE id = :id")
                    .setParameter("created", Instant.parse("2026-01-02T00:00:00Z"))
                    .setParameter("modified", Instant.parse("2026-02-03T00:00:00Z"))
                    .setParameter("id", child).executeUpdate();
        });
        mvc.perform(get("/api/v1/search").param("q", "Report")
                .param("createdFrom", "2026-01-02T00:00:00Z").param("createdTo", "2026-01-02T00:00:00Z")
                .param("modifiedFrom", "2026-02-03T00:00:00Z").param("modifiedTo", "2026-02-03T00:00:00Z").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        for (var bound : List.of("createdTo", "modifiedTo")) {
            mvc.perform(get("/api/v1/search").param("q", "Report").param(bound, "2025-01-01T00:00:00Z").with(as(owner)))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
        for (var bound : List.of("createdFrom", "modifiedFrom")) {
            mvc.perform(get("/api/v1/search").param("q", "Report").param(bound, "9999-01-01T00:00:00Z").with(as(owner)))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    @Test void mimeTypeMatchesDeclarationsWithParametersAndSizeZeroIsAFileValue() throws Exception {
        tx(() -> ready(owner, null, "Report parameterized", "Text/Plain; charset=UTF-8", 0));
        mvc.perform(get("/api/v1/search").param("q", "Report").param("mimeType", "text/plain")
                .param("minSizeBytes", "0").param("maxSizeBytes", "0").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].resource.name").value("Report parameterized"));
        for (String mime : List.of("text/*", "*/*", "text/plain; charset=UTF-8", "text/plain\n")) {
            mvc.perform(get("/api/v1/search").param("q", "Report").param("mimeType", mime).with(as(owner)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void stableCombinedPagesHaveTieBreakersAndExactEmptyPageCounts() throws Exception {
        tx(() -> { ready(owner, null, "Report", null, 0); ready(owner, privateParent, "Report", null, 0); });
        var all = find(owner, "Report");
        assertThat(all.totalElements()).isEqualTo(7);
        var equalNames = all.content().stream().filter(e ->
                e.resource() instanceof FileResponse r && r.name().equals("Report")
                || e.resource() instanceof FolderResponse d && d.name().equals("Report")).toList();
        assertThat(equalNames).extracting(SearchService.Entry::resourceType)
                .containsExactly(SearchService.ResourceType.FILE, SearchService.ResourceType.FILE, SearchService.ResourceType.FOLDER);
        assertThat(equalNames.subList(0, 2).stream().map(e -> ((FileResponse)e.resource()).id()).toList())
                .isSortedAccordingTo(java.util.Comparator.comparing(UUID::toString));
        for (int p = 0; p < 7; p++) {
            var page = search.search(owner, filters("Report", p, 1));
            assertThat(page.totalElements()).isEqualTo(7);
            assertThat(page.totalPages()).isEqualTo(7);
            assertThat(page.content()).containsExactly(all.content().get(p));
            assertThat(search.search(owner, filters("Report", p, 1))).isEqualTo(page);
        }
        var empty = search.search(owner, filters("Report", Integer.MAX_VALUE, 100));
        assertThat(empty.content()).isEmpty();
        assertThat(empty.totalElements()).isEqualTo(7);
        assertThat(find(owner, "no-such-name").totalPages()).isZero();
        mvc.perform(get("/api/v1/search").param("q", "Report").with(as(owner)))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50));
    }

    @Test void lifecycleBarriersAndNonReadyFilesNeverContributeToTotals() {
        grants.create(owner, true, shared, viewerEmail, ResourceRole.VIEWER);
        grants.create(owner, false, file, viewerEmail, ResourceRole.EDITOR);
        tx(() -> {
            files.saveAndFlush(new StoredFile(UUID.randomUUID(), owner, child, "Report uploading", UUID.randomUUID().toString(), null, 0));
            var failed = new StoredFile(UUID.randomUUID(), owner, child, "Report failed", UUID.randomUUID().toString(), null, 0);
            failed.markFailed(); files.saveAndFlush(failed);
        });
        assertThat(find(viewer, "Report").totalElements()).isEqualTo(3);
        fileService.trashFile(owner, file);
        assertThat(find(viewer, "Report").totalElements()).isEqualTo(2);
        fileService.requestPermanentDeletion(owner, file);
        assertThat(find(viewer, "Report.txt").totalElements()).isZero();
        folderService.deleteFolder(owner, shared);
        assertThat(find(owner, "Report").totalElements()).isEqualTo(2);
        assertThat(find(viewer, "Report").totalElements()).isZero();
        folderService.requestPermanentDeletion(owner, shared);
        assertThat(find(owner, "child").content()).isEmpty();
        assertThat(find(viewer, "Report").totalElements()).isZero();
    }

    @Test void cyclicOrExcessiveAncestryFailsClosed() {
        grants.create(owner, false, file, viewerEmail, ResourceRole.VIEWER);
        tx(() -> {
            // FK-valid cycle, which cannot be constructed through the API.
            folders.findById(privateParent).orElseThrow().move(child); folders.flush();
        });
        assertThat(find(viewer, "Report").content()).isEmpty();
        assertThat(find(owner, "Report").totalElements()).isZero();
        tx(() -> {
            folders.findById(privateParent).orElseThrow().move(null); folders.flush();
            UUID parent = null;
            for (int i = 0; i < 257; i++) parent = folder(owner, parent, "deep " + i);
            files.findById(file).orElseThrow().move(parent); files.flush();
        });
        assertThat(find(viewer, "Report.txt").content()).isEmpty();
    }

    @Test void multipleOwnersAndOverlappingGrantsDoNotDuplicateResults() {
        grants.create(owner, true, shared, viewerEmail, ResourceRole.VIEWER);
        grants.create(owner, true, child, viewerEmail, ResourceRole.EDITOR);
        grants.create(owner, false, file, viewerEmail, ResourceRole.VIEWER);
        UUID otherFile = new TransactionTemplate(manager).execute(s -> ready(stranger, null, "Report other owner", "text/plain", 1));
        grants.create(stranger, false, otherFile, viewerEmail, ResourceRole.VIEWER);
        assertThat(find(viewer, "Report").totalElements()).isEqualTo(4);
        assertThat(find(viewer, "Report").content()).hasSize(4);
        assertThat(file(find(viewer, "Report.txt")).effectiveRole()).isEqualTo(ResourceRole.EDITOR);
    }

    @Test void searchTableLockTimeoutReturnsSanitized503() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var locker = executor.submit(() -> tx(() -> {
                em.createNativeQuery("LOCK TABLE files IN ACCESS EXCLUSIVE MODE").executeUpdate();
                held.countDown(); await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/api/v1/search").param("q", "Report").with(as(owner)))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "2"))
                    .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("Service temporarily unavailable"));
            release.countDown(); locker.get(10, TimeUnit.SECONDS);
            assertThat(find(owner, "Report").totalElements()).isEqualTo(5);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,2147483648", "size,0", "size,101", "size,abc", "scope,stranger",
            "resourceType,LINK", "minSizeBytes,-1", "maxSizeBytes,-1", "maxSizeBytes,9223372036854775808",
            "mimeType,invalid", "createdFrom,bad-date", "createdTo,+10000-01-01T00:00:00Z"})
    void malformedOrOutOfBoundsParametersAreSanitized(String param, String value) throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "Report").param(param, value).with(as(viewer)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(value))));
    }

    @Test void validatesCrossFieldBoundsAndQueryAndRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "Report")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/search").with(as(owner))).andExpect(status().isBadRequest());
        for (String q : List.of("", "  ", "x".repeat(256), "nul\u0000", "line\n")) {
            mvc.perform(get("/api/v1/search").param("q", q).with(as(owner))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/search").param("q", "Report").param("minSizeBytes", "2").param("maxSizeBytes", "1").with(as(owner)))
                .andExpect(status().isBadRequest());
        for (String prefix : List.of("created", "modified")) {
            mvc.perform(get("/api/v1/search").param("q", "Report").param(prefix + "From", "2026-02-01T00:00:00Z")
                    .param(prefix + "To", "2026-01-01T00:00:00Z").with(as(owner))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/search").param("q", "Report").param("resourceType", "FOLDER").param("minSizeBytes", "0").with(as(owner)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVOKE", "DOWNGRADE", "FILE_MOVE", "FOLDER_MOVE", "TRASH", "RESTORE", "PURGE"})
    void overlappingChangesUseCommittedPermissionLocationAndLifecycleSnapshot(String change) throws Exception {
        var grant = grants.create(owner, true, shared, viewerEmail, ResourceRole.EDITOR);
        if (change.equals("RESTORE") || change.equals("PURGE")) folderService.deleteFolder(owner, shared);
        long beforeTotal = change.equals("RESTORE") || change.equals("PURGE") ? 0 : 1;
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> tx(() -> {
                switch (change) {
                    case "REVOKE" -> grants.revoke(owner, true, shared, grant.id());
                    case "DOWNGRADE" -> grants.update(owner, true, shared, grant.id(), ResourceRole.VIEWER, grant.version());
                    case "FILE_MOVE" -> fileService.moveFile(owner, file, null);
                    case "FOLDER_MOVE" -> folderService.moveFolder(owner, child, null);
                    case "TRASH" -> folderService.deleteFolder(owner, shared);
                    case "RESTORE" -> folderService.restoreFolder(owner, shared);
                    case "PURGE" -> folderService.requestPermanentDeletion(owner, shared);
                    default -> throw new AssertionError(change);
                }
                em.flush(); written.countDown(); await(release);
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            // Cross-owner search deliberately does not wait on the writer's owner hierarchy lock.
            // Both content and count must observe the old committed state while its write is held.
            var old = find(viewer, "Report.txt");
            assertThat(old.totalElements()).isEqualTo(beforeTotal);
            assertThat(old.content()).hasSize((int) beforeTotal);
            if (beforeTotal == 1) {
                assertThat(file(old).effectiveRole()).isEqualTo(ResourceRole.EDITOR);
                assertThat(file(old).folderId()).isEqualTo(child);
            }
            release.countDown(); writer.get(10, TimeUnit.SECONDS);
            var fresh = find(viewer, "Report.txt");
            long afterTotal = change.equals("DOWNGRADE") || change.equals("RESTORE") ? 1 : 0;
            assertThat(fresh.totalElements()).isEqualTo(afterTotal);
            assertThat(fresh.content()).hasSize((int) afterTotal);
            if (afterTotal == 1) assertThat(file(fresh).effectiveRole())
                    .isEqualTo(change.equals("DOWNGRADE") ? ResourceRole.VIEWER : ResourceRole.EDITOR);
            if (afterTotal == 0) assertThatThrownBy(() -> fileService.getFile(viewer, file))
                    .isInstanceOf(com.vaultdrive.file.exception.FileNotFoundException.class);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private SearchService.SearchPage find(UUID actor, String q) { return search.search(actor, filters(q, 0, 50)); }
    private static SearchService.Filters filters(String q, int page, int size) {
        return new SearchService.Filters(q, null, SearchService.Scope.ALL, null, null, null, null, null, null, null, page, size);
    }
    private static FileResponse file(SearchService.SearchPage p) { return (FileResponse)p.content().getFirst().resource(); }
    private void tx(Runnable work) { new TransactionTemplate(manager).executeWithoutResult(s -> work.run()); }
    private UUID user() { return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Search user")).getId(); }
    private UUID folder(UUID user, UUID parent, String name) { return folders.saveAndFlush(new Folder(user, parent, name)).getId(); }
    private UUID ready(UUID user, UUID parent, String name, String mime, long size) {
        var f = new StoredFile(UUID.randomUUID(), user, parent, name, UUID.randomUUID().toString(), mime, size);
        f.markReady(); return files.saveAndFlush(f).getId();
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(UUID actor) {
        return jwt().jwt(j -> j.subject(actor.toString()));
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
