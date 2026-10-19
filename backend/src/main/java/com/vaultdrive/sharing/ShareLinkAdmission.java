package com.vaultdrive.sharing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/** Fixed memory, process-wide limits; no attacker-keyed maps or forwarded-IP trust. */
@Component
public class ShareLinkAdmission {
    private final Semaphore requests;
    private final Semaphore downloads;
    private final int burst;
    private final int perSecond;
    private final LongSupplier ticker;
    private double tokens;
    private long last;

    @org.springframework.beans.factory.annotation.Autowired
    public ShareLinkAdmission(@Value("${vaultdrive.share-links.max-requests:16}") int maxRequests,
            @Value("${vaultdrive.share-links.max-downloads:4}") int maxDownloads,
            @Value("${vaultdrive.share-links.request-burst:60}") int burst,
            @Value("${vaultdrive.share-links.requests-per-second:30}") int perSecond) {
        this(maxRequests, maxDownloads, burst, perSecond, System::nanoTime);
    }

    ShareLinkAdmission(int maxRequests, int maxDownloads, int burst, int perSecond, LongSupplier ticker) {
        if (maxRequests < 1 || maxDownloads < 1 || burst < 1 || perSecond < 1) {
            throw new IllegalArgumentException("Share link admission limits must be positive");
        }
        this.requests = new Semaphore(maxRequests);
        this.downloads = new Semaphore(maxDownloads);
        this.burst = burst;
        this.perSecond = perSecond;
        this.tokens = burst;
        this.ticker = ticker;
        this.last = ticker.getAsLong();
    }

    synchronized boolean takeRequestToken() {
        long now = ticker.getAsLong();
        tokens = Math.min(burst, tokens + Math.max(0, now - last) / 1_000_000_000.0 * perSecond);
        last = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }

    Permit request() { return requests.tryAcquire() ? new Permit(requests) : null; }
    Permit download() { return downloads.tryAcquire() ? new Permit(downloads) : null; }

    static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean closed;
        Permit(Semaphore semaphore) { this.semaphore = semaphore; }
        @Override public void close() { if (!closed) { closed = true; semaphore.release(); } }
    }
}
