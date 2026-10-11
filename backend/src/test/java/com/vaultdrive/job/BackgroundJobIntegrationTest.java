package com.vaultdrive.job;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=background_job_test",
        "spring.flyway.schemas=background_job_test",
        "spring.flyway.default-schema=background_job_test"
})
@ActiveProfiles("test")
class BackgroundJobIntegrationTest {
    @Autowired private BackgroundJobService jobs;
    @Autowired private BackgroundJobRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<UUID> ids = new ArrayList<>();

    @AfterEach
    void cleanup() {
        repository.deleteAllById(ids);
    }

    @Test
    void renewalExtendsLiveLeaseAndRecoveryDeadlineWithoutConsumingAttempt() {
        UUID id = job();
        var claim = claimFor(id);
        update(id, "claim_expires_at = clock_timestamp() + interval '2 seconds'");
        var before = load(id);
        assertThat(jobs.renew(claim)).isTrue();
        var after = load(id);
        assertThat(after.getClaimExpiresAt()).isAfter(before.getClaimExpiresAt());
        assertThat(after.getNextAttemptAt()).isEqualTo(after.getClaimExpiresAt().plusSeconds(1));
        assertThat(after.getClaimToken()).isEqualTo(claim.claimToken());
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(jobs.complete(claim)).isTrue();
        assertThat(jobs.renew(claim)).isFalse();
    }

    @Test
    void renewalNeverRevivesExpiredOrReplacedTokens() {
        UUID id = job();
        var old = claimFor(id);
        update(id, "claim_expires_at = clock_timestamp() - interval '1 second'");
        assertThat(jobs.renew(old)).isFalse();
        due(id);
        var replacement = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(jobs.renew(old)).isFalse();
        assertThat(jobs.renew(replacement)).isTrue();
        assertThat(load(id).getClaimToken()).isEqualTo(replacement.claimToken());
        assertThat(load(id).getAttemptCount()).isEqualTo(2);
    }

    @Test
    void renewalRechecksExpiryAfterWaitingForUnchangedRowLock() throws Exception {
        UUID id = job();
        var claim = claimFor(id);
        update(id, "claim_expires_at = clock_timestamp() + interval '1 second'");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch renewing = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = executor.submit(() -> transaction().executeWithoutResult(status -> {
                entityManager.createNativeQuery("SELECT id FROM background_jobs WHERE id = :id FOR UPDATE")
                        .setParameter("id", id).getSingleResult();
                holding.countDown();
                await(release);
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            var renewal = executor.submit(() -> { renewing.countDown(); return jobs.renew(claim); });
            assertThat(renewing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> renewal.get(1500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            owner.get(5, TimeUnit.SECONDS);
            assertThat(renewal.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(load(id).getClaimToken()).isEqualTo(claim.claimToken());
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ClaimedJob claimFor(UUID id) {
        var claim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(claim.id()).isEqualTo(id);
        return claim;
    }


    @Test
    void duplicateCreationPreservesProgressAndRejectsDifferentIdentity() {
        UUID id = job();
        UUID aggregate = load(id).getAggregateId();
        ClaimedJob claim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(jobs.create(id, JobType.FILE_PURGE, aggregate)).isEqualTo(id);
        assertThat(load(id).getClaimToken()).isEqualTo(claim.claimToken());
        assertThat(jobs.complete(claim)).isTrue();
        assertThat(jobs.create(id, JobType.FILE_PURGE, aggregate)).isEqualTo(id);
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(load(id).getAttemptCount()).isEqualTo(1);
        assertThatThrownBy(() -> jobs.create(id, JobType.FOLDER_PURGE, aggregate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.create(id, JobType.FILE_PURGE, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentDuplicateCreationWaitsForCommittedWinner() throws Exception {
        UUID id = track();
        UUID aggregate = UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch competing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transaction().execute(status -> {
                jobs.create(id, JobType.FILE_PURGE, aggregate);
                inserted.countDown();
                await(release);
                return id;
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                competing.countDown();
                return jobs.create(id, JobType.FILE_PURGE, aggregate);
            });
            assertThat(competing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(repository.findAllById(List.of(id))).hasSize(1);
            assertThat(load(id).getAttemptCount()).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void creationRollsBackAndUncommittedWorkCannotBeClaimed() {
        UUID id = track();
        transaction().executeWithoutResult(status -> {
            jobs.create(id, JobType.FILE_PURGE, UUID.randomUUID());
            assertThat(entityManager.find(BackgroundJob.class, id)).isNotNull();
            assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void concurrentWorkersHaveExactlyOneWinnerForOneJob() throws Exception {
        UUID id = job();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<java.util.Optional<ClaimedJob>> worker = () -> {
                ready.countDown();
                await(start);
                return jobs.claimNext(JobType.FILE_PURGE);
            };
            var first = executor.submit(worker);
            var second = executor.submit(worker);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().flatMap(java.util.Optional::stream).toList())
                    .hasSize(1).allSatisfy(claim -> assertThat(claim.id()).isEqualTo(id));
            assertThat(load(id).getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(load(id).getAttemptCount()).isEqualTo(1);
            transaction().executeWithoutResult(status -> entityManager.createNativeQuery(
                    "SELECT id FROM background_jobs WHERE id = :id FOR UPDATE NOWAIT")
                    .setParameter("id", id).getSingleResult());
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void skipsLockedJobAndClaimRollbackLeavesItAvailable() throws Exception {
        UUID locked = job();
        UUID available = job();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = executor.submit(() -> transaction().executeWithoutResult(status -> {
                BackgroundJob row = (BackgroundJob) entityManager.createNativeQuery(
                        "SELECT * FROM background_jobs WHERE id = :id FOR UPDATE", BackgroundJob.class)
                        .setParameter("id", locked).getSingleResult();
                row.claim(java.time.Instant.now(), BackgroundJobService.LEASE);
                entityManager.flush();
                holding.countDown();
                await(release);
                status.setRollbackOnly();
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> jobs.claimNext(JobType.FILE_PURGE).orElseThrow().id())
                    .get(5, TimeUnit.SECONDS)).isEqualTo(available);
            release.countDown();
            owner.get(5, TimeUnit.SECONDS);
            assertThat(load(locked).getAttemptCount()).isZero();
            assertThat(jobs.claimNext(JobType.FILE_PURGE).orElseThrow().id()).isEqualTo(locked);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiryFencesAllOutcomesBeforeAndAfterRecoveryAndHonorsBackoff() {
        UUID id = job();
        ClaimedJob old = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
        update(id, "claim_expires_at = clock_timestamp() - interval '1 second'");
        assertThat(jobs.complete(old)).isFalse();
        assertThat(jobs.retry(old)).isFalse();
        assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
        due(id);
        ClaimedJob replacement = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        assertThat(replacement.id()).isEqualTo(id);
        assertThat(replacement.claimToken()).isNotEqualTo(old.claimToken());
        assertThat(replacement.attemptCount()).isEqualTo(2);
        assertThat(load(id).getLastFailureCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(jobs.complete(old)).isFalse();
        assertThat(jobs.retry(old)).isFalse();
        assertThat(load(id).getClaimToken()).isEqualTo(replacement.claimToken());
        assertThat(jobs.complete(replacement)).isTrue();
        assertThat(jobs.complete(replacement)).isFalse();
        assertThat(jobs.retry(replacement)).isFalse();
        assertThat(load(id).getCompletedAt()).isNotNull();
        assertThat(load(id).getClaimToken()).isNull();
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void completionRechecksExpiryAfterWaitingForRowLock() throws Exception {
        UUID id = job();
        var claim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
        update(id, "claim_expires_at = clock_timestamp() + interval '1 second'");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch completeStarted = new CountDownLatch(1);
        CountDownLatch expire = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = executor.submit(() -> transaction().executeWithoutResult(status -> {
                entityManager.createNativeQuery("SELECT id FROM background_jobs WHERE id = :id FOR UPDATE")
                        .setParameter("id", id).getSingleResult();
                holding.countDown();
                await(expire);
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            var completion = executor.submit(() -> {
                completeStarted.countDown();
                return jobs.complete(claim);
            });
            assertThat(completeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // Hold the unchanged row past its real database-clock lease deadline.
            // A WHERE predicate checked before this lock wait must not authorize completion.
            assertThatThrownBy(() -> completion.get(1500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            expire.countDown();
            owner.get(5, TimeUnit.SECONDS);
            assertThat(completion.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(load(id).getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(load(id).getCompletedAt()).isNull();
        } finally {
            expire.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void retriesBackOffAndExhaustDurablyUsingPersistedAttempts() {
        UUID id = job();
        for (int attempt = 1; attempt <= BackgroundJobService.MAX_ATTEMPTS; attempt++) {
            ClaimedJob claim = jobs.claimNext(JobType.FILE_PURGE).orElseThrow();
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            // Forged snapshot metadata cannot reset the budget or alter the backoff.
            var altered = new ClaimedJob(claim.id(), claim.jobType(), claim.aggregateType(),
                    claim.aggregateId(), claim.claimToken(), claim.claimExpiresAt(), 0);
            assertThat(jobs.retry(altered)).isTrue();
            BackgroundJob row = load(id);
            assertThat(Duration.between(row.getUpdatedAt(), row.getNextAttemptAt()).toMillis())
                    .isBetween(BackgroundJobService.backoff(attempt).toMillis() - 100,
                            BackgroundJobService.backoff(attempt).toMillis());
            assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
            if (attempt < BackgroundJobService.MAX_ATTEMPTS) {
                assertThat(row.getStatus()).isEqualTo(JobStatus.PENDING);
                due(id);
            }
        }
        BackgroundJob row = load(id);
        assertThat(row.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(row.getLastFailureCode()).isEqualTo("WORK_FAILED");
        assertThat(row.getCompletedAt()).isNotNull();
        assertThat(row.getClaimToken()).isNull();
        assertThat(jobs.create(id, row.getJobType(), row.getAggregateId())).isEqualTo(id);
        assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
    }

    @Test
    void abandonedFinalAttemptIsRecoveredToFailed() {
        UUID id = job();
        for (int attempt = 1; attempt <= BackgroundJobService.MAX_ATTEMPTS; attempt++) {
            assertThat(jobs.claimNext(JobType.FILE_PURGE).orElseThrow().attemptCount()).isEqualTo(attempt);
            update(id, "claim_expires_at = clock_timestamp() - interval '1 second', "
                    + "next_attempt_at = clock_timestamp() - interval '1 second'");
        }
        assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(load(id).getLastFailureCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(load(id).getCompletedAt()).isNotNull();
    }

    @Test
    void workerTypeFilterAndAggregateReferenceSurviveWithoutBusinessRows() {
        UUID id = track();
        jobs.create(id, JobType.FOLDER_PURGE, UUID.randomUUID());
        assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
        var claim = jobs.claimNext(JobType.FOLDER_PURGE).orElseThrow();
        assertThat(claim.aggregateType()).isEqualTo("FOLDER");
        assertThat(jobs.complete(claim)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status = 'RUNNING'", "status = 'SUCCEEDED'", "status = 'UNKNOWN'",
            "claim_token = '00000000-0000-0000-0000-000000000001'::uuid",
            "attempt_count = -1", "max_attempts = 0", "attempt_count = 11",
            "aggregate_type = 'FOLDER'", "last_failure_code = 'raw secret detail'"})
    void databaseGuardsRejectInvalidState(String assignment) {
        UUID id = job();
        assertThatThrownBy(() -> update(id, assignment)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.PENDING);
    }

    private UUID track() {
        UUID id = UUID.randomUUID();
        ids.add(id);
        return id;
    }

    private UUID job() {
        UUID id = track();
        jobs.create(id, JobType.FILE_PURGE, UUID.randomUUID());
        return id;
    }

    private BackgroundJob load(UUID id) { return repository.findById(id).orElseThrow(); }
    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }
    private void due(UUID id) { update(id, "next_attempt_at = clock_timestamp() - interval '1 second'"); }
    private void update(UUID id, String assignment) {
        transaction().executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE background_jobs SET " + assignment + " WHERE id = :id")
                .setParameter("id", id).executeUpdate());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Test coordination timeout"); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted", e);
        }
    }
}
