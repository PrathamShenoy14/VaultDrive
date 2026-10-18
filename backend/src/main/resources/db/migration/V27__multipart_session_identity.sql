-- M1 is metadata only. Existing allocations and V25/V26 evidence are untouched.
ALTER TABLE file_storage_objects ADD COLUMN upload_protocol VARCHAR(24) NOT NULL DEFAULT 'SERVER_PUT'
    CHECK (upload_protocol IN ('SERVER_PUT', 'DIRECT_MULTIPART'));
ALTER TABLE file_storage_objects ADD CONSTRAINT chk_multipart_no_put_evidence CHECK (
    upload_protocol = 'SERVER_PUT' OR
    (num_nonnulls(storage_namespace, endpoint, region, bucket) = 4
        AND admitted_file_version IS NULL AND admitted_folder_id IS NULL AND put_succeeded_at IS NULL
        AND write_precluded_at IS NULL AND reservation_expired_at IS NULL AND expired_file_version IS NULL));

CREATE TABLE upload_sessions (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL UNIQUE REFERENCES file_storage_objects(file_id),
    owner_id UUID NOT NULL,
    original_folder_id UUID,
    original_file_version BIGINT NOT NULL CHECK (original_file_version >= 0),
    storage_key VARCHAR(1024) NOT NULL,
    storage_namespace VARCHAR(255) NOT NULL,
    endpoint VARCHAR(1024) NOT NULL,
    region VARCHAR(255) NOT NULL,
    bucket VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    declared_size BIGINT NOT NULL CHECK (declared_size BETWEEN 1 AND 5368709120),
    declared_type VARCHAR(255) NOT NULL CHECK (length(declared_type) BETWEEN 1 AND 255),
    part_size INTEGER NOT NULL CHECK (part_size = 8388608),
    part_count INTEGER NOT NULL CHECK (part_count = (declared_size + part_size - 1) / part_size
        AND part_count BETWEEN 1 AND 10000),
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    request_fingerprint VARCHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + interval '24 hours'),
    state VARCHAR(24) NOT NULL DEFAULT 'RESERVED'
        CHECK (state IN ('RESERVED', 'INITIATING', 'OPEN', 'COMPLETION_DECIDED', 'ABANDONMENT_DECIDED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    initiation_id UUID,
    garage_upload_id TEXT CHECK (length(garage_upload_id) BETWEEN 1 AND 2048),
    decision_id UUID,
    decided_at TIMESTAMPTZ,
    UNIQUE (owner_id, idempotency_key),
    CHECK (expires_at = created_at + interval '24 hours'),
    CHECK ((decision_id IS NULL) = (decided_at IS NULL)),
    CHECK ((state IN ('COMPLETION_DECIDED', 'ABANDONMENT_DECIDED')) = (decision_id IS NOT NULL)),
    CHECK (state <> 'RESERVED' OR (initiation_id IS NULL AND garage_upload_id IS NULL)),
    CHECK (state NOT IN ('INITIATING', 'OPEN', 'COMPLETION_DECIDED') OR initiation_id IS NOT NULL),
    CHECK (garage_upload_id IS NULL OR initiation_id IS NOT NULL),
    CHECK (state <> 'INITIATING' OR garage_upload_id IS NULL),
    CHECK (state NOT IN ('OPEN', 'COMPLETION_DECIDED') OR garage_upload_id IS NOT NULL)
);
CREATE INDEX idx_upload_sessions_expiry ON upload_sessions(state, expires_at, id);

-- Call after hierarchy -> fresh file -> allocation -> session locks. No storage proof is inferred.
CREATE FUNCTION multipart_session_eligible(session upload_sessions) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    WITH RECURSIVE ancestry AS (
        SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
            ARRAY[p.id] AS path, false AS cycle, 1 AS depth
        FROM folders p WHERE p.id = session.original_folder_id AND p.owner_id = session.owner_id
        UNION ALL
        SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
            a.path || p.id, p.id = ANY(a.path), a.depth + 1
        FROM ancestry a JOIN folders p ON p.id = a.parent_folder_id AND p.owner_id = session.owner_id
        WHERE NOT a.cycle AND a.depth < 128
    )
    SELECT EXISTS (SELECT 1 FROM files f JOIN file_storage_objects a ON a.file_id = f.id
        WHERE f.id = session.file_id AND f.owner_id = session.owner_id
            AND EXISTS (SELECT 1 FROM users WHERE id = f.owner_id)
            AND f.status = 'UPLOADING' AND f.version = session.original_file_version
            AND f.folder_id IS NOT DISTINCT FROM session.original_folder_id
            AND f.name = session.file_name AND f.size_bytes = session.declared_size
            AND f.content_type = session.declared_type
            AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL
            AND a.upload_protocol = 'DIRECT_MULTIPART'
            AND ROW(a.owner_id, a.storage_key, a.storage_namespace, a.endpoint, a.region, a.bucket)
                IS NOT DISTINCT FROM ROW(session.owner_id, session.storage_key, session.storage_namespace,
                    session.endpoint, session.region, session.bucket)
            AND f.storage_key = a.storage_key
            AND (session.original_folder_id IS NULL OR EXISTS (
                SELECT 1 FROM ancestry WHERE parent_folder_id IS NULL))
            AND NOT EXISTS (SELECT 1 FROM ancestry WHERE cycle OR deleted_at IS NOT NULL OR purge_requested_at IS NOT NULL)
            AND NOT EXISTS (SELECT 1 FROM file_purge_targets WHERE file_id = f.id)
            AND NOT EXISTS (SELECT 1 FROM file_purge_work WHERE file_id = f.id)
            AND NOT EXISTS (SELECT 1 FROM folder_purge_files WHERE file_id = f.id)
            AND NOT EXISTS (SELECT 1 FROM upload_cleanup_intents WHERE file_id = f.id))
$$;

CREATE FUNCTION guard_multipart_session() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'multipart identity is retained' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'RESERVED' OR NEW.revision <> 0 OR NEW.initiation_id IS NOT NULL
            OR NEW.garage_upload_id IS NOT NULL OR NEW.decision_id IS NOT NULL THEN
            RAISE EXCEPTION 'multipart session must begin reserved' USING ERRCODE = '23514';
        END IF;
        NEW.created_at := clock_timestamp();
        NEW.expires_at := NEW.created_at + interval '24 hours';
    ELSE
        IF (to_jsonb(NEW) - ARRAY['state','revision','initiation_id','garage_upload_id','decision_id','decided_at'])
            IS DISTINCT FROM
            (to_jsonb(OLD) - ARRAY['state','revision','initiation_id','garage_upload_id','decision_id','decided_at'])
            OR (OLD.initiation_id IS NOT NULL AND NEW.initiation_id IS DISTINCT FROM OLD.initiation_id)
            OR (OLD.garage_upload_id IS NOT NULL AND NEW.garage_upload_id IS DISTINCT FROM OLD.garage_upload_id)
            OR (OLD.decision_id IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
            RAISE EXCEPTION 'multipart identity and decisions are immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
        IF NEW.revision <> OLD.revision + 1 OR NOT (
            (OLD.state = 'RESERVED' AND NEW.state = 'INITIATING' AND NEW.initiation_id IS NOT NULL)
            OR (OLD.state = 'INITIATING' AND NEW.state = 'OPEN' AND NEW.garage_upload_id IS NOT NULL)
            OR (OLD.state = 'OPEN' AND NEW.state = 'COMPLETION_DECIDED')
            OR (OLD.state IN ('RESERVED','INITIATING','OPEN') AND NEW.state = 'ABANDONMENT_DECIDED'))
            OR (NEW.initiation_id IS DISTINCT FROM OLD.initiation_id AND NEW.state <> 'INITIATING')
            OR (NEW.garage_upload_id IS DISTINCT FROM OLD.garage_upload_id AND NEW.state <> 'OPEN') THEN
            RAISE EXCEPTION 'invalid multipart transition or revision' USING ERRCODE = '23514';
        END IF;
        IF NEW.decision_id IS NOT NULL THEN NEW.decided_at := clock_timestamp(); END IF;
        IF NEW.state <> 'ABANDONMENT_DECIDED' AND OLD.expires_at <= clock_timestamp() THEN
            RAISE EXCEPTION 'multipart authorization expired' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NOT multipart_session_eligible(NEW) THEN
        RAISE EXCEPTION 'multipart metadata is not eligible' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_multipart_session BEFORE INSERT OR UPDATE OR DELETE ON upload_sessions
FOR EACH ROW EXECUTE FUNCTION guard_multipart_session();

CREATE FUNCTION guard_allocation_protocol() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.upload_protocol = 'DIRECT_MULTIPART' THEN
        RAISE EXCEPTION 'storage allocations are retained' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF TG_OP = 'UPDATE' AND NEW.upload_protocol <> OLD.upload_protocol THEN
        RAISE EXCEPTION 'allocation protocol is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_allocation_protocol BEFORE UPDATE OR DELETE ON file_storage_objects
FOR EACH ROW EXECUTE FUNCTION guard_allocation_protocol();

-- Deferred solely to permit atomic allocation + session insertion and initiation admission.
CREATE FUNCTION verify_multipart_pair() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allocation file_storage_objects; session upload_sessions;
BEGIN
    SELECT * INTO allocation FROM file_storage_objects WHERE file_id = NEW.file_id;
    SELECT * INTO session FROM upload_sessions WHERE file_id = NEW.file_id;
    IF (allocation.upload_protocol = 'DIRECT_MULTIPART') IS DISTINCT FROM (session.id IS NOT NULL)
        OR (session.id IS NOT NULL AND allocation.write_started <> (session.initiation_id IS NOT NULL)) THEN
        RAISE EXCEPTION 'multipart allocation requires exactly one matching session/admission' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_allocation_multipart_pair AFTER INSERT OR UPDATE ON file_storage_objects
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_multipart_pair();
CREATE CONSTRAINT TRIGGER trg_session_multipart_pair AFTER INSERT OR UPDATE ON upload_sessions
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_multipart_pair();

-- M1 has no multipart completion evidence or safe removal proof. Fail closed on every path.
CREATE FUNCTION fence_unfinished_multipart_file() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM file_storage_objects WHERE file_id = CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END
        AND upload_protocol = 'DIRECT_MULTIPART') AND (TG_OP = 'DELETE' OR NEW.status = 'READY') THEN
        RAISE EXCEPTION 'multipart completion/removal evidence is not implemented' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_unfinished_multipart_file BEFORE INSERT OR UPDATE OR DELETE ON files
FOR EACH ROW EXECUTE FUNCTION fence_unfinished_multipart_file();

CREATE FUNCTION fence_multipart_legacy_proof() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM file_storage_objects WHERE file_id = NEW.file_id AND upload_protocol = 'DIRECT_MULTIPART') THEN
        RAISE EXCEPTION 'legacy proof cannot adopt multipart allocation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_no_multipart_purge_target BEFORE INSERT OR UPDATE ON file_purge_targets
FOR EACH ROW EXECUTE FUNCTION fence_multipart_legacy_proof();
CREATE TRIGGER trg_no_multipart_purge_work BEFORE INSERT OR UPDATE ON file_purge_work
FOR EACH ROW EXECUTE FUNCTION fence_multipart_legacy_proof();
CREATE TRIGGER trg_no_multipart_cleanup BEFORE INSERT OR UPDATE ON upload_cleanup_intents
FOR EACH ROW EXECUTE FUNCTION fence_multipart_legacy_proof();
