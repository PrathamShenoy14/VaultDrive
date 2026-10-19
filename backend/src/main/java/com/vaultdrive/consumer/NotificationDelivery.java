package com.vaultdrive.consumer;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.listener.FatalListenerExecutionException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared transport policy. Ingestors retain their separate transactional business contracts. */
final class NotificationDelivery {
    private NotificationDelivery() {}

    static void acceptAndAcknowledge(Message message, Channel channel, long backoff,
            Runnable accept, Consumer<InvalidNotification> quarantine,
            Function<String, FatalListenerExecutionException> stopped) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw stopped.apply("OUTER_TRANSACTION");
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                try { accept.run(); }
                catch (InvalidNotification invalid) { quarantine.accept(invalid); }
                break; // Transactional proxy returned only after commit.
            } catch (DataAccessException | TransactionException failure) {
                if (attempt == 3) throw stopped.apply("DATABASE_UNAVAILABLE");
                try { Thread.sleep(backoff * attempt); } // Outside every database transaction.
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw stopped.apply("INTERRUPTED");
                }
            } catch (RuntimeException failure) { throw stopped.apply("UNEXPECTED_FAILURE"); }
        }
        try { channel.basicAck(message.getMessageProperties().getDeliveryTag(), false); }
        catch (IOException | RuntimeException uncertain) {
            // Committed work may be redelivered; deduplication belongs to its durable ingestor.
            throw stopped.apply("ACK_UNCERTAIN");
        }
    }

    static String digest(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required"); }
    }
}
