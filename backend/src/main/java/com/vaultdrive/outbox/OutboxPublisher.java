package com.vaultdrive.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxClaimService claims;
    private final OutboxTransport transport;

    public OutboxPublisher(OutboxClaimService claims, OutboxTransport transport) {
        this.claims = claims;
        this.transport = transport;
    }

    public boolean publishNext() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox publishing must run outside a database transaction");
        }
        var next = claims.claimNext();
        if (next.isEmpty()) {
            return false;
        }
        ClaimedOutboxEvent claim = next.orElseThrow();
        if (!claims.owns(claim)) {
            return true;
        }
        PublishResult result;
        try {
            result = transport.publish(claim);
        } catch (RuntimeException exception) {
            result = PublishResult.BROKER_ERROR;
        }
        // A DB failure here deliberately leaves the committed lease for
        // recovery. Never undo a confirm or assume an uncertain DB outcome.
        boolean recorded = result == PublishResult.CONFIRMED
                ? claims.confirmed(claim) : claims.failed(claim, result);
        if (result != PublishResult.CONFIRMED || !recorded) {
            log.warn("Outbox event {} attempt {} result {} recorded {}",
                    claim.id(), claim.attemptCount(), result, recorded);
        }
        return true;
    }
}
