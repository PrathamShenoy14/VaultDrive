package com.vaultdrive.operations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Fixed component names only; successful idle polls count as evidence of a live control loop. */
@Component
public class ProcessingSignals {
    private final Map<String, Long> successes = new ConcurrentHashMap<>();
    public void success(String component) { successes.put(component, System.nanoTime()); }
    public boolean fresh(String component, Duration maximumAge) {
        Long last = successes.get(component);
        return last != null && System.nanoTime() - last <= maximumAge.toNanos();
    }
}
