package com.vaultdrive.sharing;

import com.vaultdrive.file.*;
import com.vaultdrive.folder.*;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.storage.StorageObject;
import com.vaultdrive.user.*;
import jakarta.servlet.ServletOutputStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vaultdrive.share-links.max-downloads=1", "vaultdrive.share-links.max-requests=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShareLinkDownloadIntegrationTest {
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired FolderRepository folders;
    @Autowired ShareLinkService links;
    @Autowired FolderService folderService;
    @Autowired PublicShareLinkController controller;
    @Autowired ShareLinkAdmission admission;
    @Autowired MockMvc mvc;
    @MockitoBean ObjectStorageService storage;
    UUID owner, file, folder;
    String key;
    ShareLinkService.CreatedLink link;

    @BeforeEach void fixture() {
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Owner")).getId();
        folder = folders.saveAndFlush(new Folder(owner, null, "private")).getId();
        key = UUID.randomUUID().toString();
        var f = new StoredFile(UUID.randomUUID(), owner, folder, "public.txt", key, "text/html", 3);
        f.markReady(); file = files.saveAndFlush(f).getId();
        link = links.create(owner, file, null);
    }

    @AfterEach void cleanupOnlyFixture() {
        files.deleteById(file); files.flush(); folders.deleteById(folder); folders.flush(); users.deleteById(owner);
    }

    @Test void admittedStreamMayFinishAfterRevokeAndAllStorageAndStreamingIsOutsideTransactions() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        when(storage.download(key)).thenAnswer(call -> {
            outside();
            // This real exclusive hierarchy transaction must complete during I/O.
            links.revoke(owner, file, link.link().id());
            return new StorageObject(new ByteArrayInputStream(new byte[]{1, 2, 3}) {
                @Override public long transferTo(OutputStream output) throws IOException {
                    outside();
                    return super.transferTo(output);
                }
                @Override public void close() throws IOException { closed.set(true); super.close(); }
            });
        });
        mvc.perform(get(downloadPath()).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret()))
                .andExpect(status().isOk()).andExpect(request().asyncNotStarted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Length", "3"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().doesNotExist("Location")).andExpect(content().bytes(new byte[]{1, 2, 3}));
        assertThat(closed).isTrue();
        mvc.perform(get(downloadPath()).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret())).andExpect(status().isNotFound());
        verify(storage, times(1)).download(key);
    }

    @Test void invalidSecretAndAncestorTrashNeverOpenStorage() throws Exception {
        mvc.perform(get(downloadPath()).header(ShareLinkHttpFilter.TOKEN_HEADER, ShareLinkTokens.generate()))
                .andExpect(status().isNotFound());
        folderService.deleteFolder(owner, folder);
        mvc.perform(get(downloadPath()).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret())).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test void capacityRejectsBeforeDatabaseAndStorageAndReleasesForNextRequest() throws Exception {
        try (var permit = admission.download()) {
            mvc.perform(get(downloadPath()).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret()))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        try (var first = admission.request(); var second = admission.request()) {
            mvc.perform(get(downloadPath()).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret()))
                    .andExpect(status().isTooManyRequests());
        }
        verifyNoInteractions(storage);
        doReturn(new StorageObject(new ByteArrayInputStream(new byte[]{1, 2, 3}))).when(storage).download(key);
        mvc.perform(get(downloadPath()).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret())).andExpect(status().isOk());
    }

    @Test void storageFailureIsSanitizedAndDoesNotLeakCapacityOrIdentity() throws Exception {
        when(storage.download(key)).thenThrow(new IllegalStateException("provider-private-" + key));
        mvc.perform(get(downloadPath()).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret()))
                .andExpect(status().isBadGateway()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"message\":\"Download unavailable\"}"));
        doReturn(new StorageObject(new ByteArrayInputStream(new byte[]{1, 2, 3}))).when(storage).download(key);
        mvc.perform(get(downloadPath()).header(ShareLinkHttpFilter.TOKEN_HEADER, link.secret())).andExpect(status().isOk());
    }

    @Test void clientDisconnectClosesStorageAndReleasesDownloadSlot() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        when(storage.download(key)).thenReturn(new StorageObject(new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public void close() { closed.set(true); }
        }));
        var disconnected = new MockHttpServletResponse() {
            @Override public ServletOutputStream getOutputStream() { throw new java.io.UncheckedIOException(new IOException("client disconnected")); }
        };
        assertThatThrownBy(() -> controller.download(link.link().id(), link.secret(), disconnected))
                .isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(closed).isTrue();
        try (var permit = admission.download()) { assertThat(permit).isNotNull(); }
    }

    private String downloadPath() { return ShareLinkIntegrationTest.publicPath(link.link().id()) + "/download"; }
    private static void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
}
