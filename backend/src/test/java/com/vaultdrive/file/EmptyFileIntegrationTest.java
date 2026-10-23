package com.vaultdrive.file;

import com.vaultdrive.activity.ResourceActivityService;
import com.vaultdrive.file.EmptyFileTransactions.*;
import com.vaultdrive.folder.*;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.job.*;
import com.vaultdrive.sharing.*;
import com.vaultdrive.storage.*;
import com.vaultdrive.user.*;
import com.vaultdrive.worker.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.io.ByteArrayInputStream;
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

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS) @Timeout(45)
class EmptyFileIntegrationTest {
    static final String SCHEMA="empty_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema="+SCHEMA);
        r.add("spring.flyway.schemas",()->SCHEMA); r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @Autowired EmptyFileService service;
    @Autowired EmptyFileTransactions creations;
    @Autowired UploadFinalizationTransactions finalization;
    @Autowired FileService files;
    @Autowired FolderService folders;
    @Autowired StoredFileRepository repository;
    @Autowired UserRepository users;
    @Autowired GrantService grants;
    @Autowired ShareLinkService links;
    @Autowired MultipartSessionService multipart;
    @Autowired FileCopyService copies;
    @Autowired FolderCopyService folderCopies;
    @Autowired FolderCopyTransactions folderTx;
    @Autowired StorageUsageService usage;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @Autowired HierarchyCoordinator hierarchy;
    @Autowired BackgroundJobService jobs;
    @Autowired FilePurgeJobHandler purge;
    @Autowired FolderPurgeJobHandler folderPurge;
    @MockitoBean ObjectStorageService storage;
    @MockitoSpyBean ResourceActivityService activity;
    UUID owner,editor,viewer,folder;
    TransactionTemplate tx;
    final Map<String,CopyStorage.ObjectInfo> objects=new ConcurrentHashMap<>();

    @BeforeEach void setup() {
        reset(storage,org.springframework.test.util.AopTestUtils.<ResourceActivityService>getUltimateTargetObject(activity)); objects.clear(); tx=new TransactionTemplate(manager);
        owner=users.saveAndFlush(new User(UUID.randomUUID()+"@example.com","fixture","Owner")).getId();
        editor=users.saveAndFlush(new User(UUID.randomUUID()+"@example.com","fixture","Editor")).getId();
        viewer=users.saveAndFlush(new User(UUID.randomUUID()+"@example.com","fixture","Viewer")).getId();
        folder=folders.createFolder(owner,null,"destination");
        grants.create(owner,true,folder,users.findById(editor).orElseThrow().getEmail(),ResourceRole.EDITOR);
        grants.create(owner,true,folder,users.findById(viewer).orElseThrow().getEmail(),ResourceRole.VIEWER);
        doAnswer(c->{noTransaction(); StorageTarget t=c.getArgument(0); UUID id=c.getArgument(1);
            objects.put(t.key(),new CopyStorage.ObjectInfo(0,"\"empty\"",c.getArgument(2),Map.of("vaultdrive-empty",id.toString())));return null;
        }).when(storage).putEmpty(any(),any(),any());
        when(storage.inspectCopyObject(any())).thenAnswer(c->{noTransaction(); return objects.get(((StorageTarget)c.getArgument(0)).key());});
        when(storage.download(anyString())).thenAnswer(c->{noTransaction(); return new StorageObject(new ByteArrayInputStream(new byte[0]));});
        doAnswer(c->{noTransaction(); CopyStorage.Command x=c.getArgument(0);
            objects.put(x.destination().key(),new CopyStorage.ObjectInfo(0,"\"copied\"",x.contentType(),Map.of("vaultdrive-copy",x.operationId().toString())));return null;
        }).when(storage).copyObject(any());
        doAnswer(c->{noTransaction();objects.remove(((StorageTarget)c.getArgument(0)).key());return null;}).when(storage).delete(any());
    }

    @Test void ownerAndEditorCreateIndependentReadyFilesWithoutQuotaOrDuplicateHistory() {
        var a=create(owner,"owner"); var b=create(editor,"editor");
        assertThat(a.state()).isEqualTo("READY"); assertThat(b.state()).isEqualTo("READY");
        assertThat(a.fileId()).isNotEqualTo(b.fileId()); assertThat(key(a.fileId())).isNotEqualTo(key(b.fileId()));
        assertThat(repository.findById(b.fileId()).orElseThrow().getOwnerId()).isEqualTo(owner);
        assertThat(service.create(editor,"editor",request("editor"))).isEqualTo(b);
        service.recover(owner,b.id());
        verify(storage,times(2)).putEmpty(any(),any(),any());
        assertThat(jdbc.queryForMap("SELECT actor_id,owner_id,actor_display_name,actor_attribution FROM resource_activity_events WHERE resource_id=?",b.fileId()))
                .containsEntry("actor_id",editor).containsEntry("owner_id",owner).containsEntry("actor_display_name","Editor").containsEntry("actor_attribution","CALLER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=?",Integer.class,b.fileId())).isEqualTo(1);
        assertThat(usage.get(owner).usedBytes()).isZero(); assertThat(usage.get(owner).reservedBytes()).isZero();
        assertThat(usage.get(editor).usedBytes()).isZero();
    }

    @Test void authenticationViewerDenialValidationCollisionsAndChangedReplay() throws Exception {
        mvc.perform(post("/api/v1/files/empty").contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/files/empty").with(as(owner)).contentType("application/json").content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/files/empty").with(as(viewer)).header("Idempotency-Key","viewer").contentType("application/json").content(body("denied"))) .andExpect(status().isForbidden());
        for (String invalid : List.of("", "*/*", "text/plain\r\nx: y")) {
            assertThatThrownBy(()->service.create(owner,"bad",new Request(folder,"bad",invalid))).isInstanceOf(IllegalArgumentException.class);
        }
        var a=create(owner,"one");
        assertThatThrownBy(()->service.create(owner,"other",request("one"))).isInstanceOf(com.vaultdrive.file.exception.DuplicateFileNameException.class);
        assertThatThrownBy(()->service.create(owner,"one",request("changed"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(get("/api/v1/empty-file-creations/{id}",a.id()).with(as(viewer))).andExpect(status().isNotFound());
        assertThatThrownBy(()->multipart.reserve(owner,"zero",new MultipartSessionService.Request(folder,"multipart",0,"text/plain"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void httpEmptyDownloadMimeLengthSharingRenameMoveTrashRestore() throws Exception {
        var response=mvc.perform(post("/api/v1/files/empty").with(as(editor)).header("Idempotency-Key","http")
                .contentType("application/json").content(body("empty.txt")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.state").value("READY")).andExpect(jsonPath("$.storageVerified").value(true))
                .andExpect(jsonPath("$.storageKey").doesNotExist()).andReturn();
        UUID file=jdbc.queryForObject("SELECT file_id FROM empty_file_creations WHERE owner_id=?",UUID.class,owner);
        var download=mvc.perform(get("/api/v1/files/{id}/download",file).with(as(viewer)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(download)).andExpect(status().isOk()).andExpect(header().string("Content-Length","0"))
                .andExpect(content().contentType("text/plain")).andExpect(content().bytes(new byte[0]));
        files.renameFile(editor,file,"renamed.txt"); files.moveFile(owner,file,null);
        assertThatThrownBy(()->files.getFile(viewer,file)).isInstanceOf(RuntimeException.class);
        grants.create(owner,false,file,users.findById(viewer).orElseThrow().getEmail(),ResourceRole.VIEWER);
        assertThat(files.downloadFile(viewer,file).sizeBytes()).isZero();
        files.trashFile(owner,file); assertThatThrownBy(()->files.getFile(owner,file)).isInstanceOf(RuntimeException.class);
        files.restoreFile(owner,file); assertThat(files.getFile(viewer,file).sizeBytes()).isZero();
        assertThat(response.getResponse().getHeader("Location")).startsWith("/api/v1/empty-file-creations/");
    }

    @Test void defaultMimeAndPublicLinkEmptyDownload() throws Exception {
        var s=service.create(owner,"binary",new Request(null,"empty",null));
        assertThat(files.getFile(owner,s.fileId()).contentType()).isEqualTo("application/octet-stream");
        var link=links.create(owner,s.fileId(),null);
        mvc.perform(get("/api/v1/public/share-links/{id}/download",link.link().id()).header("X-VaultDrive-Share-Token",link.secret()))
                .andExpect(status().isOk()).andExpect(header().string("Content-Length","0"))
                .andExpect(content().contentType("application/octet-stream")).andExpect(content().bytes(new byte[0]));
    }

    @Test void zeroLimitAllowsEmptyButOverLimitRejectsWithoutBypassingAccounting() {
        jdbc.update("UPDATE storage_quotas SET limit_bytes=0 WHERE owner_id=?",owner);
        create(owner,"zero-limit"); assertThat(usage.get(owner).availableBytes()).isZero();
        // Existing nonempty charge can be grandfathered by lowering a limit; empty still checks it.
        UUID nonempty=UUID.randomUUID(); jdbc.update("UPDATE storage_quotas SET limit_bytes=10 WHERE owner_id=?",owner);
        repository.saveAndFlush(new StoredFile(nonempty,owner,null,"charged","fixture/"+nonempty,"text/plain",1));
        jdbc.update("UPDATE storage_quotas SET limit_bytes=0 WHERE owner_id=?",owner);
        assertThatThrownBy(()->create(owner,"over-limit")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM empty_file_creations WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);
        assertThat(usage.get(owner).reservedBytes()).isEqualTo(1);
    }

    @Test void unknownPutNeverRedispatchesAndPositiveRecoveryCommitsOneEvent() {
        doAnswer(c->{throw new CopyStorage.Uncertain();}).when(storage).putEmpty(any(),any(),any());
        var s=create(editor,"uncertain"); assertThat(s.state()).isEqualTo("UNKNOWN");
        assertThat(usage.get(owner).uncertainStorageBytes()).isZero();
        assertThat(usage.get(owner).uncertainStorageAllocations()).isEqualTo(1);
        assertThat(usage.get(owner).unknownSizeAllocations()).isZero();
        assertThat(service.create(editor,"uncertain",request("uncertain"))).isEqualTo(s);
        assertThat(service.recover(owner,s.id())).isEqualTo(s);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=?",Integer.class,s.fileId())).isZero();
        objects.put(key(s.fileId()),evidence(s,"wrong")); assertThat(service.recover(owner,s.id())).isEqualTo(s);
        objects.put(key(s.fileId()),evidence(s,s.id().toString()));
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("READY"); service.recover(owner,s.id());
        verify(storage,times(1)).putEmpty(any(),any(),any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=?",Integer.class,s.fileId())).isEqualTo(1);
    }

    @Test void blockedDestinationRetainsReceiptAndRecoveryDoesNoIoAfterRestore() {
        doAnswer(c->{StorageTarget t=c.getArgument(0);UUID id=c.getArgument(1);
            objects.put(t.key(),new CopyStorage.ObjectInfo(0,"etag","text/plain",Map.of("vaultdrive-empty",id.toString())));
            folders.deleteFolder(owner,folder);return null;}).when(storage).putEmpty(any(),any(),any());
        var s=create(owner,"blocked"); assertThat(s.state()).isEqualTo("STORAGE_COMPLETE");
        assertThat(repository.findById(s.fileId()).orElseThrow().getStatus()).isEqualTo(FileStatus.UPLOADING);
        folders.restoreFolder(owner,folder);clearInvocations(storage);
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("READY");verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(strings={"SIZE","TYPE","ETAG","MARKER"})
    void mismatchedEvidenceNeverPublishesOrCreatesReceipt(String mismatch) {
        var s=creations.reserve(owner,"evidence",request("evidence"));var p=creations.claim(owner,s.id());
        assertThat(p.permitted()).isTrue();
        objects.put(key(s.fileId()),new CopyStorage.ObjectInfo(mismatch.equals("SIZE")?1:0,
                mismatch.equals("ETAG")?"":"etag",mismatch.equals("TYPE")?"application/octet-stream":"text/plain",
                Map.of("vaultdrive-empty",mismatch.equals("MARKER")?UUID.randomUUID().toString():s.id().toString())));
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT put_succeeded_at IS NULL FROM file_storage_objects WHERE file_id=?",Boolean.class,s.fileId())).isTrue();
        assertThatThrownBy(()->jdbc.update("UPDATE files SET status='READY',version=version+1 WHERE id=?",s.fileId())).isInstanceOf(RuntimeException.class);
        verify(storage,never()).putEmpty(any(),any(),any());
    }

    @Test void revokedEditorCannotReplayOrDispatchButOwnerCanRecoverAdmittedFacts() {
        var s=creations.reserve(editor,"revoke",request("revoke"));
        assertThat(creations.claim(editor,s.id()).permitted()).isTrue();
        UUID grant=jdbc.queryForObject("SELECT id FROM resource_grants WHERE folder_id=? AND grantee_user_id=?",UUID.class,folder,editor);
        grants.revoke(owner,true,folder,grant);
        jdbc.update("UPDATE users SET display_name='Changed after admission' WHERE id=?",editor);
        assertThatThrownBy(()->service.get(editor,s.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.create(editor,"revoke",request("revoke"))).isInstanceOf(RuntimeException.class);
        objects.put(key(s.fileId()),evidence(s,s.id().toString()));
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("READY");
        assertThat(jdbc.queryForMap("SELECT actor_id,actor_display_name FROM resource_activity_events WHERE resource_id=?",s.fileId()))
                .containsEntry("actor_id",editor).containsEntry("actor_display_name","Editor");
        verify(storage,never()).putEmpty(any(),any(),any());
    }

    @Test void trashAndPendingPurgeBlockAdmissionAndDispatchWithNoStorageCalls() {
        var s=creations.reserve(owner,"pending",request("pending"));
        folders.deleteFolder(owner,folder);
        assertThatThrownBy(()->create(owner,"trashed")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->creations.claim(owner,s.id())).isInstanceOf(RuntimeException.class);
        folders.requestPermanentDeletion(owner,folder);
        assertThatThrownBy(()->create(owner,"pending-parent")).isInstanceOf(RuntimeException.class);
        assertThatCode(()->service.recover(owner,s.id())).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT write_started FROM file_storage_objects WHERE file_id=?",Boolean.class,s.fileId())).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void postgresRetainsDispatchAndAllocationAndConcurrentNamesHaveOneWinner() throws Exception {
        assertThatThrownBy(()->ordered(()->creations.reserve(owner,"name-a",request("same")),
                ()->creations.reserve(owner,"name-b",request("same"))))
                .hasRootCauseInstanceOf(com.vaultdrive.file.exception.DuplicateFileNameException.class);
        UUID id=jdbc.queryForObject("SELECT id FROM empty_file_creations WHERE owner_id=?",UUID.class,owner);
        var permit=creations.claim(owner,id);assertThat(permit.permitted()).isTrue();
        assertThat(creations.claim(owner,id).permitted()).isFalse();
        assertThatThrownBy(()->jdbc.update("UPDATE empty_file_creations SET dispatched_at=NULL WHERE id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE empty_file_creations SET name='other' WHERE id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM empty_file_creations WHERE id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM file_storage_objects WHERE file_id=?",permit.snapshot().status().fileId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM files WHERE id=?",permit.snapshot().status().fileId())).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void readyPublicationRevalidatesDestinationInBothTrashOrders(boolean readyFirst) throws Exception {
        var s=creations.reserve(owner,"ready-race",request("ready-race"));creations.claim(owner,s.id());
        creations.receipt(s.id(),evidence(s,s.id().toString()));
        Supplier<Object> ready=()->{hierarchy.acquireShared(owner);return finalization.recover(s.fileId());};
        Supplier<Object> trash=()->{folders.deleteFolder(owner,folder);return null;};
        ordered(readyFirst?ready:trash,readyFirst?trash:ready);
        assertThat(repository.findById(s.fileId()).orElseThrow().getStatus()).isEqualTo(readyFirst?FileStatus.READY:FileStatus.UPLOADING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=?",Integer.class,s.fileId())).isEqualTo(readyFirst?1:0);
        folders.restoreFolder(owner,folder);assertThat(service.recover(owner,s.id()).state()).isEqualTo("READY");verifyNoInteractions(storage);
    }

    @Test void historyFailureRollsBackReadyAndRetainsIndependentReceipt() {
        doThrow(new IllegalStateException("fixture rollback"))
                .when(org.springframework.test.util.AopTestUtils.<ResourceActivityService>getUltimateTargetObject(activity)).uploadCompleted(any());
        assertThatThrownBy(()->create(owner,"rollback")).isInstanceOf(IllegalStateException.class);
        UUID id=jdbc.queryForObject("SELECT id FROM empty_file_creations WHERE owner_id=?",UUID.class,owner);
        assertThat(service.get(owner,id).state()).isEqualTo("STORAGE_COMPLETE");
        reset(org.springframework.test.util.AopTestUtils.<ResourceActivityService>getUltimateTargetObject(activity));clearInvocations(storage);
        assertThat(service.recover(owner,id).state()).isEqualTo("READY"); verifyNoInteractions(storage);
    }

    @Test void concurrentSameRequestHasOneIdentityOnePutAndOneEvent() throws Exception {
        var start=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await(5,TimeUnit.SECONDS);return create(owner,"race");});
            var b=pool.submit(()->{start.await(5,TimeUnit.SECONDS);return create(owner,"race");});
            var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertThat(first.fileId()).isEqualTo(second.fileId());
            assertThat(service.recover(owner,first.id()).state()).isEqualTo("READY");
            verify(storage,times(1)).putEmpty(any(),any(),any());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=?",Integer.class,first.fileId())).isEqualTo(1);
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void hierarchyBarrierOrdersTrashVersusAdmission(boolean createFirst) throws Exception {
        Supplier<Object> create=()->creations.reserve(owner,"barrier",request("barrier"));
        Supplier<Object> trash=()->{folders.deleteFolder(owner,folder);return null;};
        if (createFirst) ordered(create,trash); else assertThatThrownBy(()->ordered(trash,create)).hasRootCauseInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM empty_file_creations WHERE owner_id=?",Integer.class,owner)).isEqualTo(createFirst?1:0);
        verifyNoInteractions(storage);
    }

    @Test void copiesAndFolderCopiesHaveIndependentObjectsSafeReplaysAndReceiptBackedPurge() throws Exception {
        var s=create(owner,"source");
        var c=copies.copy(owner,s.fileId(),"copy",new FileCopyTransactions.Request(null,"copy"));
        assertThat(c.state()).isEqualTo("READY");assertThat(c.fileId()).isNotEqualTo(s.fileId());
        var job=folderTx.admit(owner,folder,"folder-copy",new FolderCopyTransactions.Request(null,"copied folder"));
        assertThat(folderCopies.execute(owner,job.id()).state()).isEqualTo("COMPLETED");
        UUID member=jdbc.queryForObject("SELECT destination_id FROM folder_copy_members WHERE job_id=? AND kind='FILE'",UUID.class,job.id());
        assertThat(Set.of(key(s.fileId()),key(c.fileId()),key(member))).hasSize(3);
        assertThat(copies.copy(owner,s.fileId(),"copy",new FileCopyTransactions.Request(null,"copy"))).isEqualTo(c);
        folderCopies.recover(owner,job.id()); verify(storage,times(2)).copyObject(any());
        files.trashFile(owner,s.fileId());purge(s.fileId());
        assertThat(objects).containsKeys(key(c.fileId()),key(member));
        files.trashFile(owner,c.fileId());purge(c.fileId());
        folders.deleteFolder(owner,job.folderId());UUID event=folders.requestPermanentDeletion(owner,job.folderId());
        jobs.create(event,JobType.FOLDER_PURGE,job.folderId());var claim=jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        for(int i=0;i<25;i++){var result=folderPurge.handle(MultipartPurgeTestContext.context(claim));if(result==JobHandler.Result.SUCCEEDED){jobs.complete(claim);break;}}
        assertThat(repository.existsById(member)).isFalse();assertThat(usage.get(owner).usedBytes()).isZero();
        assertThat(service.get(owner,s.id()).state()).isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("SELECT metadata_finalized_at IS NOT NULL AND bytes_deleted_at IS NOT NULL FROM file_purge_work WHERE file_id=?",Boolean.class,s.fileId())).isTrue();
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void folderPurgeDistinguishesNeverAdmittedFromUnknownEmptyWrites(boolean admitted) throws Exception {
        var s=creations.reserve(owner,"disposal",request("disposal"));
        if(admitted){assertThat(creations.claim(owner,s.id()).permitted()).isTrue();}
        folders.deleteFolder(owner,folder);UUID event=folders.requestPermanentDeletion(owner,folder);
        jobs.create(event,JobType.FOLDER_PURGE,folder);var claim=jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        boolean complete=false;
        for(int i=0;i<8;i++){
            var result=folderPurge.handle(MultipartPurgeTestContext.context(claim));
            if(result==JobHandler.Result.SUCCEEDED){jobs.complete(claim);complete=true;break;}
        }
        assertThat(complete).isEqualTo(!admitted);assertThat(repository.existsById(s.fileId())).isEqualTo(admitted);
        if(admitted){
            assertThat(jdbc.queryForObject("SELECT count(*) FROM file_purge_work WHERE file_id=? AND (bytes_deleted_at IS NOT NULL OR never_written_at IS NOT NULL)",Integer.class,s.fileId())).isZero();
            assertThat(service.get(owner,s.id()).state()).isEqualTo("UNKNOWN");
        }else{
            assertThat(jdbc.queryForObject("SELECT never_written_at IS NOT NULL AND bytes_deleted_at IS NULL AND metadata_finalized_at IS NOT NULL FROM file_purge_work WHERE file_id=?",Boolean.class,s.fileId())).isTrue();
        }
        verifyNoInteractions(storage);
    }

    private Status create(UUID actor,String name){return service.create(actor,name,request(name));}
    private Request request(String name){return new Request(folder,name,"text/plain");}
    private String body(String name){return "{\"folderId\":\""+folder+"\",\"name\":\""+name+"\",\"contentType\":\"text/plain\"}";}
    private String key(UUID file){return jdbc.queryForObject("SELECT storage_key FROM file_storage_objects WHERE file_id=?",String.class,file);}
    private CopyStorage.ObjectInfo evidence(Status s,String marker){return new CopyStorage.ObjectInfo(0,"etag","text/plain",Map.of("vaultdrive-empty",marker));}
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor as(UUID actor){return jwt().jwt(j->j.subject(actor.toString()));}
    private static void noTransaction(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    private void purge(UUID file) throws Exception {
        UUID event=files.requestPermanentDeletion(owner,file);jobs.create(event,JobType.FILE_PURGE,file);var claim=jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(purge.handle(MultipartPurgeTestContext.context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);jobs.complete(claim);
    }
    private void ordered(Supplier<Object> first,Supplier<Object> second) throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var entered=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);var pid=new AtomicInteger();
        try {
            var a=pool.submit(()->tx.execute(t->{Object value=first.get();held.countDown();await(release);return value;}));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->tx.execute(t->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));entered.countDown();return second.get();}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);boolean blocked=false;
            while(System.nanoTime()<deadline){if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))){blocked=true;break;}Thread.yield();}
            assertThat(blocked).isTrue();assertThat(b.isDone()).isFalse();
            release.countDown();a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    private static void await(CountDownLatch l){try{if(!l.await(8,TimeUnit.SECONDS))throw new AssertionError("timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
