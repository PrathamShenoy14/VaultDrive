package com.vaultdrive.hierarchy;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class HierarchyCoordinatorIntegrationTest {

    @Autowired
    private HierarchyCoordinator hierarchyCoordinator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void sharedLocksForSameOwnerAreCompatible() throws Exception {
        assertCompatible(
                hierarchyCoordinator::acquireShared,
                hierarchyCoordinator::acquireShared
        );
    }

    @Test
    void exclusiveLockWaitsForSharedLockOnSameOwner()
            throws Exception {
        assertBlockedUntilFirstTransactionCompletes(
                hierarchyCoordinator::acquireShared,
                hierarchyCoordinator::acquireExclusive
        );
    }

    @Test
    void exclusiveLockWaitsForExclusiveLockOnSameOwner()
            throws Exception {
        assertBlockedUntilFirstTransactionCompletes(
                hierarchyCoordinator::acquireExclusive,
                hierarchyCoordinator::acquireExclusive
        );
    }

    @Test
    void transactionRollbackReleasesExclusiveLock() throws Exception {
        UUID ownerId = UUID.randomUUID();
        TransactionTemplate transactionTemplate = transactionTemplate();

        transactionTemplate.executeWithoutResult(status -> {
            hierarchyCoordinator.acquireExclusive(ownerId);
            status.setRollbackOnly();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> nextTransaction = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status ->
                            hierarchyCoordinator.acquireExclusive(ownerId)
                    )
            );

            nextTransaction.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsLockAcquisitionOutsideTransaction() {
        assertThatThrownBy(() ->
                hierarchyCoordinator.acquireShared(UUID.randomUUID())
        ).isInstanceOf(IllegalStateException.class)
         .hasMessage(
                 "Hierarchy coordination requires an active transaction"
         );
    }

    private void assertCompatible(
            Consumer<UUID> firstLock,
            Consumer<UUID> secondLock
    ) throws Exception {
        UUID ownerId = UUID.randomUUID();
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstTransaction = executor.submit(() ->
                    inTransaction(() -> {
                        firstLock.accept(ownerId);
                        firstAcquired.countDown();
                        await(releaseFirst);
                    })
            );

            assertThat(firstAcquired.await(5, TimeUnit.SECONDS))
                    .isTrue();

            Future<?> secondTransaction = executor.submit(() ->
                    inTransaction(() -> {
                        secondLock.accept(ownerId);
                        secondAcquired.countDown();
                    })
            );

            assertThat(secondAcquired.await(5, TimeUnit.SECONDS))
                    .isTrue();

            releaseFirst.countDown();
            firstTransaction.get(5, TimeUnit.SECONDS);
            secondTransaction.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private void assertBlockedUntilFirstTransactionCompletes(
            Consumer<UUID> firstLock,
            Consumer<UUID> secondLock
    ) throws Exception {
        UUID ownerId = UUID.randomUUID();
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstTransaction = executor.submit(() ->
                    inTransaction(() -> {
                        firstLock.accept(ownerId);
                        firstAcquired.countDown();
                        await(releaseFirst);
                    })
            );

            assertThat(firstAcquired.await(5, TimeUnit.SECONDS))
                    .isTrue();

            Future<?> secondTransaction = executor.submit(() ->
                    inTransaction(() -> {
                        secondAttempted.countDown();
                        secondLock.accept(ownerId);
                        secondAcquired.countDown();
                    })
            );

            assertThat(secondAttempted.await(5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(secondAcquired.await(500, TimeUnit.MILLISECONDS))
                    .isFalse();

            releaseFirst.countDown();

            assertThat(secondAcquired.await(5, TimeUnit.SECONDS))
                    .isTrue();
            firstTransaction.get(5, TimeUnit.SECONDS);
            secondTransaction.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private void inTransaction(Runnable action) {
        transactionTemplate().executeWithoutResult(status -> action.run());
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
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
}
