package com.vaultdrive.file;

import com.vaultdrive.file.exception.FileUploadException;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static com.vaultdrive.file.UploadFinalizationTransactions.Outcome.*;

@SpringBootTest
@ActiveProfiles("test")
class UploadSuccessGarageIntegrationTest {
    private static final String SCHEMA = "upload_garage_" + UUID.randomUUID().toString().replace("-", "");
    private static final java.util.concurrent.atomic.AtomicBoolean RETAIN_SCHEMA = new java.util.concurrent.atomic.AtomicBoolean();
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired FileMetadataService metadata;
    @Autowired FileService files;
    @Autowired FolderService folders;
    @Autowired UploadFinalizationTransactions transactions;
    @Autowired UploadFinalizationRecovery recovery;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean ObjectStorageService storage;
    @MockitoSpyBean S3Client s3;
    private TransactionTemplate tx;
    private UUID owner;
    private final List<String> owned = new ArrayList<>();
    private static final byte[] BYTES = new byte[]{1, 3, 5, 7};

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Garage success")).getId();
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Object response = i.callRealMethod();
            owned.add(((PutObjectRequest) i.getArgument(0)).key()); // Register only a known completed PUT.
            return response;
        }).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
    @AfterEach void cleanup() {
        @SuppressWarnings("unchecked")
        List<String> admitted = tx.execute(status -> em.createNativeQuery(
                "SELECT storage_key FROM file_storage_objects WHERE owner_id = :owner AND write_started")
                .setParameter("owner", owner).getResultList());
        // An unexpected uncertain PUT must retain its allocation/schema, even on a failed test.
        if (!owned.containsAll(admitted)) {
            RETAIN_SCHEMA.set(true);
            throw new AssertionError("Uncertain disposable write retained in " + SCHEMA);
        }
        try {
            for (String key : owned) { storage.delete(key); }
        } catch (RuntimeException failure) {
            RETAIN_SCHEMA.set(true);
            throw new AssertionError("Disposable byte cleanup failed; allocation retained in " + SCHEMA);
        }
        tx.executeWithoutResult(status -> {
            for (String table : List.of("file_storage_objects", "files", "folders")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE owner_id = :owner").setParameter("owner", owner).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM users WHERE id = :owner").setParameter("owner", owner).executeUpdate();
        });
    }
    @AfterAll static void removeSchema(@Autowired DataSource source) throws Exception {
        if (!SCHEMA.matches("upload_garage_[0-9a-f]{32}")) { throw new AssertionError("Invalid schema"); }
        if (RETAIN_SCHEMA.get()) { return; }
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realPutCrashBoundariesRetainBytesAndRecoveryNeverTouchesGarage(boolean receiptCommitted) throws Exception {
        var file = metadata.createUploading(new StoredFile(UUID.randomUUID(), owner, null, "bytes.txt",
                "integration-tests/" + SCHEMA + "/" + UUID.randomUUID(), "application/octet-stream", BYTES.length));
        try (var input = new ByteArrayInputStream(BYTES)) {
            storage.upload(file.getStorageKey(), input, BYTES.length, file.getContentType());
        }
        if (receiptCommitted) { transactions.recordSuccess(file); }
        clearInvocations(storage, s3);
        assertThat(transactions.recover(file.getId())).isEqualTo(receiptCommitted ? RECOVERED : NO_RECEIPT);
        assertThat(transactions.recover(file.getId())).isEqualTo(receiptCommitted ? ALREADY_READY : NO_RECEIPT);
        verifyNoInteractions(storage, s3);
        try (var bytes = storage.download(file.getStorageKey()).inputStream()) {
            assertThat(bytes.readAllBytes()).isEqualTo(BYTES);
        }
    }

    @Test void foregroundRecordsAfterCloseAndBlockedDestinationRetainsObjectUntilExplicitRestoreRecovery() throws Exception {
        UUID folder = folders.createFolder(owner, null, "destination");
        var multipart = new MockMultipartFile("file", "blocked.txt", "text/plain", BYTES) {
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(BYTES) {
                    @Override public void close() {
                        // The SDK has returned successfully; force the destination change at close.
                        folders.deleteFolder(owner, folder);
                    }
                };
            }
        };
        assertThatThrownBy(() -> files.uploadFile(owner, folder, multipart)).isInstanceOf(FileUploadException.class);
        Object[] row = tx.execute(status -> (Object[]) em.createNativeQuery(
                "SELECT f.id, f.storage_key, f.status, a.put_succeeded_at IS NOT NULL FROM files f "
                + "JOIN file_storage_objects a ON a.file_id = f.id WHERE f.owner_id = :owner")
                .setParameter("owner", owner).getSingleResult());
        UUID id = (UUID) row[0]; String key = (String) row[1];
        assertThat(row[2]).isEqualTo("UPLOADING"); assertThat(row[3]).isEqualTo(true);
        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(storage, never()).delete(any(String.class));
        clearInvocations(storage, s3);
        assertThat(recovery.recoverPage(null).blocked()).isEqualTo(1);
        folders.restoreFolder(owner, folder);
        assertThat(transactions.recover(id)).isEqualTo(RECOVERED);
        assertThat(transactions.recover(id)).isEqualTo(ALREADY_READY);
        verifyNoInteractions(storage, s3);
        try (var bytes = storage.download(key).inputStream()) { assertThat(bytes.readAllBytes()).isEqualTo(BYTES); }
    }
}
