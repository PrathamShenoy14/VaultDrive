package com.vaultdrive.file;

import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.storage.FileStorageIdentityService;
import com.vaultdrive.storage.StorageKeyGenerator;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** M1 PostgreSQL-only boundary. Decision identities confer no storage dispatch authority. */
@Service
@Transactional(timeout = 10)
public class MultipartSessionService {
    public static final int PART_SIZE = 8 * 1024 * 1024;
    public static final long MAX_SIZE = 5L * 1024 * 1024 * 1024;
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final UserRepository users;
    private final FolderAccessValidator folders;
    private final FileNameValidator names;
    private final StoredFileRepository files;
    private final FileStorageIdentityService allocations;
    private final StorageKeyGenerator keys;

    public MultipartSessionService(EntityManager em, HierarchyCoordinator hierarchy, UserRepository users,
            FolderAccessValidator folders, FileNameValidator names, StoredFileRepository files,
            FileStorageIdentityService allocations, StorageKeyGenerator keys) {
        this.em = em;
        this.hierarchy = hierarchy;
        this.users = users;
        this.folders = folders;
        this.names = names;
        this.files = files;
        this.allocations = allocations;
        this.keys = keys;
    }

    public record Request(UUID folderId, String name, long size, String contentType) { }
    public record Session(UUID id, UUID fileId, String state, long revision, UUID initiationId,
            String uploadId, UUID decisionId, Instant createdAt, Instant expiresAt) { }
    public enum Decision { COMPLETE, ABANDON }

    public Session reserve(UUID owner, String idempotencyKey, Request request) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(request);
        if (idempotencyKey == null || !idempotencyKey.matches("[!-~]{1,128}")) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        String name = names.validateAndNormalize(request.name());
        if (request.size() < 1 || request.size() > MAX_SIZE) {
            throw new IllegalArgumentException("Multipart size must be positive and at most five GiB");
        }
        String type = request.contentType() == null ? "application/octet-stream" : request.contentType().trim();
        if (type.isEmpty() || type.length() > 255 || type.chars().anyMatch(Character::isISOControl)
                || !MediaType.parseMediaType(type).isConcrete()) {
            throw new IllegalArgumentException("Invalid declared content type");
        }
        String fingerprint = fingerprint(request.folderId(), name, request.size(), type);
        bounds();
        hierarchy.acquireShared(owner);
        if (!users.existsById(owner)) { throw absent(); }
        // Separate two-int advisory namespace, acquired only before creating resource rows.
        // Collisions conservatively serialize reservations; no existing-resource path takes this lock.
        em.createNativeQuery("SELECT pg_advisory_xact_lock(1297109833, hashtext(:identity))")
                .setParameter("identity", owner + ":" + idempotencyKey).getSingleResult();
        List<?> existing = em.createNativeQuery("SELECT id, request_fingerprint FROM upload_sessions "
                + "WHERE owner_id = :owner AND idempotency_key = :key")
                .setParameter("owner", owner).setParameter("key", idempotencyKey).getResultList();
        if (!existing.isEmpty()) {
            Object[] row = (Object[]) existing.getFirst();
            if (!fingerprint.equals(row[1])) { throw conflict(); }
            // Replay retains its original identity/deadline even if expired or ancestry changed.
            return read((UUID) row[0]);
        }
        if (request.folderId() != null) { folders.requireAccessibleFolder(owner, request.folderId()); }
        folders.requireNoPendingPurgeInAncestry(owner, request.folderId());
        if ((Boolean) em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM files WHERE owner_id = :owner
                    AND folder_id IS NOT DISTINCT FROM cast(:folder AS uuid) AND name = :name
                    AND deleted_at IS NULL AND status IN ('UPLOADING','READY'))
                """).setParameter("owner", owner).setParameter("folder", request.folderId())
                .setParameter("name", name).getSingleResult()) {
            throw new DuplicateFileNameException("A file with this name already exists");
        }
        UUID fileId = UUID.randomUUID();
        StoredFile file = files.saveAndFlush(new StoredFile(fileId, owner, request.folderId(), name,
                keys.generateFileKey(owner, fileId), type, request.size()));
        allocations.reserveMultipart(file);
        UUID id = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO upload_sessions(id, file_id, owner_id, original_folder_id, original_file_version,
                    storage_key, storage_namespace, endpoint, region, bucket, file_name, declared_size,
                    declared_type, part_size, part_count, idempotency_key, request_fingerprint)
                SELECT :id, file_id, owner_id, :folder, :version, storage_key, storage_namespace,
                    endpoint, region, bucket, :name, :size, :type, :partSize, :count, :key, :fingerprint
                FROM file_storage_objects WHERE file_id = :file
                """).setParameter("id", id).setParameter("file", fileId).setParameter("folder", request.folderId())
                .setParameter("version", file.getVersion()).setParameter("name", name)
                .setParameter("size", request.size()).setParameter("type", type).setParameter("partSize", PART_SIZE)
                .setParameter("count", (request.size() + PART_SIZE - 1) / PART_SIZE)
                .setParameter("key", idempotencyKey).setParameter("fingerprint", fingerprint).executeUpdate();
        return read(id);
    }

    public Session beginInitiation(UUID owner, UUID id, long revision, UUID initiationId) {
        Objects.requireNonNull(initiationId);
        Session session = lock(owner, id);
        if (initiationId.equals(session.initiationId())) { return session; }
        require(session.revision() == revision && session.state().equals("RESERVED"));
        em.createNativeQuery("UPDATE file_storage_objects SET write_started = true WHERE file_id = :file")
                .setParameter("file", session.fileId()).executeUpdate();
        em.createNativeQuery("UPDATE upload_sessions SET state = 'INITIATING', initiation_id = :initiation, "
                + "revision = revision + 1 WHERE id = :id")
                .setParameter("initiation", initiationId).setParameter("id", id).executeUpdate();
        return read(id);
    }

    /** Future storage adapter may record a known Create result; M1 never invokes that adapter. */
    public Session recordUploadId(UUID owner, UUID id, long revision, UUID initiationId, String uploadId) {
        if (uploadId == null || uploadId.isBlank() || uploadId.length() > 2048) {
            throw new IllegalArgumentException("Invalid upload identity");
        }
        Session session = lock(owner, id);
        require(Objects.equals(session.initiationId(), initiationId) && initiationId != null);
        if (uploadId.equals(session.uploadId())) { return session; }
        require(session.revision() == revision && session.state().equals("INITIATING"));
        em.createNativeQuery("UPDATE upload_sessions SET state = 'OPEN', garage_upload_id = :upload, "
                + "revision = revision + 1 WHERE id = :id")
                .setParameter("upload", uploadId).setParameter("id", id).executeUpdate();
        return read(id);
    }

    public Session decide(UUID owner, UUID id, long revision, Decision decision, UUID decisionId) {
        Objects.requireNonNull(decision);
        Objects.requireNonNull(decisionId);
        Session session = lock(owner, id);
        String state = decision == Decision.COMPLETE ? "COMPLETION_DECIDED" : "ABANDONMENT_DECIDED";
        if (session.state().equals(state) && decisionId.equals(session.decisionId())) { return session; }
        require(session.decisionId() == null && session.revision() == revision);
        require(decision == Decision.ABANDON || session.state().equals("OPEN"));
        em.createNativeQuery("UPDATE upload_sessions SET state = :state, decision_id = :decision, "
                + "decided_at = clock_timestamp(), revision = revision + 1 WHERE id = :id")
                .setParameter("state", state).setParameter("decision", decisionId).setParameter("id", id).executeUpdate();
        return read(id);
    }

    private Session lock(UUID owner, UUID id) {
        bounds();
        hierarchy.acquireShared(owner);
        // Pre-row-lock read is immutable, owner-scoped identity only.
        List<?> identities = em.createNativeQuery("SELECT file_id FROM upload_sessions WHERE id = :id AND owner_id = :owner")
                .setParameter("id", id).setParameter("owner", owner).getResultList();
        if (identities.isEmpty()) { throw absent(); }
        UUID file = (UUID) identities.getFirst();
        em.createNativeQuery("SELECT id FROM files WHERE id = :file FOR UPDATE").setParameter("file", file).getResultList();
        em.createNativeQuery("SELECT file_id FROM file_storage_objects WHERE file_id = :file FOR UPDATE")
                .setParameter("file", file).getResultList();
        em.createNativeQuery("SELECT id FROM upload_sessions WHERE id = :id FOR UPDATE").setParameter("id", id).getSingleResult();
        if (!(Boolean) em.createNativeQuery("SELECT multipart_session_eligible(s) FROM upload_sessions s WHERE id = :id")
                .setParameter("id", id).getSingleResult()) { throw absent(); }
        return read(id);
    }

    private Session read(UUID id) {
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT id, file_id, state, revision, initiation_id, garage_upload_id, decision_id, created_at, expires_at
                FROM upload_sessions WHERE id = :id
                """).setParameter("id", id).getSingleResult();
        return new Session((UUID) r[0], (UUID) r[1], (String) r[2], ((Number) r[3]).longValue(),
                (UUID) r[4], (String) r[5], (UUID) r[6], instant(r[7]), instant(r[8]));
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) { return instant; }
        if (value instanceof OffsetDateTime time) { return time.toInstant(); }
        return ((java.sql.Timestamp) value).toInstant();
    }

    private static String fingerprint(UUID folder, String name, long size, String type) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : List.of("DIRECT_MULTIPART:M1:8388608:24h", String.valueOf(folder), name,
                    Long.toString(size), type)) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void require(boolean valid) { if (!valid) { throw conflict(); } }
    private static IllegalStateException conflict() { return new IllegalStateException("Multipart request conflicts with retained identity or decision"); }
    private static FileNotFoundException absent() { return new FileNotFoundException("Upload session not found"); }
    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
    }
}
