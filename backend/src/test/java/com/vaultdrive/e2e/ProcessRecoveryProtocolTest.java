package com.vaultdrive.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.vaultdrive.e2e.ProcessRecoveryProtocol.*;
import static org.assertj.core.api.Assertions.*;

class ProcessRecoveryProtocolTest {
    @TempDir Path directory;

    @Test void refusesTheCurrentMavenOrTestRunnerEvenWithItsCorrectPidAndStartTime() {
        var process = ProcessHandle.current();
        var identity = new Identity(process.pid(), process.info().startInstant().orElseThrow(), UUID.randomUUID(),
                "async_e2e_" + "a".repeat(32));
        assertThatThrownBy(() -> identity.requireOwned(process, directory)).isInstanceOf(IllegalStateException.class);
    }

    @Test void refusesPidReuseWrongStartTimeAndInvalidSchema() {
        var process = ProcessHandle.current();
        for (var identity : java.util.List.of(
                new Identity(process.pid() + 1, process.info().startInstant().orElseThrow(), UUID.randomUUID(), "public"),
                new Identity(process.pid(), Instant.EPOCH, UUID.randomUUID(), "async_e2e_" + "a".repeat(32)))) {
            assertThatThrownBy(() -> identity.requireOwned(process, directory)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void positiveOwnershipNeedsEveryIdentityFieldAndExactArgument() {
        var process = org.mockito.Mockito.mock(ProcessHandle.class);
        var info = org.mockito.Mockito.mock(ProcessHandle.Info.class);
        UUID token = UUID.randomUUID();
        Instant started = Instant.now();
        var identity = new Identity(123, started, token, "async_e2e_" + "a".repeat(32));
        org.mockito.Mockito.when(process.info()).thenReturn(info);
        org.mockito.Mockito.when(process.isAlive()).thenReturn(true);
        org.mockito.Mockito.when(process.pid()).thenReturn(123L);
        org.mockito.Mockito.when(info.startInstant()).thenReturn(java.util.Optional.of(started));
        String[] arguments = {"-cp", "test-classpath", ProcessRecoveryApplication.class.getName(), directory.toAbsolutePath().toString(), token.toString(), "initial"};
        org.mockito.Mockito.when(info.arguments()).thenReturn(java.util.Optional.of(arguments));
        assertThatCode(() -> identity.requireOwned(process, directory)).doesNotThrowAnyException();
        for (int index : java.util.List.of(2, 3, 4)) {
            String[] wrong = arguments.clone();
            wrong[index] += "-different";
            org.mockito.Mockito.when(info.arguments()).thenReturn(java.util.Optional.of(wrong));
            assertThatThrownBy(() -> identity.requireOwned(process, directory)).isInstanceOf(IllegalStateException.class);
        }
        org.mockito.Mockito.when(info.arguments()).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> identity.requireOwned(process, directory)).isInstanceOf(IllegalStateException.class);
    }

    @Test void journalCannotOverwriteAnExistingOwnershipMarker() throws Exception {
        Path marker = directory.resolve("ownership");
        write(marker, "original");
        assertThatThrownBy(() -> write(marker, "replacement")).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(marker)).isEqualTo("original");
    }

    @Test void staleOrMissingApprovalNeverAuthorizesTerminationAndValidApprovalIsConsumedOnce() throws Exception {
        var identity = new Identity(123, Instant.now(), UUID.randomUUID(), "async_e2e_" + "a".repeat(32));
        Path permit = directory.resolve("permit-" + identity.token() + ".txt");
        assertThatThrownBy(() -> consumePermit(permit, identity)).isInstanceOf(java.nio.file.NoSuchFileException.class);
        write(permit, "private-incorrect-value-must-not-be-rendered");
        assertThatThrownBy(() -> consumePermit(permit, identity)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Approval permit does not match the reported live test identity");
        assertThat(Files.exists(directory.resolve("permit-consumed"))).isFalse();
        Files.delete(permit);
        write(permit, identity.permit());
        consumePermit(permit, identity);
        assertThat(Files.exists(permit)).isFalse();
        assertThat(Files.readString(directory.resolve("permit-consumed"))).isEqualTo(identity.permit());
        assertThatThrownBy(() -> consumePermit(permit, identity)).isInstanceOf(java.nio.file.NoSuchFileException.class);
    }

    @Test @Timeout(5) void everyBoundaryWaitsUntilExplicitReleaseAndOnlyFiresOnce() throws Exception {
        for (Point point : Point.values()) {
            Path run = Files.createDirectory(directory.resolve(point.name()));
            var gate = new Gate(run, point);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var blocked = executor.submit(() -> { gate.hit(point); return true; });
                await("failpoint marker", Duration.ofSeconds(1), () -> Files.exists(run.resolve("hit")));
                assertThat(gate.held()).isTrue();
                assertThat(blocked.isDone()).isFalse();
                write(run.resolve("release"), "true");
                assertThat(blocked.get(1, TimeUnit.SECONDS)).isTrue();
                gate.hit(point);
                assertThat(Files.readString(run.resolve("hit"))).isEqualTo(point.name());
            }
        }
    }

    @Test @Timeout(5) void stopCancelsBoundaryWithoutAllowingExecutionToResume() throws Exception {
        var gate = new Gate(directory, Point.DELETE);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var blocked = executor.submit(() -> { gate.hit(Point.DELETE); return true; });
            await("failpoint marker", Duration.ofSeconds(1), () -> Files.exists(directory.resolve("hit")));
            write(directory.resolve("stop"), "true");
            assertThatThrownBy(() -> blocked.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            assertThatThrownBy(() -> gate.hit(Point.DELETE)).isInstanceOf(InterruptedException.class);
        }
    }

    @Test void refusesBoundaryInsideTransaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> new Gate(directory, Point.RECEIPT).hit(Point.RECEIPT))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Failpoint must follow transaction completion");
            assertThat(Files.exists(directory.resolve("hit"))).isFalse();
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    }
}
