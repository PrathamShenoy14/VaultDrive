package com.vaultdrive.file;

import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.storage.FileStorageIdentityService;
import com.vaultdrive.storage.StorageKeyGenerator;
import com.vaultdrive.storage.StorageBinding;
import com.vaultdrive.storage.StorageTarget;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;

/** Short PostgreSQL decisions only; storage I/O belongs to MultipartTransferService. */
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
    private final StorageBinding binding;

    public MultipartSessionService(EntityManager em, HierarchyCoordinator hierarchy, UserRepository users,
            FolderAccessValidator folders, FileNameValidator names, StoredFileRepository files,
            FileStorageIdentityService allocations, StorageKeyGenerator keys, StorageBinding binding) {
        this.em = em;
        this.hierarchy = hierarchy;
        this.users = users;
        this.folders = folders;
        this.names = names;
        this.files = files;
        this.allocations = allocations;
        this.keys = keys;
        this.binding = binding;
    }

    public record Request(UUID folderId, String name, long size, String contentType) { }
    public record Session(UUID id, UUID fileId, String state, long revision, UUID initiationId,
            String uploadId, UUID decisionId, Instant createdAt, Instant expiresAt,
            long declaredSize, int partSize, int partCount) { }
    public enum Decision { COMPLETE, ABANDON }
    public record PartExpectation(int partNumber, long length, String sha256) { }
    public record RegisteredPart(int partNumber, long length, String sha256, UUID registrationId) { }
    public record Transfer(Session session, StorageTarget target, String contentType, Instant authorizationDeadline,
            List<RegisteredPart> parts) { }
    public record Dispatch(Transfer transfer, boolean permitted) { }

    public Session get(UUID owner, UUID id) { return lock(owner, id); }

    /** Only the caller that commits this new record receives an ephemeral dispatch permit. */
    public Dispatch claimInitiation(UUID owner, UUID id) {
        Session session = lock(owner, id);
        Transfer transfer = transfer(session, List.of()); // Also reject binding drift before consuming permission.
        if (!session.state().equals("RESERVED")) { return new Dispatch(transfer, false); }
        requireLive(session);
        UUID initiation = UUID.randomUUID();
        session = beginInitiation(owner, id, session.revision(), initiation);
        em.createNativeQuery("INSERT INTO multipart_initiation_dispatches(session_id, initiation_id) VALUES (:id, :init)")
                .setParameter("id", id).setParameter("init", initiation).executeUpdate();
        return new Dispatch(transfer(session, List.of()), true);
    }

    public Transfer authorizeParts(UUID owner, UUID id, List<PartExpectation> requested) {
        Session session = lock(owner, id);
        requireOpen(session);
        if (requested == null || requested.isEmpty() || requested.size() > 100) {
            throw new IllegalArgumentException("Part batch must contain 1 through 100 parts");
        }
        var numbers = new HashSet<Integer>();
        for (PartExpectation part : requested) {
            if (part == null || part.partNumber() < 1 || part.partNumber() > session.partCount()
                    || !numbers.add(part.partNumber())
                    || !validSha256(part.sha256())) {
                throw new IllegalArgumentException("Invalid part geometry, duplicate number or SHA-256 (canonical base64 required)");
            }
        }
        var registered = new ArrayList<RegisteredPart>();
        for (PartExpectation part : requested.stream().sorted(Comparator.comparingInt(PartExpectation::partNumber)).toList()) {
            List<?> rows = em.createNativeQuery("SELECT expected_length, expected_sha256, registration_id FROM upload_session_parts "
                    + "WHERE session_id = :id AND part_number = :number FOR UPDATE")
                    .setParameter("id", id).setParameter("number", part.partNumber()).getResultList();
            UUID registration;
            if (rows.isEmpty()) {
                if (part.length() != Math.min(PART_SIZE, session.declaredSize() - (part.partNumber() - 1L) * PART_SIZE)) {
                    throw new IllegalArgumentException("Invalid fixed part geometry");
                }
                registration = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO upload_session_parts(session_id,part_number,expected_length,expected_sha256,registration_id) "
                        + "VALUES (:id,:number,:length,:sha,:registration)")
                        .setParameter("id", id).setParameter("number", part.partNumber()).setParameter("length", part.length())
                        .setParameter("sha", part.sha256()).setParameter("registration", registration).executeUpdate();
            } else {
                Object[] row = (Object[]) rows.getFirst();
                require(((Number) row[0]).longValue() == part.length() && row[1].equals(part.sha256()));
                registration = (UUID) row[2];
            }
            registered.add(new RegisteredPart(part.partNumber(), part.length(), part.sha256(), registration));
        }
        return transfer(session, List.copyOf(registered));
    }

    public Transfer authorizeListing(UUID owner, UUID id, int marker, int limit) {
        Session session = lock(owner, id);
        requireOpen(session);
        if (marker < 0 || marker > session.partCount() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid part marker or page size (1 through 100)");
        }
        return transfer(session, List.of());
    }

    private Transfer transfer(Session session, List<RegisteredPart> parts) {
        Object[] r = (Object[]) em.createNativeQuery("SELECT storage_namespace, endpoint, region, bucket, storage_key, declared_type, "
                + "extract(epoch from expires_at - clock_timestamp()) FROM upload_sessions WHERE id = :id")
                .setParameter("id", session.id()).getSingleResult();
        var target = new StorageTarget((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4]);
        require(binding.matches(target));
        Instant deadline = Instant.now().plusMillis(Math.max(0, Math.min(300_000,
                (long) (((Number) r[6]).doubleValue() * 1000))));
        if (session.expiresAt().isBefore(deadline)) { deadline = session.expiresAt(); }
        return new Transfer(session, target, (String) r[5], deadline, parts);
    }

    private void requireOpen(Session session) {
        require(session.state().equals("OPEN") && session.uploadId() != null);
        requireLive(session);
    }
    private void requireLive(Session session) {
        require((Boolean) em.createNativeQuery("SELECT expires_at > clock_timestamp() FROM upload_sessions WHERE id = :id")
                .setParameter("id", session.id()).getSingleResult());
    }
    private static boolean validSha256(String value) {
        if (value == null || value.length() != 44) { return false; }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length == 32 && Base64.getEncoder().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException invalid) { return false; }
    }

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

    /** Independently records a known Create result; a failed commit never permits another Create. */
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
                SELECT id, file_id, state, revision, initiation_id, garage_upload_id, decision_id, created_at, expires_at,
                    declared_size, part_size, part_count
                FROM upload_sessions WHERE id = :id
                """).setParameter("id", id).getSingleResult();
        return new Session((UUID) r[0], (UUID) r[1], (String) r[2], ((Number) r[3]).longValue(),
                (UUID) r[4], (String) r[5], (UUID) r[6], instant(r[7]), instant(r[8]),
                ((Number) r[9]).longValue(), ((Number) r[10]).intValue(), ((Number) r[11]).intValue());
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
    private static MultipartConflictException conflict() { return new MultipartConflictException(); }
    private static FileNotFoundException absent() { return new FileNotFoundException("Upload session not found"); }
    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
    }
}
