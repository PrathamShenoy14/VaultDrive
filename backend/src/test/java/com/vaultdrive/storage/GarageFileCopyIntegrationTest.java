package com.vaultdrive.storage;

import com.vaultdrive.file.*;
import com.vaultdrive.file.FileCopyTransactions.*;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.job.*;
import com.vaultdrive.user.*;
import com.vaultdrive.worker.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Only fresh run-scoped objects. Ambiguous storage effects retain their journal; no sweep. */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="GARAGE_COPY_TEST",matches="true")
@Timeout(90)
class GarageFileCopyIntegrationTest {
    static final String SCHEMA="copy_live_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema="+SCHEMA);
        r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @Autowired FileCopyService copies;
    @Autowired FileCopyTransactions transactions;
    @Autowired FileService files;
    @Autowired StoredFileRepository repository;
    @Autowired UserRepository users;
    @Autowired FolderService folders;
    @Autowired StorageBinding binding;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired S3Presigner signer;
    @Autowired BackgroundJobService jobs;
    @Autowired FilePurgeJobHandler purge;
    @Autowired StorageUsageService usage;
    @MockitoSpyBean ObjectStorageService storage;
    @MockitoSpyBean S3Client s3;
    @MockitoBean StorageKeyGenerator keys;
    GarageMultipartFixture fixture;
    UUID owner,source;
    final byte[] first=new byte[8388608],last="copy source final part".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final Map<String,UUID> destinations=new LinkedHashMap<>();
    final Set<String> safe=new HashSet<>();

    @BeforeEach void setup() throws Exception {
        fixture=new GarageMultipartFixture();fixture.event("COPY_M1","fresh isolated source and destinations");
        new Random(421).nextBytes(first);
        var allocation=fixture.create(true);var a=fixture.upload(allocation,1,first,true);var b=fixture.upload(allocation,2,last,true);
        fixture.complete(allocation,List.of(a,b));
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(t->{
            owner=users.saveAndFlush(new User(UUID.randomUUID()+"@example.com","fixture","Copy live")).getId();source=UUID.randomUUID();
            StoredFile f=new StoredFile(source,owner,null,"source.bin",allocation.key,"application/octet-stream",first.length+last.length);
            f.markReady();repository.saveAndFlush(f);var target=binding.target(allocation.key);
            jdbc.update("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,storage_namespace,endpoint,region,bucket,write_started) VALUES (?,?,?,?,?,?,?,true)",
                    target.key(),source,owner,target.namespace(),target.endpoint(),target.region(),target.bucket());
        });
        when(keys.generateFileKey(any(),any())).thenAnswer(c->"integration-tests/copy-m1/"+fixture.run+"/"+c.getArgument(1));
        doAnswer(c->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            CopyStorage.Command command=c.getArgument(0);destinations.put(command.destination().key(),command.operationId());
            fixture.event("COPY_DISPATCH",command.operationId()+" "+command.destination().key());
            return c.callRealMethod();
        }).when(storage).copyObject(any());
        clearInvocations(s3,storage);
    }

    @AfterEach void cleanup() throws Exception {
        reset(storage);reset(s3);
        for(var entry:destinations.entrySet()) {
            if(safe.contains(entry.getKey())) {
                assertThat(entry.getKey()).startsWith("integration-tests/copy-m1/"+fixture.run+"/");
                s3.deleteObject(b->b.bucket(fixture.properties.bucket()).key(entry.getKey()));fixture.event("COPY_OWNED_DELETE_ACK",entry.getKey());
            }else {fixture.event("COPY_RETAINED",entry.getValue()+" "+entry.getKey());fixture.retain=true;}
        }
        fixture.close();
    }

    @Test void realMultipartObjectCopiesWithReplacementMetadataAndIndependentDeletion() throws Exception {
        var ready=copies.copy(owner,source,"live",new Request(null,"copy.bin"));
        verifyObject(ready);assertThat(ready.state()).isEqualTo("READY");
        assertThat(usage.get(owner).usedBytes()).isEqualTo(2L*(first.length+last.length));
        filePurge(source);assertThat(repository.existsById(source)).isFalse();verifyObject(ready);
        var replay=copies.copy(owner,source,"live",new Request(null,"copy.bin"));assertThat(replay).isEqualTo(ready);
        copies.recover(owner,ready.id()); verify(s3,times(1)).copyObject(any(CopyObjectRequest.class));
        filePurge(ready.fileId());assertThat(repository.existsById(ready.fileId())).isFalse();assertThat(usage.get(owner).usedBytes()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_activity_events WHERE resource_id=? AND event_type='COPIED'",Integer.class,ready.fileId())).isEqualTo(1);
    }

    @Test void realSuccessfulCopyWithDroppedNetworkResponseRecoversFromPositiveHeadWithoutRedispatch() throws Exception {
        var s=transactions.reserve(owner,source,"drop",new Request(null,"drop.bin"));
        try(var proxy=new CopyDropProxy(fixture)) {
            var adapter=new S3ObjectStorageService(proxy.client,fixture.properties,binding,signer);
            doAnswer(c->{
                CopyStorage.Command command=c.getArgument(0);destinations.put(command.destination().key(),command.operationId());
                fixture.event("COPY_DISPATCH_PROXY",command.operationId()+" "+command.destination().key());
                adapter.copyObject(command);return null;
            }).when(storage).copyObject(any());
            var unknown=copies.execute(owner,s.id());proxy.verifyAccepted();
            assertThat(unknown.state()).isEqualTo("UNKNOWN");assertThat(unknown.storageVerified()).isFalse();
        }
        verifyObject(s);assertThat(usage.get(owner).reservedBytes()).isEqualTo(first.length+last.length);
        var ready=copies.recover(owner,s.id());assertThat(ready.state()).isEqualTo("READY");
        var receipt=jdbc.queryForMap("SELECT receipt_at,object_etag,dispatched_at FROM file_copy_operations WHERE id=?",s.id());
        copies.recover(owner,s.id());copies.execute(owner,s.id());
        assertThat(jdbc.queryForMap("SELECT receipt_at,object_etag,dispatched_at FROM file_copy_operations WHERE id=?",s.id())).isEqualTo(receipt);
        verify(storage,times(1)).copyObject(any());verify(s3,never()).copyObject(any(CopyObjectRequest.class));
        fixture.event("COPY_RECOVERED","positive identity HEAD, one wire copy, exact bytes");
    }

    @Test void destinationTrashDuringRealCopyRetainsReceiptAndFinalizesAfterRestore() throws Exception {
        UUID folder=folders.createFolder(owner,null,"destination");
        doAnswer(c->{CopyStorage.Command command=c.getArgument(0);destinations.put(command.destination().key(),command.operationId());
            fixture.event("COPY_DISPATCH",command.operationId()+" "+command.destination().key());
            c.callRealMethod();folders.deleteFolder(owner,folder);return null;
        }).when(storage).copyObject(any());
        var blocked=copies.copy(owner,source,"trash",new Request(folder,"blocked.bin"));verifyObject(blocked);
        assertThat(blocked.state()).isEqualTo("BLOCKED");assertThat(blocked.storageVerified()).isTrue();
        folders.restoreFolder(owner,folder);clearInvocations(s3);
        assertThat(copies.recover(owner,blocked.id()).state()).isEqualTo("READY");verifyNoInteractions(s3);
    }

    private void verifyObject(Status s) throws Exception {
        String key=jdbc.queryForObject("SELECT storage_key FROM file_storage_objects WHERE file_id=?",String.class,s.fileId());
        var result=s3.getObjectAsBytes(b->b.bucket(fixture.properties.bucket()).key(key));
        byte[] expected=Arrays.copyOf(first,first.length+last.length);System.arraycopy(last,0,expected,first.length,last.length);
        assertThat(result.asByteArray()).isEqualTo(expected);
        assertThat(result.response().metadata()).containsExactlyEntriesOf(Map.of("vaultdrive-copy",s.id().toString()));
        safe.add(key);fixture.event("COPY_POSITIVE_BYTES",s.id()+" length="+expected.length);
    }
    private void filePurge(UUID file) throws Exception {
        files.trashFile(owner,file);files.requestPermanentDeletion(owner,file);
        UUID event=jdbc.queryForObject("SELECT event_id FROM file_purge_targets WHERE file_id=?",UUID.class,file);
        jobs.create(event,JobType.FILE_PURGE,file);var claim=jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(purge.handle(MultipartPurgeTestContext.context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);jobs.complete(claim);
    }
}
