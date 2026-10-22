package com.vaultdrive.file;

import com.vaultdrive.file.FileCopyTransactions.*;
import com.vaultdrive.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Explicit bounded execution/recovery; no background dispatch or unsafe retry. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class FileCopyService {
    private final FileCopyTransactions tx;
    private final ObjectStorageService storage;
    public FileCopyService(FileCopyTransactions tx, ObjectStorageService storage) { this.tx=tx; this.storage=storage; }

    public Status copy(UUID actor, UUID source, String key, Request request) {
        return execute(actor,tx.reserve(actor,source,key,request).id());
    }
    public Status execute(UUID actor, UUID id) {
        Permit preflight = tx.preflight(actor,id);
        Snapshot s = preflight.snapshot();
        if (!preflight.permitted()) { return s.status(); }
        CopyStorage.ObjectInfo source;
        try { source=storage.inspectCopyObject(s.source()); }
        catch (CopyStorage.Uncertain unavailable) { return tx.get(actor,id); }
        Permit permit=tx.claim(actor,id,source);
        if (!permit.permitted()) { return permit.snapshot().status(); }
        s=permit.snapshot();
        try { storage.copyObject(new CopyStorage.Command(s.source(),s.destination(),id,s.sourceEtag(),s.contentType())); }
        catch (CopyStorage.Uncertain unknown) { return tx.get(actor,id); }
        return recover(actor,id);
    }
    public Status recover(UUID actor, UUID id) {
        Snapshot s=tx.probe(actor,id);
        if (s.status().terminal() || !s.dispatched()) { return s.status(); }
        if (!s.status().storageVerified()) {
            CopyStorage.ObjectInfo object;
            try { object=storage.inspectCopyObject(s.destination()); }
            catch (CopyStorage.Uncertain unknown) { return tx.get(actor,id); }
            if (!FileCopyTransactions.matches(s,object)) { return tx.get(actor,id); }
            tx.receipt(actor,id,object); // Independent positive receipt commit precedes READY.
        }
        return tx.finalizeReady(actor,id);
    }
}
