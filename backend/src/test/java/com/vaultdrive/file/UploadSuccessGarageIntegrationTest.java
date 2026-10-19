package com.vaultdrive.file;

import org.springframework.context.annotation.Import;

import com.vaultdrive.support.HistoricalObjectFixtures;
import com.vaultdrive.support.HistoricalUploadFixtures;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static com.vaultdrive.file.UploadFinalizationTransactions.Outcome.*;

@Import(HistoricalObjectFixtures.class)
@SpringBootTest
@ActiveProfiles("test")
class UploadSuccessGarageIntegrationTest {
    @Autowired HistoricalObjectFixtures historicalObjects;

    @Autowired HistoricalUploadFixtures history;

    private static final String SCHEMA = "upload_garage_" + UUID.randomUUID().toString().replace("-", "");
    private static final java.util.concurrent.atomic.AtomicBoolean RETAIN_SCHEMA = new java.util.concurrent.atomic.AtomicBoolean();
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired com.vaultdrive.storage.StorageBinding binding;
    @Autowired UploadFinalizationTransactions transactions;
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
            for (String key : owned) { storage.delete(binding.target(key)); }
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
        var file = history.reservation(new StoredFile(UUID.randomUUID(), owner, null, "bytes.txt",
                "integration-tests/" + SCHEMA + "/" + UUID.randomUUID(), "application/octet-stream", BYTES.length));
        history.admission(file.getStorageKey());
        historicalObjects.write(file, BYTES);
        if (receiptCommitted) { history.receipt(file); }
        clearInvocations(storage, s3);
        assertThat(transactions.recover(file.getId())).isEqualTo(receiptCommitted ? RECOVERED : NO_RECEIPT);
        assertThat(transactions.recover(file.getId())).isEqualTo(receiptCommitted ? ALREADY_READY : NO_RECEIPT);
        verifyNoInteractions(storage, s3);
        try (var bytes = storage.download(file.getStorageKey()).inputStream()) {
            assertThat(bytes.readAllBytes()).isEqualTo(BYTES);
        }
    }

}
