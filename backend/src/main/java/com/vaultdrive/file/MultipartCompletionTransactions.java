package com.vaultdrive.file;

import com.vaultdrive.activity.ResourceActivityService;

import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.storage.*;
import com.vaultdrive.storage.MultipartStorage.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

/** Short independent commits. Lock order: hierarchy -> file -> allocation -> session -> parts -> completion -> receipt. */
@Service
@Transactional(timeout = 10)
public class MultipartCompletionTransactions {
    private final EntityManager em;
    private final MultipartSessionService sessions;
    private final StoredFileRepository files;
    private final StorageBinding binding;
    private final ObjectMapper mapper;
    private final ResourceActivityService activity;

    public MultipartCompletionTransactions(EntityManager em, MultipartSessionService sessions,
            StoredFileRepository files, StorageBinding binding, ObjectMapper mapper,
            ResourceActivityService activity) {
        this.em = em; this.sessions = sessions; this.files = files; this.binding = binding; this.mapper = mapper;
        this.activity = activity;
    }

    public record Snapshot(Session session, StorageTarget target, List<RegisteredPart> registered,
            List<ManifestPart> manifest, String manifestHash, String expectedSha256, String contentType) { }
    public record Permit(Snapshot snapshot, boolean permitted) { }
    public enum Evidence { COMPLETE_HEAD, HEAD_RECOVERY }

    public Snapshot authorize(UUID actor, UUID id) {
        Session session = sessions.get(actor, id); // Public actor/access check before external observations.
        require(List.of("OPEN", "COMPLETION_DECIDED").contains(session.state()));
        Snapshot snapshot = snapshot(session);
        if (session.state().equals("OPEN")) {
            require(live(id) && binding.matches(snapshot.target()));
            require(snapshot.registered().size() == session.partCount());
        }
        return snapshot;
    }

    public Snapshot freeze(UUID actor, Snapshot expected, List<ManifestPart> manifest) {
        Session current = sessions.get(actor, expected.session().id());
        Snapshot fresh = snapshot(current);
        // Concurrent identical HTTP requests observe the first permanent decision/manifest.
        if (current.state().equals("COMPLETION_DECIDED")) { return fresh; }
        require(current.state().equals("OPEN") && current.revision() == expected.session().revision()
                && fresh.target().equals(expected.target()) && binding.matches(fresh.target())
                && fresh.registered().equals(expected.registered()) && live(current.id()));
        UUID decision = UUID.randomUUID();
        sessions.decide(actor, current.id(), current.revision(), Decision.COMPLETE, decision);
        em.createNativeQuery("INSERT INTO multipart_completions(session_id, decision_id, garage_upload_id, manifest, activity_actor_id, activity_actor_display_name) "
                + "VALUES (:id,:decision,:upload,cast(:manifest AS jsonb),:actor,"
                + "coalesce((SELECT display_name FROM users WHERE id = :actor), '[deleted account]'))")
                .setParameter("actor", actor)
                .setParameter("id", current.id()).setParameter("decision", decision).setParameter("upload", current.uploadId())
                .setParameter("manifest", mapper.writeValueAsString(manifest)).executeUpdate();
        return snapshot(sessions.lockForEvidence(current.ownerId(), current.id()));
    }

    /** A newly committed single-assignment dispatch ID is the only permission to call Complete. */
    public Permit claim(UUID actor, UUID id) {
        Session admitted = sessions.authorizeCaller(actor, id);
        UUID owner = admitted.ownerId();
        Snapshot s = locked(owner, id);
        if (s.manifest().isEmpty() || s.session().completion().receiptRecorded()
                || s.session().completion().dispatchId() != null) { return new Permit(s, false); }
        String blocker = !binding.matches(s.target()) ? "IDENTITY_MISMATCH"
                : !eligible(id) ? "FINALIZATION_BLOCKED" : !live(id) ? "AUTHORIZATION_EXPIRED" : null;
        if (blocker != null) { transition(id, "BLOCKED", blocker); return new Permit(locked(owner, id), false); }
        int changed = em.createNativeQuery("UPDATE multipart_completions SET dispatch_id = :dispatch, "
                + "disposition = 'UNKNOWN', reason = 'DISPATCH_UNCERTAIN', revision = revision + 1 "
                + "WHERE session_id = :id AND dispatch_id IS NULL")
                .setParameter("dispatch", UUID.randomUUID()).setParameter("id", id).executeUpdate();
        require(changed == 1);
        return new Permit(locked(owner, id), true);
    }

    /** Repeatable read-only provider probe permission; it never confers completion dispatch authority. */
    public Permit probe(UUID actor, UUID id) {
        Session admitted = sessions.authorizeCaller(actor, id);
        UUID owner = admitted.ownerId();
        Snapshot s = locked(owner, id);
        if (s.manifest().isEmpty() || s.session().completion().receiptRecorded()
                || s.session().completion().dispatchId() == null) { return new Permit(s, false); }
        String blocker = !binding.matches(s.target()) ? "IDENTITY_MISMATCH"
                : !eligible(id) ? "FINALIZATION_BLOCKED" : null;
        if (blocker != null) { transition(id, "BLOCKED", blocker); return new Permit(locked(owner, id), false); }
        return new Permit(s, true); // Expiry does not invalidate positive evidence for an existing dispatcher.
    }

    public Session recordReceipt(UUID owner, Snapshot expected, CompletedObject observed, Evidence kind) {
        Snapshot s = locked(owner, expected.session().id());
        require(!s.manifest().isEmpty() && s.target().equals(expected.target())
                && Objects.equals(s.manifestHash(), expected.manifestHash())
                && Objects.equals(s.session().completion().dispatchId(), expected.session().completion().dispatchId())
                && matches(s, observed));
        if (s.session().completion().receiptRecorded()) {
            require((Boolean) em.createNativeQuery("SELECT etag = :etag AND sha256 = :sha AND size_bytes = :size "
                    + "FROM multipart_completion_receipts WHERE session_id = :id")
                    .setParameter("etag", observed.etag()).setParameter("sha", observed.sha256())
                    .setParameter("size", observed.size()).setParameter("id", s.session().id()).getSingleResult());
            return s.session();
        }
        em.createNativeQuery("""
                INSERT INTO multipart_completion_receipts(session_id, file_id, decision_id, dispatch_id,
                    initiation_id, garage_upload_id, storage_identity, manifest_hash, size_bytes, sha256,
                    etag, content_type, object_metadata, evidence_kind)
                SELECT s.id, s.file_id, s.decision_id, c.dispatch_id, s.initiation_id, s.garage_upload_id,
                    multipart_storage_identity(s), c.manifest_hash, :size, :sha, :etag, :type, cast(:metadata AS jsonb), :kind
                FROM upload_sessions s JOIN multipart_completions c ON c.session_id = s.id WHERE s.id = :id
                """).setParameter("id", s.session().id()).setParameter("size", observed.size()).setParameter("sha", observed.sha256())
                .setParameter("etag", observed.etag()).setParameter("type", observed.contentType())
                .setParameter("metadata", mapper.writeValueAsString(observed.metadata())).setParameter("kind", kind.name()).executeUpdate();
        transition(s.session().id(), "STORAGE_COMPLETE", null);
        return sessions.lockForEvidence(owner, s.session().id());
    }

    public Session finalizeReady(UUID owner, UUID id) {
        Snapshot s = locked(owner, id);
        if (s.session().completion() == null || !s.session().completion().receiptRecorded()
                || s.session().completion().ready()) { return s.session(); }
        if (!binding.matches(s.target())) { transition(id, "BLOCKED", "IDENTITY_MISMATCH"); }
        else if (alreadyReady(s.session())) { transition(id, "READY", null); }
        else if (!eligible(id)) { transition(id, "BLOCKED", "FINALIZATION_BLOCKED"); }
        else {
            long version = ((Number) em.createNativeQuery("SELECT original_file_version FROM upload_sessions WHERE id = :id")
                    .setParameter("id", id).getSingleResult()).longValue();
            require(files.transitionUploadStatus(s.session().fileId(), owner, FileStatus.UPLOADING, version, FileStatus.READY, Instant.now()) == 1);
            activity.uploadCompleted(s.session().fileId());
            transition(id, "READY", null);
        }
        return sessions.lockForEvidence(owner, id);
    }

    public Session note(UUID owner, UUID id, String disposition, String reason) {
        Snapshot s = locked(owner, id);
        if (s.session().completion() != null && !s.session().completion().ready()
                && (!s.session().completion().receiptRecorded() || disposition.equals("BLOCKED"))) { transition(id, disposition, reason); }
        return sessions.lockForEvidence(owner, id);
    }

    /** Result reload for a request authorized before a concurrent folder barrier; no new capability. */
    public Session retained(UUID owner, UUID id) { return sessions.lockForEvidence(owner, id); }

    public void authorizeResponse(UUID actor, UUID id) { sessions.authorizeCaller(actor, id); }

    public static boolean matches(Snapshot s, CompletedObject o) {
        return o != null && o.size() == s.session().declaredSize() && Objects.equals(o.sha256(), s.expectedSha256())
                && o.etag() != null && !o.etag().isBlank() && o.etag().length() <= 1024
                && o.etag().chars().noneMatch(Character::isISOControl) && Objects.equals(o.contentType(), s.contentType())
                && o.metadata() != null && s.session().id().toString().equals(o.metadata().get("vaultdrive-session"))
                && s.session().initiationId().toString().equals(o.metadata().get("vaultdrive-initiation"));
    }

    private Snapshot locked(UUID owner, UUID id) {
        Session s = sessions.lockForEvidence(owner, id);
        em.createNativeQuery("SELECT session_id FROM multipart_completions WHERE session_id = :id FOR UPDATE")
                .setParameter("id", id).getResultList();
        return snapshot(s);
    }

    private Snapshot snapshot(Session session) {
        Object[] r = (Object[]) em.createNativeQuery("SELECT storage_namespace, endpoint, region, bucket, storage_key, declared_type "
                + "FROM upload_sessions WHERE id = :id").setParameter("id", session.id()).getSingleResult();
        @SuppressWarnings("unchecked") List<Object[]> rows = em.createNativeQuery("SELECT part_number,expected_length,expected_sha256,registration_id "
                + "FROM upload_session_parts WHERE session_id = :id ORDER BY part_number")
                .setParameter("id", session.id()).getResultList();
        List<RegisteredPart> registered = rows.stream().map(p -> new RegisteredPart(((Number) p[0]).intValue(),
                ((Number) p[1]).longValue(), (String) p[2], (UUID) p[3])).toList();
        List<?> completion = em.createNativeQuery("SELECT manifest::text,manifest_hash,expected_sha256 FROM multipart_completions WHERE session_id = :id")
                .setParameter("id", session.id()).getResultList();
        List<ManifestPart> manifest = List.of(); String hash = null, sha = null;
        if (!completion.isEmpty()) {
            Object[] c = (Object[]) completion.getFirst();
            manifest = List.of(mapper.readValue((String) c[0], ManifestPart[].class)); hash = (String) c[1]; sha = (String) c[2];
        }
        return new Snapshot(session, new StorageTarget((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4]),
                registered, manifest, hash, sha, (String) r[5]);
    }

    private boolean eligible(UUID id) {
        return (Boolean) em.createNativeQuery("SELECT multipart_session_eligible(s) FROM upload_sessions s WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }
    private boolean live(UUID id) {
        return (Boolean) em.createNativeQuery("SELECT expires_at > clock_timestamp() FROM upload_sessions WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }
    private boolean alreadyReady(Session s) {
        return (Boolean) em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM files WHERE id = :file AND status = 'READY' "
                + "AND owner_id = (SELECT owner_id FROM upload_sessions WHERE id = :id) "
                + "AND storage_key = (SELECT storage_key FROM upload_sessions WHERE id = :id))")
                .setParameter("file", s.fileId()).setParameter("id", s.id()).getSingleResult();
    }
    private void transition(UUID id, String disposition, String reason) {
        em.createNativeQuery("UPDATE multipart_completions SET disposition = :state, reason = :reason, revision = revision + 1 "
                + "WHERE session_id = :id AND ROW(disposition,reason) IS DISTINCT FROM ROW(cast(:state AS varchar),cast(:reason AS varchar))")
                .setParameter("id", id).setParameter("state", disposition).setParameter("reason", reason).executeUpdate();
    }
    private static void require(boolean valid) { if (!valid) { throw new MultipartConflictException(); } }
}
