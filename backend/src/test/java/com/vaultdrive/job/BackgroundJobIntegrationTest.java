package com.vaultdrive.job;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

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
    @Autowired private DataSource dataSource;
    private final List<UUID> ids = new ArrayList<>();

    @BeforeEach
    void createTestWorkLedger() {
        transaction().executeWithoutResult(status -> entityManager.createNativeQuery("""
                CREATE TABLE IF NOT EXISTS test_completed_units (
                    job_id UUID NOT NULL,
                    unit BIGINT NOT NULL, PRIMARY KEY(job_id, unit))
                """).executeUpdate());
    }

    @AfterEach
    void cleanup() {
        // No job FK: the simulated work must not lock a job before checkpoint fencing.
        transaction().executeWithoutResult(status -> ids.forEach(id -> entityManager.createNativeQuery(
                "DELETE FROM test_completed_units WHERE job_id = :id").setParameter("id", id).executeUpdate()));
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
        assertThat(row.getFailureCount()).isEqualTo(10);
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
        assertThat(load(id).getFailureCount()).isEqualTo(10);
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
            "attempt_count = -1", "max_attempts = 0", "failure_count = 11",
            "aggregate_type = 'FOLDER'", "last_failure_code = 'raw secret detail'"})
    void databaseGuardsRejectInvalidState(String assignment) {
        UUID id = job();
        assertThatThrownBy(() -> update(id, assignment)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void moreThanTenDurableContinuationsThenSuccessDoNotSpendFailureBudget() {
        UUID id = job();
        for (int unit = 0; unit < 15; unit++) {
            var claim = claimFor(id);
            checkpoint(claim, unit);
            assertThat(jobs.continueJob(claim)).isTrue();
            var row = load(id);
            assertThat(row.getStatus()).isEqualTo(JobStatus.PENDING);
            assertThat(row.getAttemptCount()).isEqualTo(unit + 1);
            assertThat(row.getFailureCount()).isZero();
            assertThat(row.getProgressVersion()).isEqualTo(unit + 1);
            assertThat(row.getNextAttemptAt()).isAfter(row.getUpdatedAt());
            assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
            assertThat(jobs.continueJob(claim)).isFalse();
            assertThat(jobs.complete(claim)).isFalse();
            due(id);
        }
        assertThat(jobs.complete(claimFor(id))).isTrue();
        assertThat(load(id).getAttemptCount()).isEqualTo(16);
        assertThat(load(id).getFailureCount()).isZero();
    }

    @Test
    void continuationsNeverResetFailuresAndMixedAbandonmentExhaustsAtTen() {
        UUID id = job();
        for (int failure = 1; failure <= 10; failure++) {
            var healthy = claimFor(id);
            checkpoint(healthy, failure - 1);
            assertThat(jobs.continueJob(healthy)).isTrue();
            assertThat(load(id).getFailureCount()).isEqualTo(failure - 1);
            due(id);
            var failing = claimFor(id);
            if (failure % 2 == 1) {
                assertThat(jobs.retry(failing)).isTrue();
                var row = load(id);
                assertThat(row.getFailureCount()).isEqualTo(failure);
                assertThat(Duration.between(row.getUpdatedAt(), row.getNextAttemptAt()).toMillis())
                        .isBetween(BackgroundJobService.backoff(failure).toMillis() - 100,
                                BackgroundJobService.backoff(failure).toMillis());
            } else {
                update(id, "claim_expires_at = clock_timestamp() - interval '1 second'");
                assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty(); // backoff still applies
            }
            due(id);
        }
        assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
        var row = load(id);
        assertThat(row.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(row.getAttemptCount()).isEqualTo(20);
        assertThat(row.getFailureCount()).isEqualTo(10);
        assertThat(row.getProgressVersion()).isEqualTo(10);
    }

    @Test
    void crashAfterCheckpointCommitRetainsProgressButChargesAbandonment() {
        UUID id = job();
        var old = claimFor(id);
        checkpoint(old, 0);
        update(id, "claim_expires_at = clock_timestamp() - interval '1 second'");
        assertThat(jobs.continueJob(old)).isFalse();
        due(id);
        var replacement = claimFor(id);
        assertThat(load(id).getProgressVersion()).isEqualTo(1);
        assertThat(load(id).getClaimProgressVersion()).isEqualTo(1);
        assertThat(load(id).getFailureCount()).isEqualTo(1);
        assertThat(jobs.continueJob(old)).isFalse();
        assertThatThrownBy(() -> checkpoint(old, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(unitCount(id)).isEqualTo(1);
        assertThat(load(id).getClaimToken()).isEqualTo(replacement.claimToken());
        // Prior claim's progress cannot authorize another empty yield.
        assertThat(jobs.continueJob(replacement)).isFalse();
        assertThat(load(id).getFailureCount()).isEqualTo(2);
        assertThat(load(id).getLastFailureCode()).isEqualTo("NO_PROGRESS");
        due(id);
        var resumed = claimFor(id);
        checkpoint(resumed, 1);
        assertThat(jobs.complete(resumed)).isTrue();
        assertThat(load(id).getProgressVersion()).isEqualTo(2);
        assertThat(load(id).getFailureCount()).isEqualTo(2);
    }

    @Test
    void noProgressContinuationUsesBoundedFailureBudgetAndDuplicateDoesNotChargeTwice() {
        UUID id = job();
        for (int failure = 1; failure <= 10; failure++) {
            var claim = claimFor(id);
            assertThat(jobs.continueJob(claim)).isFalse();
            assertThat(jobs.continueJob(claim)).isFalse();
            assertThat(load(id).getFailureCount()).isEqualTo(failure);
            due(id);
        }
        assertThat(load(id).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(load(id).getLastFailureCode()).isEqualTo("NO_PROGRESS");
        assertThat(jobs.claimNext(JobType.FILE_PURGE)).isEmpty();
    }

    @Test
    void checkpointRequiresTransactionAndRollsBackWithWorkAndRejectsReplay() {
        UUID id = job();
        var claim = claimFor(id);
        assertThatThrownBy(() -> jobs.checkpoint(claim, 0))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        transaction().executeWithoutResult(status -> {
            recordUnit(id, 0);
            jobs.checkpoint(claim, 0);
            status.setRollbackOnly();
        });
        assertThat(load(id).getProgressVersion()).isZero();
        assertThat(unitCount(id)).isZero();
        checkpoint(claim, 0);
        assertThatThrownBy(() -> checkpoint(claim, 0)).isInstanceOf(IllegalStateException.class);
        assertThat(load(id).getProgressVersion()).isEqualTo(1);
        assertThat(unitCount(id)).isEqualTo(1);
        assertThat(jobs.continueJob(claim)).isTrue();
        assertThatThrownBy(() -> update(id, "attempt_count = 0, last_attempt_at = NULL"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> update(id, "progress_version = 0"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void continuationAndCheckpointRecheckPostgresLeaseAfterJobLockWait(boolean recordingCheckpoint) throws Exception {
        UUID id = job();
        var claim = claimFor(id);
        checkpoint(claim, 0);
        update(id, "claim_expires_at = clock_timestamp() + interval '1 second'");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = executor.submit(() -> transaction().executeWithoutResult(status -> {
                entityManager.createNativeQuery("SELECT id FROM background_jobs WHERE id = :id FOR UPDATE")
                        .setParameter("id", id).getSingleResult();
                holding.countDown(); await(release);
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            var continuation = executor.submit(() -> {
                started.countDown();
                if (!recordingCheckpoint) { return jobs.continueJob(claim); }
                try { checkpoint(claim, 1); return true; }
                catch (IllegalStateException stale) { return false; }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> continuation.get(1500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); owner.get(5, TimeUnit.SECONDS);
            assertThat(continuation.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(load(id).getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(load(id).getFailureCount()).isZero();
            assertThat(load(id).getProgressVersion()).isEqualTo(1);
            assertThat(unitCount(id)).isEqualTo(1);
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void checkpoint(ClaimedJob claim, long version) {
        transaction().executeWithoutResult(status -> {
            recordUnit(claim.id(), version);
            jobs.checkpoint(claim, version);
        });
    }

    private void recordUnit(UUID id, long version) {
        entityManager.createNativeQuery("""
                INSERT INTO test_completed_units(job_id, unit) VALUES (:id, :unit)
                ON CONFLICT DO NOTHING
                """).setParameter("id", id).setParameter("unit", version).executeUpdate();
    }

    private long unitCount(UUID id) {
        return transaction().execute(status -> ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM test_completed_units WHERE job_id = :id")
                .setParameter("id", id).getSingleResult()).longValue());
    }

    @Test
    void v18BackfillPreservesClaimHistoryTerminalOutcomesAndRetryDates() throws Exception {
        String schema = "job_migration_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("17").load().migrate();
            try (var connection = dataSource.getConnection()) {
                String previous = connection.getSchema();
                try {
                    connection.setSchema(schema);
                    try (var insert = connection.prepareStatement("""
                            INSERT INTO background_jobs(id, job_type, aggregate_type, aggregate_id,
                                status, attempt_count, last_attempt_at, next_attempt_at,
                                claim_token, claim_expires_at, completed_at)
                            VALUES (?, 'FILE_PURGE', 'FILE', ?, ?, ?,
                                CASE WHEN ? > 0 THEN timestamptz '2026-01-01 00:00:00Z' END,
                                timestamptz '2026-01-02 00:00:00Z',
                                CASE WHEN ? = 'RUNNING' THEN ?::uuid END,
                                CASE WHEN ? = 'RUNNING' THEN timestamptz '2026-01-02 00:00:00Z' END,
                                CASE WHEN ? IN ('SUCCEEDED', 'FAILED') THEN timestamptz '2026-01-01 00:00:00Z' END)
                            """)) {
                        for (String state : List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED")) {
                            int claims = state.equals("PENDING") ? 3 : 10;
                            insert.setObject(1, UUID.randomUUID()); insert.setObject(2, UUID.randomUUID());
                            insert.setString(3, state); insert.setInt(4, claims); insert.setInt(5, claims);
                            insert.setString(6, state); insert.setString(7, UUID.randomUUID().toString());
                            insert.setString(8, state); insert.setString(9, state); insert.executeUpdate();
                        }
                    }
                } finally { connection.setSchema(previous); }
            }
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            try (var connection = dataSource.getConnection()) {
                String previous = connection.getSchema();
                try {
                    connection.setSchema(schema);
                    try (var query = connection.createStatement(); var rows = query.executeQuery("""
                            SELECT status, attempt_count, failure_count, progress_version, claim_progress_version,
                                next_attempt_at, claim_token, completed_at FROM background_jobs
                            """)) {
                        int found = 0;
                        while (rows.next()) {
                            found++;
                            String state = rows.getString(1);
                            assertThat(rows.getLong(2)).isEqualTo(state.equals("PENDING") ? 3 : 10);
                            assertThat(rows.getInt(3)).isEqualTo(switch (state) {
                                case "RUNNING", "SUCCEEDED" -> 9;
                                case "FAILED" -> 10;
                                default -> 3;
                            });
                            assertThat(rows.getLong(4)).isZero(); assertThat(rows.getLong(5)).isZero();
                            assertThat(rows.getTimestamp(6).toInstant()).isEqualTo(java.time.Instant.parse("2026-01-02T00:00:00Z"));
                            assertThat(rows.getObject(7) != null).isEqualTo(state.equals("RUNNING"));
                            assertThat(rows.getObject(8) != null).isEqualTo(state.equals("FAILED") || state.equals("SUCCEEDED"));
                        }
                        assertThat(found).isEqualTo(4);
                    }
                } finally { connection.setSchema(previous); }
            }
        } finally {
            // Only the unique disposable fixture schema, following the existing migration-test pattern.
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).cleanDisabled(false).load().clean();
        }
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
