package com.vaultdrive.job;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class BackgroundJobService {
    public static final int MAX_ATTEMPTS = 10;
    public static final Duration LEASE = Duration.ofSeconds(30);
    private static final int RECOVERY_SCAN_LIMIT = 20;

    private final EntityManager entityManager;

    public BackgroundJobService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Joins a caller transaction so future notification acknowledgement can follow commit. */
    @Transactional
    public UUID create(UUID eventId, JobType type, UUID aggregateId) {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(aggregateId);
        entityManager.createNativeQuery("""
                INSERT INTO background_jobs (id, job_type, aggregate_type, aggregate_id, max_attempts)
                VALUES (:id, :type, :aggregateType, :aggregateId, :maxAttempts)
                ON CONFLICT (id) DO NOTHING
                """).setParameter("id", eventId).setParameter("type", type.name())
                .setParameter("aggregateType", type.aggregateType()).setParameter("aggregateId", aggregateId)
                .setParameter("maxAttempts", MAX_ATTEMPTS).executeUpdate();
        BackgroundJob existing = entityManager.find(BackgroundJob.class, eventId);
        if (existing.getJobType() != type || !existing.getAggregateId().equals(aggregateId)) {
            throw new IllegalArgumentException("Job identity already belongs to different work");
        }
        return existing.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedJob> claimNext(JobType type) {
        Objects.requireNonNull(type);
        // Lock only job rows, ordered by due time/creation/id. Read protected state
        // under that lock. No hierarchy/resource locks or external I/O here.
        for (int i = 0; i < RECOVERY_SCAN_LIMIT; i++) {
            List<?> rows = entityManager.createNativeQuery("""
                    SELECT * FROM background_jobs
                    WHERE job_type = :type AND next_attempt_at <= clock_timestamp()
                      AND (status = 'PENDING' OR
                           (status = 'RUNNING' AND claim_expires_at <= clock_timestamp()))
                    ORDER BY next_attempt_at, created_at, id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, BackgroundJob.class).setParameter("type", type.name()).getResultList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            BackgroundJob job = (BackgroundJob) rows.getFirst();
            Instant now = databaseNow();
            if (job.getAttemptCount() >= job.getMaxAttempts()) {
                job.exhaust(now);
                entityManager.flush();
                continue;
            }
            job.claim(now, LEASE);
            entityManager.flush();
            return Optional.of(ClaimedJob.from(job));
        }
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(ClaimedJob claim) {
        if (ownedRow(claim).isEmpty()) {
            return false;
        }
        return entityManager.createNativeQuery("""
                UPDATE background_jobs SET status = 'SUCCEEDED', completed_at = clock_timestamp(),
                    updated_at = clock_timestamp(), claim_token = NULL, claim_expires_at = NULL,
                    last_failure_code = NULL
                WHERE id = :id AND status = 'RUNNING' AND claim_token = :token
                    AND claim_expires_at > clock_timestamp()
                """).setParameter("id", claim.id()).setParameter("token", claim.claimToken())
                .executeUpdate() == 1;
    }

    /** Records a controlled code only; never accepts exception messages or payloads. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retry(ClaimedJob claim) {
        // Read the persisted attempt under lock rather than trusting caller snapshot fields.
        Optional<BackgroundJob> owned = ownedRow(claim);
        if (owned.isEmpty()) {
            return false;
        }
        BackgroundJob job = owned.orElseThrow();
        Instant now = databaseNow();
        return entityManager.createNativeQuery("""
                UPDATE background_jobs
                SET status = CASE WHEN attempt_count >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    completed_at = CASE WHEN attempt_count >= max_attempts THEN clock_timestamp() ELSE NULL END,
                    updated_at = clock_timestamp(), next_attempt_at = :next,
                    claim_token = NULL, claim_expires_at = NULL, last_failure_code = 'WORK_FAILED'
                WHERE id = :id AND status = 'RUNNING' AND claim_token = :token
                    AND claim_expires_at > clock_timestamp()
                """).setParameter("id", claim.id()).setParameter("token", claim.claimToken())
                .setParameter("next", now.plus(backoff(job.getAttemptCount()))).executeUpdate() == 1;
    }

    private Optional<BackgroundJob> ownedRow(ClaimedJob claim) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT * FROM background_jobs WHERE id = :id FOR UPDATE
                """, BackgroundJob.class).setParameter("id", claim.id()).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BackgroundJob job = (BackgroundJob) rows.getFirst();
        Instant now = databaseNow();
        if (job.getStatus() != JobStatus.RUNNING || !Objects.equals(job.getClaimToken(), claim.claimToken())
                || !job.getClaimExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(job);
    }

    static Duration backoff(int attempt) {
        long seconds = 1;
        for (int i = 1; i < attempt && seconds < 60; i++) {
            seconds = Math.min(60, seconds * 2);
        }
        return Duration.ofSeconds(seconds);
    }

    private Instant databaseNow() {
        return (Instant) entityManager.createNativeQuery("SELECT clock_timestamp()", Instant.class)
                .getSingleResult();
    }
}
