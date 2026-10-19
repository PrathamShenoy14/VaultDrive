package com.vaultdrive.sharing;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class ShareLinkAdmissionTest {
    @Test void boundsRateAndConcurrencyWithoutWaitingAndReleasesIdempotently() {
        AtomicLong time = new AtomicLong();
        var admission = new ShareLinkAdmission(1, 1, 2, 1, time::get);
        assertThat(admission.takeRequestToken()).isTrue();
        assertThat(admission.takeRequestToken()).isTrue();
        assertThat(admission.takeRequestToken()).isFalse();
        time.set(500_000_000);
        assertThat(admission.takeRequestToken()).isFalse();
        time.set(1_000_000_000);
        assertThat(admission.takeRequestToken()).isTrue();
        time.set(100_000_000_000L);
        assertThat(admission.takeRequestToken()).isTrue();
        assertThat(admission.takeRequestToken()).isTrue();
        assertThat(admission.takeRequestToken()).isFalse();
        var request = admission.request();
        var download = admission.download();
        assertThat(admission.request()).isNull();
        assertThat(admission.download()).isNull();
        request.close(); request.close(); download.close(); download.close();
        try (var next = admission.request(); var nextDownload = admission.download()) {
            assertThat(next).isNotNull(); assertThat(nextDownload).isNotNull();
            assertThat(admission.request()).isNull(); assertThat(admission.download()).isNull();
        }
    }

    @Test void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new ShareLinkAdmission(0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShareLinkAdmission(1, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShareLinkAdmission(1, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShareLinkAdmission(1, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
