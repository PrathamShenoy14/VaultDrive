package com.vaultdrive.file;

import com.vaultdrive.file.MultipartSessionService.*;
import com.vaultdrive.storage.MultipartStorage;
import com.vaultdrive.storage.ObjectStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.NEVER)
public class MultipartTransferService {
    private final MultipartSessionService sessions;
    private final ObjectStorageService storage;

    public MultipartTransferService(MultipartSessionService sessions, ObjectStorageService storage) {
        this.sessions = sessions;
        this.storage = storage;
    }

    public Session initiate(UUID actor, UUID id) {
        Dispatch dispatch = sessions.claimInitiation(actor, id); // Permit committed before Garage I/O.
        Session session = dispatch.transfer().session();
        if (!dispatch.permitted()) {
            if (!List.of("INITIATING", "OPEN").contains(session.state())) { throw new MultipartConflictException(); }
            sessions.authorizeCaller(actor, id);
            return session;
        }
        try {
            String upload = storage.initiateMultipart(dispatch.transfer().target(), id, session.initiationId(),
                    dispatch.transfer().contentType());
            session = sessions.recordUploadId(session.ownerId(), id, session.revision(), session.initiationId(), upload);
        } catch (RuntimeException uncertain) {
            // Includes result commit loss, expiry and ancestry changes. Never retry Create or compensate.
            // Retain the prior snapshot; response authorization is outside this catch.
        }
        sessions.authorizeCaller(actor, id);
        return session;
    }

    public record PartUrl(RegisteredPart part, MultipartStorage.SignedPart upload) {
        @Override public String toString() { return "PartUrl[REDACTED]"; }
    }

    public List<PartUrl> partUrls(UUID actor, UUID id, List<PartExpectation> parts) {
        Transfer transfer = sessions.authorizeParts(actor, id, parts); // Expectations committed before signing.
        List<PartUrl> signed = transfer.parts().stream().map(p -> new PartUrl(p, storage.signPart(transfer.target(),
                transfer.session().uploadId(), p.partNumber(), p.length(), p.sha256(), transfer.authorizationDeadline()))).toList();
        sessions.validatePartAuthorization(actor, id, transfer.session().revision());
        return signed;
    }

    public MultipartStorage.Page list(UUID actor, UUID id, int marker, int limit) {
        Transfer transfer = sessions.authorizeListing(actor, id, marker, limit);
        var page = storage.listParts(transfer.target(), transfer.session().uploadId(), marker, limit);
        sessions.validatePartAuthorization(actor, id, transfer.session().revision());
        return page;
    }
}
