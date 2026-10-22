package com.vaultdrive.file;

import com.vaultdrive.activity.ResourceActivityService;
import com.vaultdrive.file.FileCopyTransactions.*;
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
@Timeout(45)
class FileCopyIntegrationTest {
    static final String SCHEMA="copy_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema="+SCHEMA);
        r.add("spring.flyway.schemas",()->SCHEMA); r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @Autowired FileCopyTransactions copies;
    @Autowired FileCopyService service;
    @Autowired StoredFileRepository files;
    @Autowired FileService fileService;
    @Autowired FolderService folderService;
    @Autowired FolderRepository folders;
    @Autowired UserRepository users;
    @Autowired GrantService grants;
    @Autowired StorageBinding binding;
    @Autowired StorageUsageService usage;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired HierarchyCoordinator hierarchy;
    @Autowired MockMvc mvc;
    @Autowired BackgroundJobService jobs;
    @Autowired FilePurgeJobHandler fileHandler;
    @Autowired FolderPurgeJobHandler folderHandler;
    @Autowired FolderPurgeDiscoveryService discovery;
    @Autowired FolderPurgeFileService members;
    @MockitoBean ObjectStorageService storage;
    @MockitoSpyBean ResourceActivityService activity;
    UUID owner, stranger, source, sourceFolder, destination;
    TransactionTemplate tx;
    final Map<String,CopyStorage.ObjectInfo> objects=new ConcurrentHashMap<>();

    @BeforeEach void setup() {
        tx=new TransactionTemplate(manager); reset(storage,activity); objects.clear();
        tx.executeWithoutResult(t->{
            owner=users.saveAndFlush(new User(UUID.randomUUID()+"@example.com","fixture","Copy actor")).getId();
            stranger=users.saveAndFlush(new User(UUID.randomUUID()+"@example.com","fixture","Stranger")).getId();
            sourceFolder=folders.saveAndFlush(new Folder(owner,null,"source folder")).getId();
            destination=folders.saveAndFlush(new Folder(owner,null,"destination")).getId();
            source=UUID.randomUUID();
            StoredFile f=new StoredFile(source,owner,sourceFolder,"original.txt","fixture/"+source,"text/plain",3);
            f.markReady(); files.saveAndFlush(f);
            StorageTarget b=binding.target(f.getStorageKey());
            jdbc.update("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,storage_namespace,endpoint,region,bucket,write_started) VALUES (?,?,?,?,?,?,?,true)",
                    b.key(),source,owner,b.namespace(),b.endpoint(),b.region(),b.bucket());
            objects.put(b.key(),new CopyStorage.ObjectInfo(3,"\"source-etag\"","text/plain",Map.of()));
        });
        when(storage.inspectCopyObject(any())).thenAnswer(c->{
            noTransaction(); var o=objects.get(((StorageTarget)c.getArgument(0)).key());
            if(o==null) throw new CopyStorage.Uncertain(); return o;
        });
        doAnswer(c->{ noTransaction(); CopyStorage.Command command=c.getArgument(0);
            assertThat(objects.get(command.source().key()).etag()).isEqualTo(command.sourceEtag());
            objects.put(command.destination().key(),new CopyStorage.ObjectInfo(3,"\"copy-etag\"",command.contentType(),Map.of("vaultdrive-copy",command.operationId().toString())));
            return null;
        }).when(storage).copyObject(any());
        doAnswer(c->{noTransaction(); objects.remove(((StorageTarget)c.getArgument(0)).key()); return null;}).when(storage).delete(any());
    }

    @Test void successHasIndependentIdentityQuotaActorAndExactlyOneEventAndDispatch() {
        var reserved=reserve("copy.txt","one"); assertCharge(3,3);
        jdbc.update("UPDATE users SET display_name='Changed' WHERE id=?",owner);
        var ready=service.execute(owner,reserved.id()); assertThat(ready.state()).isEqualTo("READY"); assertCharge(6,0);
        assertThat(ready.fileId()).isNotEqualTo(source); assertThat(key(ready.fileId())).isNotEqualTo(key(source));
        assertThat(jdbc.queryForMap("SELECT actor_id,owner_id,actor_display_name,actor_attribution,event_type FROM resource_activity_events WHERE resource_id=?",ready.fileId()))
                .containsEntry("actor_id",owner).containsEntry("owner_id",owner).containsEntry("actor_display_name","Copy actor")
                .containsEntry("actor_attribution","CALLER").containsEntry("event_type","COPIED");
        var evidence=operation(ready.id());
        assertThat(service.copy(owner,source,"one",new Request(destination," copy.txt "))).isEqualTo(ready);
        assertThat(service.recover(owner,ready.id())).isEqualTo(ready);
        assertThat(operation(ready.id())).isEqualTo(evidence); verify(storage,times(1)).copyObject(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=?",Integer.class,ready.fileId())).isEqualTo(1);
        fileService.renameFile(owner,ready.fileId(),"renamed.txt"); fileService.trashFile(owner,source);
        assertThat(files.findById(ready.fileId()).orElseThrow().getStatus()).isEqualTo(FileStatus.READY); assertCharge(6,0);
        fileService.trashFile(owner,ready.fileId()); assertCharge(6,0); fileService.restoreFile(owner,ready.fileId());
    }

    @Test void httpAuthenticationValidationContractAndRetainedOwnerIsolation() throws Exception {
        mvc.perform(post("/api/v1/files/{id}/copy",source).contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/files/{id}/copy",source).with(as(owner)).contentType("application/json").content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/files/{id}/copy",source).with(as(owner)).header("Idempotency-Key","http")
                .contentType("application/json").content("{\"destinationFolderId\":\""+destination+"\",\"name\":\"http.txt\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.state").value("READY")).andExpect(jsonPath("$.storageVerified").value(true))
                .andExpect(jsonPath("$.storageKey").doesNotExist()).andExpect(jsonPath("$.sourceEtag").doesNotExist());
        UUID id=jdbc.queryForObject("SELECT id FROM file_copy_operations WHERE owner_id=?",UUID.class,owner);
        mvc.perform(get("/api/v1/copy-operations/{id}",id).with(as(owner))).andExpect(status().isOk());
        for(UUID hidden:List.of(id,UUID.randomUUID())) {
            mvc.perform(get("/api/v1/copy-operations/{id}",hidden).with(as(stranger))).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/copy-operations/{id}/recover",hidden).with(as(stranger))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/files/{id}/copy",source).with(as(stranger)).header("Idempotency-Key","foreign")
                .contentType("application/json").content("{\"name\":\"foreign\"}")) .andExpect(status().isNotFound());
    }

    @ParameterizedTest @ValueSource(strings={"VIEWER","EDITOR"})
    void collaboratorsCannotCopySourceOrUseForeignDestination(String role) throws Exception {
        String email=users.findById(stranger).orElseThrow().getEmail();
        grants.create(owner,false,source,email,ResourceRole.valueOf(role));
        grants.create(owner,true,destination,email,ResourceRole.valueOf(role));
        mvc.perform(post("/api/v1/files/{id}/copy",source).with(as(stranger)).header("Idempotency-Key","denied")
                .contentType("application/json").content("{\"name\":\"denied\"}")) .andExpect(status().isForbidden());
        // A separate owner source also cannot select somebody else's shared destination.
        StoredFile own=new StoredFile(UUID.randomUUID(),stranger,null,"own","own/"+UUID.randomUUID(),"text/plain",3); own.markReady(); files.saveAndFlush(own);
        mvc.perform(post("/api/v1/files/{id}/copy",own.getId()).with(as(stranger)).header("Idempotency-Key","foreign-destination")
                .contentType("application/json").content("{\"destinationFolderId\":\""+destination+"\",\"name\":\"denied\"}")) .andExpect(status().isForbidden());
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(strings={"SOURCE_TRASH","SOURCE_ANCESTOR_TRASH","SOURCE_PENDING","SOURCE_FAILED","SOURCE_UPLOADING","DEST_TRASH","DEST_PENDING"})
    void lifecycleBlocksAdmissionWithoutReservation(String state) {
        switch(state) {
            case "SOURCE_TRASH" -> fileService.trashFile(owner,source);
            case "SOURCE_ANCESTOR_TRASH" -> folderService.deleteFolder(owner,sourceFolder);
            case "SOURCE_PENDING" -> {fileService.trashFile(owner,source); fileService.requestPermanentDeletion(owner,source);}
            case "SOURCE_FAILED" -> jdbc.update("UPDATE files SET status='FAILED' WHERE id=?",source);
            case "SOURCE_UPLOADING" -> jdbc.update("UPDATE files SET status='UPLOADING' WHERE id=?",source);
            case "DEST_TRASH" -> folderService.deleteFolder(owner,destination);
            case "DEST_PENDING" -> {folderService.deleteFolder(owner,destination); folderService.requestPermanentDeletion(owner,destination);}
        }
        assertThatThrownBy(()->reserve("blocked","blocked")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_copy_operations WHERE owner_id=?",Integer.class,owner)).isZero();
        verifyNoInteractions(storage);
    }

    @Test void namesCollideAndIdempotencyCannotBeRebound() {
        var a=reserve("copy","same");
        assertThatThrownBy(()->reserve("copy","other")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->reserve("different","same")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->copies.reserve(owner,source,"same",new Request(null,"copy"))).isInstanceOf(RuntimeException.class);
        assertThat(reserve("copy","same")).isEqualTo(a); assertCharge(3,3);
    }

    @Test void quotaExhaustionRollsBackFileAllocationAndIntent() {
        jdbc.update("UPDATE storage_quotas SET limit_bytes=5 WHERE owner_id=?",owner);
        assertThatThrownBy(()->reserve("too large","quota")).isInstanceOf(RuntimeException.class);
        assertCharge(3,0); assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_storage_objects WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);
        verifyNoInteractions(storage);
    }

    @Test void unknownCopyRetainsReservationAndOnlyPositiveMarkerCanRecoverWithoutRedispatch() {
        doThrow(new CopyStorage.Uncertain()).when(storage).copyObject(any());
        var s=service.copy(owner,source,"unknown",new Request(destination,"unknown"));
        assertThat(s.state()).isEqualTo("UNKNOWN"); assertCharge(3,3);
        assertThat(service.execute(owner,s.id()).state()).isEqualTo("UNKNOWN");
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("UNKNOWN"); // Absent.
        objects.put(key(s.fileId()),new CopyStorage.ObjectInfo(3,"etag","text/plain",Map.of("vaultdrive-copy",UUID.randomUUID().toString())));
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("UNKNOWN"); // Wrong writer marker.
        objects.put(key(s.fileId()),new CopyStorage.ObjectInfo(4,"etag","text/plain",Map.of("vaultdrive-copy",s.id().toString())));
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("UNKNOWN"); // Wrong length.
        objects.put(key(s.fileId()),evidence(s));
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("READY"); assertCharge(6,0);
        verify(storage,times(1)).copyObject(any()); verify(storage,never()).delete(any());
    }

    @Test void receiptCommitSurvivesReadyEventFailureAndRecoveryOnlyRetriesMetadata() {
        var s=reserve("receipt","receipt");
        var target=org.springframework.test.util.AopTestUtils.<ResourceActivityService>getUltimateTargetObject(activity);
        doThrow(new IllegalStateException("event failure")).when(target).copied(any(),any(),any());
        assertThatThrownBy(()->service.execute(owner,s.id())).isInstanceOf(IllegalStateException.class);
        assertThat(copies.get(owner,s.id()).state()).isEqualTo("STORAGE_COMPLETE"); assertCharge(3,3);
        var before=operation(s.id()); reset(target); clearInvocations(storage);
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("READY");
        assertThat(operation(s.id()).get("receipt_at")).isEqualTo(before.get("receipt_at")); verifyNoInteractions(storage);
    }

    @Test void receiptRollbackLeavesUnknownAndPositiveRecoveryRecordsOnce() {
        var s=reserve("receipt rollback","receipt rollback"); var p=copies.claim(owner,s.id(),sourceInfo());
        tx.executeWithoutResult(t->{copies.receipt(owner,s.id(),evidence(s)); t.setRollbackOnly();});
        assertThat(copies.get(owner,s.id()).storageVerified()).isFalse(); assertCharge(3,3);
        objects.put(p.snapshot().destination().key(),evidence(s));
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("READY"); verify(storage,never()).copyObject(any());
    }

    @Test void sourceReadFailureBeforeDispatchCanRetrySafelyAndNeverChargesTwice() {
        jdbc.update("UPDATE storage_quotas SET limit_bytes=6 WHERE owner_id=?",owner);
        var s=reserve("preflight","preflight");
        String sourceKey=key(source);
        doThrow(new CopyStorage.Uncertain()).when(storage).inspectCopyObject(argThat(t->t!=null && t.key().equals(sourceKey)));
        assertThat(service.execute(owner,s.id()).state()).isEqualTo("RESERVED"); verify(storage,never()).copyObject(any()); assertCharge(3,3);
        doReturn(sourceInfo()).when(storage).inspectCopyObject(argThat(t->t!=null && t.key().equals(sourceKey)));
        assertThat(service.execute(owner,s.id()).state()).isEqualTo("READY"); verify(storage,times(1)).copyObject(any());
        assertThat(jdbc.queryForList("SELECT threshold FROM storage_quota_events WHERE owner_id=? ORDER BY threshold",Integer.class,owner))
                .containsExactly(80,90,100);
    }

    @Test void copyCancellationRearmsQuotaButReplayCannotReleaseOrNotifyTwice() {
        jdbc.update("UPDATE storage_quotas SET limit_bytes=6 WHERE owner_id=?",owner);
        var s=reserve("cancel quota","cancel-quota"); copies.cancel(owner,s.id());copies.cancel(owner,s.id());
        assertThat(reserve("cancel quota","cancel-quota").id()).isEqualTo(s.id());assertCharge(3,0);
        var next=reserve("next quota","next-quota");assertCharge(3,3);
        assertThat(jdbc.queryForList("SELECT threshold FROM storage_quota_events WHERE owner_id=? ORDER BY threshold",Integer.class,owner))
                .containsExactly(80,80,90,90,100,100);
        copies.cancel(owner,next.id());assertCharge(3,0);
    }

    @Test void sourceDeletionBeforeDispatchFailsSafelyAndAfterDispatchDoesNotPreventIndependentRecovery() throws Exception {
        var before=reserve("before","before"); fileService.trashFile(owner,source);
        assertThat(service.execute(owner,before.id()).state()).isEqualTo("FAILED"); assertCharge(3,0);
        fileService.restoreFile(owner,source);
        var after=reserve("after","after"); copies.claim(owner,after.id(),sourceInfo());
        fileService.trashFile(owner,source); purgeFile(source);
        assertThat(files.existsById(source)).isFalse(); assertCharge(0,3);
        objects.put(key(after.fileId()),evidence(after));
        assertThat(service.recover(owner,after.id()).state()).isEqualTo("READY"); assertCharge(3,0);
        verify(storage,never()).copyObject(any());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancellationReleasesChargeAndNameButNeverReusesKeyOrResurrectsLateSuccess(boolean dispatched) {
        var s=reserve("cancel","cancel"); if(dispatched) copies.claim(owner,s.id(),sourceInfo());
        copies.cancel(owner,s.id()); var row=operation(s.id()); copies.cancel(owner,s.id()); assertThat(operation(s.id())).isEqualTo(row);
        assertCharge(3,0); var next=reserve("cancel","next"); assertThat(key(next.fileId())).isNotEqualTo(key(s.fileId()));
        if(dispatched) copies.receipt(owner,s.id(),evidence(s));
        assertThat(service.recover(owner,s.id()).state()).isEqualTo("CANCELLED"); assertThat(files.findById(s.fileId()).orElseThrow().getStatus()).isEqualTo(FileStatus.FAILED);
        assertThat(usage.get(owner).uncertainStorageAllocations()).isEqualTo(dispatched?1:0); verifyNoInteractions(storage);
    }

    @Test void cancellationWhileStorageCallIsInFlightCannotPublishReady() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newSingleThreadExecutor();
        doAnswer(c->{noTransaction(); entered.countDown(); await(release); return null;}).when(storage).copyObject(any());
        var s=reserve("in flight","in flight");
        try {
            var future=pool.submit(()->service.execute(owner,s.id())); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(copies.cancel(owner,s.id()).state()).isEqualTo("CANCELLED"); release.countDown();
            assertThat(future.get(8,TimeUnit.SECONDS).state()).isEqualTo("CANCELLED"); assertCharge(3,0);
        } finally {release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void folderTrashVersusReadyHasBothLockOrdersAndRecoveryAfterRestore(boolean readyFirst) throws Exception {
        var s=reserve("folder race","folder race"); copies.claim(owner,s.id(),sourceInfo()); copies.receipt(owner,s.id(),evidence(s));
        Supplier<Object> ready=()->copies.finalizeReady(owner,s.id());
        Supplier<Object> trash=()->{folderService.deleteFolder(owner,destination); return null;};
        race(readyFirst?ready:trash,readyFirst?trash:ready);
        assertThat(copies.get(owner,s.id()).state()).isEqualTo(readyFirst?"READY":"BLOCKED"); assertCharge(readyFirst?6:3,readyFirst?0:3);
        folderService.restoreFolder(owner,destination); assertThat(service.recover(owner,s.id()).state()).isEqualTo("READY");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void folderPurgeUsesCopyByteProofOrSeparateLogicalDiscardAndRetainsLateEvidence(boolean ready) throws Exception {
        var s=reserve("purge","purge"); copies.claim(owner,s.id(),sourceInfo());
        if(ready) {copies.receipt(owner,s.id(),evidence(s)); copies.finalizeReady(owner,s.id());}
        folderService.deleteFolder(owner,destination); folderService.requestPermanentDeletion(owner,destination);
        UUID event=jdbc.queryForObject("SELECT event_id FROM folder_purge_runs WHERE root_folder_id=?",UUID.class,destination);
        jobs.create(event,JobType.FOLDER_PURGE,destination); var claim=jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        drain(claim); assertThat(files.existsById(s.fileId())).isFalse(); assertCharge(3,0);
        assertThat(jdbc.queryForObject("SELECT metadata_finalized_at IS NOT NULL AND (bytes_deleted_at IS NOT NULL)=? AND (copy_discarded_at IS NOT NULL)=? FROM file_purge_work WHERE file_id=?",Boolean.class,ready,!ready,s.fileId())).isTrue();
        assertThat(copies.get(owner,s.id()).state()).isEqualTo(ready?"READY":"CANCELLED");
        if(!ready) {copies.receipt(owner,s.id(),evidence(s)); assertThat(copies.finalizeReady(owner,s.id()).state()).isEqualTo("CANCELLED");}
        assertThatThrownBy(()->jdbc.update("DELETE FROM file_storage_objects WHERE file_id=?",s.fileId())).isInstanceOf(RuntimeException.class);
    }

    @Test void standaloneCopyPurgeCannotAffectSourceAndReplaysWithoutAnotherDelete() throws Exception {
        var s=service.copy(owner,source,"standalone",new Request(null,"standalone"));
        fileService.trashFile(owner,s.fileId()); purgeFile(s.fileId()); assertCharge(3,0);
        assertThat(files.findById(source).orElseThrow().getStatus()).isEqualTo(FileStatus.READY);
        assertThat(objects).containsKey(key(source)); verify(storage,times(1)).delete(argThat(t->t.key().equals(key(s.fileId()))));
    }

    @Test void logicalDiscardAndPermanentCopyDecisionRollbackTogetherOnStaleJobFence() throws Exception {
        var s=reserve("fenced","fenced");copies.claim(owner,s.id(),sourceInfo());
        folderService.deleteFolder(owner,destination);folderService.requestPermanentDeletion(owner,destination);
        UUID event=jdbc.queryForObject("SELECT event_id FROM folder_purge_runs WHERE root_folder_id=?",UUID.class,destination);
        jobs.create(event,JobType.FOLDER_PURGE,destination);var claim=jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        var context=MultipartPurgeTestContext.context(claim);
        for(int i=0;i<20;i++){long version=jdbc.queryForObject("SELECT progress_version FROM background_jobs WHERE id=?",Long.class,event);
            if(discovery.discover(claim,version,10).sealed())break;}
        members.prepare(context,s.fileId());long version=jdbc.queryForObject("SELECT progress_version FROM background_jobs WHERE id=?",Long.class,event);
        assertThatThrownBy(()->members.completeFile(context,s.fileId(),version-1)).isInstanceOf(RuntimeException.class);
        assertThat(copies.get(owner,s.id()).state()).isEqualTo("UNKNOWN");assertCharge(3,3);
        assertThat(jdbc.queryForObject("SELECT copy_discarded_at IS NULL FROM file_purge_work WHERE file_id=?",Boolean.class,s.fileId())).isTrue();
        members.completeFile(context,s.fileId(),version);
        assertThat(copies.get(owner,s.id()).state()).isEqualTo("CANCELLED");assertThat(files.findById(s.fileId()).orElseThrow().getStatus()).isEqualTo(FileStatus.UPLOADING);
        assertCharge(3,3);drain(claim);assertCharge(3,0);verifyNoInteractions(storage);
    }

    @Test void concurrentIdempotencyAndQuotaReservationsHaveOneWinnerWithoutPartialRows() throws Exception {
        var a=reserve("same","concurrent");
        race(()->copies.claim(owner,a.id(),sourceInfo()),()->copies.claim(owner,a.id(),sourceInfo()));
        assertThat(copies.get(owner,a.id()).state()).isEqualTo("UNKNOWN");
        copies.cancel(owner,a.id()); jdbc.update("UPDATE storage_quotas SET limit_bytes=6 WHERE owner_id=?",owner);
        var pool=Executors.newFixedThreadPool(2); var barrier=new CyclicBarrier(2);
        try {
            List<Future<Object>> runs=new ArrayList<>();
            for(String name:List.of("a","b")) runs.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS); try{return reserve(name,name);}catch(RuntimeException rejected){return rejected;}}));
            List<Object> results=new ArrayList<>(); for(var f:runs)results.add(f.get(8,TimeUnit.SECONDS));
            assertThat(results.stream().filter(Status.class::isInstance).count()).isEqualTo(1); assertCharge(3,3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE owner_id=?",Integer.class,owner)).isEqualTo(3); // source, cancelled, winner.
        }finally{pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    @Test void immutableIdentityReceiptAndReadyGuardsRejectDirectShortcuts() {
        var s=reserve("guard","guard");
        assertThatThrownBy(()->jdbc.update("UPDATE files SET status='READY',version=version+1 WHERE id=?",s.fileId())).isInstanceOf(RuntimeException.class);
        copies.claim(owner,s.id(),sourceInfo()); copies.receipt(owner,s.id(),evidence(s));
        for(String sql:List.of("UPDATE file_copy_operations SET source_file_id=file_id,revision=revision+1 WHERE id=?",
                "UPDATE file_copy_operations SET receipt_at=NULL,object_etag=NULL,revision=revision+1 WHERE id=?",
                "UPDATE file_copy_operations SET dispatched_at=NULL,source_etag=NULL,revision=revision+1 WHERE id=?",
                "DELETE FROM file_copy_operations WHERE id=?")) assertThatThrownBy(()->jdbc.update(sql,s.id())).isInstanceOf(RuntimeException.class);
        copies.finalizeReady(owner,s.id());
        assertThatThrownBy(()->copies.cancel(owner,s.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM files WHERE id=?",s.fileId())).isInstanceOf(RuntimeException.class);
    }

    @Test void concurrentReservationReplayAndFilenameCollisionLeaveOneIdentity() throws Exception {
        race(()->reserve("shared","same-key"),()->reserve("shared","same-key"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_copy_operations WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);
        assertThatThrownBy(()->race(()->reserve("collision","first-key"),()->reserve("collision","second-key")))
                .hasRootCauseInstanceOf(com.vaultdrive.file.exception.DuplicateFileNameException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_copy_operations WHERE owner_id=?",Integer.class,owner)).isEqualTo(2);assertCharge(3,6);
    }

    @Test void concurrentExecutionSharesOneDispatchAfterTwoSourcePreflights() throws Exception {
        var s=reserve("execute race","execute-race");var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        String sourceKey=key(source);
        doAnswer(c->{noTransaction();barrier.await(5,TimeUnit.SECONDS);return sourceInfo();})
                .when(storage).inspectCopyObject(argThat(t->t!=null && t.key().equals(sourceKey)));
        try {
            var a=pool.submit(()->service.execute(owner,s.id()));var b=pool.submit(()->service.execute(owner,s.id()));
            a.get(8,TimeUnit.SECONDS);b.get(8,TimeUnit.SECONDS);
            assertThat(copies.get(owner,s.id()).state()).isEqualTo("READY");verify(storage,times(1)).copyObject(any());assertCharge(6,0);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void sourceTrashVersusDispatchRechecksFreshStateInBothOrders(boolean dispatchFirst) throws Exception {
        var s=reserve("source race","source-race");
        Supplier<Object> claim=()->copies.claim(owner,s.id(),sourceInfo());
        Supplier<Object> trash=()->{fileService.trashFile(owner,source);return null;};
        race(dispatchFirst?claim:trash,dispatchFirst?trash:claim);
        assertThat(copies.get(owner,s.id()).state()).isEqualTo(dispatchFirst?"UNKNOWN":"FAILED");assertCharge(3,dispatchFirst?3:0);
        verifyNoInteractions(storage);
    }

    @Test void reservationAndCancellationRollbackKeepEveryChargeAndIdentityConsistent() {
        tx.executeWithoutResult(t->{reserve("rollback","rollback");t.setRollbackOnly();});assertCharge(3,0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_copy_operations WHERE owner_id=?",Integer.class,owner)).isZero();
        var s=reserve("rollback","rollback");
        tx.executeWithoutResult(t->{copies.cancel(owner,s.id());t.setRollbackOnly();});
        assertThat(copies.get(owner,s.id()).state()).isEqualTo("RESERVED");assertCharge(3,3);
        folderService.deleteFolder(owner,destination);assertThat(service.execute(owner,s.id()).state()).isEqualTo("RESERVED");verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancelVersusReadyHasOnePermanentWinnerAndOneQuotaTransition(boolean readyFirst) throws Exception {
        var s=reserve("decision race","decision-race");copies.claim(owner,s.id(),sourceInfo());copies.receipt(owner,s.id(),evidence(s));
        Supplier<Object> ready=()->copies.finalizeReady(owner,s.id());Supplier<Object> cancel=()->copies.cancel(owner,s.id());
        if(readyFirst) assertThatThrownBy(()->race(ready,cancel)).hasRootCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        else race(cancel,ready);
        assertThat(copies.get(owner,s.id()).state()).isEqualTo(readyFirst?"READY":"CANCELLED");assertCharge(readyFirst?6:3,0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=? AND event_type='COPIED'",Integer.class,s.fileId())).isEqualTo(readyFirst?1:0);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void unknownOrMismatchedSourceBindingCannotBeAdoptedFromCurrentConfiguration(boolean unknown) {
        UUID legacy=UUID.randomUUID();String legacyKey="fixture/"+legacy;var b=binding.target(legacyKey);
        tx.executeWithoutResult(t->{
            var f=new StoredFile(legacy,owner,null,"legacy source",legacyKey,"text/plain",3);f.markReady();files.saveAndFlush(f);
            if(unknown) jdbc.update("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,write_started) VALUES (?,?,?,true)",legacyKey,legacy,owner);
            else jdbc.update("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,write_started,storage_namespace,endpoint,region,bucket) VALUES (?,?,?,true,?,?,?,?)",
                    legacyKey,legacy,owner,"other-binding",b.endpoint(),b.region(),b.bucket());
        });
        assertThatThrownBy(()->copies.reserve(owner,legacy,"binding",new Request(destination,"binding")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertCharge(6,0);verifyNoInteractions(storage);
    }

    private Status reserve(String name,String key){return copies.reserve(owner,source,key.replace(' ','-'),new Request(destination,name));}
    private String key(UUID file){return jdbc.queryForObject("SELECT storage_key FROM file_storage_objects WHERE file_id=?",String.class,file);}
    private CopyStorage.ObjectInfo sourceInfo(){return new CopyStorage.ObjectInfo(3,"\"source-etag\"","text/plain",Map.of());}
    private CopyStorage.ObjectInfo evidence(Status s){return new CopyStorage.ObjectInfo(3,"\"copy-etag\"","text/plain",Map.of("vaultdrive-copy",s.id().toString()));}
    private Map<String,Object> operation(UUID id){return jdbc.queryForMap("SELECT * FROM file_copy_operations WHERE id=?",id);}
    private void assertCharge(long used,long reserved){var u=usage.get(owner); assertThat(u.usedBytes()).isEqualTo(used); assertThat(u.reservedBytes()).isEqualTo(reserved);}
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor as(UUID actor){return jwt().jwt(j->j.subject(actor.toString()));}
    private static void noTransaction(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    private void purgeFile(UUID file) throws Exception {
        fileService.requestPermanentDeletion(owner,file);
        UUID event=jdbc.queryForObject("SELECT event_id FROM file_purge_targets WHERE file_id=?",UUID.class,file);
        jobs.create(event,JobType.FILE_PURGE,file); var claim=jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(fileHandler.handle(MultipartPurgeTestContext.context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
        assertThat(fileHandler.handle(MultipartPurgeTestContext.context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED); jobs.complete(claim);
    }
    private void drain(ClaimedJob claim) throws Exception {
        for(int i=0;i<30;i++) {var result=folderHandler.handle(MultipartPurgeTestContext.context(claim)); if(result==JobHandler.Result.SUCCEEDED){jobs.complete(claim);return;} assertThat(result).isEqualTo(JobHandler.Result.CONTINUE);}
        throw new AssertionError("Folder did not finish");
    }
    private void race(Supplier<Object> first,Supplier<Object> second) throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var entered=new CountDownLatch(1); var pid=new AtomicInteger();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->tx.execute(t->{Object result=first.get();held.countDown();await(release);return result;}));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->tx.execute(t->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));entered.countDown();return second.get();}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1); boolean blocked=false;
            while(System.nanoTime()<deadline){if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))){blocked=true;break;}Thread.yield();}
            assertThat(blocked).isTrue(); assertThat(b.isDone()).isFalse(); release.countDown(); a.get(8,TimeUnit.SECONDS); b.get(8,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(8,TimeUnit.SECONDS))throw new AssertionError("Latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
