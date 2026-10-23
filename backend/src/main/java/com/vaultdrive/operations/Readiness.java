package com.vaultdrive.operations;

import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.worker.JobWorker;
import com.vaultdrive.outbox.OutboxPublisherProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class Readiness implements org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
    public record Snapshot(boolean ready, Map<String, Boolean> components, Map<String, Long> work, long checkedAtNanos) { }
    private final OperationsProperties p;
    private final OperationalState state;
    private final ObjectStorageService storage;
    private final ConnectionFactory rabbit;
    private final OutboxPublisherProperties routes;
    private final ProcessingSignals signals;
    private final ApplicationContext context;
    private final ObjectProvider<JobWorker> worker;
    private volatile Snapshot snapshot = new Snapshot(false, Map.of(), Map.of(), 0);
    private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
    public Readiness(OperationsProperties p, OperationalState state, ObjectStorageService storage, ConnectionFactory rabbit,
            OutboxPublisherProperties routes, ProcessingSignals signals, ApplicationContext context, ObjectProvider<JobWorker> worker) {
        this.p=p; this.state=state; this.storage=storage; this.rabbit=rabbit; this.routes=routes;
        this.signals=signals; this.context=context; this.worker=worker;
    }
    public boolean ready() {
        Snapshot s = snapshot;
        return !closing.get() && p.probesEnabled() && s.ready() && System.nanoTime()-s.checkedAtNanos() <= p.staleAfter().toNanos();
    }
    public Snapshot snapshot() { return snapshot; }
    public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event) {
        if (event.getApplicationContext() == context) closing.set(true);
    }
    public void probe() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Dependency probes require no enclosing transaction");
        var components = new LinkedHashMap<String, Boolean>();
        Map<String, Long> work = Map.of();
        try { work = state.inspect(); components.put("postgresql", true); }
        catch (RuntimeException unavailable) { components.put("postgresql", false); }
        try { storage.checkAvailability(); components.put("garage", true); }
        catch (RuntimeException unavailable) { components.put("garage", false); }
        try (var connection = rabbit.createConnection()) {
            var channel = connection.createChannel(false);
            try {
                var purge = channel.queueDeclarePassive(routes.queue());
                var notifications = channel.queueDeclarePassive(routes.permissionQueue());
                components.put("rabbitmq", true);
                components.put("brokerConsumers", purge.getConsumerCount() > 0 && notifications.getConsumerCount() > 0);
            } finally { channel.close(); }
        } catch (Exception unavailable) { components.put("rabbitmq", false); }
        for (String name : List.of("purgeNotificationContainer", "permissionNotificationContainer")) {
            boolean live = context.containsBean(name) && context.getBean(name) instanceof SimpleMessageListenerContainer c
                    && c.isRunning() && c.getActiveConsumerCount() > 0;
            components.put(name, live);
        }
        for (String name : List.of("publisher", "reconciliation", "expiry", "finalization")) components.put(name, signals.fresh(name, p.staleAfter()));
        var jobs = worker.getIfAvailable();
        components.put("worker", jobs != null && jobs.operational(p.staleAfter()));
        // Retained business recovery can require client action; it must not remove that API's traffic.
        components.put("durableWork", work.entrySet().stream().filter(e -> !Set.of("unresolvedExpiry", "pendingRecovery").contains(e.getKey()))
                .allMatch(e -> e.getValue() == 0));
        snapshot = new Snapshot(components.values().stream().allMatch(Boolean::booleanValue), Map.copyOf(components), work, System.nanoTime());
    }
}
