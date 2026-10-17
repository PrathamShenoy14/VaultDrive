package com.vaultdrive.file;

import com.vaultdrive.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnownSuccessCleanupTest {
    @Test void scanFailurePreservesCursorAndNeverTouchesStorage() {
        var transactions = mock(KnownSuccessCleanupTransactions.class);
        var storage = mock(ObjectStorageService.class);
        UUID cursor = UUID.randomUUID();
        when(transactions.scan(cursor)).thenThrow(new IllegalStateException("private details"));
        var page = new KnownSuccessCleanup(transactions, storage).cleanupPage(cursor);
        assertThat(page.nextCursor()).isEqualTo(cursor);
        assertThat(page.scanFailed()).isEqualTo(1); assertThat(page.scanned()).isZero();
        assertThat(page.toString()).doesNotContain("private details");
        verifyNoInteractions(storage);
    }

    @Test void blockedAndFailedCandidatesAdvanceWithoutStarvingLaterCandidates() {
        var transactions = mock(KnownSuccessCleanupTransactions.class);
        var storage = mock(ObjectStorageService.class);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        when(transactions.scan(null)).thenReturn(List.of(a, b, c));
        when(transactions.prepare(a)).thenThrow(new IllegalStateException("private target"));
        when(transactions.prepare(b)).thenReturn(new KnownSuccessCleanupTransactions.Prepared(KnownSuccessCleanupTransactions.Outcome.BLOCKED, null));
        when(transactions.prepare(c)).thenReturn(new KnownSuccessCleanupTransactions.Prepared(KnownSuccessCleanupTransactions.Outcome.COMPLETE, null));
        var page = new KnownSuccessCleanup(transactions, storage).cleanupPage(null);
        assertThat(page.nextCursor()).isEqualTo(c); assertThat(page.scanned()).isEqualTo(3);
        assertThat(page.failed()).isEqualTo(1); assertThat(page.blocked()).isEqualTo(1);
        assertThat(page.alreadyComplete()).isEqualTo(1); assertThat(page.toString()).doesNotContain("private target");
        verifyNoInteractions(storage);
    }

    @Test void interruptionDoesNotStartAnotherCandidateOrAdvanceCursor() {
        var transactions = mock(KnownSuccessCleanupTransactions.class);
        var storage = mock(ObjectStorageService.class);
        UUID cursor = UUID.randomUUID(), next = UUID.randomUUID();
        when(transactions.scan(cursor)).thenReturn(List.of(next));
        Thread.currentThread().interrupt();
        try {
            var page = new KnownSuccessCleanup(transactions, storage).cleanupPage(cursor);
            assertThat(page.scanned()).isZero(); assertThat(page.nextCursor()).isEqualTo(cursor);
            verify(transactions, never()).prepare(any()); verifyNoInteractions(storage);
        } finally { Thread.interrupted(); }
    }
}
