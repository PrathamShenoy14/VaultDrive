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

    public Cancellation cancel(UUID owner, UUID id) { return advance(owner, transactions.cancel(owner, id)); }
    public Cancellation recover(UUID owner, UUID id) { return advance(owner, transactions.authorizeRecovery(owner, id)); }
    public Cancellation get(UUID owner, UUID id) { return transactions.authorizeRecovery(owner, id); }

    private Cancellation advance(UUID owner, Cancellation retained) {
        Permit permit;
        try { permit = transactions.claim(owner, retained.session().id()); }
        catch (RuntimeException uncertainPermissionCommit) {
            // Intent is already durable. A lost claim acknowledgement confers no ephemeral permission.
            try { return transactions.retained(owner, retained.session().id()); }
            catch (RuntimeException stillUnavailable) { return retained; }
        }
        if (!permit.permitted()) return permit.cancellation();
        try {
            storage.abortMultipart(permit.target(), permit.cancellation().session().uploadId());
            return transactions.acknowledge(owner, permit);
        } catch (RuntimeException uncertain) {
            try { return transactions.unavailable(owner, retained.session().id()); }
            catch (RuntimeException databaseUnavailable) {
                try { return transactions.retained(owner, retained.session().id()); }
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
