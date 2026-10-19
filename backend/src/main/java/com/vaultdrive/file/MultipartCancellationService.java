package com.vaultdrive.file;

import com.vaultdrive.file.MultipartCancellationTransactions.*;
import com.vaultdrive.storage.ObjectStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Explicit bounded operations only. No retries, absence probes, object deletion or scheduler. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class MultipartCancellationService {
    private final MultipartCancellationTransactions transactions;
    private final ObjectStorageService storage;

    public MultipartCancellationService(MultipartCancellationTransactions transactions, ObjectStorageService storage) {
        this.transactions = transactions; this.storage = storage;
    }

    public Cancellation cancel(UUID actor, UUID id) {
        Cancellation result = advance(actor, transactions.cancel(actor, id));
        transactions.authorizeResponse(actor, id);
        return result;
    }
    public Cancellation recover(UUID actor, UUID id) {
        Cancellation result = advance(actor, transactions.authorizeRecovery(actor, id));
        transactions.authorizeResponse(actor, id);
        return result;
    }
    public Cancellation get(UUID actor, UUID id) { return transactions.authorizeRecovery(actor, id); }

    private Cancellation advance(UUID actor, Cancellation retained) {
        UUID resourceOwner = retained.session().ownerId();
        Permit permit;
        try { permit = transactions.claim(actor, retained.session().id()); }
        catch (RuntimeException uncertainPermissionCommit) {
            // Intent is already durable. A lost claim acknowledgement confers no ephemeral permission.
            try { return transactions.retained(resourceOwner, retained.session().id()); }
            catch (RuntimeException stillUnavailable) { return retained; }
        }
        if (!permit.permitted()) return permit.cancellation();
        try {
            storage.abortMultipart(permit.target(), permit.cancellation().session().uploadId());
            return transactions.acknowledge(resourceOwner, permit);
        } catch (RuntimeException uncertain) {
            try { return transactions.unavailable(resourceOwner, retained.session().id()); }
            catch (RuntimeException databaseUnavailable) {
                try { return transactions.retained(resourceOwner, retained.session().id()); }
                catch (RuntimeException stillUnavailable) { return permit.cancellation(); }
            }
        }
    }

    public record ExpiryPage(List<Expiry> results, UUID nextCursor) { }

    /** PostgreSQL-only expiry; it decides abandonment but never dispatches even a first abort. */
    public ExpiryPage expire(UUID after, int limit) {
        MultipartCancellationTransactions.requireLimit(limit);
        List<Candidate> page = transactions.candidates(after, limit);
        var results = new ArrayList<Expiry>();
        for (Candidate c : page) {
            try { results.add(transactions.expire(c)); }
            catch (RuntimeException unavailable) { results.add(new Expiry(c.id(), "BLOCKED", "DATABASE_UNAVAILABLE")); }
        }
        return new ExpiryPage(List.copyOf(results), page.size() == limit ? page.getLast().id() : null);
    }
}
