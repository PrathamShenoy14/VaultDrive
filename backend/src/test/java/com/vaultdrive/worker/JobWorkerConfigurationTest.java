package com.vaultdrive.worker;

import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.JobType;
import com.vaultdrive.job.ClaimedJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobWorkerConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JobWorkerConfiguration.class)
            .withBean(BackgroundJobService.class, () -> mock(BackgroundJobService.class));

    @Test
    void disabledByDefaultCreatesNoSchedulingResources() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JobWorker.class);
            verifyNoInteractions(context.getBean(BackgroundJobService.class));
        });
    }

    @Test
    void enabledWithoutHandlersNeverClaimsOrFakesSuccess() {
        runner.withPropertyValues("vaultdrive.jobs.worker.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            var worker = context.getBean(JobWorker.class);
            worker.poll();
            verifyNoInteractions(context.getBean(BackgroundJobService.class));
        });
    }

    @Test
    void rejectsUnsafeRenewalTimingAndUnboundedConcurrency() {
        runner.withPropertyValues("vaultdrive.jobs.worker.renewal-interval=11s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("vaultdrive.jobs.worker.concurrency=1000")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextCloseStopsAdmissionAndBoundsWaitingForUncooperativeHandler() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var handler = new JobHandler() {
            public JobType jobType() { return JobType.FILE_PURGE; }
            public Result handle(JobExecutionContext context) throws Exception {
                entered.countDown();
                while (true) {
                    try { if (release.await(5, TimeUnit.SECONDS)) { break; } }
                    catch (InterruptedException ignored) { interrupted.countDown(); }
                }
                return Result.SUCCEEDED;
            }
        };
        var jobs = mock(BackgroundJobService.class);
        var claim = new ClaimedJob(UUID.randomUUID(), JobType.FILE_PURGE, "FILE", UUID.randomUUID(),
                UUID.randomUUID(), Instant.now().plusSeconds(30), 1);
        when(jobs.claimNext(JobType.FILE_PURGE)).thenReturn(Optional.of(claim));
        when(jobs.renew(any())).thenReturn(true);
        try {
            new ApplicationContextRunner().withUserConfiguration(JobWorkerConfiguration.class)
                    .withBean(BackgroundJobService.class, () -> jobs)
                    .withPropertyValues("vaultdrive.jobs.worker.enabled=true", "vaultdrive.jobs.worker.concurrency=1",
                            "vaultdrive.jobs.worker.shutdown-grace=100ms")
                    .withBean(JobHandler.class, () -> handler)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                        var worker = context.getBean(JobWorker.class);
                        var closer = Executors.newSingleThreadExecutor();
                        try { closer.submit(context::close).get(2, TimeUnit.SECONDS); }
                        finally { closer.shutdownNow(); closer.awaitTermination(2, TimeUnit.SECONDS); }
                        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                        worker.poll();
                        verify(jobs, times(1)).claimNext(JobType.FILE_PURGE);
                        verify(jobs, never()).complete(any());
                    });
        } finally { release.countDown(); }
    }

    @Test
    void duplicateHandlerTypesAreRejectedBeforeStartingThreads() {
        var handler = mock(JobHandler.class);
        when(handler.jobType()).thenReturn(JobType.FILE_PURGE);
        var properties = new JobWorkerProperties(true, 1, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofMinutes(5), Duration.ofMillis(100));
        assertThatThrownBy(() -> new JobWorker(mock(BackgroundJobService.class), List.of(handler, handler), properties))
                .hasMessage("Duplicate job handler type");
    }
}
