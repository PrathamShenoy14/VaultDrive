package com.vaultdrive.outbox;

import com.rabbitmq.client.Address;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.Frame;
import com.rabbitmq.client.impl.nio.SocketChannelFrameHandler;
import com.rabbitmq.client.impl.nio.SocketChannelFrameHandlerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real client frame queue/socket, with a deterministically non-draining I/O loop. */
@SuppressWarnings("deprecation")
final class StalledNioSocket implements AutoCloseable {
    final OutboxRabbitIo io = new OutboxRabbitIo();
    final SocketChannelFrameHandler handler;
    private final CountDownLatch releaseIo = new CountDownLatch(1);
    private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    private final Socket peer;

    StalledNioSocket() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        io.configure(factory);
        CountDownLatch held = new CountDownLatch(1);
        factory.getNioParams().getNioExecutor().execute(() -> {
            held.countDown();
            try {
                releaseIo.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(held.await(2, TimeUnit.SECONDS)).isTrue();
        var frames = new SocketChannelFrameHandlerFactory(1000, factory.getNioParams(), false, null, 1024);
        handler = (SocketChannelFrameHandler) frames.create(
                new Address(server.getInetAddress().getHostAddress(), server.getLocalPort()), "localhost");
        peer = server.accept(); // No AMQP broker, credentials, or remote mutation.
        assertThat(handler.getState().getChannel().isBlocking()).isFalse();
        for (int i = 0; i < OutboxRabbitIo.FRAME_QUEUE_CAPACITY; i++) {
            write();
        }
    }

    void write() throws IOException {
        handler.writeFrame(new Frame(8, 0, new byte[0]));
    }

    void drain() {
        while (handler.getState().getWriteQueue().poll() != null) {
            // Simulate the socket becoming writable; leave the loop held for determinism.
        }
    }

    @Override
    public void close() throws Exception {
        handler.close(); // Directly closes the non-blocking SocketChannel even with a full queue.
        peer.close();
        server.close();
        releaseIo.countDown();
        io.close();
        assertThat(handler.getState().getChannel().isOpen()).isFalse();
    }
}
