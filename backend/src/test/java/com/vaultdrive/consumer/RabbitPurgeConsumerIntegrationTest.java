package com.vaultdrive.consumer;

import com.rabbitmq.client.Channel;
import com.vaultdrive.job.BackgroundJobRepository;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.JobStatus;
import com.vaultdrive.outbox.OutboxClaimService;
import com.vaultdrive.outbox.OutboxEvent;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.outbox.OutboxPublisher;
import com.vaultdrive.outbox.OutboxPublisherProperties;
import com.vaultdrive.outbox.OutboxWriter;
import com.vaultdrive.outbox.RabbitOutboxTransport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=notification_consumer_test",
        "spring.flyway.schemas=notification_consumer_test", "spring.flyway.default-schema=notification_consumer_test"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_VHOST", matches = "vaultdrive_outbox_test")
class RabbitPurgeConsumerIntegrationTest {
    @Autowired private NotificationIngestor ingestor;
    @MockitoSpyBean private BackgroundJobService jobs;
    @Autowired private BackgroundJobRepository jobRepository;
    @Autowired private OutboxWriter writer;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private OutboxClaimService claims;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper mapper;
    private CachingConnectionFactory factory;
    private RabbitTemplate template;
    private RabbitAdmin admin;
    private OutboxPublisherProperties properties;
    private final List<SimpleMessageListenerContainer> containers = new ArrayList<>();
    private final List<UUID> ids = new ArrayList<>();
    private final List<String> hashes = new ArrayList<>();

    @BeforeEach
    void connectOnlyToExistingIsolatedVhost() {
        if (System.getenv("RABBITMQ_USERNAME") == null || System.getenv("RABBITMQ_PASSWORD") == null) {
            throw new IllegalStateException("Private RabbitMQ test credentials required");
        }
        factory = new CachingConnectionFactory("127.0.0.1", 5672);
        factory.setUsername(System.getenv("RABBITMQ_USERNAME"));
        factory.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        factory.setVirtualHost("vaultdrive_outbox_test");
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        factory.setConnectionTimeout(3000);
        factory.setCloseTimeout(1000);
        factory.getRabbitConnectionFactory().setChannelRpcTimeout(5000);
        template = new RabbitTemplate(factory);
        admin = new RabbitAdmin(factory);
        admin.setAutoStartup(false);
        String suffix = UUID.randomUUID().toString();
        properties = new OutboxPublisherProperties(false, 3, 20, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(60), "vaultdrive.outbox.events.test." + suffix,
                "vaultdrive.purge.requests.test." + suffix, "purge.requested");
        admin.declareExchange(new org.springframework.amqp.core.DirectExchange(properties.exchange(), true, false));
        admin.declareQueue(org.springframework.amqp.core.QueueBuilder.durable(properties.queue()).quorum().build());
        admin.declareBinding(new org.springframework.amqp.core.Binding(properties.queue(),
                org.springframework.amqp.core.Binding.DestinationType.QUEUE, properties.exchange(), properties.routingKey(), null));
    }

    @AfterEach
    void cleanupOnlyTestFixturesAndTopology() {
        containers.forEach(SimpleMessageListenerContainer::destroy);
        reset(jobs);
        jobRepository.deleteAllById(ids);
        outbox.deleteAllById(ids);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> hashes.forEach(hash ->
                entityManager.createNativeQuery("DELETE FROM notification_quarantine WHERE body_sha256 = :hash")
                        .setParameter("hash", hash).executeUpdate()));
        if (factory != null) {
            try {
                if (properties != null) { admin.deleteQueue(properties.queue()); admin.deleteExchange(properties.exchange()); }
            } finally { factory.destroy(); }
        }
    }

    @Test
    void publisherToConsumerFileAndFolderDeliveriesCreateJobsAndDuplicatesPreserveState() throws Exception {
        var file = event(false);
        var folder = event(true);
        CountDownLatch acked = new CountDownLatch(3);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        start(new PurgeNotificationListener(ingestor, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) {
                try { super.onMessage(message, channel); } catch (Throwable error) { failure.set(error); throw error; }
                finally { acked.countDown(); }
            }
        });
        var publisher = new OutboxPublisher(claims, new RabbitOutboxTransport(template, admin, mapper, properties));
        assertThat(publisher.publishNext()).isTrue();
        assertThat(publisher.publishNext()).isTrue();
        send(ConsumerTestFixtures.message(file, mapper));
        assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(jobRepository.findAllById(ids)).hasSize(2).allSatisfy(job -> {
            assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
            assertThat(job.getAttemptCount()).isZero();
        });
        assertThat(jobRepository.findById(folder.getId()).orElseThrow().getAggregateType()).isEqualTo("FOLDER");
        containers.getFirst().stop();
        assertThat(template.receive(properties.queue(), 100)).isNull();
    }

    @Test
    void malformedAndUnsupportedVersionReachDurableQuarantineWithoutJobs() throws Exception {
        var event = event(false);
        var valid = ConsumerTestFixtures.message(event, mapper);
        ObjectNode json = (ObjectNode) mapper.readTree(valid.getBody());
        json.put("payloadVersion", 99);
        var version = new Message(mapper.writeValueAsBytes(json), valid.getMessageProperties());
        var malformed = new Message("invalid private payload".getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties());
        hashes.add(PurgeNotificationListener.digest(version.getBody()));
        hashes.add(PurgeNotificationListener.digest(malformed.getBody()));
        CountDownLatch acked = new CountDownLatch(2);
        start(new PurgeNotificationListener(ingestor, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) {
                super.onMessage(message, channel);
                acked.countDown();
            }
        });
        send(version);
        send(malformed);
        assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(jobRepository.findById(event.getId())).isEmpty();
        assertThat(quarantineReasons().stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder("MALFORMED", "UNSUPPORTED_VERSION");
        containers.getFirst().stop();
        assertThat(template.receive(properties.queue(), 100)).isNull();
    }

    @Test
    void databaseRollbackExhaustionStopsContainerAndExplicitRestartRedelivers() throws Exception {
        var event = event(false);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            attempts.incrementAndGet();
            call.callRealMethod();
            throw new DataAccessResourceFailureException("sensitive simulated database failure");
        }).when(jobs).create(eq(event.getId()), any(), any());
        CountDownLatch stopped = new CountDownLatch(1);
        var container = build(new PurgeNotificationListener(ingestor, mapper, 1));
        container.setApplicationEventPublisher(applicationEvent -> {
            if (applicationEvent instanceof ListenerContainerConsumerFailedEvent failed && failed.isFatal()) { stopped.countDown(); }
        });
        container.start();
        send(ConsumerTestFixtures.message(event, mapper));
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        container.destroy();
        factory.resetConnection(); // Close test consumer channel; unacked message returns to queue.
        assertThat(attempts).hasValue(3);
        assertThat(jobRepository.findById(event.getId())).isEmpty();
        reset(jobs);
        CountDownLatch acked = new CountDownLatch(1);
        AtomicReference<Boolean> redelivered = new AtomicReference<>();
        start(new PurgeNotificationListener(ingestor, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) {
                redelivered.set(message.getMessageProperties().isRedelivered());
                super.onMessage(message, channel);
                acked.countDown();
            }
        });
        assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(redelivered.get()).isTrue();
        assertThat(jobRepository.findAllById(List.of(event.getId()))).hasSize(1);
        containers.getLast().stop();
        assertThat(template.receive(properties.queue(), 100)).isNull();
    }

    @Test
    void consumerCrashAfterCommitBeforeAckRedeliversWithStableJobIdentity() throws Exception {
        var event = event(false);
        CountDownLatch committed = new CountDownLatch(1);
        var container = start(new PurgeNotificationListener(ingestor, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) {
                ingestor.accept(PurgeNotification.parse(message, mapper));
                committed.countDown();
                // Deliberately return with no ACK: represents process loss after commit.
            }
        });
        send(ConsumerTestFixtures.message(event, mapper));
        assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(jobRepository.findById(event.getId())).isPresent();
        container.destroy();
        factory.resetConnection();
        CountDownLatch acked = new CountDownLatch(1);
        AtomicReference<Boolean> redelivered = new AtomicReference<>();
        start(new PurgeNotificationListener(ingestor, mapper, 1) {
            @Override public void onMessage(Message message, Channel channel) {
                redelivered.set(message.getMessageProperties().isRedelivered());
                super.onMessage(message, channel);
                acked.countDown();
            }
        });
        assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(redelivered.get()).isTrue();
        assertThat(jobRepository.findAllById(List.of(event.getId()))).hasSize(1);
        assertThat(jobRepository.findById(event.getId()).orElseThrow().getAttemptCount()).isZero();
    }

    private SimpleMessageListenerContainer build(PurgeNotificationListener listener) {
        var container = PurgeConsumerConfiguration.container(factory, properties.queue(), listener);
        container.setAutoStartup(false);
        container.afterPropertiesSet();
        containers.add(container);
        return container;
    }
    private SimpleMessageListenerContainer start(PurgeNotificationListener listener) {
        var container = build(listener);
        container.start();
        return container;
    }
    private void send(Message message) { template.send(properties.exchange(), properties.routingKey(), message); }
    private OutboxEvent event(boolean folder) {
        UUID id = new TransactionTemplate(transactionManager).execute(status -> {
            // Due selection uses PostgreSQL time, independently of the Windows/VM clock offset.
            Instant due = ((Instant) entityManager.createNativeQuery("SELECT clock_timestamp()", Instant.class)
                    .getSingleResult()).minusSeconds(60);
            return writer.append(folder
                    ? OutboxEvent.folderPurgeRequested(UUID.randomUUID(), UUID.randomUUID(), due)
                    : OutboxEvent.filePurgeRequested(UUID.randomUUID(), UUID.randomUUID(), due)).getId();
        });
        ids.add(id);
        return outbox.findById(id).orElseThrow();
    }
    private List<?> quarantineReasons() {
        return new TransactionTemplate(transactionManager).execute(status -> entityManager.createNativeQuery(
                "SELECT reason_code FROM notification_quarantine WHERE body_sha256 IN (:hashes)")
                .setParameter("hashes", hashes).getResultList());
    }
}
