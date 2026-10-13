package com.vaultdrive.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

@Entity
@Table(name = "background_jobs")
public class BackgroundJob {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false, length = 32)
    private JobType jobType;
    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 32)
    private String aggregateType;
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JobStatus status;
    @Column(name = "attempt_count", nullable = false)
    private long attemptCount;
    @Column(name = "failure_count", nullable = false)
    private int failureCount;
    @Column(name = "progress_version", nullable = false)
    private long progressVersion;
    @Column(name = "claim_progress_version", nullable = false)
    private long claimProgressVersion;
    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "claim_token")
    private UUID claimToken;
    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;
    @Column(name = "last_failure_code", length = 48)
    private String lastFailureCode;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    protected BackgroundJob() {}

    public UUID getId() { return id; }

    public JobType getJobType() { return jobType; }

    public String getAggregateType() { return aggregateType; }

    public UUID getAggregateId() { return aggregateId; }

    public JobStatus getStatus() { return status; }

    public long getAttemptCount() { return attemptCount; }

    public int getFailureCount() { return failureCount; }

    public long getProgressVersion() { return progressVersion; }

    public long getClaimProgressVersion() { return claimProgressVersion; }

    public int getMaxAttempts() { return maxAttempts; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }

    public UUID getClaimToken() { return claimToken; }

    public Instant getClaimExpiresAt() { return claimExpiresAt; }

    public Instant getLastAttemptAt() { return lastAttemptAt; }

    public String getLastFailureCode() { return lastFailureCode; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getCompletedAt() { return completedAt; }

    void claim(Instant now, Duration lease) {
        status = JobStatus.RUNNING;
        claimToken = UUID.randomUUID();
        claimExpiresAt = now.plus(lease);
        attemptCount++;
        claimProgressVersion = progressVersion;
        lastAttemptAt = now;
        updatedAt = now;
        nextAttemptAt = claimExpiresAt.plus(BackgroundJobService.backoff(failureCount + 1));
    }

    void abandon() {
        failureCount++;
        lastFailureCode = "LEASE_EXPIRED";
    }

    void exhaust(Instant now) {
        status = JobStatus.FAILED;
        lastFailureCode = "LEASE_EXPIRED";
        claimToken = null;
        claimExpiresAt = null;
        completedAt = now;
        updatedAt = now;
    }
}
