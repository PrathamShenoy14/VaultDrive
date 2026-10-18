package com.vaultdrive.file;

import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.storage.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/** Short commits; hierarchy -> fresh file -> allocation -> session -> cancellation. No storage I/O. */
@Service
@Transactional(timeout = 10)
public class MultipartCancellationTransactions {
    private final EntityManager em;
    private final MultipartSessionService sessions;
    private final StorageBinding binding;

    public MultipartCancellationTransactions(EntityManager em, MultipartSessionService sessions, StorageBinding binding) {
        this.em = em; this.sessions = sessions; this.binding = binding;
    }

    public record Cancellation(Session session, String cause, String disposition, String reason, UUID dispatchId,
            long revision, String evidenceKind, Instant acknowledgedAt) {
        public boolean cancelled() { return disposition.equals("CANCELLED"); }
    }
    public record Permit(Cancellation cancellation, StorageTarget target, boolean permitted) { }
    public record Candidate(UUID owner, UUID id) { }
    public record Expiry(UUID id, String disposition, String reason) { }

    public Cancellation cancel(UUID owner, UUID id) {
        Session s = sessions.get(owner, id); // Same fresh owner/ancestry barrier as all session APIs.
        require(!s.state().equals("COMPLETION_DECIDED"));
        return decide(owner, s, "CANCEL");
    }

    public Cancellation authorizeRecovery(UUID owner, UUID id) {
        Session s = sessions.get(owner, id);
        require(s.state().equals("ABANDONMENT_DECIDED"));
        // Adopt old M1 abandonment only through cancel(), never invent a completion/cancellation decision here.
        require(hasIntent(id));
        return read(s);
    }

    /** Scalar keyset only; each candidate must be independently locked and revalidated. */
    public List<Candidate> candidates(UUID after, int limit) {
        requireLimit(limit);
        em.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
        @SuppressWarnings("unchecked") List<Object[]> rows = em.createNativeQuery("SELECT owner_id,id FROM upload_sessions "
                + "WHERE decision_id IS NULL AND expires_at <= clock_timestamp() "
                + "AND (cast(:after AS uuid) IS NULL OR id > :after) ORDER BY id LIMIT :limit")
                .setParameter("after", after).setParameter("limit", limit).getResultList();
        return rows.stream().map(r -> new Candidate((UUID) r[0], (UUID) r[1])).toList();
    }

    public Expiry expire(Candidate c) {
        Session s = sessions.lockForEvidence(c.owner(), c.id());
        if (s.decisionId() != null) { return new Expiry(s.id(), "SKIPPED", "DECISION_EXISTS"); }
        if (!(Boolean) em.createNativeQuery("SELECT expires_at <= clock_timestamp() FROM upload_sessions WHERE id = :id")
                .setParameter("id", s.id()).getSingleResult()) { return new Expiry(s.id(), "SKIPPED", "NOT_EXPIRED"); }
        if (!eligible(s.id())) { return new Expiry(s.id(), "BLOCKED", "LIFECYCLE_BLOCKED"); }
        Cancellation result = decide(c.owner(), s, "EXPIRED");
        return new Expiry(s.id(), result.disposition(), result.reason());
    }

    private Cancellation decide(UUID owner, Session s, String cause) {
        if (hasIntent(s.id())) { return read(s); }
        if (s.decisionId() == null) { s = sessions.decide(owner, s.id(), s.revision(), Decision.ABANDON, UUID.randomUUID()); }
        require(s.state().equals("ABANDONMENT_DECIDED"));
        em.createNativeQuery("INSERT INTO multipart_cancellations(session_id,decision_id,initiation_id,garage_upload_id,storage_identity,cause) "
                + "SELECT id,decision_id,initiation_id,garage_upload_id,multipart_storage_identity(s),:cause FROM upload_sessions s WHERE id = :id")
                .setParameter("id", s.id()).setParameter("cause", cause).executeUpdate();
        if (s.initiationId() == null) { transition(s.id(), "CANCELLED", null, "NEVER_INITIATED"); }
        return read(s);
    }

    /** Only a new, independently committed dispatch UUID grants an ephemeral abort permit. */
    public Permit claim(UUID owner, UUID id) {
        Session s = sessions.lockForEvidence(owner, id);
        require(s.state().equals("ABANDONMENT_DECIDED") && hasIntent(id));
        em.createNativeQuery("SELECT session_id FROM multipart_cancellations WHERE session_id = :id FOR UPDATE")
                .setParameter("id", id).getSingleResult();
        Cancellation c = read(s);
        StorageTarget target = target(id);
        if (c.cancelled() || c.dispatchId() != null) { return new Permit(c, target, false); }
        String blocker = !binding.matches(target) ? "IDENTITY_MISMATCH" : !eligible(id) ? "LIFECYCLE_BLOCKED"
                : s.uploadId() == null ? "UPLOAD_ID_UNKNOWN" : !verifiedInitiation(id) ? "INITIATION_UNVERIFIED" : null;
        if (blocker != null) { transition(id, "BLOCKED", blocker, null); return new Permit(read(s), target, false); }
        int changed = em.createNativeQuery("UPDATE multipart_cancellations SET dispatch_id = :dispatch, disposition = 'UNKNOWN', "
                + "reason = 'DISPATCH_UNCERTAIN', revision = revision + 1 WHERE session_id = :id AND dispatch_id IS NULL")
                .setParameter("dispatch", UUID.randomUUID()).setParameter("id", id).executeUpdate();
        require(changed == 1);
        return new Permit(read(s), target, true);
    }

    /** Records a fact even if folder trash/purge followed permission; never changes file or purge snapshots. */
    public Cancellation acknowledge(UUID owner, Permit permit) {
        Session s = sessions.lockForEvidence(owner, permit.cancellation().session().id());
        Cancellation c = read(s);
        require(permit.permitted() && Objects.equals(c.dispatchId(), permit.cancellation().dispatchId())
                && c.dispatchId() != null && target(s.id()).equals(permit.target()));
        if (!c.cancelled()) { transition(s.id(), "CANCELLED", null, "ABORT_ACK"); }
        return read(s);
    }

    public Cancellation unavailable(UUID owner, UUID id) {
        Session s = sessions.lockForEvidence(owner, id);
        Cancellation c = read(s);
        if (!c.cancelled()) { transition(id, "UNKNOWN", "STORAGE_UNAVAILABLE", null); }
        return read(s);
    }

    public Cancellation retained(UUID owner, UUID id) { return read(sessions.lockForEvidence(owner, id)); }

    private boolean hasIntent(UUID id) {
        return (Boolean) em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM multipart_cancellations WHERE session_id = :id)")
                .setParameter("id", id).getSingleResult();
    }
    private boolean eligible(UUID id) {
        return (Boolean) em.createNativeQuery("SELECT multipart_session_eligible(s) FROM upload_sessions s WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }
    private boolean verifiedInitiation(UUID id) {
        return (Boolean) em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM multipart_initiation_dispatches d "
                + "JOIN upload_sessions s ON s.id = d.session_id AND s.initiation_id = d.initiation_id WHERE s.id = :id)")
                .setParameter("id", id).getSingleResult();
    }
    private StorageTarget target(UUID id) {
        Object[] r = (Object[]) em.createNativeQuery("SELECT storage_namespace,endpoint,region,bucket,storage_key FROM upload_sessions WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        return new StorageTarget((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4]);
    }
    private Cancellation read(Session s) {
        Object[] r = (Object[]) em.createNativeQuery("SELECT cause,disposition,reason,dispatch_id,revision,evidence_kind,acknowledged_at "
                + "FROM multipart_cancellations WHERE session_id = :id").setParameter("id", s.id()).getSingleResult();
        Instant acknowledged = r[6] == null ? null : r[6] instanceof Instant i ? i : ((OffsetDateTime) r[6]).toInstant();
        return new Cancellation(s, (String) r[0], (String) r[1], (String) r[2], (UUID) r[3], ((Number) r[4]).longValue(), (String) r[5], acknowledged);
    }
    private void transition(UUID id, String state, String reason, String evidence) {
        em.createNativeQuery("UPDATE multipart_cancellations SET disposition = :state,reason = :reason,evidence_kind = :evidence, "
                + "revision = revision + 1 WHERE session_id = :id AND ROW(disposition,reason,evidence_kind) "
                + "IS DISTINCT FROM ROW(cast(:state AS varchar),cast(:reason AS varchar),cast(:evidence AS varchar))")
                .setParameter("id", id).setParameter("state", state).setParameter("reason", reason).setParameter("evidence", evidence).executeUpdate();
    }
    static void requireLimit(int limit) { if (limit < 1 || limit > 100) throw new IllegalArgumentException("Page size must be 1 through 100"); }
    private static void require(boolean valid) { if (!valid) throw new MultipartConflictException(); }
}
