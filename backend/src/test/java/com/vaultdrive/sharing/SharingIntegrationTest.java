package com.vaultdrive.sharing;

import com.vaultdrive.file.*;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.*;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.user.*;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SharingIntegrationTest {
    @Autowired UserRepository users;
    @Autowired FolderRepository folders;
    @Autowired StoredFileRepository files;
    @Autowired GrantService grants;
    @Autowired ResourceReadService reads;
    @Autowired FileService fileService;
    @Autowired FolderService folderService;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    @Autowired com.vaultdrive.storage.FileStorageIdentityService identity;
    UUID owner, viewer, stranger;
    Folder privateParent, shared, child, sibling;
    StoredFile file;

    @BeforeEach
    void fixture() {
        owner = user("owner"); viewer = user("viewer"); stranger = user("stranger");
        privateParent = folder(null, "private");
        shared = folder(privateParent.getId(), "shared");
        child = folder(shared.getId(), "child");
        sibling = folder(privateParent.getId(), "sibling");
        file = ready(child.getId(), "file.txt");
    }

    @Test
    void directFileAccessRedactsPrivateParentAndDoesNotExposeSiblings() throws Exception {
        grant(false, file.getId(), ResourceRole.VIEWER);
        var response = fileService.getFile(viewer, file.getId());
        assertThat(response.folderId()).isNull();
        assertThat(response.effectiveRole()).isEqualTo(ResourceRole.VIEWER);
        assertThat(response.allowedCapabilities()).containsExactly("READ_METADATA", "DOWNLOAD");
        assertThatThrownBy(() -> reads.folder(viewer, child.getId())).isInstanceOf(FolderNotFoundException.class);
        assertThatThrownBy(() -> reads.folder(viewer, sibling.getId())).isInstanceOf(FolderNotFoundException.class);
        assertThat(reads.files(viewer, null, 0, 50).content()).isEmpty();
        assertThat(reads.folders(viewer, null)).isEmpty();
        mvc.perform(get("/api/v1/files/{id}", file.getId()).with(as(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.folderId").isEmpty())
                .andExpect(jsonPath("$.storageKey").doesNotExist());
    }

    @Test
    void folderInheritanceUsesStrongestRoleAndRedactsOnlyInaccessibleParent() {
        grant(true, shared.getId(), ResourceRole.EDITOR);
        grant(false, file.getId(), ResourceRole.VIEWER);
        assertThat(reads.folder(viewer, shared.getId()).parentFolderId()).isNull();
        assertThat(reads.folder(viewer, child.getId()).parentFolderId()).isEqualTo(shared.getId());
        assertThat(reads.file(viewer, file.getId()).role()).isEqualTo(ResourceRole.EDITOR);
        assertThat(reads.file(viewer, file.getId()).fileResponse().folderId()).isEqualTo(child.getId());
        assertThat(reads.folders(viewer, shared.getId())).extracting("id").containsExactly(child.getId());
        assertThat(reads.files(viewer, child.getId(), 0, 10).content()).extracting("id").containsExactly(file.getId());
        assertThat(grants.list(owner, false, file.getId()).direct()).hasSize(1);
        assertThat(grants.list(owner, false, file.getId()).inherited()).singleElement()
                .satisfies(g -> assertThat(g.folderId()).isEqualTo(shared.getId()));
    }

    @Test
    void directEditorOutranksInheritedViewerAndOwnerRemainsOwner() {
        grant(true, shared.getId(), ResourceRole.VIEWER);
        grant(false, file.getId(), ResourceRole.EDITOR);
        assertThat(reads.file(viewer, file.getId()).role()).isEqualTo(ResourceRole.EDITOR);
        assertThat(reads.file(owner, file.getId()).role()).isEqualTo(ResourceRole.OWNER);
        assertThat(reads.folder(owner, shared.getId()).parentFolderId()).isEqualTo(privateParent.getId());
    }

    @Test
    void sharedWithMeContainsOnlyVisibleDirectEntryPointsAndFiltersBeforePagination() {
        grant(true, shared.getId(), ResourceRole.VIEWER);
        grant(false, file.getId(), ResourceRole.VIEWER);
        var hidden = ready(null, "aaa-hidden.txt");
        grant(false, hidden.getId(), ResourceRole.VIEWER);
        fileService.trashFile(owner, hidden.getId());
        var page = reads.sharedWithMe(viewer, 0, 1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).singleElement().satisfies(e -> assertThat(e.resourceType()).isEqualTo("FILE"));
        assertThat(reads.sharedWithMe(viewer, 1, 1).content()).singleElement()
                .satisfies(e -> assertThat(e.resourceType()).isEqualTo("FOLDER"));
        assertThat(reads.sharedWithMe(viewer, 5, 1).totalElements()).isEqualTo(2);
        assertThat(reads.sharedWithMe(viewer, 5, 1).content()).isEmpty();
        assertThat(reads.sharedWithMe(stranger, 0, 10).totalElements()).isZero();
    }

    @Test
    void grantCreationAndRoleChangesNeverModifyFileLifecycleOrVersion() {
        var before = reads.file(owner, file.getId());
        long version = file.getVersion();
        var grant = grant(false, file.getId(), ResourceRole.VIEWER);
        var changed = grants.update(owner, false, file.getId(), grant.id(), ResourceRole.EDITOR, 0L);
        assertThat(changed.version()).isEqualTo(1);
        assertThat(grants.update(owner, false, file.getId(), grant.id(), ResourceRole.EDITOR, 1L).version()).isEqualTo(1);
        em.refresh(file);
        assertThat(file.getVersion()).isEqualTo(version);
        assertThat(reads.file(owner, file.getId()).updatedAt()).isEqualTo(before.updatedAt());
        grants.revoke(owner, false, file.getId(), grant.id());
        assertThatThrownBy(() -> reads.file(viewer, file.getId())).isInstanceOf(FileNotFoundException.class);
        assertThat(grant(false, file.getId(), ResourceRole.VIEWER).id()).isNotEqualTo(grant.id());
    }

    @Test
    void revokingDirectGrantRetainsIndependentInheritedAccess() {
        grant(true, shared.getId(), ResourceRole.VIEWER);
        var direct = grant(false, file.getId(), ResourceRole.EDITOR);
        grants.revoke(owner, false, file.getId(), direct.id());
        assertThat(reads.file(viewer, file.getId()).role()).isEqualTo(ResourceRole.VIEWER);
    }

    @Test
    void trashSuspendsInheritanceAndDirectGrantsAndRestoreReactivatesThem() {
        grant(true, shared.getId(), ResourceRole.VIEWER);
        var direct = grant(false, file.getId(), ResourceRole.EDITOR);
        folderService.deleteFolder(owner, shared.getId());
        assertThatThrownBy(() -> reads.file(viewer, file.getId())).isInstanceOf(FileNotFoundException.class);
        assertThat(reads.sharedWithMe(viewer, 0, 10).totalElements()).isZero();
        assertThat(grants.list(owner, false, file.getId()).direct()).hasSize(1);
        grants.update(owner, false, file.getId(), direct.id(), ResourceRole.VIEWER, 0L);
        assertThatThrownBy(() -> grants.update(owner, false, file.getId(), direct.id(), ResourceRole.EDITOR, 1L))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> grants.create(owner, true, child.getId(), email(stranger), ResourceRole.VIEWER))
                .isInstanceOf(ResponseStatusException.class);
        folderService.restoreFolder(owner, shared.getId());
        assertThat(reads.file(viewer, file.getId()).role()).isEqualTo(ResourceRole.VIEWER);
    }

    @Test
    void ownerCanRevokeWhileTrashedAndRestoreDoesNotRecreateGrant() {
        var direct = grant(false, file.getId(), ResourceRole.VIEWER);
        fileService.trashFile(owner, file.getId());
        grants.revoke(owner, false, file.getId(), direct.id());
        fileService.restoreFile(owner, file.getId());
        assertThatThrownBy(() -> reads.file(viewer, file.getId())).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void rootFallbackRestoreDropsOldInheritanceButRetainsDirectGrant() {
        grant(true, child.getId(), ResourceRole.EDITOR);
        grant(false, file.getId(), ResourceRole.VIEWER);
        fileService.trashFile(owner, file.getId());
        folderService.deleteFolder(owner, shared.getId());
        assertThat(fileService.restoreFile(owner, file.getId()).folderId()).isNull();
        assertThat(reads.file(viewer, file.getId()).role()).isEqualTo(ResourceRole.VIEWER);
    }

    @Test
    void ownerMovesRecomputeInheritanceAndPreserveDirectGrant() {
        grant(true, shared.getId(), ResourceRole.EDITOR);
        var direct = grant(false, file.getId(), ResourceRole.VIEWER);
        fileService.moveFile(owner, file.getId(), null);
        assertThat(reads.file(viewer, file.getId()).role()).isEqualTo(ResourceRole.VIEWER);
        fileService.moveFile(owner, file.getId(), child.getId());
        grants.revoke(owner, false, file.getId(), direct.id());
        folderService.moveFolder(owner, child.getId(), null);
        assertThatThrownBy(() -> reads.file(viewer, file.getId())).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void pendingPurgeBlocksAllGrantOperationsAndReads() {
        var grant = grant(false, file.getId(), ResourceRole.VIEWER);
        folderService.deleteFolder(owner, shared.getId());
        folderService.requestPermanentDeletion(owner, shared.getId());
        assertThatThrownBy(() -> grants.list(owner, false, file.getId())).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> grants.revoke(owner, false, file.getId(), grant.id())).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> grants.update(owner, false, file.getId(), grant.id(), ResourceRole.EDITOR, 0L))
                .isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> grant(false, file.getId(), ResourceRole.VIEWER)).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> reads.file(viewer, file.getId())).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void onlyReadyFilesAreVisibleEvenWithInheritedAccess() {
        grant(true, shared.getId(), ResourceRole.VIEWER);
        var uploading = files.saveAndFlush(new StoredFile(UUID.randomUUID(), owner, child.getId(), "upload.txt",
                UUID.randomUUID().toString(), "text/plain", 1));
        assertThatThrownBy(() -> reads.file(viewer, uploading.getId())).isInstanceOf(FileNotFoundException.class);
        em.createNativeQuery("UPDATE files SET status = 'FAILED' WHERE id = :id").setParameter("id", uploading.getId()).executeUpdate();
        assertThat(reads.files(viewer, child.getId(), 0, 10).totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> reads.file(owner, uploading.getId())).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void cyclesAndExcessiveAncestryFailClosed() {
        grant(true, shared.getId(), ResourceRole.VIEWER);
        em.createNativeQuery("UPDATE folders SET parent_folder_id = :child WHERE id = :parent")
                .setParameter("child", child.getId()).setParameter("parent", shared.getId()).executeUpdate();
        assertThatThrownBy(() -> reads.file(viewer, file.getId())).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> reads.folder(owner, shared.getId())).isInstanceOf(FolderNotFoundException.class);
        Folder deepest = folder(null, "depth-0");
        for (int i = 1; i < 258; i++) deepest = folder(deepest.getId(), "depth-" + i);
        UUID id = deepest.getId();
        assertThatThrownBy(() -> reads.folder(owner, id)).isInstanceOf(FolderNotFoundException.class);
    }

    @Test
    void postgresGuardsTargetsRolesOwnershipRecipientIdentityAndVersions() {
        var grant = grant(false, file.getId(), ResourceRole.VIEWER);
        String base = "INSERT INTO resource_grants(id, owner_id, file_id, folder_id, grantee_user_id, role) VALUES ('";
        invalid(base + UUID.randomUUID() + "','" + owner + "',NULL,NULL,'" + viewer + "','VIEWER')");
        invalid(base + UUID.randomUUID() + "','" + owner + "','" + file.getId() + "','" + shared.getId() + "','" + viewer + "','VIEWER')");
        invalid(base + UUID.randomUUID() + "','" + owner + "','" + file.getId() + "',NULL,'" + stranger + "','OWNER')");
        invalid(base + UUID.randomUUID() + "','" + owner + "','" + file.getId() + "',NULL,'" + owner + "','VIEWER')");
        invalid(base + UUID.randomUUID() + "','" + stranger + "','" + file.getId() + "',NULL,'" + viewer + "','VIEWER')");
        invalid(base + UUID.randomUUID() + "','" + owner + "','" + file.getId() + "',NULL,'" + UUID.randomUUID() + "','VIEWER')");
        invalid(base + UUID.randomUUID() + "','" + owner + "','" + file.getId() + "',NULL,'" + viewer + "','VIEWER')");
        invalid("UPDATE resource_grants SET role='EDITOR' WHERE id='" + grant.id() + "'");
        invalid("UPDATE resource_grants SET grantee_user_id='" + stranger + "',version=1 WHERE id='" + grant.id() + "'");
        invalid("UPDATE resource_grants SET file_id=NULL,folder_id='" + shared.getId() + "',version=1 WHERE id='" + grant.id() + "'");
        invalid("UPDATE resource_grants SET owner_id='" + stranger + "',version=1 WHERE id='" + grant.id() + "'");
        invalid("UPDATE resource_grants SET id='" + UUID.randomUUID() + "',version=1 WHERE id='" + grant.id() + "'");
        invalid("UPDATE resource_grants SET created_at=created_at+interval '1 second',version=1 WHERE id='" + grant.id() + "'");
        grant(true, shared.getId(), ResourceRole.VIEWER);
        invalid(base + UUID.randomUUID() + "','" + owner + "',NULL,'" + shared.getId() + "','" + viewer + "','VIEWER')");
        invalid(base + UUID.randomUUID() + "','" + stranger + "',NULL,'" + shared.getId() + "','" + viewer + "','VIEWER')");
        assertThat(reads.file(viewer, file.getId()).role()).isEqualTo(ResourceRole.VIEWER);
    }

    @Test
    void metadataDeletionCascadesOnlyTargetGrants() {
        identity.reserve(file);
        grant(false, file.getId(), ResourceRole.VIEWER);
        grant(true, shared.getId(), ResourceRole.VIEWER);
        files.delete(file); files.flush();
        assertThat(grants.list(owner, true, shared.getId()).direct()).hasSize(1);
        assertThat(reads.sharedWithMe(viewer, 0, 10).totalElements()).isEqualTo(1);
        assertThat(((Number)em.createNativeQuery("SELECT count(*) FROM resource_grants WHERE file_id = :id")
                .setParameter("id", file.getId()).getSingleResult()).longValue()).isZero();
        assertThat(((Number)em.createNativeQuery("SELECT count(*) FROM file_storage_objects WHERE file_id = :id")
                .setParameter("id", file.getId()).getSingleResult()).longValue()).isEqualTo(1);
        grant(true, child.getId(), ResourceRole.VIEWER);
        folders.delete(child); folders.flush();
        assertThat(((Number)em.createNativeQuery("SELECT count(*) FROM resource_grants WHERE folder_id = :id")
                .setParameter("id", child.getId()).getSingleResult()).longValue()).isZero();
    }

    @Test
    void grantHttpContractIncludesOwnerOnlyManagementAndOptimisticPreconditions() throws Exception {
        String path = "/api/v1/files/" + file.getId() + "/grants";
        mvc.perform(post(path).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\" " + email(viewer).toUpperCase() + " \",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""));
        var grant = grants.list(owner, false, file.getId()).direct().getFirst();
        mvc.perform(get(path).with(as(viewer))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(as(stranger))).andExpect(status().isNotFound());
        mvc.perform(post(path).with(as(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nonexistent@example.test\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch(path + "/" + grant.id()).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"EDITOR\"}")).andExpect(status().isPreconditionRequired());
        mvc.perform(patch(path + "/" + grant.id()).with(as(owner)).header("If-Match", "\"9\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(patch(path + "/" + grant.id()).with(as(owner)).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        mvc.perform(delete(path + "/" + grant.id()).with(as(viewer))).andExpect(status().isForbidden());
        mvc.perform(delete(path + "/" + grant.id()).with(as(owner))).andExpect(status().isNoContent());
        mvc.perform(delete(path + "/" + grant.id()).with(as(owner))).andExpect(status().isNoContent());
    }

    @Test
    void rejectsSelfDuplicateUnregisteredAndOwnerRoleGrants() throws Exception {
        grant(false, file.getId(), ResourceRole.VIEWER);
        String path = "/api/v1/files/" + file.getId() + "/grants";
        for (String recipient : new String[]{email(owner), "missing@example.test"}) {
            mvc.perform(post(path).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + recipient + "\",\"role\":\"VIEWER\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(path).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(viewer) + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(path).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(stranger) + "\",\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void routesRequireAuthenticationAndBoundPagination() throws Exception {
        mvc.perform(get("/api/v1/shared-with-me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/files/{id}", file.getId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/folders/{id}/grants", shared.getId())).andExpect(status().isUnauthorized());
        for (String query : new String[]{"?page=-1", "?size=0", "?size=101"}) {
            mvc.perform(get("/api/v1/shared-with-me" + query).with(as(viewer))).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/files" + query).with(as(viewer))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void viewerAndEditorCannotMutateContentOrInspectOwnerTrash() throws Exception {
        var grant = grant(true, shared.getId(), ResourceRole.VIEWER);
        for (ResourceRole role : new ResourceRole[]{ResourceRole.VIEWER, ResourceRole.EDITOR}) {
            if (role == ResourceRole.EDITOR) grants.update(owner, true, shared.getId(), grant.id(), role, 0L);
            mvc.perform(patch("/api/v1/files/{id}/name", file.getId()).with(as(viewer))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"changed.txt\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/files/{id}/move", file.getId()).with(as(viewer))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"folderId\":null}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/files/{id}", file.getId()).with(as(viewer))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/files/{id}/restore", file.getId()).with(as(viewer))).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/files/{id}/permanent", file.getId()).with(as(viewer))).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/folders/{id}", child.getId()).with(as(viewer))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/folders").with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"new\",\"parentFolderId\":\"" + shared.getId() + "\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/files/trash").with(as(viewer))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty());
        }
        em.refresh(file);
        assertThat(file.getName()).isEqualTo("file.txt");
        assertThat(file.getFolderId()).isEqualTo(child.getId());
        assertThat(file.getDeletedAt()).isNull();
        assertThat(folders.findByOwnerIdAndParentFolderIdAndDeletedAtIsNull(owner, shared.getId())).hasSize(1);
    }

    @Test
    void ownerAndCollaboratorListingsPreserveStableOrderingAndCounts() {
        grant(true, shared.getId(), ResourceRole.VIEWER);
        Folder lower = folder(shared.getId(), "alpha");
        Folder upper = folder(shared.getId(), "Alpha");
        assertThat(reads.folders(viewer, shared.getId())).extracting("id")
                .containsExactly(upper.getId(), lower.getId(), child.getId());
        assertThat(reads.folders(owner, shared.getId())).extracting("id")
                .containsExactly(upper.getId(), lower.getId(), child.getId());
        ready(child.getId(), "aaa.txt");
        assertThat(reads.files(viewer, child.getId(), 0, 1).content()).extracting("name").containsExactly("aaa.txt");
        assertThat(reads.files(viewer, child.getId(), 1, 1).content()).extracting("name").containsExactly("file.txt");
        assertThat(reads.files(owner, child.getId(), 9, 1).totalElements()).isEqualTo(2);
        folder(shared.getId(), "\u03c3");
        folder(shared.getId(), "\u03c2");
        var expected = folders.findByOwnerIdAndParentFolderIdAndDeletedAtIsNull(owner, shared.getId()).stream()
                .sorted(java.util.Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Folder::getName).thenComparing(Folder::getId)).map(Folder::getId).toList();
        assertThat(reads.folders(viewer, shared.getId())).extracting("id").containsExactlyElementsOf(expected);
    }

    @Test
    void folderGrantHttpRoutesReturnSourcesAndScopeGrantIdsToTheirTarget() throws Exception {
        String path = "/api/v1/folders/" + shared.getId() + "/grants";
        mvc.perform(post(path).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(viewer) + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        var grant = grants.list(owner, true, shared.getId()).direct().getFirst();
        mvc.perform(get("/api/v1/folders/{id}/grants", child.getId()).with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.direct").isEmpty())
                .andExpect(jsonPath("$.inherited[0].folderId").value(shared.getId().toString()));
        mvc.perform(patch("/api/v1/folders/{id}/grants/{grant}", child.getId(), grant.id()).with(as(owner))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/folders/{id}/grants/{grant}", child.getId(), grant.id()).with(as(owner)))
                .andExpect(status().isNoContent());
        assertThat(grants.list(owner, true, shared.getId()).direct()).hasSize(1);
        mvc.perform(get("/api/v1/shared-with-me").with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].resource.parentFolderId").isEmpty())
                .andExpect(jsonPath("$.content[0].resource.effectiveRole").value("VIEWER"));
    }

    private UUID user(String name) {
        return users.saveAndFlush(new User(name + "-" + UUID.randomUUID() + "@example.test", "hash", name)).getId();
    }
    private String email(UUID user) { return users.findById(user).orElseThrow().getEmail(); }
    private Folder folder(UUID parent, String name) { return folders.saveAndFlush(new Folder(owner, parent, name)); }
    private StoredFile ready(UUID folder, String name) {
        StoredFile f = files.saveAndFlush(new StoredFile(UUID.randomUUID(), owner, folder, name,
                UUID.randomUUID().toString(), "text/plain", 1));
        f.markReady(); return files.saveAndFlush(f);
    }
    private GrantService.Grant grant(boolean folder, UUID id, ResourceRole role) {
        return grants.create(owner, folder, id, email(viewer), role);
    }
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor as(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()));
    }
    private void invalid(String sql) {
        em.unwrap(Session.class).doWork(connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(sql)).isInstanceOf(java.sql.SQLException.class);
            } finally {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            }
        });
    }
}
