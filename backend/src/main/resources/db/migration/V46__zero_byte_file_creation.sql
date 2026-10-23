-- Narrow new zero-byte SERVER_PUT admission; no historical evidence is backfilled.
CREATE TABLE empty_file_creations (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL UNIQUE REFERENCES file_storage_objects(file_id),
    owner_id UUID NOT NULL,
    initiator_id UUID NOT NULL,
    actor_display_name VARCHAR(100) NOT NULL,
    folder_id UUID,
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[!-~]{1,128}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    dispatched_at TIMESTAMPTZ,
    UNIQUE(owner_id,initiator_id,idempotency_key)
);

CREATE FUNCTION empty_file_eligible(c empty_file_creations) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM files f JOIN file_storage_objects a ON a.file_id=f.id
        WHERE f.id=c.file_id AND f.owner_id=c.owner_id AND f.folder_id IS NOT DISTINCT FROM c.folder_id
            AND f.name=c.name AND f.content_type=c.content_type AND f.size_bytes=0
            AND f.status='UPLOADING' AND f.version=0 AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL
            AND a.owner_id=f.owner_id AND a.storage_key=f.storage_key AND a.upload_protocol='SERVER_PUT'
            AND a.quota_size_bytes=0 AND a.storage_namespace IS NOT NULL
            AND a.write_precluded_at IS NULL AND a.reservation_expired_at IS NULL
            AND EXISTS (SELECT 1 FROM users WHERE id=c.owner_id)
            AND (c.folder_id IS NULL OR EXISTS (SELECT 1 FROM resolve_resource_access(c.owner_id,NULL,c.folder_id)))
            AND NOT EXISTS (SELECT 1 FROM file_purge_work WHERE file_id=f.id)
            AND NOT EXISTS (SELECT 1 FROM folder_purge_files WHERE file_id=f.id))
$$;

CREATE FUNCTION guard_empty_creation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'empty creation is retained' USING ERRCODE='23514'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.dispatched_at IS NOT NULL OR NOT empty_file_eligible(NEW)
            OR EXISTS (SELECT 1 FROM file_storage_objects WHERE file_id=NEW.file_id AND write_started) THEN
            RAISE EXCEPTION 'empty creation requires fresh zero-byte allocation' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (to_jsonb(NEW)-'dispatched_at') IS DISTINCT FROM (to_jsonb(OLD)-'dispatched_at')
            OR OLD.dispatched_at IS NOT NULL OR NEW.dispatched_at IS NULL OR NOT empty_file_eligible(NEW)
            OR NOT EXISTS (SELECT 1 FROM file_storage_objects WHERE file_id=NEW.file_id
                AND write_started AND admitted_file_version=0 AND admitted_folder_id IS NOT DISTINCT FROM NEW.folder_id) THEN
            RAISE EXCEPTION 'empty creation identity and dispatch are immutable' USING ERRCODE='23514';
        END IF;
        NEW.dispatched_at:=clock_timestamp();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_empty_creation BEFORE INSERT OR UPDATE OR DELETE ON empty_file_creations
FOR EACH ROW EXECUTE FUNCTION guard_empty_creation();

CREATE FUNCTION verify_empty_dispatch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM empty_file_creations c JOIN file_storage_objects a ON a.file_id=c.file_id
        WHERE c.file_id=NEW.file_id AND (a.write_started <> (c.dispatched_at IS NOT NULL)
            OR a.upload_protocol <> 'SERVER_PUT' OR a.quota_size_bytes <> 0)) THEN
        RAISE EXCEPTION 'empty allocation and dispatch must agree' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_empty_allocation_pair AFTER INSERT OR UPDATE ON file_storage_objects
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_empty_dispatch();
CREATE CONSTRAINT TRIGGER trg_empty_creation_pair AFTER INSERT OR UPDATE ON empty_file_creations
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_empty_dispatch();

CREATE FUNCTION fence_empty_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND EXISTS (SELECT 1 FROM empty_file_creations WHERE file_id=OLD.file_id) THEN
        RAISE EXCEPTION 'empty allocation is retained' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER trg_empty_allocation_retained BEFORE DELETE ON file_storage_objects
FOR EACH ROW EXECUTE FUNCTION fence_empty_allocation();

-- V25's genuine receipt is required even for the zero-byte READY transition.
CREATE FUNCTION fence_empty_file() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c empty_file_creations;
BEGIN
    SELECT * INTO c FROM empty_file_creations WHERE file_id=OLD.id;
    IF TG_OP='DELETE' THEN
        IF c.id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM file_purge_work w JOIN file_storage_objects a ON a.file_id=w.file_id
            WHERE w.file_id=OLD.id AND ROW(OLD.owner_id,OLD.folder_id,OLD.version,OLD.status,
                OLD.created_at,OLD.updated_at,OLD.deleted_at,OLD.purge_requested_at,OLD.storage_key)
                IS NOT DISTINCT FROM ROW(w.owner_id,w.folder_id,w.file_version,w.file_status,
                    w.file_created_at,w.file_updated_at,w.deleted_at,w.purge_requested_at,w.storage_key)
                AND ((OLD.status='READY' AND w.bytes_deleted_at IS NOT NULL AND a.put_succeeded_at IS NOT NULL)
                    OR (w.never_written_at IS NOT NULL AND NOT a.write_started AND a.write_precluded_at IS NOT NULL))) THEN
            RAISE EXCEPTION 'empty removal lacks exact storage proof' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    IF c.id IS NOT NULL AND NEW.status='READY' AND OLD.status<>'READY' THEN
        IF NOT empty_file_eligible(c) OR NOT EXISTS (SELECT 1 FROM file_storage_objects
                WHERE file_id=NEW.id AND put_succeeded_at IS NOT NULL AND write_started)
            OR NEW.version<>OLD.version+1
            OR (to_jsonb(NEW)-ARRAY['status','version','updated_at']) IS DISTINCT FROM
                (to_jsonb(OLD)-ARRAY['status','version','updated_at']) THEN
            RAISE EXCEPTION 'empty READY requires positive receipt and fresh eligibility' USING ERRCODE='23514';
        END IF;
    ELSIF c.id IS NOT NULL AND OLD.status='READY' AND NEW.status<>'READY' THEN
        RAISE EXCEPTION 'empty readiness cannot regress' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_empty_file BEFORE UPDATE OR DELETE ON files FOR EACH ROW EXECUTE FUNCTION fence_empty_file();

-- Zero-byte copies still use real independent COPY objects and original copy receipts.
ALTER TABLE file_copy_operations DROP CONSTRAINT file_copy_operations_size_bytes_check;
ALTER TABLE file_copy_operations ADD CONSTRAINT file_copy_operations_size_bytes_check
    CHECK (size_bytes BETWEEN 0 AND 5368709120);
ALTER TABLE folder_copy_members DROP CONSTRAINT folder_copy_members_check1;
ALTER TABLE folder_copy_members ADD CONSTRAINT folder_copy_members_check1 CHECK (
    (kind='FILE' AND size_bytes BETWEEN 0 AND 5368709120 AND content_type IS NOT NULL)
    OR (kind='FOLDER' AND size_bytes IS NULL AND content_type IS NULL AND operation_id IS NULL));

-- Even zero-byte inserts lock/check the owner quota; counters remain unchanged.
CREATE OR REPLACE FUNCTION account_file_storage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner UUID; old_used BIGINT := 0; old_reserved BIGINT := 0;
    new_used BIGINT := 0; new_reserved BIGINT := 0; q storage_quotas;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        owner := OLD.owner_id;
        IF OLD.status = 'READY' THEN old_used := OLD.size_bytes; END IF;
        IF OLD.status = 'UPLOADING' THEN old_reserved := OLD.size_bytes; END IF;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        owner := NEW.owner_id;
        IF TG_OP = 'UPDATE' AND ROW(NEW.owner_id, NEW.size_bytes) IS DISTINCT FROM ROW(OLD.owner_id, OLD.size_bytes) THEN
            RAISE EXCEPTION 'quota ownership and length are immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.status = 'READY' THEN new_used := NEW.size_bytes; END IF;
        IF NEW.status = 'UPLOADING' THEN new_reserved := NEW.size_bytes; END IF;
    END IF;
    IF TG_OP = 'INSERT' OR ROW(old_used,old_reserved) IS DISTINCT FROM ROW(new_used,new_reserved) THEN
        -- Final accounting lock: never acquire another existing resource afterward.
        SELECT * INTO STRICT q FROM storage_quotas WHERE owner_id = owner FOR UPDATE;
        IF (TG_OP = 'INSERT' OR new_used::numeric + new_reserved > old_used::numeric + old_reserved)
            AND q.used_bytes::numeric + q.reserved_bytes + new_used + new_reserved - old_used - old_reserved > q.limit_bytes THEN
            RAISE EXCEPTION 'Storage quota exceeded' USING ERRCODE = '23514', CONSTRAINT = 'storage_quota_exceeded';
        END IF;
        UPDATE storage_quotas SET used_bytes = used_bytes - old_used + new_used,
            reserved_bytes = reserved_bytes - old_reserved + new_reserved WHERE owner_id = owner;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
