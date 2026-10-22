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
class FolderCopyIntegrationTest {
    static final String SCHEMA="folder_copy_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema="+SCHEMA);
        r.add("spring.flyway.schemas",()->SCHEMA); r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @Autowired FolderCopyTransactions folderCopies;
    @Autowired FolderCopyService folderCopyService;
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


    FolderCopyTransactions.Status admit(String key) {
        return folderCopies.admit(owner,sourceFolder,key,new FolderCopyTransactions.Request(destination,"snapshot"));
    }
    FolderCopyTransactions.Status finish(UUID id) {
        var s=folderCopies.get(owner,id);
        for(int i=0;i<300 && !s.terminal();i++) s=folderCopyService.execute(owner,id);
        return s;
    }
    void noTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    String key(UUID file) { return jdbc.queryForObject("SELECT storage_key FROM file_storage_objects WHERE file_id=?",String.class,file); }
    UUID copiedFile(UUID job) { return jdbc.queryForObject("SELECT destination_id FROM folder_copy_members WHERE job_id=? AND kind='FILE' ORDER BY ordinal LIMIT 1",UUID.class,job); }
    void charge(long used,long reserved) { var u=usage.get(owner);assertThat(u.usedBytes()).isEqualTo(used);assertThat(u.reservedBytes()).isEqualTo(reserved); }
    org.springframework.test.web.servlet.request.RequestPostProcessor as(UUID actor) { return jwt().jwt(j->j.subject(actor.toString())); }

    @Test void nestedSnapshotIndependentIdentitiesActorAndAtomicVisibility() throws Exception {
        UUID child=folderService.createFolder(owner,sourceFolder,"child");
        fileService.moveFile(owner,source,child);
        var s=admit("nested");
        jdbc.update("UPDATE users SET display_name='Changed' WHERE id=?",owner);
        assertThat(s.totalMembers()).isEqualTo(3); assertThat(s.checkpoint()).isZero();
        mvc.perform(get("/api/v1/folders/{id}",s.folderId()).with(as(owner))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/folders/{id}/children",destination).with(as(owner))).andExpect(jsonPath("$.totalElements").value(0));
        assertThat(finish(s.id()).state()).isEqualTo("COMPLETED");
        UUID copied=copiedFile(s.id());
        assertThat(copied).isNotEqualTo(source);assertThat(key(copied)).isNotEqualTo(key(source));charge(6,0);
        mvc.perform(get("/api/v1/folders/{id}",s.folderId()).with(as(owner))).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT actor_display_name FROM resource_activity_events WHERE event_type='COPIED' AND owner_id=?",String.class,owner))
                .hasSize(3).containsOnly("Copy actor");
        assertThat(folderCopies.admit(owner,sourceFolder,"nested",new FolderCopyTransactions.Request(destination," snapshot ")).id()).isEqualTo(s.id());
        assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("COMPLETED");
        verify(storage,times(1)).copyObject(any());
        fileService.trashFile(owner,source);assertThat(files.findById(copied).orElseThrow().getStatus()).isEqualTo(FileStatus.READY);
        fileService.trashFile(owner,copied);assertThat(files.findById(source)).isPresent();
    }

    @Test void httpAdmissionPollingRecoveryAuthenticationAndValidation() throws Exception {
        mvc.perform(post("/api/v1/folders/{id}/copy",sourceFolder).contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/folders/{id}/copy",sourceFolder).with(as(owner)).contentType("application/json").content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/folders/{id}/copy",sourceFolder).with(as(owner)).header("Idempotency-Key","http")
                .contentType("application/json").content("{\"destinationFolderId\":\""+destination+"\",\"name\":\"snapshot\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Retry-After","2")).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.state").value("ACTIVE")).andExpect(jsonPath("$.totalFiles").value(1)).andExpect(jsonPath("$.storageKey").doesNotExist());
        mvc.perform(post("/api/v1/folders/{id}/copy",sourceFolder).with(as(owner)).header("Idempotency-Key","http-collision")
                .contentType("application/json").content("{\"destinationFolderId\":\""+destination+"\",\"name\":\"snapshot\"}"))
                .andExpect(status().isConflict());
        var s=admit("http");
        mvc.perform(get("/api/v1/folder-copy-operations/{id}",s.id()).with(as(owner))).andExpect(status().isOk());
        for(UUID id:List.of(s.id(),UUID.randomUUID())) {
            mvc.perform(get("/api/v1/folder-copy-operations/{id}",id).with(as(stranger))).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/folder-copy-operations/{id}/execute",id).with(as(stranger))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/folder-copy-operations/{id}/recover",s.id()).with(as(owner))).andExpect(status().isAccepted());
        verifyNoInteractions(storage);
        mvc.perform(post("/api/v1/folder-copy-operations/{id}/execute",s.id()).with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
        mvc.perform(post("/api/v1/folder-copy-operations/{id}/cancel",s.id()).with(as(owner))).andExpect(status().isConflict());
    }

    @ParameterizedTest @ValueSource(strings={"VIEWER","EDITOR"})
    void ownerOnlySourceAndDestination(String role) throws Exception {
        String email=users.findById(stranger).orElseThrow().getEmail();
        grants.create(owner,true,sourceFolder,email,ResourceRole.valueOf(role));
        grants.create(owner,true,destination,email,ResourceRole.valueOf(role));
        assertThatThrownBy(()->folderCopies.admit(stranger,sourceFolder,"denied",new FolderCopyTransactions.Request(null,"x")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        UUID own=folderService.createFolder(stranger,null,"own");
        assertThatThrownBy(()->folderCopies.admit(stranger,own,"denied-dest",new FolderCopyTransactions.Request(destination,"x")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(storage);
    }

    @Test void collisionsDescendantsAndChangedIdempotencyAreRejectedWithoutPartialAdmission() {
        UUID child=folderService.createFolder(owner,sourceFolder,"child");
        for(UUID dest:List.of(sourceFolder,child)) assertThatThrownBy(()->folderCopies.admit(owner,sourceFolder,"bad-"+dest,new FolderCopyTransactions.Request(dest,"x"))).isInstanceOf(RuntimeException.class);
        var s=admit("once");
        assertThatThrownBy(()->admit("twice")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->folderCopies.admit(owner,sourceFolder,"once",new FolderCopyTransactions.Request(null,"other"))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM folder_copy_jobs WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);
        assertThat(folderCopies.get(owner,s.id()).checkpoint()).isZero();charge(3,0);
    }

    @Test void membershipAndNamesDoNotDriftAfterSourceMutation() {
        var s=admit("frozen");
        fileService.renameFile(owner,source,"changed");fileService.moveFile(owner,source,null);
        folderService.createFolder(owner,sourceFolder,"added later");
        assertThat(finish(s.id()).state()).isEqualTo("COMPLETED");
        assertThat(files.findById(copiedFile(s.id())).orElseThrow().getName()).isEqualTo("original.txt");
        assertThat(folderCopies.get(owner,s.id()).totalMembers()).isEqualTo(2);
    }

    @Test void sourceUnavailableBeforeReservationFailsWithoutStorageAndCanBeDiscarded() {
        var s=admit("source-change");fileService.trashFile(owner,source);
        assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("FAILED");charge(3,0);
        verifyNoInteractions(storage);
        var discarded=folderCopies.discard(owner,s.id());assertThat(discarded.disposalOperationId()).isNotNull();
        assertThat(folderCopies.discard(owner,s.id()).disposalOperationId()).isEqualTo(discarded.disposalOperationId());
    }

    @Test void largeSubtreeResumesFromDurableBoundedCheckpointsWithoutDuplicateCharges() {
        tx.executeWithoutResult(t->{
            for(int i=0;i<23;i++) {
                UUID id=UUID.randomUUID();StoredFile f=new StoredFile(id,owner,sourceFolder,"file-"+i,"fixture/"+id,"text/plain",3);f.markReady();files.saveAndFlush(f);
                var b=binding.target(f.getStorageKey());jdbc.update("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,storage_namespace,endpoint,region,bucket,write_started) VALUES (?,?,?,?,?,?,?,true)",b.key(),id,owner,b.namespace(),b.endpoint(),b.region(),b.bucket());
                objects.put(b.key(),new CopyStorage.ObjectInfo(3,"source-etag","text/plain",Map.of()));
            }
        });
        var s=admit("large");assertThat(s.totalMembers()).isEqualTo(25);
        var first=folderCopyService.execute(owner,s.id());assertThat(first.state()).isEqualTo("ACTIVE");assertThat(first.checkpoint()).isEqualTo(FolderCopyService.BATCH_SIZE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resolve_resource_access(?,NULL,?)",Integer.class,owner,s.folderId())).isZero();
        // New orchestration instance reads only PostgreSQL checkpoints, as after restart.
        var restarted=new FolderCopyService(folderCopies,service);
        var done=first;for(int i=0;i<20 && !done.terminal();i++) done=restarted.execute(owner,s.id());
        assertThat(done.state()).isEqualTo("COMPLETED");assertThat(done.completedFiles()).isEqualTo(24);charge(144,0);
        verify(storage,times(24)).copyObject(any());
    }

    @Test void oversizedSnapshotIsRejectedAtomically() {
        jdbc.update("INSERT INTO folders(id,owner_id,parent_folder_id,name,created_at,updated_at) SELECT gen_random_uuid(),?,?,'child-'||g,clock_timestamp(),clock_timestamp() FROM generate_series(1,1000) g",owner,sourceFolder);
        assertThatThrownBy(()->admit("oversize")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM folder_copy_jobs WHERE owner_id=?",Integer.class,owner)).isZero();verifyNoInteractions(storage);
    }

    @Test void quotaExhaustionIsDurablyBlockedAndResumeDoesNotChargeTwice() {
        var s=admit("quota");jdbc.update("UPDATE storage_quotas SET limit_bytes=5 WHERE owner_id=?",owner);
        assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("BLOCKED");charge(3,0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_copy_operations WHERE folder_copy_job_id=?",Integer.class,s.id())).isZero();
        jdbc.update("UPDATE storage_quotas SET limit_bytes=100 WHERE owner_id=?",owner);
        assertThat(finish(s.id()).state()).isEqualTo("COMPLETED");charge(6,0);verify(storage,times(1)).copyObject(any());
    }

    @Test void unknownOutcomeOnlyRecoversPositiveEvidenceWithoutRedispatch() {
        doAnswer(c->{CopyStorage.Command command=c.getArgument(0);objects.put(command.destination().key(),new CopyStorage.ObjectInfo(3,"copy-etag",command.contentType(),Map.of("vaultdrive-copy",command.operationId().toString())));throw new CopyStorage.Uncertain();}).when(storage).copyObject(any());
        var s=admit("unknown");assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("UNKNOWN");charge(3,3);
        assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("UNKNOWN");
        assertThat(folderCopyService.recover(owner,s.id()).state()).isEqualTo("COMPLETED");charge(6,0);verify(storage,times(1)).copyObject(any());
    }

    @Test void absentUnknownEvidenceNeverRearmsDispatch() {
        doThrow(new CopyStorage.Uncertain()).when(storage).copyObject(any());var s=admit("absent");
        assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("UNKNOWN");
        assertThat(folderCopyService.recover(owner,s.id()).state()).isEqualTo("UNKNOWN");
        assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("UNKNOWN");verify(storage,times(1)).copyObject(any());charge(3,3);
        assertThat(folderCopies.cancel(owner,s.id()).state()).isEqualTo("CANCELLED");charge(3,3);
    }

    @Test void destinationTrashBlocksAndRestorationAllowsReceiptOnlyRecovery() {
        doAnswer(c->{CopyStorage.Command command=c.getArgument(0);objects.put(command.destination().key(),new CopyStorage.ObjectInfo(3,"copy-etag",command.contentType(),Map.of("vaultdrive-copy",command.operationId().toString())));folderService.deleteFolder(owner,destination);return null;}).when(storage).copyObject(any());
        var s=admit("trash");assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("BLOCKED");charge(3,3);
        folderService.restoreFolder(owner,destination);
        assertThat(folderCopyService.recover(owner,s.id()).state()).isEqualTo("COMPLETED");charge(6,0);verify(storage,times(1)).copyObject(any());
    }

    @Test void cancellationDuringStorageIsPermanentAndRetainsLateReceiptAndQuotaUntilDiscard() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        doAnswer(c->{noTransaction();CopyStorage.Command command=c.getArgument(0);entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();objects.put(command.destination().key(),new CopyStorage.ObjectInfo(3,"copy-etag",command.contentType(),Map.of("vaultdrive-copy",command.operationId().toString())));return null;}).when(storage).copyObject(any());
        var s=admit("cancel");
        try {
            Future<?> running=pool.submit(()->folderCopyService.execute(owner,s.id()));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(folderCopies.cancel(owner,s.id()).state()).isEqualTo("CANCELLED");release.countDown();running.get(15,TimeUnit.SECONDS);
            assertThat(folderCopies.get(owner,s.id()).state()).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM file_copy_operations WHERE folder_copy_job_id=? AND receipt_at IS NOT NULL",Integer.class,s.id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE owner_id=? AND event_type='COPIED'",Integer.class,owner)).isZero();charge(3,3);
            assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("CANCELLED");
            assertThat(folderCopies.discard(owner,s.id()).disposalOperationId()).isNotNull();
            verify(storage,times(1)).copyObject(any());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void concurrentIdempotentAdmissionAndExecutionHaveOneTreeAndOneDispatch() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier start=new CyclicBarrier(2);
        try {
            Callable<FolderCopyTransactions.Status> task=()->{start.await(10,TimeUnit.SECONDS);return admit("race");};
            var a=pool.submit(task);var b=pool.submit(task);var s=a.get(15,TimeUnit.SECONDS);assertThat(b.get(15,TimeUnit.SECONDS).id()).isEqualTo(s.id());
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            doAnswer(c->{CopyStorage.Command command=c.getArgument(0);entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();objects.put(command.destination().key(),new CopyStorage.ObjectInfo(3,"copy-etag",command.contentType(),Map.of("vaultdrive-copy",command.operationId().toString())));return null;}).when(storage).copyObject(any());
            Future<?> active=pool.submit(()->folderCopyService.execute(owner,s.id()));
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("UNKNOWN");}
            finally {release.countDown();}
            active.get(15,TimeUnit.SECONDS);assertThat(folderCopyService.recover(owner,s.id()).state()).isEqualTo("COMPLETED");charge(6,0);verify(storage,times(1)).copyObject(any());
        } finally {pool.shutdownNow();}
    }

    @Test void snapshotEvidenceAndTerminalDecisionsCannotBeRewritten() {
        var s=admit("guards");
        assertThatThrownBy(()->jdbc.update("UPDATE folder_copy_members SET name='rewritten' WHERE job_id=?",s.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM folder_copy_members WHERE job_id=?",s.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE folder_copy_jobs SET state='COMPLETED',completed_at=clock_timestamp(),revision=revision+1 WHERE id=?",s.id())).isInstanceOf(RuntimeException.class);
        folderCopies.cancel(owner,s.id());
        assertThatThrownBy(()->jdbc.update("UPDATE folder_copy_jobs SET state='ACTIVE',revision=revision+1 WHERE id=?",s.id())).isInstanceOf(RuntimeException.class);
    }

    @Test void partialReadyFilesStayHiddenFromReadsSearchAndOwnerMutations() throws Exception {
        var s=admit("partial");var root=folderCopies.next(owner,s.id(),false);folderCopies.checkpoint(owner,s.id(),root);
        var step=folderCopies.next(owner,s.id(),false);var ready=service.execute(owner,step.operationId());
        UUID copy=ready.fileId();charge(6,0);
        mvc.perform(get("/api/v1/files/{id}",copy).with(as(owner))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/search").param("q","original").with(as(owner))).andExpect(jsonPath("$.totalElements").value(1));
        assertThatThrownBy(()->fileService.moveFile(owner,copy,null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->folderService.renameFolder(owner,s.folderId(),"escape")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->folderService.moveFolder(owner,s.folderId(),null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->folderService.createFolder(owner,s.folderId(),"escape")).isInstanceOf(RuntimeException.class);
        assertThat(folderCopyService.recover(owner,s.id()).state()).isEqualTo("COMPLETED");verify(storage,times(1)).copyObject(any());
    }

    @Test void durableReservationSurvivesAbandonedCallerWithoutRecoveryDispatch() {
        var s=admit("interrupt");var root=folderCopies.next(owner,s.id(),false);folderCopies.checkpoint(owner,s.id(),root);
        var step=folderCopies.next(owner,s.id(),false);charge(3,3);
        assertThat(folderCopyService.recover(owner,s.id()).state()).isEqualTo("BLOCKED");verifyNoInteractions(storage);
        service.execute(owner,step.operationId());
        var restarted=new FolderCopyService(folderCopies,service);
        assertThat(restarted.recover(owner,s.id()).state()).isEqualTo("COMPLETED");charge(6,0);verify(storage,times(1)).copyObject(any());
    }

    @Test void folderPublicationAndActivityRollbackTogetherAndRecoverWithoutStorage() {
        var s=admit("activity-failure");
        tx.executeWithoutResult(t->doThrow(new IllegalStateException("injected activity failure")).when(activity).copiedFolder(eq(owner),anyString(),any()));
        assertThatThrownBy(()->folderCopyService.execute(owner,s.id())).isInstanceOf(IllegalStateException.class);
        assertThat(folderCopies.get(owner,s.id()).state()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resolve_resource_access(?,NULL,?)",Integer.class,owner,s.folderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=?",Integer.class,s.folderId())).isZero();
        reset(activity);clearInvocations(storage);
        assertThat(folderCopyService.recover(owner,s.id()).state()).isEqualTo("COMPLETED");verifyNoInteractions(storage);
    }

    @Test void cancellationDiscardUsesExistingPurgeAndRetainsUnknownEvidence() throws Exception {
        doThrow(new CopyStorage.Uncertain()).when(storage).copyObject(any());var s=admit("dispose");
        folderCopyService.execute(owner,s.id());UUID file=copiedFile(s.id());folderCopies.cancel(owner,s.id());
        var discarded=folderCopies.discard(owner,s.id());drain(discarded.disposalOperationId(),s.folderId());
        assertThat(folders.existsById(s.folderId())).isFalse();assertThat(files.existsById(file)).isFalse();charge(3,0);
        assertThat(usage.get(owner).uncertainStorageAllocations()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT copy_discarded_at IS NOT NULL AND metadata_finalized_at IS NOT NULL FROM file_purge_work WHERE file_id=?",Boolean.class,file)).isTrue();
        assertThat(folderCopies.get(owner,s.id()).state()).isEqualTo("CANCELLED");
        assertThat(admit("dispose").folderId()).isEqualTo(s.folderId());verify(storage,never()).delete(any());
    }

    @Test void completedDestinationPurgeDoesNotAffectSource() throws Exception {
        var s=admit("independent");assertThat(finish(s.id()).state()).isEqualTo("COMPLETED");UUID copy=copiedFile(s.id());
        folderService.deleteFolder(owner,s.folderId());folderService.requestPermanentDeletion(owner,s.folderId());
        UUID event=jdbc.queryForObject("SELECT event_id FROM folder_purge_runs WHERE root_folder_id=?",UUID.class,s.folderId());
        drain(event,s.folderId());assertThat(files.existsById(copy)).isFalse();assertThat(files.existsById(source)).isTrue();charge(3,0);
        assertThat(objects).containsKey(key(source));assertThat(folderCopies.get(owner,s.id()).state()).isEqualTo("COMPLETED");
    }

    @Test void parentPurgeDuringUnknownCopyOwnsSealedDisposal() throws Exception {
        doThrow(new CopyStorage.Uncertain()).when(storage).copyObject(any());var s=admit("parent-purge");folderCopyService.execute(owner,s.id());
        folderService.deleteFolder(owner,destination);folderService.requestPermanentDeletion(owner,destination);
        UUID event=jdbc.queryForObject("SELECT event_id FROM folder_purge_runs WHERE root_folder_id=?",UUID.class,destination);
        assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("BLOCKED");
        folderCopies.cancel(owner,s.id());assertThatThrownBy(()->folderCopies.discard(owner,s.id())).isInstanceOf(RuntimeException.class);
        drain(event,destination);assertThat(folders.existsById(s.folderId())).isFalse();charge(3,0);verify(storage,times(1)).copyObject(any());
    }

    @Test void maximumSnapshotFitsAdmissionBound() {
        jdbc.update("INSERT INTO folders(id,owner_id,parent_folder_id,name,created_at,updated_at) SELECT gen_random_uuid(),?,?,'child-'||g,clock_timestamp(),clock_timestamp() FROM generate_series(1,998) g",owner,sourceFolder);
        var s=admit("maximum");assertThat(s.totalMembers()).isEqualTo(1000);
        assertThat(finish(s.id()).state()).isEqualTo("COMPLETED");
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void snapshotVersusSourceTrashReadsFreshAfterHierarchyLock(boolean snapshotFirst) throws Exception {
        Supplier<Object> admission=()->admit("locked-snapshot");Supplier<Object> trash=()->{folderService.deleteFolder(owner,sourceFolder);return null;};
        if(snapshotFirst) race(admission,trash);
        else assertThatThrownBy(()->race(trash,admission)).hasRootCauseInstanceOf(com.vaultdrive.folder.exception.FolderNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM folder_copy_jobs WHERE owner_id=?",Integer.class,owner)).isEqualTo(snapshotFirst?1:0);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationVersusPublicationHasOnePermanentWinner(boolean publicationFirst) throws Exception {
        var s=admit("publication-race");var root=folderCopies.next(owner,s.id(),false);folderCopies.checkpoint(owner,s.id(),root);
        var step=folderCopies.next(owner,s.id(),false);service.execute(owner,step.operationId());
        Supplier<Object> publish=()->folderCopies.checkpoint(owner,s.id(),step);Supplier<Object> cancel=()->folderCopies.cancel(owner,s.id());
        if(publicationFirst) assertThatThrownBy(()->race(publish,cancel)).hasRootCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        else race(cancel,publish);
        assertThat(folderCopies.get(owner,s.id()).state()).isEqualTo(publicationFirst?"COMPLETED":"CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=?",Integer.class,s.folderId())).isEqualTo(publicationFirst?1:0);
    }

    @Test void snapshotExcludesOtherUnpublishedCopySubtrees() {
        admit("hidden-child");
        var parent=folderCopies.admit(owner,destination,"parent-snapshot",new FolderCopyTransactions.Request(null,"parent snapshot"));
        assertThat(parent.totalMembers()).isEqualTo(1);
        assertThat(finish(parent.id()).state()).isEqualTo("COMPLETED");verifyNoInteractions(storage);
    }

    @Test void concurrentDifferentKeysCollideAtOneDestinationWithoutAnotherTree() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier start=new CyclicBarrier(2);
        try {
            List<Future<Object>> runs=new ArrayList<>();
            for(String key:List.of("first-key","second-key")) runs.add(pool.submit(()->{start.await(5,TimeUnit.SECONDS);try{return admit(key);}catch(RuntimeException conflict){return conflict;}}));
            List<Object> results=new ArrayList<>();for(var run:runs)results.add(run.get(10,TimeUnit.SECONDS));
            assertThat(results.stream().filter(FolderCopyTransactions.Status.class::isInstance).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM folder_copy_jobs WHERE owner_id=?",Integer.class,owner)).isEqualTo(1);charge(3,0);
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    @Test void overdeepSnapshotIsRejectedBeforeAdmission() {
        tx.executeWithoutResult(t->{UUID parent=sourceFolder;for(int i=0;i<64;i++)parent=folders.saveAndFlush(new Folder(owner,parent,"deep-"+i)).getId();});
        assertThatThrownBy(()->admit("deep")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM folder_copy_jobs WHERE owner_id=?",Integer.class,owner)).isZero();verifyNoInteractions(storage);
    }

    @Test void sourceTrashAfterReservationFailsMemberAndReleasesOnlyItsLogicalCharge() {
        var s=admit("reserved-source-change");var root=folderCopies.next(owner,s.id(),false);folderCopies.checkpoint(owner,s.id(),root);
        folderCopies.next(owner,s.id(),false);charge(3,3);fileService.trashFile(owner,source);
        assertThat(folderCopyService.execute(owner,s.id()).state()).isEqualTo("FAILED");charge(3,0);verifyNoInteractions(storage);
    }

    @Test void internalMemberKeysCannotCollideWithPublicM1KeysOrBreakHistoricalReplay() {
        var s=admit("key-namespace");String key="folder:"+s.id()+":"+source;
        var publicCopy=service.copy(owner,source,key,new Request(null,"public.txt"));
        assertThat(publicCopy.state()).isEqualTo("READY");
        assertThat(finish(s.id()).state()).isEqualTo("COMPLETED");
        assertThat(service.copy(owner,source,key,new Request(null,"public.txt")).id()).isEqualTo(publicCopy.id());
        assertThat(copiedFile(s.id())).isNotEqualTo(publicCopy.fileId());charge(9,0);verify(storage,times(2)).copyObject(any());
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void retainedM1ConflictRecordsBlockedWithoutDroppingTheReservation(boolean recover) {
        var s=admit("retained-conflict");var root=folderCopies.next(owner,s.id(),false);folderCopies.checkpoint(owner,s.id(),root);
        var step=folderCopies.next(owner,s.id(),false);FileCopyService refusing=mock(FileCopyService.class);
        var conflict=new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Retained binding conflict");
        if(recover)when(refusing.recover(owner,step.operationId())).thenThrow(conflict);
        else when(refusing.execute(owner,step.operationId())).thenThrow(conflict);
        var orchestration=new FolderCopyService(folderCopies,refusing);
        assertThat((recover?orchestration.recover(owner,s.id()):orchestration.execute(owner,s.id())).state()).isEqualTo("BLOCKED");charge(3,3);verifyNoInteractions(storage);
        assertThat(finish(s.id()).state()).isEqualTo("COMPLETED");charge(6,0);
    }

    private void drain(UUID event, UUID root) throws Exception {
        jobs.create(event,JobType.FOLDER_PURGE,root);var claim=jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        for(int i=0;i<60;i++) {var result=folderHandler.handle(MultipartPurgeTestContext.context(claim));if(result==JobHandler.Result.SUCCEEDED){jobs.complete(claim);return;}assertThat(result).isEqualTo(JobHandler.Result.CONTINUE);}
        throw new AssertionError("Purge did not finish");
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
