package com.vaultdrive.storage;

import com.vaultdrive.file.*;
import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;

import javax.sql.DataSource;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.job.*;
import com.vaultdrive.worker.*;
import static com.vaultdrive.worker.MultipartPurgeTestContext.context;

import static com.vaultdrive.storage.GarageMultipartFixture.sha;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GARAGE_MULTIPART_PURGE_TEST", matches = "true")
@Timeout(90)
class GarageMultipartPurgeIntegrationTest {
    static final String SCHEMA = "multipart_purge_live_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartTransferService transfers;
    @Autowired MultipartCancellationService cancellations;
    @Autowired MultipartCompletionService completions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @MockitoBean StorageKeyGenerator keys;
    @MockitoSpyBean S3Client s3;
    @Autowired FolderService folders;
    @Autowired FileService files;
    @Autowired FileMetadataService metadata;
    @Autowired BackgroundJobService jobs;
    @Autowired FolderPurgeJobHandler folderHandler;
    @Autowired FilePurgeJobHandler fileHandler;
    @Autowired FilePurgeProgressService progress;
    GarageMultipartFixture f;
    UUID owner;

    @BeforeAll void initialize() throws Exception {
        f = new GarageMultipartFixture(); f.event("M5", "production purge compatibility; isolated PostgreSQL");
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "fixture", "M5 live")).getId();
    }
    @BeforeEach void stubs() {
        clearInvocations(s3);
        when(keys.generateFileKey(any(), any())).thenAnswer(call -> "integration-tests/multipart-m5/" + f.run + "/" + call.getArgument(1));
        doAnswer(call -> {
            assertNoTransaction(); CreateMultipartUploadRequest request = call.getArgument(0);
            var a = new GarageMultipartFixture.Allocation(request.key()); f.allocations.add(a); f.event("ALLOCATED", a.key);
            try { var result = (CreateMultipartUploadResponse) call.callRealMethod();
                a.uploadId = result.uploadId(); f.event("CREATE_HTTP_200", a.key + " " + a.uploadId); return result;
            } catch (Throwable failure) { a.uncertain = true; f.event("CREATE_UNKNOWN", a.key); throw failure; }
        }).when(s3).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        doAnswer(call -> {
            assertNoTransaction(); AbortMultipartUploadRequest request = call.getArgument(0); var a = allocation(request.key());
            assertThat(request.uploadId()).isEqualTo(a.uploadId); f.event("ABORT_DISPATCH", a.key);
            try { var response = (AbortMultipartUploadResponse) call.callRealMethod();
                assertThat(response.sdkHttpResponse().statusCode()).isEqualTo(204); a.aborted = true;
                f.event("ABORT_HTTP_204", a.key); return response;
            } catch (Throwable failure) { a.uncertain = true; f.event("ABORT_UNKNOWN", a.key); throw failure; }
        }).when(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        doAnswer(call -> {
            assertNoTransaction(); CompleteMultipartUploadRequest request = call.getArgument(0); var a = allocation(request.key());
            a.completionDispatched = true; f.event("COMPLETE_DISPATCH", a.key);
            try { var result = call.callRealMethod(); a.completed = true; f.event("COMPLETE_HTTP_200", a.key); return result; }
            catch (Throwable failure) { a.uncertain = true; throw failure; }
        }).when(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }
    @AfterEach void controlRemainsReadable() throws Exception {
        f.assertBytes(f.control, GarageMultipartFixture.CONTROL);
    }
    @AfterAll void dispose() throws Exception {
        if (f == null) return;
        f.close();
        if (f.retain || f.allocations.stream().anyMatch(a -> a.uncertain)) return;
        assertThat(SCHEMA).matches("multipart_purge_live_[0-9a-f]{32}");
        try (var c = source.getConnection(); var statement = c.createStatement()) {
            assertThat(c.getCatalog()).isEqualTo("vaultdrive_test"); statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Test void completedReadyBytesAreDeletedDespiteAcceptedLatePartWithoutResurrection() throws Exception {
        byte[] bytes = bytes(1024 * 1024); var s = open(bytes.length); var a = allocation(s);
        var url = signed(s, 1, bytes); assertThat(f.send(url, url.url().toURI(), bytes).status()).isEqualTo(200);
        a.activeRequests.incrementAndGet();
        try (var gate = new GarageMultipartFixture.GatedPart(url, bytes)) {
            assertThat(completions.complete(owner, s.id()).completion().ready()).isTrue(); assertBytes(a, bytes);
            var claim = standalone(s); assertThat(fileHandler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
            int status = gate.finish(); f.event("LATE_PART_AFTER_M5_DELETE", "http=" + status); assertThat(status).isIn(200,404);
            f.noObject(a); // Observation; no part-block reclamation inference.
            assertThat(jdbc.queryForObject("SELECT bytes_deleted_at IS NOT NULL AND metadata_finalized_at IS NOT NULL FROM file_purge_work WHERE file_id = ?", Boolean.class,s.fileId())).isTrue();
            clearInvocations(s3); assertThat(fileHandler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED);
            verify(s3,never()).deleteObject(any(DeleteObjectRequest.class));
        } catch (Exception | AssertionError failure) { a.uncertain=true; throw failure; }
        finally { a.activeRequests.decrementAndGet(); }
    }

    @Test void lostAbortResponseDoesNotBlockFolderRemovalOrPermitStorageCleanup() throws Exception {
        UUID root=folders.createFolder(owner,null,"lost-abort"); var s=openIn(root,"reuse",4096); var a=allocation(s);
        try (var proxy=new MultipartAbortDropProxy(f)) {
            doAnswer(call -> { assertNoTransaction(); return proxy.client.abortMultipartUpload((AbortMultipartUploadRequest)call.getArgument(0)); })
                .when(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
            assertThat(cancellations.cancel(owner,s.id()).disposition()).isEqualTo("UNKNOWN");
            proxy.verifyAccepted(); a.aborted=true; f.event("ABORT_KNOWN_BY_VERIFIER","application remains UNKNOWN");
            var newer=openIn(root,"reuse",1); // Distinct immutable allocation while old physical disposition remains unknown.
            assertThat(allocation(newer).key).isNotEqualTo(a.key);
            clearInvocations(s3); drain(folderClaim(root));
            verifyNoInteractions(s3);
            assertThat(jdbc.queryForObject("SELECT disposition FROM multipart_cancellations WHERE session_id = ?",String.class,s.id())).isEqualTo("UNKNOWN");
            assertLogical(s); assertLogical(newer);
        } catch (Exception | AssertionError failure) { if (!a.aborted) a.uncertain=true; throw failure; }
    }

    @Test void permittedCompleteMayFinishAfterFolderRemovalWithRetainedObjectAndNoReadyResurrection() throws Exception {
        UUID root=folders.createFolder(owner,null,"late-complete"); byte[] bytes=bytes(4096); var s=openIn(root,"late",bytes.length); var a=allocation(s);
        var url=signed(s,1,bytes); assertThat(f.send(url,url.url().toURI(),bytes).status()).isEqualTo(200);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var executor=Executors.newSingleThreadExecutor();
        doAnswer(call -> {
            assertNoTransaction(); entered.countDown();
            if (!release.await(15,TimeUnit.SECONDS)) throw new AssertionError("release timeout");
            a.completionDispatched=true; f.event("COMPLETE_DISPATCH",a.key);
            try { var result=call.callRealMethod(); a.completed=true; f.event("COMPLETE_HTTP_200",a.key); return result; }
            catch (Throwable failure) { a.uncertain=true; throw failure; }
        }).when(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        try {
            var future=executor.submit(() -> completions.complete(owner,s.id()));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue(); drain(folderClaim(root)); assertLogical(s);
            release.countDown(); assertThat(future.get(15,TimeUnit.SECONDS).completion().reason()).isEqualTo("FINALIZATION_BLOCKED");
            assertLogical(s); assertBytes(a,bytes);
            verify(s3,never()).deleteObject(any(DeleteObjectRequest.class));
            f.event("M5_LATE_COMPLETED_OBJECT_RETAINED","logical deletion only; test verifier owns later disposal");
        } catch (Exception | AssertionError failure) { if (!a.completed) a.uncertain=true; throw failure; }
        finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void oldDeletionReceiptCannotDeleteNewSameNameObject() throws Exception {
        byte[] oldBytes=bytes(1024), newBytes=bytes(2048); var old=openIn(null,"same",oldBytes.length);
        var url=signed(old,1,oldBytes); assertThat(f.send(url,url.url().toURI(),oldBytes).status()).isEqualTo(200);
        assertThat(completions.complete(owner,old.id()).completion().ready()).isTrue(); var claim=standalone(old);
        assertThat(fileHandler.handle(context(claim))).isEqualTo(JobHandler.Result.SUCCEEDED); assertThat(jobs.complete(claim)).isTrue();
        var newer=openIn(null,"same",newBytes.length); var next=signed(newer,1,newBytes);
        assertThat(f.send(next,next.url().toURI(),newBytes).status()).isEqualTo(200);
        assertThat(completions.complete(owner,newer.id()).completion().ready()).isTrue();
        clearInvocations(s3); assertThat(progress.recoverSucceeded(claim.id())).isTrue();
        verifyNoInteractions(s3); assertBytes(allocation(newer),newBytes);
        assertThat(allocation(newer).key).isNotEqualTo(allocation(old).key);
    }

    private void assertBytes(GarageMultipartFixture.Allocation a, byte[] expected) throws Exception {
        assertThat(f.s3.getObjectAsBytes(b -> b.bucket(f.properties.bucket()).key(a.key)).asByteArray()).isEqualTo(expected);
        f.event("M5_GET_EXACT", "size=" + expected.length + " sha256=" + sha(expected));
    }
    private Session openIn(UUID folder,String name,long size) {
        var s=sessions.reserve(owner,UUID.randomUUID().toString(),new Request(folder,name,size,"application/octet-stream"));
        return transfers.initiate(owner,s.id());
    }
    private ClaimedJob standalone(Session s) {
        files.trashFile(owner,s.fileId()); metadata.requestPermanentDeletion(owner,s.fileId());
        UUID event=jdbc.queryForObject("SELECT event_id FROM file_purge_targets WHERE file_id = ?",UUID.class,s.fileId());
        jobs.create(event,JobType.FILE_PURGE,s.fileId()); return jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
    }
    private ClaimedJob folderClaim(UUID root) {
        folders.deleteFolder(owner,root); folders.requestPermanentDeletion(owner,root);
        UUID event=jdbc.queryForObject("SELECT event_id FROM folder_purge_runs WHERE root_folder_id = ?",UUID.class,root);
        jobs.create(event,JobType.FOLDER_PURGE,root); return jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
    }
    private void drain(ClaimedJob claim) throws Exception {
        for(int i=0;i<30;i++) if(folderHandler.handle(context(claim)) == JobHandler.Result.SUCCEEDED) return;
        throw new AssertionError("Unfinished folder purge");
    }
    private void assertLogical(Session s) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE id = ?",Integer.class,s.fileId())).isZero();
        assertThat(jdbc.queryForObject("SELECT multipart_discarded_at IS NOT NULL AND metadata_finalized_at IS NOT NULL AND bytes_deleted_at IS NULL AND never_written_at IS NULL FROM file_purge_work WHERE file_id = ?",Boolean.class,s.fileId())).isTrue();
    }

    private Session open(long size) {
        var s = sessions.reserve(owner, UUID.randomUUID().toString(), new Request(null, UUID.randomUUID().toString(), size, "application/octet-stream"));
        var opened = transfers.initiate(owner, s.id()); assertThat(opened.state()).isEqualTo("OPEN"); return opened;
    }
    private PresignedUploadPartRequest signed(Session s, int number, byte[] bytes) {
        var signed = transfers.partUrls(owner, s.id(), List.of(new PartExpectation(number, bytes.length, sha(bytes)))).getFirst().upload();
        return PresignedUploadPartRequest.builder().expiration(signed.expiresAt()).signedHeaders(signed.requiredHeaders())
                .httpRequest(SdkHttpFullRequest.builder().uri(URI.create(signed.url())).method(SdkHttpMethod.PUT).headers(signed.requiredHeaders()).build())
                .isBrowserExecutable(false).build();
    }
    private GarageMultipartFixture.Allocation allocation(Session s) { return f.allocations.stream().filter(a -> Objects.equals(a.uploadId, s.uploadId())).findFirst().orElseThrow(); }
    private GarageMultipartFixture.Allocation allocation(String key) { return f.allocations.stream().filter(a -> a.key.equals(key)).findFirst().orElseThrow(); }
    private static byte[] bytes(int length) { byte[] result = new byte[length]; new Random(44).nextBytes(result); return result; }
    private static void assertNoTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
}
