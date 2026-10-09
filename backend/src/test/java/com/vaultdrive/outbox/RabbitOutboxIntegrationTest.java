package com.vaultdrive.outbox;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=outbox_publisher_test",
        "spring.flyway.schemas=outbox_publisher_test",
        "spring.flyway.default-schema=outbox_publisher_test",
        "vaultdrive.outbox.publisher.max-attempts=3",
        "vaultdrive.outbox.publisher.max-backoff=2s"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RABBITMQ_TEST_VHOST", matches = "vaultdrive_outbox_test")
class RabbitOutboxIntegrationTest {
    @Autowired private OutboxClaimService claims;
    @Autowired private OutboxWriter writer;
    @Autowired private OutboxEventRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper mapper;

    private CachingConnectionFactory factory;
    private RabbitTemplate template;
    private RabbitAdmin admin;
    private OutboxPublisherProperties properties;
    private OutboxRabbitIo io;
    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach
    void connectOnlyToApprovedIsolatedVhost() {
        String username = System.getenv("RABBITMQ_USERNAME");
        String password = System.getenv("RABBITMQ_PASSWORD");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("RabbitMQ integration-test credentials are required");
        }
        var client = new com.rabbitmq.client.ConnectionFactory();
        io = new OutboxRabbitIo();
        io.configure(client);
        factory = new CachingConnectionFactory(client);
        factory.setHost("127.0.0.1");
        factory.setPort(5672);
        factory.setCloseTimeout(OutboxRabbitIo.CLOSE_TIMEOUT_MS);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setVirtualHost("vaultdrive_outbox_test");
        factory.setConnectionTimeout(3000);
        factory.getRabbitConnectionFactory().setChannelRpcTimeout(5000);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        template = new RabbitTemplate(factory);
        admin = new RabbitAdmin(factory);
        admin.setAutoStartup(false);
        String suffix = UUID.randomUUID().toString();
        properties = new OutboxPublisherProperties(false, 3, 20, Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(2),
                "vaultdrive.outbox.events.test." + suffix,
                "vaultdrive.purge.requests.test." + suffix, "purge.requested");
    }

    @AfterEach
    void removeOnlyThisTestsDataAndTopology() {
        repository.deleteAllById(eventIds);
        if (factory != null) {
            try {
                if (properties != null) {
                    admin.deleteQueue(properties.queue());
                    admin.deleteExchange(properties.exchange());
                }
            } finally {
                factory.destroy();
                if (io != null) {
                    io.close();
                }
            }
        }
    }

    @Test
    void realBrokerConfirmMarksEventPublishedAndEnqueuesPersistentEnvelope() {
        UUID id = event();
        new OutboxPublisher(claims, transport(admin)).publishNext();
        assertThat(repository.findById(id).orElseThrow().getDeliveryStatus())
                .isEqualTo(OutboxDeliveryStatus.PUBLISHED);
        Message message = template.receive(properties.queue(), 5000);
        assertEnvelope(message, id);
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(template.receive(properties.queue())).isNull();
    }

    @Test
    void brokerAcceptanceThenCrashReplaysTwoMessagesWithOneStableEventId() {
        UUID id = event();
        ClaimedOutboxEvent beforeCrash = claims.claimNext().orElseThrow();
        assertThat(transport(admin).publish(beforeCrash)).isEqualTo(PublishResult.CONFIRMED);
        // Intentionally omit DB completion: this is the acceptance/recording crash window.
        assertThat(repository.findById(id).orElseThrow().getPublishedAt()).isNull();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        UPDATE outbox_events SET claim_expires_at = clock_timestamp() - interval '1 second',
                            next_attempt_at = clock_timestamp() - interval '1 second'
                        WHERE id = :id
                        """).setParameter("id", id).executeUpdate());
        new OutboxPublisher(claims, transport(admin)).publishNext();
        assertEnvelope(template.receive(properties.queue(), 5000), id);
        assertEnvelope(template.receive(properties.queue(), 5000), id);
        assertThat(repository.findById(id).orElseThrow().getAttemptCount()).isEqualTo(2);
        assertThat(repository.findById(id).orElseThrow().getDeliveryStatus())
                .isEqualTo(OutboxDeliveryStatus.PUBLISHED);
        assertThat(claims.confirmed(beforeCrash)).isFalse();
    }

    @Test
    void realMandatoryReturnDoesNotMarkPublishedDespiteBrokerAck() {
        UUID id = event();
        RabbitAdmin withoutBinding = mock(RabbitAdmin.class);
        doAnswer(call -> { admin.declareExchange(call.getArgument(0)); return null; })
                .when(withoutBinding).declareExchange(any());
        doAnswer(call -> admin.declareQueue(call.getArgument(0)))
                .when(withoutBinding).declareQueue(any());
        // Exchange/queue exist, but binding is intentionally absent in this test.
        new OutboxPublisher(claims, transport(withoutBinding)).publishNext();
        OutboxEvent row = repository.findById(id).orElseThrow();
        assertThat(row.getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PENDING);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getLastFailureCode()).isEqualTo("RETURNED");
    }

    private RabbitOutboxTransport transport(RabbitAdmin rabbitAdmin) {
        return new RabbitOutboxTransport(template, rabbitAdmin, mapper, properties);
    }

    private UUID event() {
        UUID id = new TransactionTemplate(transactionManager).execute(status -> writer.append(
                OutboxEvent.filePurgeRequested(UUID.randomUUID(), UUID.randomUUID(), Instant.now().minusSeconds(60))
        ).getId());
        eventIds.add(id);
        return id;
    }

    private void assertEnvelope(Message message, UUID id) {
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        var json = mapper.readTree(message.getBody());
        assertThat(json.get("eventId").asString()).isEqualTo(id.toString());
        assertThat(json.get("payloadVersion").asInt()).isEqualTo(1);
    }
}
