package com.vaultdrive.folder;

import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;
import java.util.concurrent.*;

import com.vaultdrive.folder.dto.RestoreFolderResponse;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FolderConcurrencyIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private FolderService folderService;

    @Test
    void shouldBlockSecondTransactionUntilFirstReleasesLock()
            throws Exception {

        User user = userRepository.saveAndFlush(
                new User(
                        UUID.randomUUID() + "@example.com",
                        "temporary-test-hash",
                        "Concurrency Test"
                )
        );

        UUID userId = user.getId();

        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        try {
            Future<?> firstTransaction = executor.submit(() -> {

                transactionTemplate.executeWithoutResult(status -> {

                    userRepository.findByIdForUpdate(userId)
                            .orElseThrow();

                    firstLockAcquired.countDown();

                    try {
                        if (!releaseFirstLock.await(
                                10,
                                TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "Timed out waiting to release first lock"
                            );
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(exception);
                    }
                });
            });

            assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS))
                    .isTrue();

            Future<?> secondTransaction = executor.submit(() -> {

                transactionTemplate.executeWithoutResult(status -> {

                    secondAttemptStarted.countDown();

                    userRepository.findByIdForUpdate(userId)
                            .orElseThrow();
                });
            });

            assertThat(secondAttemptStarted.await(5, TimeUnit.SECONDS))
                    .isTrue();

            // The second transaction should still be waiting.
            Thread.sleep(500);

            assertThat(secondTransaction.isDone())
                    .isFalse();

            // Release the first transaction.
            releaseFirstLock.countDown();

            firstTransaction.get(5, TimeUnit.SECONDS);
            secondTransaction.get(5, TimeUnit.SECONDS);

            assertThat(secondTransaction.isDone())
                    .isTrue();

        } finally {
            releaseFirstLock.countDown();

            executor.shutdownNow();

            userRepository.deleteById(userId);
        }
    }

    @Test
    void shouldRestoreConcurrentFoldersWithUniqueNames()
            throws Exception {
    
        // 1. Create a committed test user.
    
        User user = userRepository.saveAndFlush(
                new User(
                        UUID.randomUUID() + "@example.com",
                        "temporary-test-hash",
                        "Concurrent Restoration Test"
                )
        );
    
        UUID ownerId = user.getId();
    
        // 2. Create two deleted folders with identical names.
    
        Folder firstFolder = new Folder(
                ownerId,
                null,
                "Documents"
        );
    
        Folder secondFolder = new Folder(
                ownerId,
                null,
                "Documents"
        );
    
        firstFolder.softDelete();
        secondFolder.softDelete();
    
        folderRepository.saveAllAndFlush(
                List.of(firstFolder, secondFolder)
        );
    
        // 3. Prepare two concurrent restoration requests.
    
        ExecutorService executor =
                Executors.newFixedThreadPool(2);
    
        CountDownLatch startSignal =
                new CountDownLatch(1);
    
        try {
    
            Future<RestoreFolderResponse> firstRequest =
                    executor.submit(() -> {
    
                        if (!startSignal.await(
                                5,
                                TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "First request timed out"
                            );
                        }
    
                        return folderService.restoreFolder(
                                ownerId,
                                firstFolder.getId()
                        );
                    });
    
            Future<RestoreFolderResponse> secondRequest =
                    executor.submit(() -> {
    
                        if (!startSignal.await(
                                5,
                                TimeUnit.SECONDS
                        )) {
                            throw new IllegalStateException(
                                    "Second request timed out"
                            );
                        }
    
                        return folderService.restoreFolder(
                                ownerId,
                                secondFolder.getId()
                        );
                    });
    
            // 4. Allow both requests to begin.
    
            startSignal.countDown();
    
            // 5. Wait for both restorations.
    
            RestoreFolderResponse firstResponse =
                    firstRequest.get(
                            15,
                            TimeUnit.SECONDS
                    );
    
            RestoreFolderResponse secondResponse =
                    secondRequest.get(
                            15,
                            TimeUnit.SECONDS
                    );
    
            // 6. Verify both original UUIDs are preserved.
    
            assertThat(firstResponse.folderId())
                    .isEqualTo(firstFolder.getId());
    
            assertThat(secondResponse.folderId())
                    .isEqualTo(secondFolder.getId());
    
            // 7. Verify the generated names are distinct.
    
            assertThat(
                    Set.of(
                            firstResponse.restoredName(),
                            secondResponse.restoredName()
                    )
            ).containsExactlyInAnyOrder(
                    "Documents",
                    "Documents (restored)"
            );
    
            // 8. Verify both folders were restored to the root.
    
            assertThat(firstResponse.parentFolderId())
                    .isNull();
    
            assertThat(secondResponse.parentFolderId())
                    .isNull();
    
            // 9. Verify the final persisted database state.
    
            List<Folder> restoredFolders =
                    folderRepository
                            .findByOwnerIdAndParentFolderIdIsNullAndDeletedAtIsNull(
                                    ownerId
                            );
    
            assertThat(restoredFolders)
                    .hasSize(2);
    
            assertThat(restoredFolders)
                    .extracting(Folder::getName)
                    .containsExactlyInAnyOrder(
                            "Documents",
                            "Documents (restored)"
                    );
    
        } finally {
    
            executor.shutdownNow();
    
            // Remove test folders before deleting their owner.
            folderRepository.deleteAllById(
                    List.of(
                            firstFolder.getId(),
                            secondFolder.getId()
                    )
            );
    
            folderRepository.flush();
    
            userRepository.deleteById(ownerId);
        }
    }
}