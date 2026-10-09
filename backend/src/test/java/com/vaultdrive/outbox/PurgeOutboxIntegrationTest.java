package com.vaultdrive.outbox;

import com.vaultdrive.file.FileMetadataService;
import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class PurgeOutboxIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private StoredFileRepository fileRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private FileMetadataService fileService;
    @Autowired private FolderService folderService;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @MockitoSpyBean private OutboxWriter outboxWriter;

    private TransactionTemplate transaction;
    private UUID ownerId;
    private UUID otherOwnerId;
    private UUID fileId;
    private UUID folderId;

    @BeforeEach
    void createCommittedFixture() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            User owner = userRepository.saveAndFlush(new User(
                    UUID.randomUUID() + "@example.com", "test-hash", "Outbox owner"
            ));
            ownerId = owner.getId();
            otherOwnerId = userRepository.saveAndFlush(new User(
                    UUID.randomUUID() + "@example.com", "test-hash", "Other owner"
            )).getId();
            fileId = UUID.randomUUID();
            StoredFile file = new StoredFile(
                    fileId, ownerId, null, "outbox.txt",
                    "users/" + ownerId + "/files/" + fileId, "text/plain", 10L
            );
            file.markReady();
            file.softDelete();
            fileRepository.saveAndFlush(file);
            Folder folder = new Folder(ownerId, null, "Outbox folder");
            folder.softDelete();
            folderId = folderRepository.saveAndFlush(folder).getId();
        });
    }

    @AfterEach
    void removeOnlyFixtureData() {
        OutboxWriter target = AopTestUtils.getUltimateTargetObject(outboxWriter);
        reset(target);
        transaction.executeWithoutResult(status -> {
            outboxRepository.deleteAll(events(Resource.FILE));
            outboxRepository.deleteAll(events(Resource.FOLDER));
            outboxRepository.flush();
            fileRepository.deleteById(fileId);
            folderRepository.deleteById(folderId);
            fileRepository.flush();
            folderRepository.flush();
            userRepository.deleteAllById(List.of(ownerId, otherOwnerId));
        });
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void commitsPurgeAndVersionedJsonEventAtomically(Resource resource) {
        request(resource, ownerId);

        OutboxEvent event = onlyEvent(resource);
        assertThat(purgeRequestedAt(resource)).isNotNull();
        assertThat(event.getId()).isNotNull();
        assertThat(event.getEventType()).isEqualTo(resource + "_PURGE_REQUESTED");
        assertThat(event.getAggregateType()).isEqualTo(resource.name());
        assertThat(event.getAggregateId()).isEqualTo(id(resource));
        assertThat(event.getPayloadVersion()).isEqualTo(1);
        assertThat(event.getPayload()).isEqualTo(Map.of(
                "ownerId", ownerId.toString(),
                resource == Resource.FILE ? "fileId" : "folderId", id(resource).toString()
        ));
        assertThat(event.getCreatedAt()).isEqualTo(purgeRequestedAt(resource));
        assertThat(event.getNextAttemptAt()).isEqualTo(event.getCreatedAt());
        assertThat(event.getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getLastAttemptAt()).isNull();
        assertThat(event.getPublishedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void anotherTransactionCannotSeeUncommittedPurgeOrEvent(Resource resource)
            throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                request(resource, ownerId);
                assertThat(events(resource)).hasSize(1);
                try {
                    executor.submit(() -> transaction.executeWithoutResult(observer -> {
                        assertThat(events(resource)).isEmpty();
                        assertThat(purgeRequestedAt(resource)).isNull();
                    })).get(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("Independent transaction visibility check failed", exception);
                }
            });
        }
        assertThat(events(resource)).hasSize(1);
        assertThat(purgeRequestedAt(resource)).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void outerRollbackRemovesBothFlushedPurgeAndEvent(Resource resource) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            request(resource, ownerId);
            assertThat(events(resource)).hasSize(1);
            throw new IllegalStateException("Rollback after both writes");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(purgeRequestedAt(resource)).isNull();
        assertThat(events(resource)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void databaseOutboxInsertFailureRollsBackAlreadyFlushedPurge(Resource resource) {
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "payloadVersion", 0);
            return invocation.callRealMethod();
        }).when(AopTestUtils.<OutboxWriter>getUltimateTargetObject(outboxWriter))
                .append(any(OutboxEvent.class));

        assertThatThrownBy(() -> request(resource, ownerId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(purgeRequestedAt(resource)).isNull();
        assertThat(events(resource)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void unauthorizedRequestDoesNotPersistEvent(Resource resource) {
        assertThatThrownBy(() -> request(resource, otherOwnerId))
                .isInstanceOf(notFound(resource));
        assertThat(events(resource)).isEmpty();
        assertThat(purgeRequestedAt(resource)).isNull();
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void activeResourceRequestDoesNotPersistEvent(Resource resource) {
        transaction.executeWithoutResult(status -> {
            if (resource == Resource.FILE) {
                StoredFile file = fileRepository.findById(fileId).orElseThrow();
                file.restore(null, file.getName());
                fileRepository.flush();
            } else {
                Folder folder = folderRepository.findById(folderId).orElseThrow();
                folder.restore(null, folder.getName());
                folderRepository.flush();
            }
        });
        assertThatThrownBy(() -> request(resource, ownerId))
                .isInstanceOf(notFound(resource));
        assertThat(events(resource)).isEmpty();
        assertThat(purgeRequestedAt(resource)).isNull();
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void repeatedRequestDoesNotCreateAnotherEvent(Resource resource) {
        request(resource, ownerId);
        UUID eventId = onlyEvent(resource).getId();
        assertThatThrownBy(() -> request(resource, ownerId))
                .isInstanceOf(notFound(resource));
        assertThat(onlyEvent(resource).getId()).isEqualTo(eventId);
    }

    @ParameterizedTest
    @MethodSource("simultaneousPurgeRequests")
    void simultaneousRequestsEmitOneEventAfterWinnerCommitOrRollback(Resource resource, boolean rollback)
            throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                request(resource, ownerId);
                flushed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for test release");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted test", exception);
                }
                if (rollback) {
                    status.setRollbackOnly();
                }
            }));
            assertThat(flushed.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> request(resource, ownerId));
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            if (rollback) {
                second.get(5, TimeUnit.SECONDS);
            } else {
                assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(notFound(resource));
            }
            assertThat(events(resource)).hasSize(1);
            assertThat(purgeRequestedAt(resource)).isNotNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Stream<Arguments> simultaneousPurgeRequests() {
        return Stream.of(Arguments.of(Resource.FILE, false), Arguments.of(Resource.FILE, true),
                Arguments.of(Resource.FOLDER, false), Arguments.of(Resource.FOLDER, true));
    }

    @Test
    void writerRejectsCallsWithoutAnExistingTransaction() {
        assertThatThrownBy(() -> outboxWriter.append(
                OutboxEvent.filePurgeRequested(ownerId, fileId, Instant.now())
        )).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(events(Resource.FILE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "attempt_count = -1", "delivery_status = 'UNKNOWN'",
            "delivery_status = 'PUBLISHED'", "published_at = CURRENT_TIMESTAMP",
            "payload_version = 0", "payload = '[]'::jsonb"
    })
    void databaseRejectsInvalidDeliveryOrPayloadMetadata(String invalidAssignment) {
        request(Resource.FILE, ownerId);
        UUID eventId = onlyEvent(Resource.FILE).getId();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                        "UPDATE outbox_events SET " + invalidAssignment + " WHERE id = :id"
                ).setParameter("id", eventId).executeUpdate()
        )).hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(exception -> {
                    Throwable root = exception;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(((java.sql.SQLException) root).getSQLState())
                            .isEqualTo("23514");
                });
        assertThat(onlyEvent(Resource.FILE).getDeliveryStatus())
                .isEqualTo(OutboxDeliveryStatus.PENDING);
    }

    private void request(Resource resource, UUID owner) {
        if (resource == Resource.FILE) {
            fileService.requestPermanentDeletion(owner, fileId);
        } else {
            folderService.requestPermanentDeletion(owner, folderId);
        }
    }

    private UUID id(Resource resource) {
        return resource == Resource.FILE ? fileId : folderId;
    }

    private List<OutboxEvent> events(Resource resource) {
        return outboxRepository.findByAggregateTypeAndAggregateId(resource.name(), id(resource));
    }

    private OutboxEvent onlyEvent(Resource resource) {
        List<OutboxEvent> events = events(resource);
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private Instant purgeRequestedAt(Resource resource) {
        return resource == Resource.FILE
                ? fileRepository.findById(fileId).orElseThrow().getPurgeRequestedAt()
                : folderRepository.findById(folderId).orElseThrow().getPurgeRequestedAt();
    }

    private Class<? extends RuntimeException> notFound(Resource resource) {
        return resource == Resource.FILE ? FileNotFoundException.class : FolderNotFoundException.class;
    }

    private enum Resource { FILE, FOLDER }
}
