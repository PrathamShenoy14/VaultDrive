package com.vaultdrive.file;

import com.vaultdrive.file.exception.DuplicateFileNameException;
import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.FolderAccessValidator;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.sharing.ResourceAccessRepository;
import com.vaultdrive.sharing.ResourceRole;
import com.vaultdrive.storage.*;
import jakarta.persistence.EntityManager;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

/** Short database boundaries. Hierarchy -> admission idempotency -> namespace -> new file/quota;
 * existing work uses hierarchy -> file -> allocation -> creation. No storage I/O here. */
@Service
@Transactional(timeout = 10)
public class EmptyFileTransactions {
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final ResourceAccessRepository access;
    private final FolderAccessValidator folders;
    private final FileNameValidator names;
    private final StoredFileRepository files;
    private final StorageKeyGenerator keys;
    private final StorageBinding binding;

    public EmptyFileTransactions(EntityManager em, HierarchyCoordinator hierarchy, ResourceAccessRepository access,
            FolderAccessValidator folders, FileNameValidator names, StoredFileRepository files,
            StorageKeyGenerator keys, StorageBinding binding) {
        this.em=em; this.hierarchy=hierarchy; this.access=access; this.folders=folders;
        this.names=names; this.files=files; this.keys=keys; this.binding=binding;
    }
    public record Request(UUID folderId, String name, String contentType) { }
    public record Status(UUID id, UUID fileId, String state, boolean storageVerified) { }
    public record Snapshot(Status status, UUID owner, UUID initiator, UUID folder, String name,
                           String contentType, StorageTarget target, boolean dispatched) { }
    public record Permit(Snapshot snapshot, boolean permitted) { }

    public Status reserve(UUID actor, String key, Request request) {
        if (request == null || key == null || !key.matches("[!-~]{1,128}")) { throw new IllegalArgumentException("Invalid empty-file request"); }
        String name=names.validateAndNormalize(request.name());
        String type=request.contentType()==null ? "application/octet-stream" : request.contentType().trim();
        if (type.isEmpty() || type.length()>255 || type.chars().anyMatch(Character::isISOControl)
                || !MediaType.parseMediaType(type).isConcrete()) { throw new IllegalArgumentException("Invalid content type"); }
        UUID owner=request.folderId()==null ? actor : access.owner(true,request.folderId());
        bounds(); hierarchy.acquireShared(owner);
        authorizeDestination(actor,owner,request.folderId());
        em.createNativeQuery("SELECT pg_advisory_xact_lock(1162694740,hashtext(:key))")
                .setParameter("key",owner+":"+actor+":"+key).getSingleResult();
        List<?> replay=em.createNativeQuery("SELECT id,folder_id,name,content_type FROM empty_file_creations WHERE owner_id=:owner AND initiator_id=:actor AND idempotency_key=:key")
                .setParameter("owner",owner).setParameter("actor",actor).setParameter("key",key).getResultList();
        if (!replay.isEmpty()) {
            Object[] r=(Object[])replay.getFirst();
            if (!Objects.equals(request.folderId(),r[1]) || !name.equals(r[2]) || !type.equals(r[3])) { throw conflict(); }
            return read((UUID)r[0]).status();
        }
        hierarchy.acquireNamespaceExclusive(owner,request.folderId());
        if ((Boolean)em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM files WHERE owner_id=:owner AND folder_id IS NOT DISTINCT FROM cast(:folder AS uuid) AND name=:name AND deleted_at IS NULL AND status IN ('UPLOADING','READY'))")
                .setParameter("owner",owner).setParameter("folder",request.folderId()).setParameter("name",name).getSingleResult()) {
            throw new DuplicateFileNameException("A file with this name already exists");
        }
        UUID id=UUID.randomUUID(), file=UUID.randomUUID();
        StoredFile f=files.saveAndFlush(new StoredFile(file,owner,request.folderId(),name,keys.generateFileKey(owner,file),type,0));
        StorageTarget target=binding.target(f.getStorageKey());
        em.createNativeQuery("INSERT INTO file_storage_objects(storage_key,file_id,owner_id,storage_namespace,endpoint,region,bucket,upload_protocol) VALUES (:key,:file,:owner,:namespace,:endpoint,:region,:bucket,'SERVER_PUT')")
                .setParameter("key",target.key()).setParameter("file",file).setParameter("owner",owner).setParameter("namespace",target.namespace())
                .setParameter("endpoint",target.endpoint()).setParameter("region",target.region()).setParameter("bucket",target.bucket()).executeUpdate();
        em.createNativeQuery("INSERT INTO empty_file_creations(id,file_id,owner_id,initiator_id,actor_display_name,folder_id,name,content_type,idempotency_key) VALUES (:id,:file,:owner,:actor,(SELECT display_name FROM users WHERE id=:actor),:folder,:name,:type,:key)")
                .setParameter("id",id).setParameter("file",file).setParameter("owner",owner).setParameter("actor",actor)
                .setParameter("folder",request.folderId()).setParameter("name",name).setParameter("type",type).setParameter("key",key).executeUpdate();
        return read(id).status();
    }

    public Snapshot get(UUID actor, UUID id) { return lock(actor,id); }

    /** Only the newly acknowledged commit can grant one PUT call. */
    public Permit claim(UUID actor, UUID id) {
        Snapshot s=lock(actor,id);
        if (!s.status().state().equals("RESERVED")) { return new Permit(s,false); }
        authorizeDestination(actor,s.owner(),s.folder());
        if (!eligible(s)) { return new Permit(s,false); }
        requireBinding(s);
        em.createNativeQuery("UPDATE file_storage_objects SET write_started=true,admitted_file_version=0,admitted_folder_id=:folder WHERE file_id=:file")
                .setParameter("folder",s.folder()).setParameter("file",s.status().fileId()).executeUpdate();
        em.createNativeQuery("UPDATE empty_file_creations SET dispatched_at=clock_timestamp() WHERE id=:id")
                .setParameter("id",id).executeUpdate();
        return new Permit(read(id),true);
    }

    /** Internal evidence boundary: an admitted write can finish after an Editor's grant is revoked. */
    public void receipt(UUID id, CopyStorage.ObjectInfo observed) {
        bounds(); lockRows(id); Snapshot s=read(id); requireBinding(s);
        if (!s.dispatched() || !matches(s,observed)) { throw conflict(); }
        if (s.status().storageVerified()) { return; }
        em.createNativeQuery("UPDATE file_storage_objects SET put_succeeded_at=clock_timestamp() WHERE file_id=:file")
                .setParameter("file",s.status().fileId()).executeUpdate();
    }

    public static boolean matches(Snapshot s, CopyStorage.ObjectInfo o) {
        return o!=null && o.size()==0 && Objects.equals(o.contentType(),s.contentType())
                && o.etag()!=null && !o.etag().isBlank() && o.etag().length()<=1024
                && o.etag().chars().noneMatch(Character::isISOControl) && o.metadata()!=null
                && s.status().id().toString().equals(o.metadata().get("vaultdrive-empty"));
    }
    private Snapshot lock(UUID actor, UUID id) {
        bounds(); lockRows(id); Snapshot s=read(id);
        if (!actor.equals(s.owner())) {
            if (!actor.equals(s.initiator())) { throw absent(); }
            authorizeDestination(actor,s.owner(),s.folder());
        }
        return s;
    }
    private void lockRows(UUID id) {
        List<?> identities=em.createNativeQuery("SELECT file_id,owner_id FROM empty_file_creations WHERE id=:id")
                .setParameter("id",id).getResultList();
        if (identities.isEmpty()) { throw absent(); }
        Object[] identity=(Object[])identities.getFirst(); // Immutable identity only before hierarchy coordination.
        hierarchy.acquireShared((UUID)identity[1]);
        em.createNativeQuery("SELECT id FROM files WHERE id=:file FOR UPDATE").setParameter("file",identity[0]).getResultList();
        em.createNativeQuery("SELECT file_id FROM file_storage_objects WHERE file_id=:file FOR UPDATE").setParameter("file",identity[0]).getResultList();
        em.createNativeQuery("SELECT id FROM empty_file_creations WHERE id=:id FOR UPDATE").setParameter("id",id).getSingleResult();
    }
    private boolean eligible(Snapshot s) {
        return (Boolean)em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM files WHERE id=:file AND status='UPLOADING' AND version=0 AND deleted_at IS NULL AND purge_requested_at IS NULL) AND NOT EXISTS (SELECT 1 FROM folder_purge_files WHERE file_id=:file) AND NOT EXISTS (SELECT 1 FROM file_purge_work WHERE file_id=:file)")
                .setParameter("file",s.status().fileId()).getSingleResult();
    }
    private void authorizeDestination(UUID actor, UUID owner, UUID folder) {
        if (folder!=null && access.require(actor,true,folder,false).role()==ResourceRole.VIEWER) { throw new AccessDeniedException("Editor permission required"); }
        if (folder!=null) { folders.requireAccessibleFolder(owner,folder); }
        folders.requireNoPendingPurgeInAncestry(owner,folder);
    }
    private Snapshot read(UUID id) {
        List<?> rows=em.createNativeQuery("SELECT c.file_id,c.owner_id,c.initiator_id,c.folder_id,c.name,c.content_type,c.dispatched_at IS NOT NULL,a.put_succeeded_at IS NOT NULL,a.storage_namespace,a.endpoint,a.region,a.bucket,a.storage_key,f.status FROM empty_file_creations c JOIN file_storage_objects a ON a.file_id=c.file_id LEFT JOIN files f ON f.id=c.file_id WHERE c.id=:id")
                .setParameter("id",id).getResultList();
        if (rows.isEmpty()) { throw absent(); }
        Object[] r=(Object[])rows.getFirst();
        String state=r[13]==null ? "REMOVED" : "READY".equals(r[13]) ? "READY" : "FAILED".equals(r[13]) ? "FAILED" : (Boolean)r[7] ? "STORAGE_COMPLETE" : (Boolean)r[6] ? "UNKNOWN" : "RESERVED";
        return new Snapshot(new Status(id,(UUID)r[0],state,(Boolean)r[7]),(UUID)r[1],(UUID)r[2],(UUID)r[3],(String)r[4],(String)r[5],
                new StorageTarget((String)r[8],(String)r[9],(String)r[10],(String)r[11],(String)r[12]),(Boolean)r[6]);
    }
    private void requireBinding(Snapshot s) { if (!binding.matches(s.target())) { throw conflict(); } }
    private void bounds() { em.createNativeQuery("SET LOCAL statement_timeout='3s'").executeUpdate(); em.createNativeQuery("SET LOCAL lock_timeout='2s'").executeUpdate(); }
    private static FileNotFoundException absent() { return new FileNotFoundException("Empty-file creation not found"); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Empty-file creation conflicts with retained state"); }
}
