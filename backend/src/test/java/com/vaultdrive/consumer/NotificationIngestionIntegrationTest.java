package com.vaultdrive.consumer;

import com.rabbitmq.client.Channel;
import com.vaultdrive.job.BackgroundJobRepository;
import com.vaultdrive.job.BackgroundJobService;
import com.vaultdrive.job.JobStatus;
import com.vaultdrive.outbox.OutboxEvent;
import com.vaultdrive.outbox.OutboxEventRepository;
import com.vaultdrive.outbox.OutboxWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=notification_ingestion_test",
        "spring.flyway.schemas=notification_ingestion_test", "spring.flyway.default-schema=notification_ingestion_test"
})
@ActiveProfiles("test")
class NotificationIngestionIntegrationTest {
    @Autowired private NotificationIngestor ingestor;
    @MockitoSpyBean private BackgroundJobService jobs;
    @Autowired private BackgroundJobRepository jobRepository;
    @Autowired private OutboxWriter writer;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper mapper;
    private final List<UUID> ids = new ArrayList<>();
    private final List<String> hashes = new ArrayList<>();

    @AfterEach
    void cleanup() {
        reset(jobs);
        jobRepository.deleteAllById(ids);
        outbox.deleteAllById(ids);
        transaction().executeWithoutResult(status -> hashes.forEach(hash -> entityManager.createNativeQuery(
                "DELETE FROM notification_quarantine WHERE body_sha256 = :hash").setParameter("hash", hash).executeUpdate()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulFileAndFolderDeliveryAckOnlyAfterCommitAndDuplicatePreservesTerminalJob(boolean folder) throws Exception {
        var event = event(folder);
        var message = ConsumerTestFixtures.message(event, mapper);
        Channel channel = mock(Channel.class);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jobRepository.findById(event.getId())).isPresent();
            return null;
        }).when(channel).basicAck(7, false);
        listener().onMessage(message, channel);
        var claim = jobs.claimNext(folder ? com.vaultdrive.job.JobType.FOLDER_PURGE : com.vaultdrive.job.JobType.FILE_PURGE)
                .orElseThrow();
        jobs.complete(claim);
        listener().onMessage(message, channel);
        assertThat(jobRepository.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jobRepository.findById(event.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);
        verify(channel, times(2)).basicAck(7, false);
    }

    @Test
    void commitFailureRollsBackBeforeRetryAndAck() throws Exception {
        var event = event(false);
        AtomicInteger attempts = new AtomicInteger();
        Channel channel = mock(Channel.class);
        doAnswer(call -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) {
                assertThat(jobRepository.findById(event.getId())).isEmpty();
                verifyNoInteractions(channel);
            }
            Object result = call.callRealMethod();
            if (attempt == 1) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        throw new DataAccessResourceFailureException("sensitive simulated commit failure");
                    }
                });
            }
            return result;
        }).when(jobs).create(eq(event.getId()), any(), any());
        listener().onMessage(ConsumerTestFixtures.message(event, mapper), channel);
        assertThat(attempts).hasValue(2);
        assertThat(jobRepository.findById(event.getId()).orElseThrow().getAttemptCount()).isZero();
        verify(channel).basicAck(7, false);
    }

    @Test
    void exhaustedDatabaseFailureRollsBackAndNeverAcksOrQuarantinesValidWork() {
        var event = event(false);
        doAnswer(call -> {
            call.callRealMethod();
            throw new DataAccessResourceFailureException("private details");
        }).when(jobs).create(eq(event.getId()), any(), any());
        Channel channel = mock(Channel.class);
        assertThatThrownBy(() -> listener().onMessage(ConsumerTestFixtures.message(event, mapper), channel))
                .hasMessage("DATABASE_UNAVAILABLE");
        verify(jobs, times(3)).create(eq(event.getId()), any(), any());
        verifyNoInteractions(channel);
        assertThat(jobRepository.findById(event.getId())).isEmpty();
        assertThat(quarantineCount(ConsumerTestFixtures.message(event, mapper))).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "version", "type", "unknown", "mismatch", "identity"})
    void invalidDeliveryCreatesOnlySanitizedDurableQuarantineBeforeAck(String scenario) throws Exception {
        var event = event(false);
        var original = ConsumerTestFixtures.message(event, mapper);
        var json = (ObjectNode) mapper.readTree(original.getBody());
        switch (scenario) {
            case "malformed" -> json.remove("payload");
            case "version" -> json.put("payloadVersion", 2);
            case "type" -> json.put("eventType", "UNSUPPORTED_EVENT");
            case "unknown" -> { outbox.deleteById(event.getId()); }
            case "mismatch" -> ((ObjectNode) json.get("payload")).put("ownerId", UUID.randomUUID().toString());
            case "identity" -> jobs.create(event.getId(), com.vaultdrive.job.JobType.FOLDER_PURGE, UUID.randomUUID());
        }
        var message = new org.springframework.amqp.core.Message(mapper.writeValueAsBytes(json), original.getMessageProperties());
        String digest = PurgeNotificationListener.digest(message.getBody());
        hashes.add(digest);
        Channel channel = mock(Channel.class);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(quarantineCount(message)).isEqualTo(1);
            return null;
        }).when(channel).basicAck(7, false);
        listener().onMessage(message, channel);
        listener().onMessage(message, channel);
        if (!scenario.equals("identity")) {
            assertThat(jobRepository.findById(event.getId())).isEmpty();
        } else {
            assertThat(jobRepository.findById(event.getId()).orElseThrow().getJobType())
                    .isEqualTo(com.vaultdrive.job.JobType.FOLDER_PURGE);
        }
        verify(channel, times(2)).basicAck(7, false);
        assertThat(quarantineCount(message)).isEqualTo(1);
    }

    @Test
    void quarantineConstraintsRejectInvalidHashAndReason() {
        assertThatThrownBy(() -> ingestor.quarantine("invalid", new InvalidNotification(
                InvalidNotification.Reason.MALFORMED, null))).hasRootCauseInstanceOf(java.sql.SQLException.class);
        String hash = PurgeNotificationListener.digest(new byte[]{42});
        hashes.add(hash);
        ingestor.quarantine(hash, new InvalidNotification(InvalidNotification.Reason.MALFORMED, null));
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE notification_quarantine SET reason_code = 'UNRECOGNIZED' WHERE body_sha256 = :hash")
                .setParameter("hash", hash).executeUpdate())).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void commitThenLostAckCanRedeliverWithoutDuplicateJob() throws Exception {
        var event = event(false);
        var message = ConsumerTestFixtures.message(event, mapper);
        Channel lost = mock(Channel.class);
        doThrow(new IOException("sensitive detail")).when(lost).basicAck(7, false);
        assertThatThrownBy(() -> listener().onMessage(message, lost)).hasMessage("ACK_UNCERTAIN");
        assertThat(jobRepository.findById(event.getId())).isPresent();
        Channel retry = mock(Channel.class);
        listener().onMessage(message, retry);
        assertThat(jobRepository.findAllById(List.of(event.getId()))).hasSize(1);
        verify(retry).basicAck(7, false);
    }

    @Test
    void outerTransactionIsRejectedBeforeBrokerOrDatabaseWork() {
        var event = event(false);
        Channel channel = mock(Channel.class);
        assertThatThrownBy(() -> transaction().executeWithoutResult(status ->
                listener().onMessage(ConsumerTestFixtures.message(event, mapper), channel)))
                .hasMessage("OUTER_TRANSACTION");
        verifyNoInteractions(channel);
        assertThat(jobRepository.findById(event.getId())).isEmpty();
    }

    private PurgeNotificationListener listener() { return new PurgeNotificationListener(ingestor, mapper, 1); }
    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }
    private OutboxEvent event(boolean folder) {
        UUID id = transaction().execute(status -> writer.append(folder
                ? OutboxEvent.folderPurgeRequested(UUID.randomUUID(), UUID.randomUUID(), Instant.now())
                : OutboxEvent.filePurgeRequested(UUID.randomUUID(), UUID.randomUUID(), Instant.now())).getId());
        ids.add(id);
        return outbox.findById(id).orElseThrow(); // Database timestamp precision, as seen by publisher.
    }

    private long quarantineCount(org.springframework.amqp.core.Message message) {
        return transaction().execute(status -> ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM notification_quarantine WHERE body_sha256 = :hash")
                .setParameter("hash", PurgeNotificationListener.digest(message.getBody())).getSingleResult()).longValue());
    }
}
