-- Abandonment never supplies a multipart removal or physical reclamation proof.
CREATE TABLE multipart_cancellations (
    session_id UUID PRIMARY KEY REFERENCES upload_sessions(id),
    decision_id UUID NOT NULL UNIQUE,
    initiation_id UUID,
    garage_upload_id TEXT,
    storage_identity JSONB NOT NULL,
    cause VARCHAR(16) NOT NULL CHECK (cause IN ('CANCEL','EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    dispatch_id UUID UNIQUE,
    dispatch_decided_at TIMESTAMPTZ,
    disposition VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (disposition IN ('PENDING','UNKNOWN','BLOCKED','CANCELLED')),
    reason VARCHAR(32) CHECK (reason IN ('DISPATCH_UNCERTAIN','STORAGE_UNAVAILABLE',
        'IDENTITY_MISMATCH','LIFECYCLE_BLOCKED','UPLOAD_ID_UNKNOWN','INITIATION_UNVERIFIED')),
    evidence_kind VARCHAR(24) CHECK (evidence_kind IN ('NEVER_INITIATED','ABORT_ACK')),
    acknowledged_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((dispatch_id IS NULL) = (dispatch_decided_at IS NULL)),
    CHECK ((disposition IN ('UNKNOWN','BLOCKED')) = (reason IS NOT NULL)),
    CHECK ((disposition = 'CANCELLED') = (evidence_kind IS NOT NULL)),
    CHECK ((evidence_kind IS NULL) = (acknowledged_at IS NULL))
);

-- Caller order: shared hierarchy -> fresh file -> allocation -> session -> cancellation.
CREATE FUNCTION guard_multipart_cancellation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s upload_sessions;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'multipart cancellation is retained' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO s FROM upload_sessions WHERE id = NEW.session_id FOR UPDATE;
    IF s.state IS DISTINCT FROM 'ABANDONMENT_DECIDED'
        OR ROW(NEW.decision_id, NEW.initiation_id, NEW.garage_upload_id, NEW.storage_identity)
            IS DISTINCT FROM ROW(s.decision_id, s.initiation_id, s.garage_upload_id, multipart_storage_identity(s))
        OR EXISTS (SELECT 1 FROM multipart_completions WHERE session_id = s.id)
        OR NOT EXISTS (SELECT 1 FROM file_storage_objects a WHERE a.file_id = s.file_id
            AND a.upload_protocol = 'DIRECT_MULTIPART'
            AND ROW(a.owner_id, a.storage_key, a.storage_namespace, a.endpoint, a.region, a.bucket)
                IS NOT DISTINCT FROM ROW(s.owner_id, s.storage_key, s.storage_namespace, s.endpoint, s.region, s.bucket)) THEN
        RAISE EXCEPTION 'multipart cancellation identity mismatch' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.disposition <> 'PENDING' OR NEW.reason IS NOT NULL OR NEW.revision <> 0
            OR NEW.dispatch_id IS NOT NULL OR NEW.dispatch_decided_at IS NOT NULL
            OR NEW.evidence_kind IS NOT NULL OR NEW.acknowledged_at IS NOT NULL
            OR NOT multipart_session_eligible(s)
            OR (NEW.cause = 'EXPIRED' AND s.expires_at > clock_timestamp()) THEN
            RAISE EXCEPTION 'multipart cancellation intent is not eligible' USING ERRCODE = '23514';
        END IF;
        NEW.created_at := clock_timestamp(); NEW.updated_at := NEW.created_at;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - ARRAY['dispatch_id','dispatch_decided_at','disposition','reason',
            'evidence_kind','acknowledged_at','revision','updated_at']) IS DISTINCT FROM
        (to_jsonb(OLD) - ARRAY['dispatch_id','dispatch_decided_at','disposition','reason',
            'evidence_kind','acknowledged_at','revision','updated_at'])
        OR (OLD.dispatch_id IS NOT NULL AND ROW(NEW.dispatch_id,NEW.dispatch_decided_at)
            IS DISTINCT FROM ROW(OLD.dispatch_id,OLD.dispatch_decided_at)) THEN
        RAISE EXCEPTION 'multipart cancellation identity and dispatch are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    IF OLD.disposition = 'CANCELLED' OR NEW.revision <> OLD.revision + 1 OR NEW.disposition = 'PENDING'
        OR ROW(NEW.dispatch_id,NEW.disposition,NEW.reason,NEW.evidence_kind)
            IS NOT DISTINCT FROM ROW(OLD.dispatch_id,OLD.disposition,OLD.reason,OLD.evidence_kind) THEN
        RAISE EXCEPTION 'invalid multipart cancellation progress' USING ERRCODE = '23514';
    END IF;
    IF OLD.dispatch_id IS NULL AND NEW.dispatch_id IS NOT NULL THEN
        IF NOT multipart_session_eligible(s) OR s.garage_upload_id IS NULL
            OR NOT EXISTS (SELECT 1 FROM multipart_initiation_dispatches d
                WHERE d.session_id = s.id AND d.initiation_id = s.initiation_id)
            OR NEW.disposition <> 'UNKNOWN' OR NEW.reason IS DISTINCT FROM 'DISPATCH_UNCERTAIN' THEN
            RAISE EXCEPTION 'multipart abort dispatch is not eligible' USING ERRCODE = '23514';
        END IF;
        NEW.dispatch_decided_at := clock_timestamp();
    END IF;
    IF (NEW.disposition = 'UNKNOWN' AND NEW.dispatch_id IS NULL)
        OR (NEW.evidence_kind = 'NEVER_INITIATED' AND (s.initiation_id IS NOT NULL OR NEW.dispatch_id IS NOT NULL))
        OR (NEW.evidence_kind = 'ABORT_ACK' AND (OLD.dispatch_id IS NULL OR NEW.dispatch_id IS NULL)) THEN
        RAISE EXCEPTION 'multipart cancellation lacks matching evidence' USING ERRCODE = '23514';
    END IF;
    IF NEW.evidence_kind IS NOT NULL THEN NEW.acknowledged_at := clock_timestamp();
    ELSIF NEW.acknowledged_at IS NOT NULL THEN
        RAISE EXCEPTION 'multipart cancellation acknowledgement requires evidence' USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_multipart_cancellation BEFORE INSERT OR UPDATE OR DELETE ON multipart_cancellations
FOR EACH ROW EXECUTE FUNCTION guard_multipart_cancellation();
