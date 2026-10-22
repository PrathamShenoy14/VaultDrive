package com.vaultdrive.folder;

import com.vaultdrive.file.FileCopyService;
import com.vaultdrive.folder.FolderCopyTransactions.Status;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Explicit caller-driven bounded execution. Concurrent callers share durable M1
 * dispatch authority; an abandoned caller needs no lease reset or write retry. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class FolderCopyService {
    public static final int BATCH_SIZE=4;
    private final FolderCopyTransactions tx;
    private final FileCopyService files;
    public FolderCopyService(FolderCopyTransactions tx, FileCopyService files) { this.tx=tx; this.files=files; }
    public Status execute(UUID owner, UUID id) { return run(owner,id,false); }
    public Status recover(UUID owner, UUID id) { return run(owner,id,true); }
    private Status run(UUID owner, UUID id, boolean recover) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        for (int i=0;i<BATCH_SIZE && System.nanoTime()<deadline;i++) {
            FolderCopyTransactions.Step step;
            try { step=tx.next(owner,id,recover); }
            catch (DataIntegrityViolationException exhausted) { return tx.blocked(owner,id); }
            catch (org.springframework.web.server.ResponseStatusException conflict) {
                if (conflict.getStatusCode().value()!=409) throw conflict;
                return tx.blocked(owner,id);
            }
            if (step==null) break;
            if (!step.folder()) {
                try {
                    if (recover) files.recover(owner,step.operationId()); else files.execute(owner,step.operationId());
                } catch (org.springframework.web.server.ResponseStatusException conflict) {
                    if (conflict.getStatusCode().value()!=409) throw conflict;
                    return tx.blocked(owner,id);
                }
            }
            if (!tx.checkpoint(owner,id,step)) break;
        }
        return tx.get(owner,id);
    }
}
