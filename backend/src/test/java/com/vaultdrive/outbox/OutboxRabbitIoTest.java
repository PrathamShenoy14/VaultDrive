package com.vaultdrive.outbox;

import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

@SuppressWarnings("deprecation")
@Timeout(10)
class OutboxRabbitIoTest {
    @Test
    void saturatedClientFrameQueueTimesOutAndNextWriteCanProceed() throws Exception {
        try (var stalled = new StalledNioSocket()) {
            long start = System.nanoTime();
            assertThatThrownBy(stalled::write).isInstanceOf(IOException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(stalled.handler.getState().getWriteQueue().size())
                    .isEqualTo(OutboxRabbitIo.FRAME_QUEUE_CAPACITY);
            stalled.drain();
            stalled.write();
            assertThat(stalled.handler.getState().getWriteQueue().size()).isEqualTo(1);
        }
    }

    @Test
    void fullFrameQueueCannotPreventSocketCloseOrResourceShutdown() throws Exception {
        var stalled = new StalledNioSocket();
        long start = System.nanoTime();
        stalled.close();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void schedulerShutdownReleasesAnInFlightEnqueueAndStopsFurtherPolling() throws Exception {
        try (var stalled = new StalledNioSocket()) {
            var context = new AnnotationConfigApplicationContext();
            context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class,
                    () -> new OutboxPublisherConfiguration.RuntimeConfiguration().taskScheduler());
            context.refresh();
            var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exited = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            try {
                scheduler.scheduleWithFixedDelay(() -> {
                    entered.countDown();
                    try {
                        stalled.write();
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    } finally {
                        interrupted.set(Thread.currentThread().isInterrupted());
                        exited.countDown();
                    }
                }, Duration.ofSeconds(5));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime();
                context.close(); // Exercise ContextClosedEvent / SmartLifecycle, not just shutdown().
                assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
                assertThat(interrupted).isTrue();
                assertThat(scheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void ioAndShutdownExecutorsHaveFiniteThreadsAndQueuesAndTerminate() throws Exception {
        var factory = new ConnectionFactory();
        try (var io = new OutboxRabbitIo()) {
            io.configure(factory);
            var params = factory.getNioParams();
            assertThat(params.getNbIoThreads()).isEqualTo(1);
            assertThat(params.getWriteEnqueuingTimeoutInMs()).isEqualTo(1000);
            assertThat(params.getWriteQueueCapacity()).isEqualTo(128);
            for (var executor : new ThreadPoolExecutor[]{(ThreadPoolExecutor) params.getNioExecutor(),
                    (ThreadPoolExecutor) params.getConnectionShutdownExecutor()}) {
                assertThat(executor.getMaximumPoolSize()).isEqualTo(1);
                assertThat(executor.getQueue().remainingCapacity()).isBetween(1, 16);
            }
        }
        assertThat(factory.getNioParams().getNioExecutor().isTerminated()).isTrue();
        assertThat(factory.getNioParams().getConnectionShutdownExecutor().isTerminated()).isTrue();
    }
}
