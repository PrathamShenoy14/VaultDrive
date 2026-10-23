package com.vaultdrive.operations;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** Constant memory and no waiting queue. Limits are deliberately process-wide. */
@Component
public class RequestAdmission {
    public enum Lane { AUTHENTICATION, MULTIPART, DOWNLOADS, METADATA }
    private final Map<Lane, Bucket> buckets = new EnumMap<>(Lane.class);

    @org.springframework.beans.factory.annotation.Autowired
    public RequestAdmission(AdmissionProperties p) { this(p, System::nanoTime); }
    RequestAdmission(AdmissionProperties p, LongSupplier time) {
        buckets.put(Lane.AUTHENTICATION, new Bucket(p.authentication(), time));
        buckets.put(Lane.MULTIPART, new Bucket(p.multipart(), time));
        buckets.put(Lane.DOWNLOADS, new Bucket(p.downloads(), time));
        buckets.put(Lane.METADATA, new Bucket(p.metadata(), time));
    }

    public Permit acquire(Lane lane) { return buckets.get(lane).acquire(); }
    public Map<Lane, Counts> counts() {
        var result = new EnumMap<Lane, Counts>(Lane.class);
        buckets.forEach((lane, b) -> result.put(lane, new Counts(b.limit - b.slots.availablePermits(), b.rejected.sum())));
        return Map.copyOf(result);
    }
    public record Counts(int active, long rejected) { }
    public static final class Permit implements AutoCloseable {
        private final Semaphore slots;
        private final AtomicBoolean closed = new AtomicBoolean();
        Permit(Semaphore slots) { this.slots = slots; }
        public void close() { if (closed.compareAndSet(false, true)) slots.release(); }
    }
    private static final class Bucket {
        final Semaphore slots;
        final int limit;
        final AdmissionProperties.Policy policy;
        final LongSupplier time;
        final LongAdder rejected = new LongAdder();
        double tokens;
        long last;
        Bucket(AdmissionProperties.Policy policy, LongSupplier time) {
            this.policy = policy; this.time = time; this.limit = policy.concurrency();
            slots = new Semaphore(limit); tokens = policy.burst(); last = time.getAsLong();
        }
        synchronized Permit acquire() {
            long now = time.getAsLong();
            tokens = Math.min(policy.burst(), tokens + Math.max(0, now - last) / 1e9 * policy.perSecond());
            last = now;
            if (tokens < 1 || !slots.tryAcquire()) { rejected.increment(); return null; }
            tokens--; return new Permit(slots);
        }
    }
}
