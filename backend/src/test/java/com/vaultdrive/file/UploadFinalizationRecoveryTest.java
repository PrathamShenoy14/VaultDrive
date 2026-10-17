package com.vaultdrive.file;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.vaultdrive.file.UploadFinalizationTransactions.Outcome.*;

class UploadFinalizationRecoveryTest {
    private final UploadFinalizationTransactions tx = mock(UploadFinalizationTransactions.class);
    private final UploadFinalizationRecovery recovery = new UploadFinalizationRecovery(tx);
    private StoredFile file() { return new StoredFile(UUID.randomUUID(), UUID.randomUUID(), null, "a", "k", "text/plain", 1); }

    @Test void receiptCommitPrecedesReadyAndReloadIsOnlyUsedOnFailure() {
        var file = file(); when(tx.recover(file.getId())).thenReturn(RECOVERED);
        recovery.complete(file);
        var order = inOrder(tx); order.verify(tx).recordSuccess(file); order.verify(tx).recover(file.getId());
        verify(tx, never()).hasSuccess(any());
    }
    @Test void failedReloadPreservesOriginalFailureAndNeverFinalizes() {
        var file = file(); var failure = new IllegalStateException("receipt failure");
        doThrow(failure).when(tx).recordSuccess(file);
        when(tx.hasSuccess(file)).thenThrow(new IllegalStateException("reload failure"));
        assertThatThrownBy(() -> recovery.complete(file)).isSameAs(failure);
        assertThat(failure.getSuppressed()).hasSize(1);
        verify(tx, never()).recover(any());
    }
    @Test void blockedForegroundCompletionReportsFailureWithoutReady() {
        var file = file(); when(tx.recover(file.getId())).thenReturn(BLOCKED);
        assertThatThrownBy(() -> recovery.complete(file)).hasMessage("Upload metadata finalization is blocked");
    }
    @Test void blockedAndFailedCandidatesCannotStarveLaterCandidates() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        when(tx.scan(null)).thenReturn(List.of(a, b, c, d));
        when(tx.recover(a)).thenReturn(BLOCKED);
        when(tx.recover(b)).thenThrow(new IllegalStateException("private detail"));
        when(tx.recover(c)).thenReturn(RECOVERED); when(tx.recover(d)).thenReturn(ALREADY_READY);
        var page = recovery.recoverPage(null);
        assertThat(page.scanned()).isEqualTo(4); assertThat(page.nextCursor()).isEqualTo(d);
        assertThat(page.blocked()).isEqualTo(1); assertThat(page.failed()).isEqualTo(1);
        assertThat(page.recovered()).isEqualTo(1); assertThat(page.alreadyReady()).isEqualTo(1);
        assertThat(page.exhausted()).isFalse();
    }
    @Test void selectionFailurePreservesIncomingCursorAndReportsSanitizedCounter() {
        UUID after = UUID.randomUUID(); when(tx.scan(after)).thenThrow(new IllegalStateException("private"));
        var page = recovery.recoverPage(after);
        assertThat(page.nextCursor()).isEqualTo(after); assertThat(page.scanned()).isZero();
        assertThat(page.scanFailed()).isEqualTo(1); assertThat(page.exhausted()).isFalse();
    }
    @Test void interruptedPageDoesNotStartAnotherCandidateOrAdvanceCursor() {
        UUID after = UUID.randomUUID(), next = UUID.randomUUID(); when(tx.scan(after)).thenReturn(List.of(next));
        Thread.currentThread().interrupt();
        try {
            var page = recovery.recoverPage(after);
            assertThat(page.scanned()).isZero(); assertThat(page.nextCursor()).isEqualTo(after);
            verify(tx, never()).recover(any());
        } finally { Thread.interrupted(); }
    }
}
