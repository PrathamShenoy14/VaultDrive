package com.vaultdrive.consumer;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.listener.FatalListenerExecutionException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class PurgeNotificationListener implements ChannelAwareMessageListener {
    private static final Logger log = LoggerFactory.getLogger(PurgeNotificationListener.class);
    private final NotificationIngestor ingestor;
    private final ObjectMapper mapper;
    private final long initialBackoffMillis;

    public PurgeNotificationListener(NotificationIngestor ingestor, ObjectMapper mapper, long initialBackoffMillis) {
        if (initialBackoffMillis < 1 || initialBackoffMillis > 60000) {
            throw new IllegalArgumentException("Consumer backoff must be between 1ms and 60s");
        }
        this.ingestor = ingestor;
        this.mapper = mapper;
        this.initialBackoffMillis = initialBackoffMillis;
    }

    @Override
    public void onMessage(Message message, Channel channel) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw stopped("OUTER_TRANSACTION");
        }
        boolean committed = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                try {
                    ingestor.accept(PurgeNotification.parse(message, mapper));
                } catch (InvalidNotification invalid) {
                    ingestor.quarantine(digest(message.getBody()), invalid);
                }
                committed = true; // Transactional proxy returned only after commit.
                break;
            } catch (DataAccessException | TransactionException exception) {
                if (attempt == 3) { throw stopped("DATABASE_UNAVAILABLE"); }
                try {
                    Thread.sleep(initialBackoffMillis * attempt); // Outside every DB transaction.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw stopped("INTERRUPTED");
                }
            } catch (RuntimeException exception) {
                throw stopped("UNEXPECTED_FAILURE");
            }
        }
        if (committed) {
            try {
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            } catch (IOException | RuntimeException exception) {
                // Commit may have succeeded; leave delivery for idempotent replay.
                throw stopped("ACK_UNCERTAIN");
            }
        }
    }

    static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required");
        }
    }

    private FatalListenerExecutionException stopped(String code) {
        log.warn("Purge notification consumer stopped: {}", code);
        return new FatalListenerExecutionException(code); // No original cause/body/credentials.
    }
}
