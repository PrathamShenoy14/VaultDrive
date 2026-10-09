package com.vaultdrive.outbox;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=outbox_publisher_test",
        "spring.flyway.schemas=outbox_publisher_test",
        "spring.flyway.default-schema=outbox_publisher_test",
        "vaultdrive.outbox.publisher.max-attempts=3",
        "vaultdrive.outbox.publisher.max-backoff=2s"
})
@ActiveProfiles("test")
class OutboxPublisherIntegrationTest {

    @Autowired private OutboxWriter writer;
    @Autowired private OutboxClaimService claims;
    @Autowired private OutboxEventRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void cleanupOnlyCreatedEvents() {
        repository.deleteAllById(eventIds);
    }

    @Test
    void concurrentPublishersClaimDifferentEventsAndCommitBeforeNetworkIo() throws Exception {
        UUID first = event();
        UUID second = event();
        CountDownLatch sending = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<UUID> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        OutboxTransport transport = claim -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sent.add(claim.id());
            sending.countDown();
            await(release);
            return PublishResult.CONFIRMED;
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> new OutboxPublisher(claims, transport).publishNext());
            var two = executor.submit(() -> new OutboxPublisher(claims, transport).publishNext());
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(sent).containsExactlyInAnyOrder(first, second);
            assertThat(load(first).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PUBLISHING);
            assertThat(load(second).getPublishedAt()).isNull();
            // NOWAIT proves the publisher released row locks before network I/O.
            transaction().executeWithoutResult(status -> entityManager.createNativeQuery(
                    "SELECT id FROM outbox_events WHERE id = :id FOR UPDATE NOWAIT"
            ).setParameter("id", first).getSingleResult());
            release.countDown();
            assertThat(one.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(two.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(load(first).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PUBLISHED);
            assertThat(load(second).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PUBLISHED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void skipsALockedDueRowWithoutWaiting() throws Exception {
        UUID locked = event();
        UUID available = event();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = executor.submit(() -> transaction().executeWithoutResult(status -> {
                entityManager.createNativeQuery("SELECT id FROM outbox_events WHERE id = :id FOR UPDATE")
                        .setParameter("id", locked).getSingleResult();
                holding.countDown();
                await(release);
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> claims.claimNext().orElseThrow().id())
                    .get(5, TimeUnit.SECONDS)).isEqualTo(available);
            release.countDown();
            owner.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void saturatedNioSendLeavesEventRetryableAndPollerPublishesNextEvent() throws Exception {
        UUID stalledId = event();
        UUID nextId = event();
        try (var socket = new StalledNioSocket()) {
            var template = mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
            var factory = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory();
            factory.setPublisherConfirmType(
                    org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
            when(template.getConnectionFactory()).thenReturn(factory);
            doAnswer(call -> {
                var message = (org.springframework.amqp.core.Message) call.getArgument(2);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (message.getMessageProperties().getMessageId().equals(stalledId.toString())) {
                    try {
                        socket.write(); // Actual client frame enqueue timeout, not a mocked delay.
                    } catch (java.io.IOException exception) {
                        socket.drain();
                        throw new org.springframework.amqp.AmqpIOException(exception);
                    }
                }
                var correlation = (org.springframework.amqp.rabbit.connection.CorrelationData) call.getArgument(3);
                correlation.getFuture().complete(
                        new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true, null));
                return null;
            }).when(template).send(anyString(), anyString(), any(org.springframework.amqp.core.Message.class),
                    any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
            var properties = new OutboxPublisherProperties(false, 3, 20, Duration.ofSeconds(30),
                    Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(2), "test", "test", "test");
            var transport = new RabbitOutboxTransport(template,
                    mock(org.springframework.amqp.rabbit.core.RabbitAdmin.class),
                    new tools.jackson.databind.ObjectMapper(), properties);
            new OutboxPublisherConfiguration.Poller(new OutboxPublisher(claims, transport), properties).poll();
            assertThat(load(stalledId).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PENDING);
            assertThat(load(stalledId).getLastFailureCode()).isEqualTo("BROKER_ERROR");
            assertThat(load(stalledId).getPublishedAt()).isNull();
            assertThat(load(nextId).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PUBLISHED);
            dueNow(stalledId);
            new OutboxPublisher(claims, claim -> PublishResult.CONFIRMED).publishNext();
            assertThat(load(stalledId).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PUBLISHED);
            assertThat(load(stalledId).getAttemptCount()).isEqualTo(2);
            factory.destroy();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void leaseReclaimedDuringStalledEnqueueRejectsLateFailure() throws Exception {
        UUID id = event();
        ClaimedOutboxEvent old = claims.claimNext().orElseThrow();
        try (var socket = new StalledNioSocket()) {
            var executor = Executors.newFixedThreadPool(1);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch releaseFailure = new CountDownLatch(1);
            try {
                var stale = executor.submit(() -> {
                    entered.countDown();
                    try {
                        socket.write();
                        throw new AssertionError("Expected saturated frame queue");
                    } catch (java.io.IOException exception) {
                        assertThat(releaseFailure.await(5, TimeUnit.SECONDS)).isTrue();
                        return claims.failed(old, PublishResult.BROKER_ERROR);
                    }
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                expire(id);
                ClaimedOutboxEvent replacement = claims.claimNext().orElseThrow();
                assertThat(replacement.id()).isEqualTo(id);
                assertThat(replacement.claimToken()).isNotEqualTo(old.claimToken());
                releaseFailure.countDown();
                assertThat(stale.get(3, TimeUnit.SECONDS)).isFalse();
                assertThat(claims.owns(replacement)).isTrue();
                assertThat(load(id).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PUBLISHING);
                assertThat(claims.confirmed(replacement)).isTrue();
            } finally {
                releaseFailure.countDown();
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PublishResult.class, names = {"BROKER_ERROR", "NACK", "TIMEOUT", "RETURNED"})
    void failuresBackOffAndNeverMarkPublished(PublishResult failure) {
        UUID id = event();
        assertThat(new OutboxPublisher(claims, claim -> failure).publishNext()).isTrue();
        OutboxEvent row = load(id);
        assertThat(row.getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PENDING);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getClaimToken()).isNull();
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastFailureCode()).isEqualTo(failure.name());
        assertThat(Duration.between(row.getLastAttemptAt(), row.getNextAttemptAt()))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(claims.claimNext()).isEmpty();
    }

    @Test
    void connectionExceptionIsRecordedWithoutItsSensitiveMessage() {
        UUID id = event();
        new OutboxPublisher(claims, claim -> { throw new IllegalStateException("sensitive connection detail"); })
                .publishNext();
        assertThat(load(id).getLastFailureCode()).isEqualTo("BROKER_ERROR");
    }

    @Test
    void retriesAreBoundedAndBackoffIsCapped() {
        UUID id = event();
        for (int attempt = 1; attempt <= 3; attempt++) {
            new OutboxPublisher(claims, claim -> PublishResult.NACK).publishNext();
            assertThat(load(id).getAttemptCount()).isEqualTo(attempt);
            if (attempt < 3) {
                dueNow(id);
            }
        }
        OutboxEvent row = load(id);
        assertThat(row.getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.FAILED);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(claims.claimNext()).isEmpty();
    }

    @Test
    void expiredLeaseChangesTokenAndRejectsLateSuccessAndFailure() {
        UUID id = event();
        ClaimedOutboxEvent old = claims.claimNext().orElseThrow();
        assertThat(claims.claimNext()).isEmpty();
        expire(id);
        assertThat(claims.confirmed(old)).isFalse();
        assertThat(claims.failed(old, PublishResult.NACK)).isFalse();
        ClaimedOutboxEvent replacement = claims.claimNext().orElseThrow();
        assertThat(replacement.id()).isEqualTo(old.id());
        assertThat(replacement.claimToken()).isNotEqualTo(old.claimToken());
        assertThat(replacement.attemptCount()).isEqualTo(2);
        assertThat(claims.confirmed(old)).isFalse();
        assertThat(claims.failed(old, PublishResult.NACK)).isFalse();
        assertThat(claims.owns(replacement)).isTrue();
        assertThat(claims.confirmed(replacement)).isTrue();
    }

    @Test
    void abandonedFinalAttemptBecomesFailedAndRemainsDurable() {
        UUID id = event();
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(claims.claimNext().orElseThrow().attemptCount()).isEqualTo(attempt);
            expire(id);
        }
        assertThat(claims.claimNext()).isEmpty();
        assertThat(load(id).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.FAILED);
        assertThat(load(id).getLastFailureCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(load(id).getId()).isEqualTo(id);
    }

    @Test
    void expiredLeaseStillHonorsItsPersistedRetryBackoff() {
        UUID id = event();
        ClaimedOutboxEvent old = claims.claimNext().orElseThrow();
        update("claim_expires_at = clock_timestamp() - interval '1 second'", id);
        assertThat(claims.owns(old)).isFalse();
        assertThat(claims.claimNext()).isEmpty();
        dueNow(id);
        assertThat(claims.claimNext().orElseThrow().attemptCount()).isEqualTo(2);
    }

    @Test
    void crashAfterBrokerAcceptanceReplaysTheSameId() {
        UUID id = event();
        List<UUID> accepted = new ArrayList<>();
        ClaimedOutboxEvent beforeCrash = claims.claimNext().orElseThrow();
        accepted.add(beforeCrash.id()); // broker accepted; process dies before confirmed(...)
        assertThat(load(id).getPublishedAt()).isNull();
        expire(id);
        new OutboxPublisher(claims, claim -> {
            accepted.add(claim.id());
            return PublishResult.CONFIRMED;
        }).publishNext();
        assertThat(accepted).containsExactly(id, id);
        assertThat(load(id).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PUBLISHED);
        assertThat(load(id).getAttemptCount()).isEqualTo(2);
    }

    @Test
    void publisherRejectsAnEnclosingDatabaseTransaction() {
        event();
        assertThatThrownBy(() -> transaction().executeWithoutResult(status ->
                new OutboxPublisher(claims, claim -> PublishResult.CONFIRMED).publishNext()
        )).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "delivery_status = 'PUBLISHING'",
            "claim_token = '00000000-0000-0000-0000-000000000001'::uuid",
            "claim_expires_at = clock_timestamp()"
    })
    void databaseRejectsIncompleteOrInactiveClaims(String invalidAssignment) {
        UUID id = event();
        assertThatThrownBy(() -> update(invalidAssignment, id))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(exception -> {
                    Throwable root = exception;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(((java.sql.SQLException) root).getSQLState()).isEqualTo("23514");
                });
        assertThat(load(id).getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.PENDING);
    }

    private UUID event() {
        OutboxEvent event = transaction().execute(status -> writer.append(
                OutboxEvent.filePurgeRequested(UUID.randomUUID(), UUID.randomUUID(), Instant.now().minusSeconds(60))
        ));
        eventIds.add(event.getId());
        return event.getId();
    }

    private OutboxEvent load(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void expire(UUID id) {
        update("claim_expires_at = clock_timestamp() - interval '1 second', "
                + "next_attempt_at = clock_timestamp() - interval '1 second'", id);
    }

    private void dueNow(UUID id) {
        update("next_attempt_at = clock_timestamp() - interval '1 second'", id);
    }

    private void update(String assignment, UUID id) {
        transaction().executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE outbox_events SET " + assignment + " WHERE id = :id"
        ).setParameter("id", id).executeUpdate());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted test", exception);
        }
    }
}
