package com.vaultdrive.worker;

import com.vaultdrive.job.JobType;

/** Future handlers must validate business state, be idempotent and honor cancellation. */
public interface JobHandler {
    enum Result { SUCCEEDED, RETRY, CONTINUE }

    JobType jobType();

    /**
     * Invoked after claim commit, with no enclosing database transaction.
     * CONTINUE requires useful work and BackgroundJobService.checkpoint to have
     * committed together during this claim. Check cancellation between bounded
     * units and yield before the runtime budget, not after cancellation.
     */
    Result handle(JobExecutionContext context) throws Exception;
}
