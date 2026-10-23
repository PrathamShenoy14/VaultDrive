package com.vaultdrive.file;

import com.vaultdrive.activity.ResourceActivityService;
import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.sharing.ResourceAccessRepository;
import com.vaultdrive.sharing.ResourceRole;
import com.vaultdrive.storage.*;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/** PostgreSQL-only boundaries. Hierarchy -> idempotency (admission only) -> files in UUID order
 * -> allocations in UUID order -> operation; admission takes destination namespace before insert.
 * The quota trigger is the final existing-row accounting lock. No transaction spans storage I/O. */
@Service
@Transactional(timeout = 10)
public class FileCopyTransactions {
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final ResourceAccessRepository access;
    private final FileNameValidator names;
    private final StoredFileRepository files;
    private final StorageKeyGenerator keys;
    private final StorageBinding binding;
    private final ResourceActivityService activity;

    public FileCopyTransactions(EntityManager em, HierarchyCoordinator hierarchy, ResourceAccessRepository access,
            FileNameValidator names, StoredFileRepository files, StorageKeyGenerator keys, StorageBinding binding,
            ResourceActivityService activity) {
        this.em = em; this.hierarchy = hierarchy; this.access = access; this.names = names;
        this.files = files; this.keys = keys; this.binding = binding; this.activity = activity;
    }

    public record Request(UUID destinationFolderId, String name) { }
    public record Status(UUID id, UUID sourceFileId, UUID fileId, UUID destinationFolderId, String name,
                         String state, long revision, boolean storageVerified, Instant createdAt) {
        public boolean terminal() { return Set.of("READY", "CANCELLED", "FAILED").contains(state); }
    }
    public record Snapshot(Status status, UUID owner, long size, String contentType, StorageTarget source,
                           StorageTarget destination, String sourceEtag, boolean dispatched, String actorName) { }
    public record Permit(Snapshot snapshot, boolean permitted) { }

    public Status reserve(UUID actor, UUID source, String key, Request request) {
        if (request == null || key == null || !key.matches("[!-~]{1,128}")) { throw invalid(); }
        return reserve(actor, source, key, request, null, null, null);
    }

    /** Called only inside a coordinated folder batch; joins its checkpoint transaction. */
    public Status reserveFolderMember(UUID actor, UUID job, int ordinal) {
        bounds(); hierarchy.acquireExclusive(actor);
        Object[] m = (Object[]) em.createNativeQuery("SELECT m.source_id,m.destination_id,m.parent_id,m.name,m.size_bytes,m.content_type,j.actor_display_name "
                + "FROM folder_copy_members m JOIN folder_copy_jobs j ON j.id=m.job_id WHERE j.id=:job AND j.owner_id=:owner "
                + "AND j.state IN ('ACTIVE','BLOCKED','UNKNOWN') AND m.ordinal=:ordinal AND m.kind='FILE'")
                .setParameter("job",job).setParameter("owner",actor).setParameter("ordinal",ordinal).getSingleResult();
        requireOwner(actor,false,(UUID)m[0]);
        Object[] current=(Object[])em.createNativeQuery("SELECT size_bytes,content_type FROM files WHERE id=:id")
                .setParameter("id",m[0]).getSingleResult();
        if (!Objects.equals(current[0],m[4]) || !Objects.equals(current[1],m[5])) { throw conflict(); }
        return reserve(actor,(UUID)m[0],"folder:"+job+":"+m[0],new Request((UUID)m[2],(String)m[3]),job,(UUID)m[1],(String)m[6]);
    }

    private Status reserve(UUID actor, UUID source, String key, Request request, UUID job, UUID destinationId, String actorName) {
        String name = names.validateAndNormalize(request.name());
        bounds(); hierarchy.acquireShared(actor);
        em.createNativeQuery("SELECT pg_advisory_xact_lock(1129271385,hashtext(:key))")
                .setParameter("key", actor + ":" + key).getSingleResult();
        List<?> replay = em.createNativeQuery("SELECT id,source_file_id,file_id,folder_id,name FROM file_copy_operations WHERE owner_id=:owner AND idempotency_key=:key "
                + "AND folder_copy_job_id IS NOT DISTINCT FROM cast(:job AS uuid)")
                .setParameter("owner", actor).setParameter("key", key).setParameter("job",job).getResultList();
        if (!replay.isEmpty()) {
            Object[] r = (Object[]) replay.getFirst();
            if (!source.equals(r[1]) || !Objects.equals(request.destinationFolderId(),r[3]) || !name.equals(r[4])
                    || (destinationId!=null && !destinationId.equals(r[2]))) { throw conflict(); }
            return read(actor, (UUID) r[0]).status(); // Retained owner-only result; no new resource authority.
        }
        // Owner lock is known from the authenticated caller; do not lock a foreign resource.
        requireOwner(actor, false, source);
        lockFile(source, actor); lockAllocation(source, actor);
        requireOwner(actor, false, source); // Fresh after source-row acquisition (file move uses shared coordination).
        if (request.destinationFolderId() != null) {
            if (job == null) { requireOwner(actor, true, request.destinationFolderId()); }
            else if (!(Boolean)em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM pre_folder_copy_access(:owner,NULL,:folder))")
                    .setParameter("owner",actor).setParameter("folder",request.destinationFolderId()).getSingleResult()) { throw conflict(); }
        }
        Object[] src = (Object[]) em.createNativeQuery("SELECT size_bytes,content_type FROM files WHERE id=:id AND owner_id=:owner AND status='READY'")
                .setParameter("id",source).setParameter("owner",actor).getSingleResult();
        long size = ((Number) src[0]).longValue();
        if (size < 0 || size > MultipartSessionService.MAX_SIZE) { throw invalid(); }
        StorageTarget sourceTarget = target(source);
        if (!binding.matches(sourceTarget)) { throw conflict(); }
        hierarchy.acquireNamespaceExclusive(actor, request.destinationFolderId());
        if ((Boolean) em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM files WHERE owner_id=:owner AND folder_id IS NOT DISTINCT FROM cast(:folder AS uuid) "
                + "AND name=:name AND deleted_at IS NULL AND status IN ('UPLOADING','READY'))")
                .setParameter("owner",actor).setParameter("folder",request.destinationFolderId()).setParameter("name",name).getSingleResult()) {
            throw new DuplicateFileNameException("A file with this name already exists");
        }
        UUID fileId = destinationId == null ? UUID.randomUUID() : destinationId, id = UUID.randomUUID();
        StoredFile file = files.saveAndFlush(new StoredFile(fileId,actor,request.destinationFolderId(),name,
                keys.generateFileKey(actor,fileId),(String)src[1],size)); // Atomic quota reservation.
        StorageTarget dest = binding.target(file.getStorageKey());
        em.createNativeQuery("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,storage_namespace,endpoint,region,bucket,upload_protocol) "
                + "VALUES (:key,:file,:owner,:namespace,:endpoint,:region,:bucket,'COPY')")
                .setParameter("key",dest.key()).setParameter("file",fileId).setParameter("owner",actor)
                .setParameter("namespace",dest.namespace()).setParameter("endpoint",dest.endpoint()).setParameter("region",dest.region())
                .setParameter("bucket",dest.bucket()).executeUpdate();
        em.createNativeQuery("INSERT INTO file_copy_operations(id,owner_id,actor_display_name,source_file_id,file_id,folder_id,name,size_bytes,content_type,idempotency_key,folder_copy_job_id) "
                + "VALUES (:id,:owner,coalesce(cast(:actorName AS varchar),(SELECT display_name FROM users WHERE id=:owner)),:source,:file,:folder,:name,:size,:type,:key,:job)")
                .setParameter("id",id).setParameter("owner",actor).setParameter("source",source).setParameter("file",fileId)
                .setParameter("folder",request.destinationFolderId()).setParameter("name",name).setParameter("size",size)
                .setParameter("type",src[1]).setParameter("key",key).setParameter("job",job).setParameter("actorName",actorName).executeUpdate();
        return read(actor,id).status();
    }

    public Status get(UUID actor, UUID id) { bounds(); return read(actor,id).status(); }

    public Permit preflight(UUID actor, UUID id) {
        Snapshot s = lock(actor,id);
        if (!s.status().state().equals("RESERVED") || !eligible(id)) { return new Permit(s,false); }
        if (!sourceEligible(s)) { transition(id,"FAILED"); failFile(s); return new Permit(read(actor,id),false); }
        requireBinding(s);
        return new Permit(s,true);
    }

    /** A commit-acknowledged single assignment is the only CopyObject dispatch authority. */
    public Permit claim(UUID actor, UUID id, CopyStorage.ObjectInfo source) {
        Snapshot s = lock(actor,id);
        if (!s.status().state().equals("RESERVED")) { return new Permit(s,false); }
        if (!eligible(id)) { return new Permit(s,false); }
        if (!sourceEligible(s)) {
            transition(id,"FAILED"); failFile(s); return new Permit(read(actor,id),false);
        }
        requireBinding(s);
        if (source == null || source.size() != s.size() || !Objects.equals(source.contentType(),s.contentType()) || !validEtag(source.etag())) {
            throw conflict();
        }
        em.createNativeQuery("UPDATE file_storage_objects SET write_started=true WHERE file_id=:file")
                .setParameter("file",s.status().fileId()).executeUpdate();
        em.createNativeQuery("UPDATE file_copy_operations SET state='UNKNOWN',dispatched_at=clock_timestamp(),source_etag=:etag,revision=revision+1 WHERE id=:id")
                .setParameter("etag",source.etag()).setParameter("id",id).executeUpdate();
        return new Permit(read(actor,id),true);
    }

    public Snapshot probe(UUID actor, UUID id) {
        Snapshot s = lock(actor,id); requireBinding(s); return s;
    }

    /** Positive evidence may arrive after cancellation or folder removal; it never revives metadata. */
    public void receipt(UUID actor, UUID id, CopyStorage.ObjectInfo observed) {
        Snapshot s = lock(actor,id);
        requireBinding(s);
        if (!s.dispatched() || !matches(s,observed)) { throw conflict(); }
        if (s.status().storageVerified()) { return; }
        em.createNativeQuery("UPDATE file_copy_operations SET receipt_at=clock_timestamp(),object_etag=:etag,revision=revision+1, "
                + "state=CASE WHEN state IN ('CANCELLED','FAILED','READY') THEN state ELSE 'STORAGE_COMPLETE' END WHERE id=:id")
                .setParameter("id",id).setParameter("etag",observed.etag()).executeUpdate();
    }

    public Status finalizeReady(UUID actor, UUID id) {
        Snapshot s = lock(actor,id);
        if (s.status().terminal() || !s.status().storageVerified()) { return s.status(); }
        if (!binding.matches(s.destination()) || !eligible(id)) { transition(id,"BLOCKED"); return read(actor,id).status(); }
        if (files.transitionUploadStatus(s.status().fileId(),actor,FileStatus.UPLOADING,0,FileStatus.READY,Instant.now()) != 1) { throw conflict(); }
        activity.copied(actor,s.actorName(),s.status().fileId());
        transition(id,"READY");
        return read(actor,id).status();
    }

    public Status cancel(UUID actor, UUID id) {
        Snapshot s = lock(actor,id);
        if (s.status().state().equals("CANCELLED") || s.status().state().equals("FAILED")) { return s.status(); }
        if (s.status().state().equals("READY")) { throw conflict(); }
        if (!eligible(id)) { throw conflict(); } // Never invalidate a sealed pending-folder snapshot.
        transition(id,"CANCELLED"); failFile(s);
        return read(actor,id).status();
    }

    private void failFile(Snapshot s) {
        if (files.transitionUploadStatus(s.status().fileId(),s.owner(),FileStatus.UPLOADING,0,FileStatus.FAILED,Instant.now()) != 1) { throw conflict(); }
    }
    private boolean sourceEligible(Snapshot s) {
        return (Boolean) em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM resolve_resource_access(:owner,:source,NULL) WHERE owner_id=:owner AND effective_role='OWNER')")
                .setParameter("owner",s.owner()).setParameter("source",s.status().sourceFileId()).getSingleResult();
    }
    private boolean eligible(UUID id) {
        return (Boolean) em.createNativeQuery("SELECT copy_destination_eligible(c) FROM file_copy_operations c WHERE id=:id")
                .setParameter("id",id).getSingleResult();
    }
    private void requireOwner(UUID actor, boolean folder, UUID id) {
        // Visible collaborators get 403, strangers/missing/inaccessible resources get the existing uniform 404.
        var a = access.require(actor,folder,id,false);
        if (a.role() != ResourceRole.OWNER) { throw new AccessDeniedException("Owner permission required"); }
    }
    private Snapshot lock(UUID actor, UUID id) {
        bounds(); hierarchy.acquireShared(actor);
        List<?> identities = em.createNativeQuery("SELECT source_file_id,file_id FROM file_copy_operations WHERE id=:id AND owner_id=:owner")
                .setParameter("id",id).setParameter("owner",actor).getResultList();
        if (identities.isEmpty()) { throw new FileNotFoundException("Copy operation not found"); }
        Object[] identity = (Object[])identities.getFirst();
        List<UUID> ids = new ArrayList<>(List.of((UUID)identity[0],(UUID)identity[1]));
        ids.sort(Comparator.comparing(UUID::toString));
        for (UUID file : ids) { lockFile(file,actor); }
        for (UUID file : ids) { lockAllocation(file,actor); }
        em.createNativeQuery("SELECT id FROM file_copy_operations WHERE id=:id AND owner_id=:owner FOR UPDATE")
                .setParameter("id",id).setParameter("owner",actor).getSingleResult();
        return read(actor,id);
    }
    private void lockFile(UUID file, UUID owner) {
        em.createNativeQuery("SELECT id FROM files WHERE id=:id AND owner_id=:owner FOR UPDATE")
                .setParameter("id",file).setParameter("owner",owner).getResultList();
    }
    private void lockAllocation(UUID file, UUID owner) {
        em.createNativeQuery("SELECT file_id FROM file_storage_objects WHERE file_id=:id AND owner_id=:owner FOR UPDATE")
                .setParameter("id",file).setParameter("owner",owner).getResultList();
    }
    private Snapshot read(UUID actor, UUID id) {
        List<?> rows = em.createNativeQuery("SELECT id,source_file_id,file_id,folder_id,name,state,revision,receipt_at IS NOT NULL,created_at, "
                + "size_bytes,content_type,source_etag,dispatched_at IS NOT NULL,actor_display_name FROM file_copy_operations WHERE id=:id AND owner_id=:owner")
                .setParameter("id",id).setParameter("owner",actor).getResultList();
        if (rows.isEmpty()) { throw new FileNotFoundException("Copy operation not found"); }
        Object[] r = (Object[])rows.getFirst();
        Status status = new Status((UUID)r[0],(UUID)r[1],(UUID)r[2],(UUID)r[3],(String)r[4],(String)r[5],
                ((Number)r[6]).longValue(),(Boolean)r[7],ResourceAccessRepository.instant(r[8]));
        return new Snapshot(status,actor,((Number)r[9]).longValue(),(String)r[10],target(status.sourceFileId()),target(status.fileId()),
                (String)r[11],(Boolean)r[12],(String)r[13]);
    }
    private StorageTarget target(UUID file) {
        List<?> rows = em.createNativeQuery("SELECT storage_namespace,endpoint,region,bucket,storage_key FROM file_storage_objects WHERE file_id=:id AND storage_namespace IS NOT NULL")
                .setParameter("id",file).getResultList();
        if (rows.isEmpty()) { throw conflict(); }
        Object[] r = (Object[])rows.getFirst();
        return new StorageTarget((String)r[0],(String)r[1],(String)r[2],(String)r[3],(String)r[4]);
    }
    private void requireBinding(Snapshot s) {
        if (!binding.matches(s.source()) || !binding.matches(s.destination())) { throw conflict(); }
    }
    private void transition(UUID id, String state) {
        em.createNativeQuery("UPDATE file_copy_operations SET state=:state,revision=revision+1 WHERE id=:id AND state<>:state")
                .setParameter("state",state).setParameter("id",id).executeUpdate();
    }
    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout='3s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout='2s'").executeUpdate();
    }
    public static boolean matches(Snapshot s, CopyStorage.ObjectInfo o) {
        return o != null && o.size()==s.size() && Objects.equals(o.contentType(),s.contentType()) && validEtag(o.etag())
                && o.metadata()!=null && s.status().id().toString().equals(o.metadata().get("vaultdrive-copy"));
    }
    private static boolean validEtag(String etag) {
        return etag!=null && !etag.isBlank() && etag.length()<=1024 && etag.chars().noneMatch(Character::isISOControl);
    }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Copy conflicts with retained state"); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid copy request"); }
}
