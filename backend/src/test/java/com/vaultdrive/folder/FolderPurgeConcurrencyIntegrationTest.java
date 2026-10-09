package com.vaultdrive.folder;

import com.vaultdrive.file.FileMetadataService;
import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class FolderPurgeConcurrencyIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private FolderService folderService;

    @Autowired
    private FileMetadataService fileMetadataService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void folderPurgeRequestBlocksThenRejectsChildRestore()
            throws Exception {
        Fixture fixture = createFixture("child-restore", true, false);

        assertRejectedAfterConcurrentParentPurge(
                fixture,
                () -> folderService.restoreFolder(
                        fixture.ownerId(),
                        fixture.childFolderId()
                ),
                () -> { }
        );
    }

    @Test
    void folderPurgeRequestBlocksThenRejectsFileRootFallbackRestore()
            throws Exception {
        Fixture fixture = createFixture("file-restore", false, true);

        assertRejectedAfterConcurrentParentPurge(
                fixture,
                () -> fileMetadataService.restore(
                        fixture.ownerId(),
                        fixture.fileId()
                ),
                () -> {
                    assertThat(requireFile(fixture.fileId()).getDeletedAt())
                            .isNotNull();
                    assertThat(requireFile(fixture.fileId()).getFolderId())
                            .isEqualTo(fixture.childFolderId());
                }
        );
    }

    @Test
    void folderPurgeRequestBlocksThenRejectsDescendantMove()
            throws Exception {
        Fixture fixture = createFixture("folder-move", false, false);

        assertRejectedAfterConcurrentParentPurge(
                fixture,
                () -> folderService.moveFolder(
                        fixture.ownerId(),
                        fixture.childFolderId(),
                        null
                ),
                () -> assertThat(requireFolder(
                        fixture.childFolderId()
                ).getParentFolderId()).isEqualTo(
                        fixture.parentFolderId()
                )
        );
    }

    @Test
    void folderPurgeRequestBlocksThenRejectsUploadReservation()
            throws Exception {
        Fixture fixture = createFixture("upload-reservation", false, false);
        UUID fileId = UUID.randomUUID();
        StoredFile upload = new StoredFile(
                fileId,
                fixture.ownerId(),
                fixture.childFolderId(),
                "upload.pdf",
                "users/" + fixture.ownerId() + "/files/" + fileId,
                "application/pdf",
                100L
        );

        assertRejectedAfterConcurrentParentPurge(
                fixture,
                () -> fileMetadataService.createUploading(upload),
                () -> assertThat(storedFileRepository.existsById(fileId))
                        .isFalse()
        );
    }

    @Test
    void parentPurgeWinsAndRejectsConcurrentChildPurge()
            throws Exception {
        Fixture fixture = createFixture("parent-wins", true, false);

        assertRejectedAfterConcurrentParentPurge(
                fixture,
                () -> folderService.requestPermanentDeletion(
                        fixture.ownerId(),
                        fixture.childFolderId()
                ),
                () -> {
                    assertThat(requireFolder(fixture.parentFolderId())
                            .getPurgeRequestedAt()).isNotNull();
                    assertThat(requireFolder(fixture.childFolderId())
                            .getPurgeRequestedAt()).isNull();
                }
        );
    }

    @Test
    void childPurgeWinsAndRejectsConcurrentParentPurge()
            throws Exception {
        Fixture fixture = createFixture("child-wins", true, false);
        CountDownLatch childFlushed = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> childPurge = executor.submit(() ->
                    inTransaction(() -> {
                        folderService.requestPermanentDeletion(
                                fixture.ownerId(),
                                fixture.childFolderId()
                        );
                        childFlushed.countDown();
                        await(releaseChild);
                    })
            );

            assertThat(childFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> parentPurge = executor.submit(() ->
                    folderService.requestPermanentDeletion(
                            fixture.ownerId(),
                            fixture.parentFolderId()
                    )
            );

            assertThatThrownBy(() ->
                    parentPurge.get(500, TimeUnit.MILLISECONDS)
            ).isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseChild.countDown();
            childPurge.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() ->
                    parentPurge.get(5, TimeUnit.SECONDS)
            ).satisfies(throwable ->
                    assertThat(rootCause(throwable))
                            .isInstanceOf(FolderNotFoundException.class)
            );

            assertThat(requireFolder(fixture.parentFolderId())
                    .getPurgeRequestedAt()).isNull();
            assertThat(requireFolder(fixture.childFolderId())
                    .getPurgeRequestedAt()).isNotNull();
            assertThat(outboxEventRepository.findByAggregateTypeAndAggregateId(
                    "FOLDER", fixture.childFolderId()
            )).hasSize(1);
            assertThat(outboxEventRepository.findByAggregateTypeAndAggregateId(
                    "FOLDER", fixture.parentFolderId()
            )).isEmpty();
        } finally {
            releaseChild.countDown();
            executor.shutdownNow();
            deleteFixture(fixture);
        }
    }

    private void assertRejectedAfterConcurrentParentPurge(
            Fixture fixture,
            Runnable competingOperation,
            Runnable postAssertion
    ) throws Exception {
        CountDownLatch purgeFlushed = new CountDownLatch(1);
        CountDownLatch releasePurge = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> purge = executor.submit(() ->
                    inTransaction(() -> {
                        folderService.requestPermanentDeletion(
                                fixture.ownerId(),
                                fixture.parentFolderId()
                        );
                        purgeFlushed.countDown();
                        await(releasePurge);
                    })
            );

            assertThat(purgeFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> competing = executor.submit(competingOperation);

            assertThatThrownBy(() ->
                    competing.get(500, TimeUnit.MILLISECONDS)
            ).isInstanceOf(java.util.concurrent.TimeoutException.class);

            releasePurge.countDown();
            purge.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() ->
                    competing.get(5, TimeUnit.SECONDS)
            ).satisfies(throwable ->
                    assertThat(rootCause(throwable))
                            .isInstanceOf(FolderNotFoundException.class)
            );

            assertThat(outboxEventRepository.findByAggregateTypeAndAggregateId(
                    "FOLDER", fixture.parentFolderId()
            )).hasSize(1);
            assertThat(outboxEventRepository.findByAggregateTypeAndAggregateId(
                    "FOLDER", fixture.childFolderId()
            )).isEmpty();
            postAssertion.run();
        } finally {
            releasePurge.countDown();
            executor.shutdownNow();
            deleteFixture(fixture);
        }
    }

    private Fixture createFixture(
            String label,
            boolean trashChild,
            boolean includeTrashedFile
    ) {
        User owner = userRepository.saveAndFlush(
                new User(
                        UUID.randomUUID() + "@example.com",
                        "temporary-test-hash",
                        label
                )
        );
        Folder parent = new Folder(owner.getId(), null, "Parent");
        Folder child = new Folder(owner.getId(), parent.getId(), "Child");
        parent.softDelete();
        if (trashChild) {
            child.softDelete();
        }
        folderRepository.saveAllAndFlush(List.of(parent, child));

        UUID fileId = null;
        if (includeTrashedFile) {
            fileId = UUID.randomUUID();
            StoredFile file = new StoredFile(
                    fileId,
                    owner.getId(),
                    child.getId(),
                    "report.pdf",
                    "users/" + owner.getId() + "/files/" + fileId,
                    "application/pdf",
                    100L
            );
            file.markReady();
            file.softDelete();
            storedFileRepository.saveAndFlush(file);
        }

        return new Fixture(
                owner.getId(),
                parent.getId(),
                child.getId(),
                fileId
        );
    }

    private Folder requireFolder(UUID folderId) {
        return folderRepository.findById(folderId).orElseThrow();
    }

    private StoredFile requireFile(UUID fileId) {
        return storedFileRepository.findById(fileId).orElseThrow();
    }

    private void deleteFixture(Fixture fixture) {
        for (UUID folderId : List.of(fixture.parentFolderId(), fixture.childFolderId())) {
            outboxEventRepository.deleteAll(
                    outboxEventRepository.findByAggregateTypeAndAggregateId("FOLDER", folderId)
            );
        }
        outboxEventRepository.flush();
        if (fixture.fileId() != null
                && storedFileRepository.existsById(fixture.fileId())) {
            storedFileRepository.deleteById(fixture.fileId());
            storedFileRepository.flush();
        }
        folderRepository.deleteById(fixture.childFolderId());
        folderRepository.flush();
        folderRepository.deleteById(fixture.parentFolderId());
        folderRepository.flush();
        userRepository.deleteById(fixture.ownerId());
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> action.run());
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable result = throwable;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }

    private record Fixture(
            UUID ownerId,
            UUID parentFolderId,
            UUID childFolderId,
            UUID fileId
    ) {
    }
}
