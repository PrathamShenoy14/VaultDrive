package com.vaultdrive.outbox;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.nio.NioParams;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded client-side I/O resources; no publication task is delegated to an executor. */
@SuppressWarnings("deprecation") // Existing client transport; avoids adding Netty for this bounded fix.
final class OutboxRabbitIo implements AutoCloseable {
    static final int ENQUEUE_TIMEOUT_MS = 1000;
    static final int FRAME_QUEUE_CAPACITY = 128;
    static final int CLOSE_TIMEOUT_MS = 1000;

    private final ThreadPoolExecutor io = executor("outbox-rabbit-io", 1);
    private final ThreadPoolExecutor shutdown = executor("outbox-rabbit-close", 16);

    void configure(ConnectionFactory factory) {
        factory.setNioParams(new NioParams()
                .setNbIoThreads(1)
                .setWriteQueueCapacity(FRAME_QUEUE_CAPACITY)
                .setWriteEnqueuingTimeoutInMs(ENQUEUE_TIMEOUT_MS)
                .setNioExecutor(io)
                .setConnectionShutdownExecutor(shutdown));
        factory.useNio();
    }

    private static ThreadPoolExecutor executor(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void close() {
        // Spring destroys the dependent connection factory first. Never wait
        // indefinitely for a broker handshake or drain queued shutdown work.
        io.shutdownNow();
        shutdown.shutdownNow();
        try {
            io.awaitTermination(1, TimeUnit.SECONDS);
            shutdown.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
