package com.vaultdrive.job;

import java.time.Instant;
import java.util.UUID;

/** Immutable work snapshot; the token fences database outcomes, not external I/O. */
public record ClaimedJob(UUID id, JobType jobType, String aggregateType,
                         UUID aggregateId, UUID claimToken, Instant claimExpiresAt,
                         long attemptCount) {
    static ClaimedJob from(BackgroundJob job) {
        return new ClaimedJob(job.getId(), job.getJobType(), job.getAggregateType(),
                job.getAggregateId(), job.getClaimToken(), job.getClaimExpiresAt(), job.getAttemptCount());
    }
}
