package com.vaultdrive.worker;

import com.vaultdrive.job.JobType;

/** Future handlers must validate business state, be idempotent and honor cancellation. */
public interface JobHandler {
    enum Result { SUCCEEDED, RETRY }

    JobType jobType();

    /** Invoked after claim commit, with no enclosing database transaction. */
    Result handle(JobExecutionContext context) throws Exception;
}
