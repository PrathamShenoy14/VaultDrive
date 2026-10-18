package com.vaultdrive.file;

import com.vaultdrive.file.MultipartCompletionTransactions.*;
import com.vaultdrive.file.MultipartSessionService.Session;
import com.vaultdrive.storage.*;
import com.vaultdrive.storage.MultipartStorage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** No storage call can inherit a database transaction. Recovery never redispatches an existing dispatch ID. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class MultipartCompletionService {
    private final MultipartCompletionTransactions transactions;
    private final ObjectStorageService storage;

    public MultipartCompletionService(MultipartCompletionTransactions transactions, ObjectStorageService storage) {
        this.transactions = transactions; this.storage = storage;
    }

    public Session complete(UUID owner, UUID id) {
        Snapshot s = transactions.authorize(owner, id);
        if (s.session().state().equals("OPEN")) { s = transactions.freeze(owner, s, validateManifest(s)); }
        return advance(owner, s, false);
    }

    public Session recover(UUID owner, UUID id) {
        Snapshot s = transactions.authorize(owner, id);
        if (!s.session().state().equals("COMPLETION_DECIDED")) { throw new MultipartConflictException(); }
        return advance(owner, s, true);
    }

    private Session advance(UUID owner, Snapshot initial, boolean recover) {
        UUID id = initial.session().id();
        if (initial.manifest().isEmpty()) { return initial.session(); } // Old M1 decision supplies no M3 dispatch authority.
        if (initial.session().completion().receiptRecorded()) { return finalizeReady(owner, initial.session()); }
        Permit permit = transactions.claim(owner, id);
        Snapshot s = permit.snapshot();
        if (s.session().completion().receiptRecorded()) { return finalizeReady(owner, s.session()); }
        if (permit.permitted()) {
            CompletionAck ack;
            try { ack = storage.completeMultipart(s.target(), s.session().uploadId(), s.manifest(), s.expectedSha256()); }
            catch (RuntimeException uncertain) { return pending(owner, s.session(), "UNKNOWN", "STORAGE_UNAVAILABLE"); }
            if (ack == null || ack.etag() == null || ack.etag().isBlank()
                    || !Objects.equals(ack.sha256(), s.expectedSha256()) || !"COMPOSITE".equals(ack.checksumType())) {
                return pending(owner, s.session(), "BLOCKED", "EVIDENCE_MISMATCH");
            }
            return observe(owner, s, Evidence.COMPLETE_HEAD, ack.etag());
        }
        if (!recover || s.session().completion().dispatchId() == null) { return s.session(); }
        Permit probe = transactions.probe(owner, id);
        if (!probe.permitted()) {
            return probe.snapshot().session().completion().receiptRecorded()
                    ? finalizeReady(owner, probe.snapshot().session()) : probe.snapshot().session();
        }
        return observe(owner, probe.snapshot(), Evidence.HEAD_RECOVERY, null);
    }

    private Session observe(UUID owner, Snapshot s, Evidence kind, String acknowledgedEtag) {
        CompletedObject object;
        try { object = storage.inspectMultipartObject(s.target()); }
        catch (RuntimeException unavailable) { return pending(owner, s.session(), "UNKNOWN", "STORAGE_UNAVAILABLE"); }
        if (!MultipartCompletionTransactions.matches(s, object)
                || (acknowledgedEtag != null && !acknowledgedEtag.equals(object.etag()))) {
            return pending(owner, s.session(), "BLOCKED", "EVIDENCE_MISMATCH");
        }
        Session receipt;
        try { receipt = transactions.recordReceipt(owner, s, object, kind); }
        catch (RuntimeException uncertainCommit) { return pending(owner, s.session(), "UNKNOWN", "STORAGE_UNAVAILABLE"); }
        return finalizeReady(owner, receipt);
    }

    private Session finalizeReady(UUID owner, Session retained) {
        try { return transactions.finalizeReady(owner, retained.id()); }
        catch (RuntimeException blocked) { return pending(owner, retained, "BLOCKED", "FINALIZATION_BLOCKED"); }
    }

    private Session pending(UUID owner, Session retained, String disposition, String reason) {
        try { return transactions.note(owner, retained.id(), disposition, reason); }
        catch (RuntimeException databaseUnavailable) {
            // A lost database acknowledgement must not invent rollback or another storage dispatch.
            try { return transactions.retained(owner, retained.id()); }
            catch (RuntimeException stillUnavailable) { return retained; }
        }
    }

    private List<ManifestPart> validateManifest(Snapshot s) {
        var parts = new ArrayList<ManifestPart>();
        int marker = 0;
        for (int pageNumber = 0; pageNumber < 7; pageNumber++) {
            Page page = storage.listParts(s.target(), s.session().uploadId(), marker, 100);
            require(page != null && page.parts() != null && page.parts().size() <= 100);
            for (Part part : page.parts()) {
                int number = parts.size() + 1;
                require(part != null && number <= s.registered().size() && part.partNumber() == number);
                var expected = s.registered().get(number - 1);
                require(expected.partNumber() == number && part.size() == expected.length()
                        && part.size() == Math.min(MultipartSessionService.PART_SIZE,
                            s.session().declaredSize() - (number - 1L) * MultipartSessionService.PART_SIZE)
                        && Objects.equals(part.sha256(), expected.sha256()) && part.etag() != null
                        && !part.etag().isBlank() && part.etag().length() <= 1024
                        && part.etag().chars().noneMatch(Character::isISOControl));
                parts.add(new ManifestPart(number, expected.length(), expected.sha256(), expected.registrationId(), part.etag()));
            }
            if (page.nextMarker() == null) {
                require(parts.size() == s.session().partCount());
                return List.copyOf(parts);
            }
            require(!page.parts().isEmpty() && page.nextMarker() == parts.size() && page.nextMarker() > marker
                    && page.nextMarker() < s.session().partCount());
            marker = page.nextMarker();
        }
        throw new MultipartConflictException(); // Incomplete/ambiguous pagination is never a completion manifest.
    }
    private static void require(boolean valid) { if (!valid) { throw new MultipartConflictException(); } }
}
