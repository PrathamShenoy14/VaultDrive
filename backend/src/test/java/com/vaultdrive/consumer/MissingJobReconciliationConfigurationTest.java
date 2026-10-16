package com.vaultdrive.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissingJobReconciliationConfigurationTest {
    private final MissingJobReconciler reconciler = mock(MissingJobReconciler.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MissingJobReconciliationConfiguration.class)
            .withBean(MissingJobReconciler.class, () -> reconciler);

    @Test void disabledByDefaultHasNoPollerOrSideEffects() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MissingJobReconciliationConfiguration.Poller.class);
            var properties = context.getBean(MissingJobReconciliationProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.eligibilityDelay()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.pageSize()).isEqualTo(20);
            verifyNoInteractions(reconciler);
        });
    }

    @Test void optInStartsOnlyReconciliationAndClosesItsExecutor() {
        runner.withPropertyValues("vaultdrive.jobs.reconciliation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MissingJobReconciliationConfiguration.Poller.class);
            assertThat(context).doesNotHaveBean("jobWorker").doesNotHaveBean("outboxPoller")
                    .doesNotHaveBean("purgeNotificationContainer");
            verifyNoInteractions(reconciler); // Initial delay prevents an immediate startup scan.
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"eligibility-delay=0s", "eligibility-delay=8d", "poll-delay=0s",
            "poll-delay=2d", "page-size=0", "page-size=101"})
    void rejectsUnsafeConfiguration(String assignment) {
        runner.withPropertyValues("vaultdrive.jobs.reconciliation." + assignment)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void pageMetricsAndCursorTrackAlreadyPresentAndFailureWithoutPayloads() {
        var transactions = mock(MissingJobReconciliationTransactions.class);
        var first = new MissingJobReconciliationTransactions.Cursor(java.time.Instant.now(), java.util.UUID.randomUUID());
        var second = new MissingJobReconciliationTransactions.Cursor(first.publishedAt(), java.util.UUID.randomUUID());
        when(transactions.scan(null, 20)).thenReturn(java.util.List.of(first, second));
        when(transactions.ingest(first.eventId())).thenReturn(MissingJobReconciliationTransactions.Outcome.ALREADY_PRESENT);
        when(transactions.ingest(second.eventId())).thenThrow(new IllegalStateException("private-value"));
        var service = new MissingJobReconciler(transactions, new MissingJobReconciliationProperties(
                true, Duration.ofMinutes(5), Duration.ofMinutes(1), 20));
        var page = service.reconcilePage(null);
        assertThat(page.scanned()).isEqualTo(2);
        assertThat(page.alreadyPresent()).isEqualTo(1);
        assertThat(page.failed()).isEqualTo(1);
        assertThat(page.nextCursor()).isEqualTo(second);
        assertThat(page.toString()).doesNotContain("private-value");
    }
}
