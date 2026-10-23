package com.vaultdrive.file;

import com.vaultdrive.file.EmptyFileTransactions.*;
import com.vaultdrive.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(propagation=Propagation.NEVER)
public class EmptyFileService {
    private final EmptyFileTransactions tx;
    private final ObjectStorageService storage;
    private final UploadFinalizationTransactions finalization;
    public EmptyFileService(EmptyFileTransactions tx, ObjectStorageService storage, UploadFinalizationTransactions finalization) {
        this.tx=tx; this.storage=storage; this.finalization=finalization;
    }
    public Status create(UUID actor, String key, Request request) {
        Status s=tx.reserve(actor,key,request);
        Permit p=tx.claim(actor,s.id());
        if (p.permitted()) {
            try { storage.putEmpty(p.snapshot().target(),s.id(),p.snapshot().contentType()); }
            catch (CopyStorage.Uncertain unknown) { return tx.get(actor,s.id()).status(); }
        }
        return recover(actor,s.id());
    }
    public Status get(UUID actor, UUID id) { return tx.get(actor,id).status(); }
    public Status recover(UUID actor, UUID id) {
        Snapshot s=tx.get(actor,id);
        if (!s.dispatched() || java.util.Set.of("READY","FAILED","REMOVED").contains(s.status().state())) { return s.status(); }
        if (!s.status().storageVerified()) {
            CopyStorage.ObjectInfo observed;
            try { observed=storage.inspectCopyObject(s.target()); }
            catch (CopyStorage.Uncertain unknown) { return tx.get(actor,id).status(); }
            if (!EmptyFileTransactions.matches(s,observed)) { return tx.get(actor,id).status(); }
            tx.receipt(id,observed);
        }
        finalization.recover(s.status().fileId());
        return tx.get(actor,id).status();
    }
}
