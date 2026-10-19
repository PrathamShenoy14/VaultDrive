package com.vaultdrive.sharing;

import org.springframework.context.annotation.Import;

import com.vaultdrive.support.HistoricalUploadFixtures;
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

@Import(HistoricalUploadFixtures.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SharingIntegrationTest {
    @Autowired HistoricalUploadFixtures history;

    @Autowired UserRepository users;
    @Autowired FolderRepository folders;
    @Autowired StoredFileRepository files;
    @Autowired GrantService grants;
    @Autowired ResourceReadService reads;
    @Autowired FileService fileService;
    @Autowired FolderService folderService;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
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
        history.allocation(file);
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
    void viewerCannotMutateContentOrInspectOwnerTrash() throws Exception {
        grant(true, shared.getId(), ResourceRole.VIEWER);
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
        em.refresh(file);
        assertThat(file.getName()).isEqualTo("file.txt");
        assertThat(file.getFolderId()).isEqualTo(child.getId());
        assertThat(file.getDeletedAt()).isNull();
        assertThat(folders.findAll().stream().filter(f -> f.getOwnerId().equals(owner)
                && shared.getId().equals(f.getParentFolderId()) && f.getDeletedAt() == null).toList()).hasSize(1);
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
        var expected = folders.findAll().stream().filter(f -> f.getOwnerId().equals(owner)
                && shared.getId().equals(f.getParentFolderId()) && f.getDeletedAt() == null).toList().stream()
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

    @Test
    void editorCreatesOwnerOwnedFoldersWithInheritedAccessButNoIndependentCreatorGrant() throws Exception {
        var source = grant(true, shared.getId(), ResourceRole.EDITOR);
        UUID created = folderService.createFolder(viewer, child.getId(), "editor-created");
        Folder persisted = folders.findById(created).orElseThrow();
        assertThat(persisted.getOwnerId()).isEqualTo(owner);
        assertThat(persisted.getParentFolderId()).isEqualTo(child.getId());
        assertThat(reads.folder(viewer, created).effectiveRole()).isEqualTo(ResourceRole.EDITOR);
        assertThat(grants.list(owner, true, created).direct()).isEmpty();
        assertThat(reads.folders(viewer, null)).isEmpty();
        mvc.perform(post("/api/v1/folders").with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"http-created\",\"parentFolderId\":\"" + shared.getId() + "\"}"))
                .andExpect(status().isCreated());
        assertThat(folders.findAll().stream().filter(f -> f.getOwnerId().equals(owner)
                && shared.getId().equals(f.getParentFolderId()) && f.getDeletedAt() == null).toList())
                .extracting(Folder::getName).contains("http-created");
        grants.revoke(owner, true, shared.getId(), source.id());
        mvc.perform(get("/api/v1/folders/{id}", created).with(as(viewer))).andExpect(status().isNotFound());
        assertThat(reads.folder(owner, created).name()).isEqualTo("editor-created");
    }

    @Test
    void directlySharedEditorsRenameWithoutLeakingPrivateParentsAndCannotTrashEntryPoints() throws Exception {
        grant(false, file.getId(), ResourceRole.EDITOR);
        grant(true, shared.getId(), ResourceRole.EDITOR);
        // The file has inherited Editor as well; use an independent private file for direct-only access.
        StoredFile direct = ready(sibling.getId(), "direct.txt");
        grant(false, direct.getId(), ResourceRole.EDITOR);
        String key = direct.getStorageKey();
        long initialVersion = direct.getVersion();
        mvc.perform(patch("/api/v1/files/{id}/name", direct.getId()).with(as(viewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"changed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("changed.txt"))
                .andExpect(jsonPath("$.folderId").isEmpty()).andExpect(jsonPath("$.effectiveRole").value("EDITOR"));
        mvc.perform(patch("/api/v1/folders/{id}", shared.getId()).with(as(viewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"renamed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentFolderId").isEmpty())
                .andExpect(jsonPath("$.allowedCapabilities").value(org.hamcrest.Matchers.hasItem("CREATE_FOLDER")));
        // No-op responses must also redact private ancestry.
        assertThat(fileService.renameFile(viewer, direct.getId(), "changed").folderId()).isNull();
        assertThat(folderService.renameFolder(viewer, shared.getId(), "renamed").parentFolderId()).isNull();
        em.refresh(direct);
        assertThat(direct.getStorageKey()).isEqualTo(key);
        assertThat(direct.getVersion()).isEqualTo(initialVersion + 1);
        mvc.perform(delete("/api/v1/files/{id}", direct.getId()).with(as(viewer))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/folders/{id}", shared.getId()).with(as(viewer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/folders/{id}", sibling.getId()).with(as(viewer))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/folders/{id}/children", privateParent.getId()).with(as(viewer)))
                .andExpect(status().isNotFound());
    }

    @Test
    void trashRequiresImmediateParentEditorEvenForDirectEditorsAndRootIsOwnerOnly() throws Exception {
        grant(true, child.getId(), ResourceRole.VIEWER);
        grant(false, file.getId(), ResourceRole.EDITOR);
        mvc.perform(delete("/api/v1/files/{id}", file.getId()).with(as(viewer))).andExpect(status().isForbidden());
        grant(true, shared.getId(), ResourceRole.VIEWER);
        grant(true, sibling.getId(), ResourceRole.EDITOR);
        mvc.perform(delete("/api/v1/folders/{id}", sibling.getId()).with(as(viewer))).andExpect(status().isForbidden());
        StoredFile rootFile = ready(null, "root.txt");
        Folder rootFolder = folder(null, "root");
        grant(false, rootFile.getId(), ResourceRole.EDITOR);
        grant(true, rootFolder.getId(), ResourceRole.EDITOR);
        mvc.perform(delete("/api/v1/files/{id}", rootFile.getId()).with(as(viewer))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/folders/{id}", rootFolder.getId()).with(as(viewer))).andExpect(status().isForbidden());
        // Null destination means the actor's root; a collaborator cannot select the owner's root.
        UUID ownRoot = folderService.createFolder(viewer, null, "own-root");
        assertThat(folders.findById(ownRoot).orElseThrow().getOwnerId()).isEqualTo(viewer);
        assertThat(reads.folders(viewer, null)).extracting("id").containsExactly(ownRoot);
    }

    @Test
    void inheritedEditorRenamesAndTrashesContentIncludingOtherCreatorsAndRetainsOwnerRecovery() throws Exception {
        grant(true, shared.getId(), ResourceRole.EDITOR);
        long initialVersion = file.getVersion();
        grant(false, file.getId(), ResourceRole.VIEWER); // Strongest inherited role wins.
        mvc.perform(patch("/api/v1/files/{id}/name", file.getId()).with(as(viewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"edited\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.folderId").value(child.getId().toString()));
        mvc.perform(patch("/api/v1/folders/{id}", child.getId()).with(as(viewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"edited-child\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.effectiveRole").value("EDITOR"));
        mvc.perform(delete("/api/v1/files/{id}", file.getId()).with(as(viewer))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/files/{id}/restore", file.getId()).with(as(viewer))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/files/{id}/permanent", file.getId()).with(as(viewer))).andExpect(status().isNotFound());
        fileService.restoreFile(owner, file.getId());
        mvc.perform(delete("/api/v1/folders/{id}", child.getId()).with(as(viewer))).andExpect(status().isNoContent());
        assertThat(file.getStorageKey()).isNotBlank();
        mvc.perform(get("/api/v1/files/{id}", file.getId()).with(as(viewer))).andExpect(status().isNotFound());
        folderService.restoreFolder(owner, child.getId());
        assertThat(reads.file(viewer, file.getId()).role()).isEqualTo(ResourceRole.EDITOR);
        em.refresh(file);
        assertThat(file.getVersion()).isEqualTo(initialVersion + 3); // rename, trash, restore
    }

    @Test
    void editorCanReserveMultipartButCannotUseOwnerOnlyOperationsOrLegacyUpload() throws Exception {
        grant(true, shared.getId(), ResourceRole.EDITOR);
        for (String path : new String[]{"/api/v1/files/" + file.getId() + "/move", "/api/v1/folders/" + child.getId() + "/move"}) {
            mvc.perform(patch(path).with(as(viewer)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        for (String path : new String[]{"/api/v1/files/" + file.getId(), "/api/v1/trash/folders/" + child.getId()}) {
            mvc.perform(post(path + "/restore").with(as(viewer))).andExpect(status().isForbidden());
            mvc.perform(delete(path + "/permanent").with(as(viewer))).andExpect(status().isForbidden());
        }
        for (String path : new String[]{"/api/v1/files/" + file.getId(), "/api/v1/folders/" + child.getId()}) {
            mvc.perform(get(path + "/grants").with(as(viewer))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/files/trash").with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/v1/trash/folders").with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(multipart("/api/v1/files").file(new org.springframework.mock.web.MockMultipartFile("file", "no.txt", "text/plain", new byte[]{1})).param("folderId", child.getId().toString())
                .with(as(viewer))).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/upload-sessions").with(as(viewer)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"folderId\":\"" + child.getId()
                        + "\",\"name\":\"no.txt\",\"size\":1,\"contentType\":\"text/plain\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(owner.toString()))
                .andExpect(jsonPath("$.initiatorUserId").value(viewer.toString()));
        assertThat(reads.folder(viewer, child.getId()).allowedCapabilities()).contains("UPLOAD")
                .doesNotContain("MOVE", "RESTORE", "PURGE", "MANAGE_GRANTS");
    }

    @Test
    void strangerAndUnauthenticatedMutationsDoNotRevealResources() throws Exception {
        for (UUID actor : new UUID[]{stranger, null}) {
            // Requests without authentication are built separately so no JWT is accidentally attached.
            int expected = actor == null ? 401 : 404;
            var create = post("/api/v1/folders").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"no\",\"parentFolderId\":\"" + child.getId() + "\"}");
            if (actor != null) create.with(as(actor));
            mvc.perform(create).andExpect(status().is(expected));
            for (String path : new String[]{"/api/v1/files/" + file.getId(), "/api/v1/folders/" + child.getId()}) {
                var rename = patch(path + (path.contains("files") ? "/name" : ""))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"no\"}");
                var trash = delete(path);
                if (actor != null) { rename.with(as(actor)); trash.with(as(actor)); }
                mvc.perform(rename).andExpect(status().is(expected));
                mvc.perform(trash).andExpect(status().is(expected));
            }
        }
    }

    @Test
    void editorMutationsRespectCurrentFolderMovesAndLifecycleBarriers() throws Exception {
        grant(true, shared.getId(), ResourceRole.EDITOR);
        // Moving the entire subtree recomputes inherited access.
        folderService.moveFolder(owner, child.getId(), sibling.getId());
        mvc.perform(patch("/api/v1/files/{id}/name", file.getId()).with(as(viewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"no\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/folders").with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"no\",\"parentFolderId\":\"" + child.getId() + "\"}"))
                .andExpect(status().isNotFound());
        // An independent direct grant survives the move, but cannot authorize trash in the private parent.
        grant(false, file.getId(), ResourceRole.EDITOR);
        assertThat(fileService.renameFile(viewer, file.getId(), "direct").folderId()).isNull();
        mvc.perform(delete("/api/v1/files/{id}", file.getId()).with(as(viewer))).andExpect(status().isForbidden());
        folderService.moveFolder(owner, child.getId(), shared.getId());
        folderService.deleteFolder(owner, shared.getId());
        assertEditorBarriers();
        folderService.requestPermanentDeletion(owner, shared.getId());
        assertEditorBarriers();
        assertThat(files.findById(file.getId()).orElseThrow().getName()).isEqualTo("direct.txt");
    }

    @Test
    void editorNamespaceConflictsRemainOwnerScopedAndIncompleteFilesStayInaccessible() throws Exception {
        grant(true, shared.getId(), ResourceRole.EDITOR);
        folderService.createFolder(owner, child.getId(), "occupied");
        mvc.perform(post("/api/v1/folders").with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"occupied\",\"parentFolderId\":\"" + child.getId() + "\"}"))
                .andExpect(status().isConflict());
        folder(shared.getId(), "occupied");
        mvc.perform(patch("/api/v1/folders/{id}", child.getId()).with(as(viewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"occupied\"}"))
                .andExpect(status().isConflict());
        ready(child.getId(), "occupied.txt");
        mvc.perform(patch("/api/v1/files/{id}/name", file.getId()).with(as(viewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"occupied\"}"))
                .andExpect(status().isConflict());
        for (FileStatus state : new FileStatus[]{FileStatus.UPLOADING, FileStatus.FAILED}) {
            StoredFile incomplete = new StoredFile(UUID.randomUUID(), owner, child.getId(), state.name() + ".txt",
                    UUID.randomUUID().toString(), "text/plain", 1);
            if (state == FileStatus.FAILED) incomplete.markFailed();
            incomplete = files.saveAndFlush(incomplete);
            mvc.perform(patch("/api/v1/files/{id}/name", incomplete.getId()).with(as(viewer))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"no\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/files/{id}", incomplete.getId()).with(as(viewer)))
                    .andExpect(status().isNotFound());
            em.refresh(incomplete);
            assertThat(incomplete.getStatus()).isEqualTo(state);
            assertThat(incomplete.getDeletedAt()).isNull();
        }
        em.refresh(file);
        assertThat(file.getName()).isEqualTo("file.txt");
        assertThat(child.getName()).isEqualTo("child");
    }

    private void assertEditorBarriers() throws Exception {
        mvc.perform(post("/api/v1/folders").with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"no\",\"parentFolderId\":\"" + child.getId() + "\"}"))
                .andExpect(status().isNotFound());
        for (String path : new String[]{"/api/v1/files/" + file.getId(), "/api/v1/folders/" + child.getId()}) {
            mvc.perform(patch(path + (path.contains("files") ? "/name" : "")).with(as(viewer))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"no\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(delete(path).with(as(viewer))).andExpect(status().isNotFound());
        }
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
