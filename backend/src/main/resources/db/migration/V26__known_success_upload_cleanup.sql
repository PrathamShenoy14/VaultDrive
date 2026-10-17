-- Restricted B4B authority, separate from purge and never-written evidence.
-- The allocation FK is deliberately retained even after file metadata disappears.
CREATE TABLE upload_cleanup_intents (
    file_id UUID PRIMARY KEY REFERENCES file_storage_objects(file_id),
    file_snapshot JSONB NOT NULL CHECK (jsonb_typeof(file_snapshot) = 'object'),
    put_succeeded_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(40) NOT NULL CHECK (reason = 'RECEIPT_BACKED_FAILED'),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE upload_cleanup_progress (
    file_id UUID PRIMARY KEY REFERENCES upload_cleanup_intents(file_id),
    claim_token UUID,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    lease_until TIMESTAMPTZ,
    retry_after TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_failure VARCHAR(24) CHECK (last_failure IN ('STORAGE_FAILURE')),
    CHECK ((claim_token IS NULL) = (lease_until IS NULL)),
    CHECK (claim_token IS NULL OR revision > 0)
);

CREATE TABLE upload_cleanup_receipts (
    file_id UUID PRIMARY KEY REFERENCES upload_cleanup_intents(file_id),
    claim_token UUID NOT NULL,
    claim_revision BIGINT NOT NULL CHECK (claim_revision > 0),
    outcome VARCHAR(32) NOT NULL CHECK (outcome = 'DELETED_OR_ABSENT'),
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- JSON timestamp strings otherwise depend on the connection's TimeZone setting.
CREATE FUNCTION upload_cleanup_file_snapshot(f files) RETURNS JSONB LANGUAGE sql STABLE AS $$
    SELECT to_jsonb(f) || jsonb_build_object(
        'created_at', extract(epoch FROM f.created_at), 'updated_at', extract(epoch FROM f.updated_at),
        'deleted_at', extract(epoch FROM f.deleted_at), 'purge_requested_at', extract(epoch FROM f.purge_requested_at))
$$;

-- Called only after shared hierarchy -> fresh file -> allocation coordination.
-- One FAILED transition from the admitted version is the only accepted history.
CREATE FUNCTION upload_cleanup_eligible(file UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    WITH RECURSIVE f AS (SELECT * FROM files WHERE id = file), ancestry AS (
        SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
            ARRAY[p.id] AS path, false AS cycle, 1 AS depth
        FROM folders p JOIN f ON p.id = f.folder_id AND p.owner_id = f.owner_id
        UNION ALL
        SELECT p.id, p.parent_folder_id, p.deleted_at, p.purge_requested_at,
            a.path || p.id, p.id = ANY(a.path), a.depth + 1
        FROM ancestry a JOIN folders p ON p.id = a.parent_folder_id
        JOIN f ON p.owner_id = f.owner_id WHERE NOT a.cycle AND a.depth < 128
    )
    SELECT EXISTS (SELECT 1 FROM f JOIN file_storage_objects a ON a.file_id = f.id
        WHERE f.owner_id = a.owner_id AND f.storage_key = a.storage_key
            AND EXISTS (SELECT 1 FROM users WHERE id = f.owner_id)
            AND f.status = 'FAILED' AND f.version = a.admitted_file_version + 1
            AND f.folder_id IS NOT DISTINCT FROM a.admitted_folder_id
            AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL
            AND a.write_started AND a.put_succeeded_at IS NOT NULL
            AND num_nonnulls(a.storage_namespace, a.endpoint, a.region, a.bucket) = 4
            AND a.write_precluded_at IS NULL AND a.reservation_expired_at IS NULL
            AND a.expired_file_version IS NULL
            AND (f.folder_id IS NULL OR EXISTS (SELECT 1 FROM ancestry WHERE parent_folder_id IS NULL))
            AND NOT EXISTS (SELECT 1 FROM ancestry WHERE cycle OR deleted_at IS NOT NULL OR purge_requested_at IS NOT NULL)
            AND NOT EXISTS (SELECT 1 FROM file_purge_targets WHERE file_id = f.id)
            AND NOT EXISTS (SELECT 1 FROM file_purge_work WHERE file_id = f.id)
            AND NOT EXISTS (SELECT 1 FROM folder_purge_files WHERE file_id = f.id))
$$;

CREATE FUNCTION guard_upload_cleanup_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'upload cleanup intent is permanent and immutable' USING ERRCODE = '23514';
    END IF;
    -- Serialize against every files UPDATE, including retained READY paths.
    PERFORM id FROM files WHERE id = NEW.file_id FOR UPDATE;
    PERFORM file_id FROM file_storage_objects WHERE file_id = NEW.file_id FOR UPDATE;
    IF NOT upload_cleanup_eligible(NEW.file_id) OR NOT EXISTS (
        SELECT 1 FROM files f JOIN file_storage_objects a ON a.file_id = f.id
        WHERE f.id = NEW.file_id AND NEW.file_snapshot = upload_cleanup_file_snapshot(f)
            AND NEW.put_succeeded_at = a.put_succeeded_at) THEN
        RAISE EXCEPTION 'cleanup requires exact receipt-backed failed metadata' USING ERRCODE = '23514';
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_upload_cleanup_intent BEFORE INSERT OR UPDATE OR DELETE ON upload_cleanup_intents
FOR EACH ROW EXECUTE FUNCTION guard_upload_cleanup_intent();

-- All READY paths (including JPA entity writes and markReady) meet this final guard.
-- Preserve the accepted FAILED snapshot; folder manifests may subsequently copy it.
CREATE FUNCTION fence_abandoned_upload() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM upload_cleanup_intents i WHERE i.file_id = NEW.id
        AND i.file_snapshot IS DISTINCT FROM upload_cleanup_file_snapshot(NEW)) THEN
        RAISE EXCEPTION 'abandoned upload metadata cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_fence_abandoned_upload BEFORE INSERT OR UPDATE ON files
FOR EACH ROW EXECUTE FUNCTION fence_abandoned_upload();

CREATE FUNCTION guard_upload_cleanup_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'upload cleanup receipt is permanent and immutable' USING ERRCODE = '23514';
    END IF;
    -- Claim lock is LAST; check PostgreSQL time after waiting for it.
    PERFORM file_id FROM upload_cleanup_progress WHERE file_id = NEW.file_id FOR UPDATE;
    IF NOT EXISTS (SELECT 1 FROM upload_cleanup_progress p WHERE p.file_id = NEW.file_id
        AND p.claim_token = NEW.claim_token AND p.revision = NEW.claim_revision
        AND p.lease_until > clock_timestamp()) THEN
        RAISE EXCEPTION 'upload cleanup claim is stale' USING ERRCODE = '23514';
    END IF;
    NEW.deleted_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_upload_cleanup_receipt BEFORE INSERT OR UPDATE OR DELETE ON upload_cleanup_receipts
FOR EACH ROW EXECUTE FUNCTION guard_upload_cleanup_receipt();

CREATE FUNCTION guard_upload_cleanup_progress() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'upload cleanup progress is retained' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.file_id <> OLD.file_id OR NEW.revision < OLD.revision
        OR (NEW.claim_token IS NOT NULL AND NEW.claim_token IS DISTINCT FROM OLD.claim_token
            AND NEW.revision <> OLD.revision + 1)
        OR (NEW.claim_token IS NOT DISTINCT FROM OLD.claim_token AND NEW.revision <> OLD.revision)) THEN
        RAISE EXCEPTION 'upload cleanup claims cannot regress' USING ERRCODE = '23514';
    END IF;
    IF NEW.lease_until > clock_timestamp() + interval '30 seconds' THEN
        RAISE EXCEPTION 'upload cleanup lease exceeds bound' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_upload_cleanup_progress BEFORE INSERT OR UPDATE OR DELETE ON upload_cleanup_progress
FOR EACH ROW EXECUTE FUNCTION guard_upload_cleanup_progress();
