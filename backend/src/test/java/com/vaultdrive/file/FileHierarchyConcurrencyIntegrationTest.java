package com.vaultdrive.file;

import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
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
    private FolderService folderService;

    @Autowired
    private HierarchyCoordinator hierarchyCoordinator;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
}
