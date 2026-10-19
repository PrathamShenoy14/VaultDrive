package com.vaultdrive.consumer;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.listener.FatalListenerExecutionException;
import tools.jackson.databind.ObjectMapper;

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
        NotificationDelivery.acceptAndAcknowledge(message, channel, initialBackoffMillis,
                () -> ingestor.accept(PurgeNotification.parse(message, mapper)),
                invalid -> ingestor.quarantine(NotificationDelivery.digest(message.getBody()), invalid), this::stopped);
    }

    private FatalListenerExecutionException stopped(String code) {
        log.warn("Purge notification consumer stopped: {}", code);
        return new FatalListenerExecutionException(code); // No original cause/body/credentials.
    }
}
