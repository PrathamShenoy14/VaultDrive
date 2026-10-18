-- V27 decisions remain permanent. M3 progress is separate from the immutable session identity.
CREATE TABLE multipart_completions (
    session_id UUID PRIMARY KEY REFERENCES upload_sessions(id),
    decision_id UUID NOT NULL UNIQUE,
    garage_upload_id TEXT NOT NULL,
    manifest JSONB NOT NULL CHECK (jsonb_typeof(manifest) = 'array' AND jsonb_array_length(manifest) BETWEEN 1 AND 640),
    manifest_hash VARCHAR(64) NOT NULL CHECK (manifest_hash ~ '^[0-9a-f]{64}$'),
    -- Garage 2.3.0: Base64 SHA-256 of concatenated binary part digests, without a -N suffix.
    expected_sha256 VARCHAR(44) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    dispatch_id UUID UNIQUE,
    dispatch_decided_at TIMESTAMPTZ,
    disposition VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (disposition IN ('PENDING','UNKNOWN','STORAGE_COMPLETE','BLOCKED','READY')),
    reason VARCHAR(32) CHECK (reason IN ('DISPATCH_UNCERTAIN','STORAGE_UNAVAILABLE',
        'EVIDENCE_MISMATCH','FINALIZATION_BLOCKED','AUTHORIZATION_EXPIRED','IDENTITY_MISMATCH')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((dispatch_id IS NULL) = (dispatch_decided_at IS NULL)),
    CHECK ((disposition IN ('UNKNOWN','BLOCKED')) = (reason IS NOT NULL))
);

CREATE TABLE multipart_completion_receipts (
    session_id UUID PRIMARY KEY REFERENCES multipart_completions(session_id),
    file_id UUID NOT NULL REFERENCES file_storage_objects(file_id),
    decision_id UUID NOT NULL,
    dispatch_id UUID NOT NULL,
    initiation_id UUID NOT NULL,
    garage_upload_id TEXT NOT NULL,
    storage_identity JSONB NOT NULL CHECK (jsonb_typeof(storage_identity) = 'object'),
    manifest_hash VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 5368709120),
    sha256 VARCHAR(44) NOT NULL CHECK (sha256 ~ '^[A-Za-z0-9+/]{43}=$'
        AND encode(decode(sha256, 'base64'), 'base64') = sha256),
    etag TEXT NOT NULL CHECK (length(btrim(etag)) BETWEEN 1 AND 1024 AND etag !~ '[[:cntrl:]]'),
    content_type VARCHAR(255) NOT NULL,
    object_metadata JSONB NOT NULL,
    evidence_kind VARCHAR(24) NOT NULL CHECK (evidence_kind IN ('COMPLETE_HEAD','HEAD_RECOVERY')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION multipart_storage_identity(s upload_sessions) RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_build_object('namespace', s.storage_namespace, 'endpoint', s.endpoint,
        'region', s.region, 'bucket', s.bucket, 'key', s.storage_key)
$$;

CREATE FUNCTION guard_multipart_completion() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s upload_sessions; part JSONB; ordinal BIGINT; registered upload_session_parts; has_receipt BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'multipart completion is retained' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO s FROM upload_sessions WHERE id = NEW.session_id FOR UPDATE;
    IF s.state IS DISTINCT FROM 'COMPLETION_DECIDED' OR NEW.decision_id IS DISTINCT FROM s.decision_id
        OR NEW.garage_upload_id IS DISTINCT FROM s.garage_upload_id THEN
        RAISE EXCEPTION 'multipart completion decision identity mismatch' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.disposition <> 'PENDING' OR NEW.reason IS NOT NULL OR NEW.revision <> 0
            OR NEW.dispatch_id IS NOT NULL OR NEW.dispatch_decided_at IS NOT NULL
            OR NOT multipart_session_eligible(s) OR s.expires_at <= clock_timestamp()
            OR NOT EXISTS (SELECT 1 FROM multipart_initiation_dispatches d
                WHERE d.session_id = s.id AND d.initiation_id = s.initiation_id)
            OR jsonb_typeof(NEW.manifest) IS DISTINCT FROM 'array'
            OR jsonb_array_length(NEW.manifest) <> s.part_count
            OR (SELECT count(*) FROM upload_session_parts WHERE session_id = s.id) <> s.part_count THEN
            RAISE EXCEPTION 'multipart manifest is not eligible' USING ERRCODE = '23514';
        END IF;
        FOR part, ordinal IN SELECT value, ordinality FROM jsonb_array_elements(NEW.manifest) WITH ORDINALITY LOOP
            SELECT * INTO registered FROM upload_session_parts WHERE session_id = s.id AND part_number = ordinal;
            IF jsonb_typeof(part) IS DISTINCT FROM 'object'
                OR (SELECT count(*) FROM jsonb_object_keys(part)) <> 5
                OR (part->>'partNumber')::integer IS DISTINCT FROM ordinal
                OR (part->>'length')::bigint IS DISTINCT FROM registered.expected_length
                OR part->>'sha256' IS DISTINCT FROM registered.expected_sha256
                OR (part->>'registrationId')::uuid IS DISTINCT FROM registered.registration_id
                OR jsonb_typeof(part->'etag') IS DISTINCT FROM 'string'
                OR length(btrim(part->>'etag')) NOT BETWEEN 1 AND 1024 OR part->>'etag' ~ '[[:cntrl:]]' THEN
                RAISE EXCEPTION 'multipart manifest differs from immutable part expectations' USING ERRCODE = '23514';
            END IF;
        END LOOP;
        NEW.manifest_hash := encode(sha256(convert_to(NEW.manifest::text, 'UTF8')), 'hex');
        SELECT encode(sha256(string_agg(decode(expected_sha256, 'base64'), ''::bytea ORDER BY part_number)), 'base64')
            INTO NEW.expected_sha256 FROM upload_session_parts WHERE session_id = s.id;
        NEW.created_at := clock_timestamp(); NEW.updated_at := NEW.created_at;
        RETURN NEW;
    END IF;

    IF (to_jsonb(NEW) - ARRAY['dispatch_id','dispatch_decided_at','disposition','reason','revision','updated_at'])
        IS DISTINCT FROM
        (to_jsonb(OLD) - ARRAY['dispatch_id','dispatch_decided_at','disposition','reason','revision','updated_at'])
        OR (OLD.dispatch_id IS NOT NULL AND ROW(NEW.dispatch_id, NEW.dispatch_decided_at)
            IS DISTINCT FROM ROW(OLD.dispatch_id, OLD.dispatch_decided_at)) THEN
        RAISE EXCEPTION 'multipart manifest and dispatch identity are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    IF OLD.disposition = 'READY' OR NEW.revision <> OLD.revision + 1
        OR ROW(NEW.dispatch_id, NEW.disposition, NEW.reason) IS NOT DISTINCT FROM ROW(OLD.dispatch_id, OLD.disposition, OLD.reason)
        OR NEW.disposition = 'PENDING' THEN
        RAISE EXCEPTION 'multipart progress cannot regress or increment without a change' USING ERRCODE = '23514';
    END IF;
    SELECT EXISTS (SELECT 1 FROM multipart_completion_receipts WHERE session_id = s.id) INTO has_receipt;
    IF OLD.dispatch_id IS NULL AND NEW.dispatch_id IS NOT NULL THEN
        IF NOT multipart_session_eligible(s) OR s.expires_at <= clock_timestamp() OR has_receipt
            OR NEW.disposition <> 'UNKNOWN' OR NEW.reason IS DISTINCT FROM 'DISPATCH_UNCERTAIN' THEN
            RAISE EXCEPTION 'multipart completion dispatch is not eligible' USING ERRCODE = '23514';
        END IF;
        NEW.dispatch_decided_at := clock_timestamp();
    END IF;
    IF (NEW.disposition = 'UNKNOWN' AND (NEW.dispatch_id IS NULL OR has_receipt))
        OR (NEW.disposition IN ('STORAGE_COMPLETE','READY') AND NOT has_receipt)
        OR (NEW.disposition = 'READY' AND NOT EXISTS (SELECT 1 FROM files WHERE id = s.file_id AND status = 'READY')) THEN
        RAISE EXCEPTION 'multipart progress lacks matching evidence' USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_multipart_completion BEFORE INSERT OR UPDATE OR DELETE ON multipart_completions
FOR EACH ROW EXECUTE FUNCTION guard_multipart_completion();

CREATE FUNCTION guard_multipart_completion_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s upload_sessions; c multipart_completions;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'multipart receipt is immutable and retained' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO s FROM upload_sessions WHERE id = NEW.session_id;
    SELECT * INTO c FROM multipart_completions WHERE session_id = s.id FOR UPDATE;
    IF s.state IS DISTINCT FROM 'COMPLETION_DECIDED' OR c.dispatch_id IS NULL
        OR ROW(NEW.file_id, NEW.decision_id, NEW.dispatch_id, NEW.initiation_id, NEW.garage_upload_id,
            NEW.manifest_hash, NEW.size_bytes, NEW.sha256, NEW.content_type)
        IS DISTINCT FROM ROW(s.file_id, s.decision_id, c.dispatch_id, s.initiation_id, s.garage_upload_id,
            c.manifest_hash, s.declared_size, c.expected_sha256, s.declared_type)
        OR NEW.storage_identity IS DISTINCT FROM multipart_storage_identity(s)
        OR NEW.object_metadata->>'vaultdrive-session' IS DISTINCT FROM s.id::text
        OR NEW.object_metadata->>'vaultdrive-initiation' IS DISTINCT FROM s.initiation_id::text
        OR NOT EXISTS (SELECT 1 FROM file_storage_objects a WHERE a.file_id = s.file_id
            AND a.upload_protocol = 'DIRECT_MULTIPART' AND a.write_started
            AND ROW(a.owner_id, a.storage_key, a.storage_namespace, a.endpoint, a.region, a.bucket)
                IS NOT DISTINCT FROM ROW(s.owner_id, s.storage_key, s.storage_namespace, s.endpoint, s.region, s.bucket)) THEN
        RAISE EXCEPTION 'multipart receipt identity or positive object evidence mismatch' USING ERRCODE = '23514';
    END IF;
    -- Facts are retained even if folder trash/purge now blocks READY. No expiry test grants deletion authority.
    NEW.recorded_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_multipart_completion_receipt BEFORE INSERT OR UPDATE OR DELETE ON multipart_completion_receipts
FOR EACH ROW EXECUTE FUNCTION guard_multipart_completion_receipt();

-- Completed-session reads follow the current file location, like existing READY file APIs.
CREATE FUNCTION multipart_ready_accessible(s upload_sessions) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    WITH RECURSIVE f AS (SELECT * FROM files WHERE id = s.file_id), ancestry AS (
        SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
            ARRAY[p.id] AS path, false AS cycle, 1 AS depth
        FROM folders p JOIN f ON p.id = f.folder_id AND p.owner_id = f.owner_id
        UNION ALL
        SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
            a.path || p.id, p.id = ANY(a.path), a.depth + 1
        FROM ancestry a JOIN folders p ON p.id = a.parent_folder_id
        WHERE p.owner_id = s.owner_id AND NOT a.cycle AND a.depth < 128
    )
    SELECT EXISTS (SELECT 1 FROM f JOIN file_storage_objects a ON a.file_id = f.id
        JOIN multipart_completions c ON c.session_id = s.id
        JOIN multipart_completion_receipts r ON r.session_id = c.session_id
        WHERE f.owner_id = s.owner_id AND f.storage_key = s.storage_key AND f.status = 'READY'
            AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL
            AND a.upload_protocol = 'DIRECT_MULTIPART'
            AND ROW(a.owner_id, a.storage_key, a.storage_namespace, a.endpoint, a.region, a.bucket)
                IS NOT DISTINCT FROM ROW(s.owner_id, s.storage_key, s.storage_namespace, s.endpoint, s.region, s.bucket)
            AND EXISTS (SELECT 1 FROM users WHERE id = s.owner_id)
            AND (f.folder_id IS NULL OR EXISTS (SELECT 1 FROM ancestry WHERE parent_folder_id IS NULL))
            AND NOT EXISTS (SELECT 1 FROM ancestry WHERE cycle OR deleted_at IS NOT NULL OR purge_requested_at IS NOT NULL))
$$;

-- Replace only the M1 READY prohibition. All multipart removal and legacy purge/cleanup fences remain.
CREATE OR REPLACE FUNCTION fence_unfinished_multipart_file() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s upload_sessions;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM file_storage_objects WHERE file_id = CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END
        AND upload_protocol = 'DIRECT_MULTIPART') THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'multipart removal proof is not implemented' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'READY' THEN
        SELECT * INTO s FROM upload_sessions WHERE file_id = NEW.id;
        IF NOT EXISTS (SELECT 1 FROM multipart_completion_receipts r JOIN multipart_completions c ON c.session_id = r.session_id
            WHERE r.session_id = s.id AND r.file_id = NEW.id AND r.manifest_hash = c.manifest_hash
                AND r.dispatch_id = c.dispatch_id AND s.state = 'COMPLETION_DECIDED') THEN
            RAISE EXCEPTION 'multipart READY requires protocol-specific receipt' USING ERRCODE = '23514';
        END IF;
        IF TG_OP = 'INSERT' OR (OLD.status <> 'READY' AND
            (OLD.status <> 'UPLOADING' OR NOT multipart_session_eligible(s)
                OR OLD.version <> s.original_file_version OR NEW.version <> s.original_file_version + 1
                OR (to_jsonb(NEW) - ARRAY['status','version','updated_at'])
                    IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status','version','updated_at']))) THEN
            RAISE EXCEPTION 'multipart READY metadata is not freshly eligible' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_OP = 'UPDATE' AND OLD.status = 'READY' THEN
        RAISE EXCEPTION 'multipart readiness cannot regress' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
