package com.vaultdrive.sharing;

import com.vaultdrive.file.*;
import com.vaultdrive.folder.*;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageObject;
import com.vaultdrive.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL authorization and HTTP streaming; storage is controlled at the I/O boundary. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SharingDownloadIntegrationTest {
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired FolderRepository folders;
    @Autowired GrantService grants;
    @Autowired FolderService folderService;
    @Autowired MockMvc mvc;
    @MockitoBean ObjectStorageService storage;
    UUID owner, viewer, file, folder;
    String key;
    GrantService.Grant grant;

    @BeforeEach
    void fixture() {
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Owner")).getId();
        var recipient = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Viewer"));
        viewer = recipient.getId();
        folder = folders.saveAndFlush(new Folder(owner, null, "shared")).getId();
        key = UUID.randomUUID().toString();
        var f = new StoredFile(UUID.randomUUID(), owner, folder, "shared.txt", key, "text/plain", 3);
        f.markReady(); file = files.saveAndFlush(f).getId();
        grant = grants.create(owner, true, folder, recipient.getEmail(), ResourceRole.VIEWER);
    }

    @AfterEach
    void cleanupOnlyOwnedFixtures() {
        files.deleteById(file); files.flush();
        folders.deleteById(folder); folders.flush();
        users.deleteById(viewer); users.deleteById(owner);
    }

    @Test
    void admittedDownloadStreamsOutsideTransactionAndMayFinishAfterRevocation() throws Exception {
        when(storage.download(key)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // A real exclusive grant transaction can commit during storage I/O: no hierarchy lock remains.
            grants.revoke(owner, true, folder, grant.id());
            return new StorageObject(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        });
        var request = mvc.perform(get("/api/v1/files/{id}/download", file)
                        .with(jwt().jwt(j -> j.subject(viewer.toString()))))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mvc.perform(get("/api/v1/files/{id}/download", file)
                .with(jwt().jwt(j -> j.subject(viewer.toString())))).andExpect(status().isNotFound());
        verify(storage, times(1)).download(key);
    }

    @Test
    void ancestorTrashDeniesDownloadWithoutOpeningStorage() throws Exception {
        folderService.deleteFolder(owner, folder);
        mvc.perform(get("/api/v1/files/{id}/download", file)
                .with(jwt().jwt(j -> j.subject(viewer.toString())))).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }
}
