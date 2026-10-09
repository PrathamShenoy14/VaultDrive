package com.vaultdrive.file;

import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class FileHierarchyConcurrencyIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileMetadataService fileMetadataService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private HierarchyCoordinator hierarchyCoordinator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void restoreCompletesBeforeConcurrentPermanentDeletionRequest()
            throws Exception {
        RestoreFixture fixture = createRestoreFixture(
                "restore-before-purge",
                false
        );
        CountDownLatch restoreFlushed = new CountDownLatch(1);
        CountDownLatch releaseRestore = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> restore = executor.submit(() ->
                    inTransaction(() -> {
                        fileMetadataService.restore(
                                fixture.ownerId(),
                                fixture.fileIds().getFirst()
                        );
                        restoreFlushed.countDown();
                        await(releaseRestore);
                    })
            );

            assertThat(restoreFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> permanentDeletion = executor.submit(() ->
                    fileMetadataService.requestPermanentDeletion(
                            fixture.ownerId(),
                            fixture.fileIds().getFirst()
                    )
            );

            assertThatThrownBy(() ->
                    permanentDeletion.get(500, TimeUnit.MILLISECONDS)
            ).isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseRestore.countDown();
            restore.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() ->
                    permanentDeletion.get(5, TimeUnit.SECONDS)
            ).hasRootCauseInstanceOf(
                    com.vaultdrive.file.exception.FileNotFoundException.class
            );

            StoredFile persisted = requireFile(
                    fixture.fileIds().getFirst()
            );
            assertThat(persisted.getDeletedAt()).isNull();
            assertThat(persisted.getPurgeRequestedAt()).isNull();
            assertThat(outboxEventRepository.findByAggregateTypeAndAggregateId(
                    "FILE", persisted.getId()
            )).isEmpty();
        } finally {
            releaseRestore.countDown();
            executor.shutdownNow();
            deleteRestoreFixture(fixture);
        }
    }

    @Test
    void permanentDeletionRequestCompletesBeforeConcurrentRestore()
            throws Exception {
        RestoreFixture fixture = createRestoreFixture(
                "purge-before-restore",
                false
        );
        CountDownLatch purgeFlushed = new CountDownLatch(1);
        CountDownLatch releasePurge = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> permanentDeletion = executor.submit(() ->
                    inTransaction(() -> {
                        fileMetadataService.requestPermanentDeletion(
                                fixture.ownerId(),
                                fixture.fileIds().getFirst()
                        );
                        purgeFlushed.countDown();
                        await(releasePurge);
                    })
            );

            assertThat(purgeFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> restore = executor.submit(() ->
                    fileMetadataService.restore(
                            fixture.ownerId(),
                            fixture.fileIds().getFirst()
                    )
            );

            assertThatThrownBy(() ->
                    restore.get(500, TimeUnit.MILLISECONDS)
            ).isInstanceOf(java.util.concurrent.TimeoutException.class);

            releasePurge.countDown();
            permanentDeletion.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() -> restore.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(
                            com.vaultdrive.file.exception
                                    .FileNotFoundException.class
                    );

            StoredFile persisted = requireFile(
                    fixture.fileIds().getFirst()
            );
            assertThat(persisted.getDeletedAt()).isNotNull();
            assertThat(persisted.getPurgeRequestedAt()).isNotNull();
            assertThat(outboxEventRepository.findByAggregateTypeAndAggregateId(
                    "FILE", persisted.getId()
            )).hasSize(1);
        } finally {
            releasePurge.countDown();
            executor.shutdownNow();
            deleteRestoreFixture(fixture);
        }
    }

    @Test
    void restoreRevalidatesAfterConcurrentOriginalFolderTrash()
            throws Exception {
        RestoreFixture fixture = createRestoreFixture(
                "trash-before-restore",
                false
        );
        CountDownLatch trashFlushed = new CountDownLatch(1);
        CountDownLatch releaseTrash = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> trash = executor.submit(() ->
                    inTransaction(() -> {
                        folderService.deleteFolder(
                                fixture.ownerId(),
                                fixture.originalFolderId()
                        );
                        trashFlushed.countDown();
                        await(releaseTrash);
                    })
            );

            assertThat(trashFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<StoredFile> restore = executor.submit(() ->
                    fileMetadataService.restore(
                            fixture.ownerId(),
                            fixture.fileIds().getFirst()
                    )
            );

            assertThatThrownBy(() ->
                    restore.get(500, TimeUnit.MILLISECONDS)
            ).isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseTrash.countDown();
            trash.get(5, TimeUnit.SECONDS);

            StoredFile restored = restore.get(5, TimeUnit.SECONDS);
            assertThat(restored.getFolderId()).isNull();
            assertThat(restored.getDeletedAt()).isNull();
        } finally {
            releaseTrash.countDown();
            executor.shutdownNow();
            deleteRestoreFixture(fixture);
        }
    }

    @Test
    void restoreWaitsForConcurrentOriginalFolderMove()
            throws Exception {
        RestoreFixture fixture = createRestoreFixture(
                "move-before-restore",
                true
        );
        CountDownLatch moveFlushed = new CountDownLatch(1);
        CountDownLatch releaseMove = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> move = executor.submit(() ->
                    inTransaction(() -> {
                        folderService.moveFolder(
                                fixture.ownerId(),
                                fixture.originalFolderId(),
                                fixture.destinationFolderId()
                        );
                        moveFlushed.countDown();
                        await(releaseMove);
                    })
            );

            assertThat(moveFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<StoredFile> restore = executor.submit(() ->
                    fileMetadataService.restore(
                            fixture.ownerId(),
                            fixture.fileIds().getFirst()
                    )
            );

            assertThatThrownBy(() ->
                    restore.get(500, TimeUnit.MILLISECONDS)
            ).isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseMove.countDown();
            move.get(5, TimeUnit.SECONDS);

            StoredFile restored = restore.get(5, TimeUnit.SECONDS);
            assertThat(restored.getFolderId())
                    .isEqualTo(fixture.originalFolderId());
            assertThat(folderRepository
                    .findById(fixture.originalFolderId())
                    .orElseThrow()
                    .getParentFolderId())
                    .isEqualTo(fixture.destinationFolderId());
        } finally {
            releaseMove.countDown();
            executor.shutdownNow();
            deleteRestoreFixture(fixture);
        }
    }

    @Test
    void concurrentSameNameRestoresAllocateDistinctNames()
            throws Exception {
        RestoreFixture fixture = createRestoreFixture(
                "same-name-restores",
                true
        );
        UUID secondFileId = addTrashedFile(
                fixture.ownerId(),
                fixture.originalFolderId(),
                "report.pdf"
        );
        RestoreFixture completeFixture = new RestoreFixture(
                fixture.ownerId(),
                fixture.originalFolderId(),
                fixture.destinationFolderId(),
                List.of(fixture.fileIds().getFirst(), secondFileId)
        );
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<StoredFile> first = executor.submit(() -> {
                await(startSignal);
                return fileMetadataService.restore(
                        completeFixture.ownerId(),
                        completeFixture.fileIds().get(0)
                );
            });
            Future<StoredFile> second = executor.submit(() -> {
                await(startSignal);
                return fileMetadataService.restore(
                        completeFixture.ownerId(),
                        completeFixture.fileIds().get(1)
                );
            });

            startSignal.countDown();

            assertThat(List.of(
                    first.get(10, TimeUnit.SECONDS).getName(),
                    second.get(10, TimeUnit.SECONDS).getName()
            )).containsExactlyInAnyOrder(
                    "report.pdf",
                    "report (restored).pdf"
            );
        } finally {
            startSignal.countDown();
            executor.shutdownNow();
            deleteRestoreFixture(completeFixture);
        }
    }

    @Test
    void uploadReservationCompletesBeforeConcurrentDestinationTrash()
            throws Exception {
        UploadFixture fixture = createUploadFixture("reserve-before-trash");
        CountDownLatch reservationFlushed = new CountDownLatch(1);
        CountDownLatch releaseReservation = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> reservation = executor.submit(() ->
                    inTransaction(() -> {
                        fileMetadataService.createUploading(fixture.file());
                        reservationFlushed.countDown();
                        await(releaseReservation);
                    })
            );

            assertThat(reservationFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> trash = executor.submit(() ->
                    folderService.deleteFolder(
                            fixture.ownerId(),
                            fixture.folderId()
                    )
            );

            assertThatThrownBy(() -> trash.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseReservation.countDown();
            reservation.get(5, TimeUnit.SECONDS);
            trash.get(5, TimeUnit.SECONDS);

            StoredFile persisted = requireFile(fixture.file().getId());
            assertThat(persisted.getStatus()).isEqualTo(FileStatus.UPLOADING);
            assertThat(folderRepository.findById(fixture.folderId())
                    .orElseThrow().getDeletedAt()).isNotNull();
        } finally {
            releaseReservation.countDown();
            executor.shutdownNow();
            deleteUploadFixture(fixture);
        }
    }

    @Test
    void folderTrashDuringObjectTransferPreventsReadyFinalization() {
        UploadFixture fixture = createUploadFixture("trash-before-ready");

        try {
            StoredFile reservation =
                    fileMetadataService.createUploading(fixture.file());

            folderService.deleteFolder(
                    fixture.ownerId(),
                    fixture.folderId()
            );

            assertThatThrownBy(() ->
                    fileMetadataService.markReady(reservation)
            ).isInstanceOf(
                    com.vaultdrive.file.exception
                            .UploadFinalizationRejectedException.class
            );

            StoredFile persisted = requireFile(reservation.getId());
            assertThat(persisted.getStatus()).isEqualTo(FileStatus.FAILED);
            assertThat(persisted.getVersion()).isEqualTo(1L);
        } finally {
            deleteUploadFixture(fixture);
        }
    }

    @Test
    void competingUploadFinalizationsAllowExactlyOneTransition()
            throws Exception {
        UploadFixture fixture = createUploadFixture("competing-finalization");
        StoredFile reservation =
                fileMetadataService.createUploading(fixture.file());
        CountDownLatch readyUpdated = new CountDownLatch(1);
        CountDownLatch releaseReady = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> ready = executor.submit(() ->
                    inTransaction(() -> {
                        fileMetadataService.markReady(reservation);
                        readyUpdated.countDown();
                        await(releaseReady);
                    })
            );

            assertThat(readyUpdated.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> failed = executor.submit(() ->
                    fileMetadataService.markFailed(reservation)
            );

            assertThatThrownBy(() -> failed.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseReady.countDown();
            ready.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(
                            OptimisticLockingFailureException.class
                    );

            StoredFile persisted = requireFile(reservation.getId());
            assertThat(persisted.getStatus()).isEqualTo(FileStatus.READY);
            assertThat(persisted.getVersion()).isEqualTo(1L);
        } finally {
            releaseReady.countDown();
            executor.shutdownNow();
            deleteUploadFixture(fixture);
        }
    }

    @Test
    void fileMoveCompletesBeforeConcurrentSourceFolderTrash()
            throws Exception {
        Fixture fixture = createFixture("move-before-trash");
        CountDownLatch moveFlushed = new CountDownLatch(1);
        CountDownLatch releaseMove = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> move = executor.submit(() ->
                    inTransaction(() -> {
                        fileService.moveFile(
                                fixture.ownerId(),
                                fixture.fileId(),
                                fixture.destinationFolderId()
                        );
                        moveFlushed.countDown();
                        await(releaseMove);
                    })
            );

            assertThat(moveFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> trash = executor.submit(() ->
                    folderService.deleteFolder(
                            fixture.ownerId(),
                            fixture.sourceFolderId()
                    )
            );

            assertThatThrownBy(() -> trash.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseMove.countDown();
            move.get(5, TimeUnit.SECONDS);
            trash.get(5, TimeUnit.SECONDS);

            StoredFile persisted = storedFileRepository
                    .findById(fixture.fileId())
                    .orElseThrow();

            assertThat(persisted.getFolderId())
                    .isEqualTo(fixture.destinationFolderId());
            assertThat(folderRepository
                    .findById(fixture.sourceFolderId())
                    .orElseThrow()
                    .getDeletedAt())
                    .isNotNull();
        } finally {
            releaseMove.countDown();
            executor.shutdownNow();
            deleteFixture(fixture);
        }
    }

    @Test
    void fileRenameRevalidatesAfterConcurrentParentFolderTrash()
            throws Exception {
        Fixture fixture = createFixture("trash-before-rename");
        CountDownLatch trashFlushed = new CountDownLatch(1);
        CountDownLatch releaseTrash = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> trash = executor.submit(() ->
                    inTransaction(() -> {
                        folderService.deleteFolder(
                                fixture.ownerId(),
                                fixture.sourceFolderId()
                        );
                        trashFlushed.countDown();
                        await(releaseTrash);
                    })
            );

            assertThat(trashFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> rename = executor.submit(() ->
                    fileService.renameFile(
                            fixture.ownerId(),
                            fixture.fileId(),
                            "renamed.pdf"
                    )
            );

            assertThatThrownBy(() -> rename.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseTrash.countDown();
            trash.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() -> rename.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(FolderNotFoundException.class);

            StoredFile persisted = storedFileRepository
                    .findById(fixture.fileId())
                    .orElseThrow();

            assertThat(persisted.getName()).isEqualTo("report.pdf");
        } finally {
            releaseTrash.countDown();
            executor.shutdownNow();
            deleteFixture(fixture);
        }
    }

    @Test
    void concurrentSameFileMutationsCannotLoseAnUpdate()
            throws Exception {
        Fixture fixture = createFixture("same-file");
        CyclicBarrier bothFilesRead = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Throwable> rename = executor.submit(() ->
                    captureFailure(() ->
                            inTransaction(() -> {
                                hierarchyCoordinator.acquireShared(
                                        fixture.ownerId()
                                );
                                StoredFile file = requireFile(fixture.fileId());
                                await(bothFilesRead);
                                file.rename("renamed.pdf");
                                storedFileRepository.flush();
                            })
                    )
            );

            Future<Throwable> move = executor.submit(() ->
                    captureFailure(() ->
                            inTransaction(() -> {
                                hierarchyCoordinator.acquireShared(
                                        fixture.ownerId()
                                );
                                StoredFile file = requireFile(fixture.fileId());
                                await(bothFilesRead);
                                file.move(fixture.destinationFolderId());
                                storedFileRepository.flush();
                            })
                    )
            );

            List<Throwable> failures = java.util.stream.Stream.of(
                    rename.get(10, TimeUnit.SECONDS),
                    move.get(10, TimeUnit.SECONDS)
            ).filter(java.util.Objects::nonNull).toList();

            assertThat(failures).hasSize(1);
            assertThat(rootCause(failures.getFirst()).getClass().getName())
                    .containsAnyOf(
                            "OptimisticLock",
                            "StaleObjectStateException",
                            "StaleStateException"
                    );

            StoredFile persisted = requireFile(fixture.fileId());
            boolean renameWon = persisted.getName().equals("renamed.pdf")
                    && persisted.getFolderId().equals(
                            fixture.sourceFolderId()
                    );
            boolean moveWon = persisted.getName().equals("report.pdf")
                    && persisted.getFolderId().equals(
                            fixture.destinationFolderId()
                    );

            assertThat(renameWon || moveWon).isTrue();
            assertThat(persisted.getVersion()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            deleteFixture(fixture);
        }
    }

    private Fixture createFixture(String label) {
        User owner = userRepository.saveAndFlush(
                new User(
                        UUID.randomUUID() + "@example.com",
                        "temporary-test-hash",
                        label
                )
        );

        Folder source = folderRepository.saveAndFlush(
                new Folder(owner.getId(), null, "Source")
        );
        Folder destination = folderRepository.saveAndFlush(
                new Folder(owner.getId(), null, "Destination")
        );

        UUID fileId = UUID.randomUUID();
        StoredFile file = new StoredFile(
                fileId,
                owner.getId(),
                source.getId(),
                "report.pdf",
                "users/" + owner.getId() + "/files/" + fileId,
                "application/pdf",
                100L
        );
        file.markReady();
        storedFileRepository.saveAndFlush(file);

        return new Fixture(
                owner.getId(),
                source.getId(),
                destination.getId(),
                fileId
        );
    }

    private UploadFixture createUploadFixture(String label) {
        User owner = userRepository.saveAndFlush(
                new User(
                        UUID.randomUUID() + "@example.com",
                        "temporary-test-hash",
                        label
                )
        );

        Folder folder = folderRepository.saveAndFlush(
                new Folder(owner.getId(), null, "Destination")
        );

        UUID fileId = UUID.randomUUID();
        StoredFile file = new StoredFile(
                fileId,
                owner.getId(),
                folder.getId(),
                "upload.pdf",
                "users/" + owner.getId() + "/files/" + fileId,
                "application/pdf",
                100L
        );

        return new UploadFixture(owner.getId(), folder.getId(), file);
    }

    private RestoreFixture createRestoreFixture(
            String label,
            boolean includeDestination
    ) {
        User owner = userRepository.saveAndFlush(
                new User(
                        UUID.randomUUID() + "@example.com",
                        "temporary-test-hash",
                        label
                )
        );
        Folder originalFolder = folderRepository.saveAndFlush(
                new Folder(owner.getId(), null, "Original")
        );
        Folder destination = includeDestination
                ? folderRepository.saveAndFlush(
                        new Folder(owner.getId(), null, "Destination")
                )
                : null;
        UUID fileId = addTrashedFile(
                owner.getId(),
                originalFolder.getId(),
                "report.pdf"
        );

        return new RestoreFixture(
                owner.getId(),
                originalFolder.getId(),
                destination == null ? null : destination.getId(),
                List.of(fileId)
        );
    }

    private UUID addTrashedFile(
            UUID ownerId,
            UUID folderId,
            String name
    ) {
        UUID fileId = UUID.randomUUID();
        StoredFile file = new StoredFile(
                fileId,
                ownerId,
                folderId,
                name,
                "users/" + ownerId + "/files/" + fileId,
                "application/pdf",
                100L
        );
        file.markReady();
        file.softDelete();
        storedFileRepository.saveAndFlush(file);
        return fileId;
    }

    private void deleteFixture(Fixture fixture) {
        storedFileRepository.deleteById(fixture.fileId());
        storedFileRepository.flush();
        folderRepository.deleteAllById(List.of(
                fixture.sourceFolderId(),
                fixture.destinationFolderId()
        ));
        folderRepository.flush();
        userRepository.deleteById(fixture.ownerId());
    }

    private void deleteUploadFixture(UploadFixture fixture) {
        if (storedFileRepository.existsById(fixture.file().getId())) {
            storedFileRepository.deleteById(fixture.file().getId());
            storedFileRepository.flush();
        }
        folderRepository.deleteById(fixture.folderId());
        folderRepository.flush();
        userRepository.deleteById(fixture.ownerId());
    }

    private void deleteRestoreFixture(RestoreFixture fixture) {
        for (UUID fileId : fixture.fileIds()) {
            outboxEventRepository.deleteAll(
                    outboxEventRepository.findByAggregateTypeAndAggregateId("FILE", fileId)
            );
        }
        outboxEventRepository.flush();
        storedFileRepository.deleteAllById(fixture.fileIds());
        storedFileRepository.flush();
        folderRepository.deleteById(fixture.originalFolderId());
        folderRepository.flush();
        if (fixture.destinationFolderId() != null) {
            folderRepository.deleteById(fixture.destinationFolderId());
            folderRepository.flush();
        }
        userRepository.deleteById(fixture.ownerId());
    }

    private StoredFile requireFile(UUID fileId) {
        return storedFileRepository.findById(fileId).orElseThrow();
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> action.run());
    }

    private Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
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

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException exception) {
            throw new RuntimeException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }

    private record Fixture(
            UUID ownerId,
            UUID sourceFolderId,
            UUID destinationFolderId,
            UUID fileId
    ) {
    }

    private record UploadFixture(
            UUID ownerId,
            UUID folderId,
            StoredFile file
    ) {
    }

    private record RestoreFixture(
            UUID ownerId,
            UUID originalFolderId,
            UUID destinationFolderId,
            List<UUID> fileIds
    ) {
    }
}
