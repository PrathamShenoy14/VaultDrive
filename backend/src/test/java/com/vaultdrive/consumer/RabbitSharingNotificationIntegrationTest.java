package com.vaultdrive.consumer;

import com.rabbitmq.client.Channel;
import com.vaultdrive.outbox.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=sharing_m6_rabbit_test",
        "spring.flyway.schemas=sharing_m6_rabbit_test", "spring.flyway.default-schema=sharing_m6_rabbit_test"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_VHOST", matches = "vaultdrive_outbox_test")
class RabbitSharingNotificationIntegrationTest extends SharingNotificationFixtures {
    @Autowired OutboxClaimService claims;
    CachingConnectionFactory factory;
    RabbitTemplate template;
    RabbitAdmin admin;
    OutboxPublisherProperties properties;
    List<SimpleMessageListenerContainer> containers = new ArrayList<>();
    List<String> hashes = new ArrayList<>();

    @BeforeEach void connect() {
        if (System.getenv("RABBITMQ_USERNAME") == null || System.getenv("RABBITMQ_PASSWORD") == null) {
            throw new IllegalStateException("Private RabbitMQ test credentials required");
        }
        factory = new CachingConnectionFactory("127.0.0.1", 5672);
        factory.setUsername(System.getenv("RABBITMQ_USERNAME")); factory.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        factory.setVirtualHost("vaultdrive_outbox_test"); factory.setConnectionTimeout(3000); factory.setCloseTimeout(1000);
        factory.getRabbitConnectionFactory().setChannelRpcTimeout(5000);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); factory.setPublisherReturns(true);
        template = new RabbitTemplate(factory); admin = new RabbitAdmin(factory); admin.setAutoStartup(false);
        String suffix = UUID.randomUUID().toString();
        properties = new OutboxPublisherProperties(false, 3, 20, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(60), "vaultdrive.outbox.events.test." + suffix,
                "vaultdrive.purge.requests.test." + suffix, "purge.requested",
                "vaultdrive.permission.activity.test." + suffix, "permission.activity");
        admin.declareExchange(new DirectExchange(properties.exchange(), true, false));
        for (String queue : List.of(properties.queue(), properties.permissionQueue())) {
            admin.declareQueue(QueueBuilder.durable(queue).quorum().build());
            admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, properties.exchange(),
                    queue.equals(properties.queue()) ? properties.routingKey() : properties.permissionRoutingKey(), null));
        }
    }

    @AfterEach void disconnect() {
        containers.forEach(SimpleMessageListenerContainer::destroy);
        hashes.forEach(hash -> jdbc.update("DELETE FROM notification_quarantine WHERE body_sha256 = ?", hash));
        if (factory != null) {
            try {
                admin.deleteQueue(properties.queue()); admin.deleteQueue(properties.permissionQueue()); admin.deleteExchange(properties.exchange());
            } finally { factory.destroy(); }
        }
    }

    @Test void realPublisherAndConcurrentConsumersCommitBeforeAckAndDeduplicate() throws Exception {
        grant(); var e = event(events().getFirst()); var acked = new CountDownLatch(3);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        PermissionEventListener listener = new PermissionEventListener(ingestor, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) {
                try {
                    super.onMessage(message, channel);
                    assertThat(page(recipient).totalElements()).isEqualTo(1);
                } catch (Throwable error) { failure.set(error); throw error; }
                finally { acked.countDown(); }
            }
        };
        start(listener); start(listener);
        assertThat(new OutboxPublisher(claims, new RabbitOutboxTransport(template, admin, mapper, properties)).publishNext()).isTrue();
        send(message(e)); send(message(e));
        assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue(); assertThat(failure.get()).isNull();
        assertThat(page(recipient).content()).hasSize(1);
        assertThat(template.receive(properties.queue(), 100)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE id = ?", Long.class, e.id())).isZero();
    }

    @Test void commitBeforeAckCrashRedeliversWithoutResettingReadState() throws Exception {
        grant(); var e = event(events().getFirst()); var committed = new CountDownLatch(1);
        var first = start(new PermissionEventListener(ingestor, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) {
                ingestor.accept(PermissionEvent.parse(message, mapper)); committed.countDown(); // deliberately no ACK
            }
        });
        send(message(e)); assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
        UUID id = page(recipient).content().getFirst().id(); notifications.markRead(recipient, id);
        first.destroy(); factory.resetConnection();
        var acked = new CountDownLatch(1); var redelivered = new AtomicBoolean();
        start(new PermissionEventListener(ingestor, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) {
                redelivered.set(message.getMessageProperties().isRedelivered()); super.onMessage(message, channel); acked.countDown();
            }
        });
        assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue(); assertThat(redelivered).isTrue();
        assertThat(page(recipient).content()).hasSize(1); assertThat(notifications.unreadCount(recipient).unreadCount()).isZero();
    }

    @Test void boundedDatabaseFailuresStopUnackedThenExplicitRestartRecovers() throws Exception {
        grant(); var e = event(events().getFirst());
        var unavailable = mock(PermissionEventIngestor.class);
        doThrow(new DataAccessResourceFailureException("private failure")).when(unavailable).accept(any());
        var stopped = new CountDownLatch(1);
        var failed = build(new PermissionEventListener(unavailable, mapper, 1));
        failed.setApplicationEventPublisher(a -> {
            if (a instanceof ListenerContainerConsumerFailedEvent event && event.isFatal()) stopped.countDown();
        });
        failed.start(); send(message(e));
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue(); failed.destroy(); factory.resetConnection();
        verify(unavailable, times(3)).accept(any()); assertThat(page(recipient).content()).isEmpty();
        // The earlier grant and activity remain committed despite delivery failure.
        assertThat(reads.file(recipient, file)).isNotNull(); assertThat(events()).hasSize(1);
        var acked = new CountDownLatch(1);
        start(new PermissionEventListener(ingestor, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) {
                assertThat(message.getMessageProperties().isRedelivered()).isTrue();
                super.onMessage(message, channel); acked.countDown();
            }
        });
        assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue(); assertThat(page(recipient).content()).hasSize(1);
    }

    @Test void transientFailuresRetryThenCommitAndPoisonMessagesQuarantineBeforeAck() throws Exception {
        grant(); var e = event(events().getFirst());
        var retrying = mock(PermissionEventIngestor.class); var attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() < 3) throw new DataAccessResourceFailureException("private failure");
            ingestor.accept(call.getArgument(0)); return null;
        }).when(retrying).accept(any());
        doAnswer(call -> { ingestor.quarantine(call.getArgument(0), call.getArgument(1)); return null; })
                .when(retrying).quarantine(anyString(), any());
        var acked = new CountDownLatch(3);
        start(new PermissionEventListener(retrying, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) { super.onMessage(message, channel); acked.countDown(); }
        });
        send(message(e));
        Message poison = new Message(("private poison " + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties());
        var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(message(e).getBody()); json.put("payloadVersion", 99);
        Message unsupported = new Message(mapper.writeValueAsBytes(json), message(e).getMessageProperties());
        for (Message invalid : List.of(poison, unsupported)) { hashes.add(PermissionEventListener.digest(invalid.getBody())); send(invalid); }
        assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue(); assertThat(attempts).hasValue(3);
        assertThat(page(recipient).content()).hasSize(1);
        for (String hash : hashes) assertThat(jdbc.queryForObject("SELECT reason_code FROM notification_quarantine WHERE body_sha256 = ?", String.class, hash))
                .isIn("MALFORMED", "UNSUPPORTED_VERSION");
        containers.getFirst().stop(); assertThat(template.receive(properties.permissionQueue(), 100)).isNull();
    }

    private SimpleMessageListenerContainer build(PermissionEventListener listener) {
        var c = PermissionConsumerConfiguration.container(factory, properties.permissionQueue(), listener);
        c.setAutoStartup(false); c.afterPropertiesSet(); containers.add(c); return c;
    }
    private SimpleMessageListenerContainer start(PermissionEventListener listener) { var c = build(listener); c.start(); return c; }
    private void send(Message message) { template.send(properties.exchange(), properties.permissionRoutingKey(), message); }
}
