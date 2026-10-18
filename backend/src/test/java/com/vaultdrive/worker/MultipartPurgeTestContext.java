package com.vaultdrive.worker;

import com.vaultdrive.job.ClaimedJob;
import java.time.Duration;

/** Real bounded execution context for multipart tests outside the worker package. */
public final class MultipartPurgeTestContext {
    private MultipartPurgeTestContext() { }
    public static JobExecutionContext context(ClaimedJob claim) {
        return new JobExecutionContext(claim, Duration.ofMinutes(5).toNanos(), System::nanoTime);
    }
}
