package com.vaultdrive.folder;

import com.vaultdrive.activity.ResourceActivityService;
import com.vaultdrive.file.FileCopyTransactions;
import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.outbox.OutboxEvent;
import com.vaultdrive.outbox.OutboxWriter;
import com.vaultdrive.sharing.ResourceAccessRepository;
import com.vaultdrive.sharing.ResourceRole;
import com.vaultdrive.worker.FolderPurgeRunService;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/** Exclusive owner hierarchy precedes every mutable read. File/allocation/M1 locks
 * precede the job checkpoint write; no transaction spans provider I/O. */
@Service
@Transactional(timeout = 10)
public class FolderCopyTransactions {
    public static final int MAX_MEMBERS = 1000;
    public static final int MAX_DEPTH = 64;
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;
    private final ResourceAccessRepository access;
    private final FolderNameValidator names;
    private final FileCopyTransactions files;
    private final ResourceActivityService activity;
    private final OutboxWriter outbox;
    private final FolderPurgeRunService purges;

    public FolderCopyTransactions(EntityManager em, HierarchyCoordinator hierarchy, ResourceAccessRepository access,
            FolderNameValidator names, FileCopyTransactions files, ResourceActivityService activity,
            OutboxWriter outbox, FolderPurgeRunService purges) {
        this.em=em; this.hierarchy=hierarchy; this.access=access; this.names=names; this.files=files;
        this.activity=activity; this.outbox=outbox; this.purges=purges;
    }
    public record Request(UUID destinationFolderId, String name) { }
    public record Status(UUID id, UUID sourceFolderId, UUID destinationFolderId, UUID folderId, String name,
                         String state, int checkpoint, int totalMembers, long completedFiles, long totalFiles,
                         long revision, Instant createdAt, UUID disposalOperationId) {
        public boolean terminal() { return Set.of("COMPLETED","FAILED","CANCELLED").contains(state); }
    }
    public record Step(int ordinal, UUID operationId, boolean folder) { }

    public Status admit(UUID owner, UUID source, String key, Request request) {
        if (request==null || key==null || !key.matches("[!-~]{1,128}")) throw invalid();
        String name=names.validateAndNormalize(request.name());
        coordinate(owner);
        List<?> replay=em.createNativeQuery("SELECT id,source_folder_id,destination_folder_id,name FROM folder_copy_jobs WHERE owner_id=:owner AND idempotency_key=:key")
                .setParameter("owner",owner).setParameter("key",key).getResultList();
        if (!replay.isEmpty()) {
            Object[] r=(Object[])replay.getFirst();
            if (!source.equals(r[1]) || !Objects.equals(request.destinationFolderId(),r[2]) || !name.equals(r[3])) throw conflict();
            return read(owner,(UUID)r[0]);
        }
        requireOwner(owner,source);
        if (request.destinationFolderId()!=null) {
            requireOwner(owner,request.destinationFolderId());
            Number depth=(Number)em.createNativeQuery("SELECT cardinality(folder_path) FROM resolve_resource_access(:owner,NULL,:dest)")
                    .setParameter("owner",owner).setParameter("dest",request.destinationFolderId()).getSingleResult();
            if (depth.intValue()+MAX_DEPTH>256) throw conflict();
            if ((Boolean)em.createNativeQuery("SELECT :source=ANY(folder_path) FROM resolve_resource_access(:owner,NULL,:dest)")
                    .setParameter("owner",owner).setParameter("dest",request.destinationFolderId()).setParameter("source",source).getSingleResult()) throw conflict();
        }
        if ((Boolean)em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM folders WHERE owner_id=:owner "
                + "AND parent_folder_id IS NOT DISTINCT FROM cast(:parent AS uuid) AND name=:name AND deleted_at IS NULL)")
                .setParameter("owner",owner).setParameter("parent",request.destinationFolderId()).setParameter("name",name).getSingleResult()) throw conflict();
        // Exclusive hierarchy freezes all supported namespace/lifecycle writers. One
        // statement captures membership; LIMIT + statement timeout bound admission.
        List<?> snapshot=em.createNativeQuery("""
                WITH RECURSIVE tree AS (
                    SELECT id,parent_folder_id,name,1 depth,ARRAY[id] path FROM folders WHERE id=:source AND owner_id=:owner
                    UNION ALL
                    SELECT f.id,f.parent_folder_id,f.name,t.depth+1,t.path||f.id FROM tree t
                    JOIN folders f ON f.parent_folder_id=t.id AND f.owner_id=:owner
                    WHERE f.deleted_at IS NULL AND f.purge_requested_at IS NULL AND NOT folder_copy_hidden(f.id)
                        AND t.depth<=64 AND NOT f.id=ANY(t.path)
                ), members AS (
                    SELECT 'FOLDER' kind,id,parent_folder_id parent,name,NULL::bigint size,NULL::varchar type,depth FROM tree
                    UNION ALL
                    SELECT 'FILE',f.id,f.folder_id,f.name,f.size_bytes,f.content_type,t.depth+1
                    FROM tree t JOIN files f ON f.folder_id=t.id AND f.owner_id=:owner
                    WHERE f.deleted_at IS NULL AND f.purge_requested_at IS NULL AND f.status='READY'
                ) SELECT kind,id,parent,name,size,type,depth FROM members ORDER BY depth,kind DESC,id LIMIT 1001
                """).setParameter("source",source).setParameter("owner",owner).getResultList();
        if (snapshot.size()>MAX_MEMBERS || snapshot.stream().anyMatch(v->{
            Object[] r=(Object[])v;
            return ((Number)r[6]).intValue()>MAX_DEPTH || ("FILE".equals(r[0])
                    && (((Number)r[4]).longValue()<0 || ((Number)r[4]).longValue()>com.vaultdrive.file.MultipartSessionService.MAX_SIZE));
        })) throw conflict();
        UUID job=UUID.randomUUID(), root=UUID.randomUUID();
        em.createNativeQuery("INSERT INTO folder_copy_jobs(id,owner_id,actor_display_name,source_folder_id,destination_folder_id,folder_id,name,idempotency_key,member_count) "
                + "VALUES (:id,:owner,(SELECT display_name FROM users WHERE id=:owner),:source,:dest,:root,:name,:key,:count)")
                .setParameter("id",job).setParameter("owner",owner).setParameter("source",source).setParameter("dest",request.destinationFolderId())
                .setParameter("root",root).setParameter("name",name).setParameter("key",key).setParameter("count",snapshot.size()).executeUpdate();
        Map<UUID,UUID> mapping=new HashMap<>(); mapping.put(source,root);
        int ordinal=0;
        for (Object row:snapshot) {
            Object[] r=(Object[])row; UUID src=(UUID)r[1]; boolean folder="FOLDER".equals(r[0]);
            UUID destination=mapping.computeIfAbsent(src,ignored->UUID.randomUUID());
            UUID parent=src.equals(source)?request.destinationFolderId():mapping.get((UUID)r[2]);
            String memberName=src.equals(source)?name:(String)r[3];
            if (folder) {
                em.createNativeQuery("INSERT INTO folders(id,owner_id,parent_folder_id,name,created_at,updated_at) VALUES (:id,:owner,:parent,:name,clock_timestamp(),clock_timestamp())")
                        .setParameter("id",destination).setParameter("owner",owner).setParameter("parent",parent).setParameter("name",memberName).executeUpdate();
            }
            em.createNativeQuery("INSERT INTO folder_copy_members(job_id,ordinal,kind,source_id,destination_id,parent_id,name,size_bytes,content_type) "
                    + "VALUES (:job,:ordinal,:kind,:source,:destination,:parent,:name,:size,:type)")
                    .setParameter("job",job).setParameter("ordinal",++ordinal).setParameter("kind",r[0]).setParameter("source",src)
                    .setParameter("destination",destination).setParameter("parent",parent).setParameter("name",memberName)
                    .setParameter("size",r[4]).setParameter("type",r[5]).executeUpdate();
        }
        return read(owner,job);
    }

    public Status get(UUID owner, UUID id) { bounds(); return read(owner,id); }

    /** Prepare one durable member before I/O. Reservation and M1 linkage commit together. */
    public Step next(UUID owner, UUID id, boolean recoverOnly) {
        coordinate(owner); Status s=read(owner,id);
        if (s.terminal()) return null;
        if (!destinationAccessible(owner,s)) { state(id,"BLOCKED"); return null; }
        if (s.checkpoint()==s.totalMembers()) { publish(owner,s); return null; }
        int ordinal=s.checkpoint()+1;
        Object[] m=(Object[])em.createNativeQuery("SELECT kind,source_id,operation_id FROM folder_copy_members WHERE job_id=:id AND ordinal=:ordinal")
                .setParameter("id",id).setParameter("ordinal",ordinal).getSingleResult();
        if ("FOLDER".equals(m[0])) return new Step(ordinal,null,true);
        UUID operation=(UUID)m[2];
        if (operation==null) {
            if (recoverOnly) return null;
            if (!(Boolean)em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM resolve_resource_access(:owner,:source,NULL))")
                    .setParameter("owner",owner).setParameter("source",m[1]).getSingleResult()) { state(id,"FAILED"); return null; }
            var reserved=files.reserveFolderMember(owner,id,ordinal); operation=reserved.id();
            em.createNativeQuery("UPDATE folder_copy_members SET operation_id=:operation WHERE job_id=:id AND ordinal=:ordinal")
                    .setParameter("operation",operation).setParameter("id",id).setParameter("ordinal",ordinal).executeUpdate();
        }
        state(id,"ACTIVE");
        return new Step(ordinal,operation,false);
    }

    /** Reads durable M1 truth, never trusts a stale worker result. */
    public boolean checkpoint(UUID owner, UUID id, Step step) {
        coordinate(owner); Status s=read(owner,id);
        if (s.terminal()) return false;
        if (s.checkpoint()>=step.ordinal()) return true;
        if (s.checkpoint()+1!=step.ordinal()) throw conflict();
        if (!step.folder()) {
            String result=(String)em.createNativeQuery("SELECT c.state FROM folder_copy_members m JOIN file_copy_operations c ON c.id=m.operation_id "
                    + "WHERE m.job_id=:id AND m.ordinal=:ordinal AND c.id=:operation")
                    .setParameter("id",id).setParameter("ordinal",step.ordinal()).setParameter("operation",step.operationId()).getSingleResult();
            if (!"READY".equals(result)) {
                state(id,Set.of("FAILED","CANCELLED").contains(result)?"FAILED":"UNKNOWN".equals(result)?"UNKNOWN":"BLOCKED"); return false;
            }
        }
        em.createNativeQuery("UPDATE folder_copy_jobs SET checkpoint=:checkpoint,state='ACTIVE',revision=revision+1 WHERE id=:id")
                .setParameter("checkpoint",step.ordinal()).setParameter("id",id).executeUpdate();
        s=read(owner,id);
        if (s.checkpoint()==s.totalMembers()) {
            if (destinationAccessible(owner,s)) publish(owner,s); else state(id,"BLOCKED");
        }
        return true;
    }

    public Status blocked(UUID owner, UUID id) {
        coordinate(owner); Status s=read(owner,id); if (!s.terminal()) state(id,"BLOCKED"); return read(owner,id);
    }

    public Status cancel(UUID owner, UUID id) {
        coordinate(owner); Status s=read(owner,id);
        if ("COMPLETED".equals(s.state())) throw conflict();
        if (!s.terminal()) state(id,"CANCELLED");
        return read(owner,id);
    }

    /** Logical cancellation and physical disposal are separate durable decisions.
     * Existing bounded folder purge preserves all uncertain COPY allocations. */
    public Status discard(UUID owner, UUID id) {
        coordinate(owner); Status s=read(owner,id);
        if (!Set.of("FAILED","CANCELLED").contains(s.state())) throw conflict();
        if (s.disposalOperationId()!=null) return s;
        if (!destinationAccessible(owner,s)) throw conflict(); // Parent purge owns its sealed subtree.
        Folder root=em.find(Folder.class,s.folderId());
        if (root==null) throw conflict();
        em.refresh(root); root.softDelete(); root.requestPermanentDeletion(); em.flush();
        OutboxEvent event=OutboxEvent.folderPurgeRequested(owner,root.getId(),root.getPurgeRequestedAt());
        outbox.append(event); purges.create(event);
        em.createNativeQuery("UPDATE folder_copy_jobs SET disposal_event_id=:event,revision=revision+1 WHERE id=:id")
                .setParameter("event",event.getId()).setParameter("id",id).executeUpdate();
        return read(owner,id);
    }

    private void publish(UUID owner, Status s) {
        em.createNativeQuery("UPDATE folder_copy_jobs SET state='COMPLETED',completed_at=clock_timestamp(),revision=revision+1 WHERE id=:id")
                .setParameter("id",s.id()).executeUpdate();
        String actorName=(String)em.createNativeQuery("SELECT actor_display_name FROM folder_copy_jobs WHERE id=:id").setParameter("id",s.id()).getSingleResult();
        List<?> folders=em.createNativeQuery("SELECT destination_id FROM folder_copy_members WHERE job_id=:id AND kind='FOLDER' ORDER BY ordinal")
                .setParameter("id",s.id()).getResultList();
        for (Object folder:folders) activity.copiedFolder(owner,actorName,(UUID)folder);
    }
    private boolean destinationAccessible(UUID owner, Status s) {
        return s.destinationFolderId()==null || (Boolean)em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM resolve_resource_access(:owner,NULL,:dest) WHERE cardinality(folder_path)<=192)")
                .setParameter("owner",owner).setParameter("dest",s.destinationFolderId()).getSingleResult();
    }
    private void requireOwner(UUID owner, UUID folder) {
        if (access.require(owner,true,folder,false).role()!=ResourceRole.OWNER) throw new AccessDeniedException("Owner permission required");
    }
    private Status read(UUID owner, UUID id) {
        List<?> rows=em.createNativeQuery("""
                SELECT j.id,j.source_folder_id,j.destination_folder_id,j.folder_id,j.name,j.state,j.checkpoint,j.member_count,
                    (SELECT count(*) FROM folder_copy_members m JOIN file_copy_operations c ON c.id=m.operation_id WHERE m.job_id=j.id AND c.state='READY'),
                    (SELECT count(*) FROM folder_copy_members m WHERE m.job_id=j.id AND m.kind='FILE'),j.revision,j.created_at,j.disposal_event_id
                FROM folder_copy_jobs j WHERE j.id=:id AND j.owner_id=:owner
                """).setParameter("id",id).setParameter("owner",owner).getResultList();
        if (rows.isEmpty()) throw ResourceAccessRepository.notFound(true);
        Object[] r=(Object[])rows.getFirst();
        return new Status((UUID)r[0],(UUID)r[1],(UUID)r[2],(UUID)r[3],(String)r[4],(String)r[5],((Number)r[6]).intValue(),
                ((Number)r[7]).intValue(),((Number)r[8]).longValue(),((Number)r[9]).longValue(),((Number)r[10]).longValue(),ResourceAccessRepository.instant(r[11]),(UUID)r[12]);
    }
    private void state(UUID id, String state) {
        em.createNativeQuery("UPDATE folder_copy_jobs SET state=:state,revision=revision+1 WHERE id=:id AND state<>:state")
                .setParameter("state",state).setParameter("id",id).executeUpdate();
    }
    private void coordinate(UUID owner) { bounds(); hierarchy.acquireExclusive(owner); }
    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout='3s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout='2s'").executeUpdate();
    }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Folder copy conflicts with retained state or bounds"); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid folder copy request"); }
}
