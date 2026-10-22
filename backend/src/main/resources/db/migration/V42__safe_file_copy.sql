-- A separate protocol: no legacy PUT or multipart evidence is reclassified.
ALTER TABLE file_storage_objects DROP CONSTRAINT file_storage_objects_upload_protocol_check;
ALTER TABLE file_storage_objects ADD CONSTRAINT file_storage_objects_upload_protocol_check
    CHECK (upload_protocol IN ('SERVER_PUT','DIRECT_MULTIPART','COPY'));

CREATE TABLE file_copy_operations (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    actor_display_name VARCHAR(100) NOT NULL,
    source_file_id UUID NOT NULL REFERENCES file_storage_objects(file_id),
    file_id UUID NOT NULL UNIQUE REFERENCES file_storage_objects(file_id),
    folder_id UUID,
    name VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 5368709120),
    content_type VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[!-~]{1,128}$'),
    state VARCHAR(24) NOT NULL DEFAULT 'RESERVED' CHECK (state IN
        ('RESERVED','UNKNOWN','STORAGE_COMPLETE','BLOCKED','READY','CANCELLED','FAILED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    dispatched_at TIMESTAMPTZ,
    source_etag VARCHAR(1024),
    receipt_at TIMESTAMPTZ,
    object_etag VARCHAR(1024),
    UNIQUE(owner_id,idempotency_key),
    CHECK (source_file_id <> file_id),
    CHECK ((dispatched_at IS NULL) = (source_etag IS NULL)),
    CHECK ((receipt_at IS NULL) = (object_etag IS NULL)),
    CHECK (receipt_at IS NULL OR dispatched_at IS NOT NULL),
    CHECK (state NOT IN ('UNKNOWN','STORAGE_COMPLETE','READY') OR dispatched_at IS NOT NULL),
    CHECK (state NOT IN ('STORAGE_COMPLETE','READY') OR receipt_at IS NOT NULL),
    CHECK (state NOT IN ('RESERVED','FAILED') OR dispatched_at IS NULL)
);

CREATE FUNCTION copy_destination_eligible(c file_copy_operations) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM files f JOIN file_storage_objects a ON a.file_id = f.id
        WHERE f.id = c.file_id AND f.owner_id = c.owner_id AND f.status = 'UPLOADING' AND f.version = 0
            AND f.folder_id IS NOT DISTINCT FROM c.folder_id AND f.name = c.name
            AND f.size_bytes = c.size_bytes AND f.content_type = c.content_type
            AND f.storage_key = a.storage_key AND a.upload_protocol = 'COPY' AND a.owner_id = c.owner_id
            AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL
            AND EXISTS (SELECT 1 FROM users WHERE id = c.owner_id)
            AND (c.folder_id IS NULL OR EXISTS (SELECT 1 FROM resolve_resource_access(c.owner_id,NULL,c.folder_id)))
            AND NOT EXISTS (SELECT 1 FROM file_purge_work WHERE file_id = f.id)
            AND NOT EXISTS (SELECT 1 FROM folder_purge_files WHERE file_id = f.id))
$$;

CREATE FUNCTION copy_completed_allocation(file UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM file_copy_operations c JOIN file_storage_objects a ON a.file_id = c.file_id
        WHERE c.file_id = file AND a.upload_protocol = 'COPY' AND a.write_started
            AND a.owner_id = c.owner_id AND c.receipt_at IS NOT NULL)
$$;

CREATE FUNCTION guard_file_copy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'copy evidence is retained' USING ERRCODE = '23514'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'RESERVED' OR NEW.revision <> 0 OR NEW.dispatched_at IS NOT NULL OR NEW.receipt_at IS NOT NULL
            OR NOT copy_destination_eligible(NEW) OR NOT EXISTS (
                SELECT 1 FROM files f JOIN file_storage_objects a ON a.file_id = f.id
                WHERE f.id = NEW.source_file_id AND f.owner_id = NEW.owner_id AND f.status = 'READY'
                    AND f.size_bytes = NEW.size_bytes AND f.content_type = NEW.content_type AND f.storage_key = a.storage_key
                    AND a.owner_id = NEW.owner_id AND a.storage_namespace IS NOT NULL AND a.write_started
                    AND EXISTS (SELECT 1 FROM resolve_resource_access(NEW.owner_id,f.id,NULL))) THEN
            RAISE EXCEPTION 'copy requires eligible independent source and destination' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - ARRAY['state','revision','dispatched_at','source_etag','receipt_at','object_etag'])
        IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','revision','dispatched_at','source_etag','receipt_at','object_etag'])
        OR NEW.revision <> OLD.revision + 1
        OR (OLD.dispatched_at IS NOT NULL AND ROW(NEW.dispatched_at,NEW.source_etag)
            IS DISTINCT FROM ROW(OLD.dispatched_at,OLD.source_etag))
        OR (OLD.receipt_at IS NOT NULL AND ROW(NEW.receipt_at,NEW.object_etag)
            IS DISTINCT FROM ROW(OLD.receipt_at,OLD.object_etag))
        OR (OLD.state IN ('READY','CANCELLED','FAILED') AND NEW.state <> OLD.state) THEN
        RAISE EXCEPTION 'copy identity, terminal decision and evidence are immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.dispatched_at IS NULL AND NEW.dispatched_at IS NOT NULL THEN
        IF OLD.state <> 'RESERVED' OR NEW.state <> 'UNKNOWN' OR NOT copy_destination_eligible(NEW)
            OR NOT EXISTS (SELECT 1 FROM resolve_resource_access(NEW.owner_id,NEW.source_file_id,NULL)) THEN
            RAISE EXCEPTION 'copy dispatch is ineligible' USING ERRCODE = '23514';
        END IF;
        NEW.dispatched_at := clock_timestamp();
    END IF;
    IF OLD.receipt_at IS NULL AND NEW.receipt_at IS NOT NULL THEN
        IF OLD.dispatched_at IS NULL THEN RAISE EXCEPTION 'copy receipt lacks dispatch' USING ERRCODE = '23514'; END IF;
        NEW.receipt_at := clock_timestamp();
    END IF;
    IF NEW.state = 'READY' AND NOT EXISTS (SELECT 1 FROM files WHERE id = NEW.file_id AND status = 'READY') THEN
        RAISE EXCEPTION 'copy is not READY' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_file_copy BEFORE INSERT OR UPDATE OR DELETE ON file_copy_operations
FOR EACH ROW EXECUTE FUNCTION guard_file_copy();

CREATE FUNCTION verify_copy_pair() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a file_storage_objects; c file_copy_operations;
BEGIN
    SELECT * INTO a FROM file_storage_objects WHERE file_id = NEW.file_id;
    SELECT * INTO c FROM file_copy_operations WHERE file_id = NEW.file_id;
    IF (a.upload_protocol = 'COPY') IS DISTINCT FROM (c.id IS NOT NULL)
        OR (c.id IS NOT NULL AND (a.owner_id <> c.owner_id OR a.write_started <> (c.dispatched_at IS NOT NULL))) THEN
        RAISE EXCEPTION 'copy requires matching allocation and dispatch' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_allocation_copy_pair AFTER INSERT OR UPDATE ON file_storage_objects
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_copy_pair();
CREATE CONSTRAINT TRIGGER trg_operation_copy_pair AFTER INSERT OR UPDATE ON file_copy_operations
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_copy_pair();

-- COPY allocations are permanently retained. Legacy proof writers cannot adopt them.
CREATE FUNCTION guard_copy_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.upload_protocol = 'COPY' THEN
        IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'copy allocation is retained' USING ERRCODE = '23514'; END IF;
        IF NEW.put_succeeded_at IS NOT NULL OR NEW.write_precluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'copy cannot use PUT evidence' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_copy_allocation BEFORE UPDATE OR DELETE ON file_storage_objects
FOR EACH ROW EXECUTE FUNCTION guard_copy_allocation();

-- Unfinished folder members retain a separate logical receipt, never byte proof.
ALTER TABLE file_purge_work ADD COLUMN copy_discarded_at TIMESTAMPTZ;
ALTER TABLE file_purge_work ADD CONSTRAINT chk_copy_discard CHECK (copy_discarded_at IS NULL OR
    (file_status <> 'READY' AND bytes_deleted_at IS NULL AND never_written_at IS NULL AND multipart_discarded_at IS NULL));
ALTER TABLE file_purge_work DROP CONSTRAINT chk_file_purge_finalization;
ALTER TABLE file_purge_work ADD CONSTRAINT chk_file_purge_finalization CHECK (
    metadata_finalized_at IS NULL OR (coalesce(bytes_deleted_at,never_written_at,multipart_discarded_at,copy_discarded_at) IS NOT NULL
        AND metadata_finalized_at >= coalesce(bytes_deleted_at,never_written_at,multipart_discarded_at,copy_discarded_at)));

CREATE FUNCTION copy_discard_verified(w file_purge_work) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT w.file_status <> 'READY' AND EXISTS (
        SELECT 1 FROM file_copy_operations c JOIN file_storage_objects a ON a.file_id = c.file_id
        JOIN folder_purge_files m ON m.file_id = a.file_id JOIN folder_purge_runs r ON r.event_id = m.event_id
        WHERE a.file_id = w.file_id AND a.upload_protocol = 'COPY' AND a.owner_id = c.owner_id AND c.state IN ('CANCELLED','FAILED')
            AND r.discovery_sealed_at IS NOT NULL AND r.phase IN ('DELETE_FILES','DELETE_FOLDERS','FINALIZED')
            AND ROW(w.owner_id,w.folder_id,w.file_version,w.file_status,w.file_created_at,w.file_updated_at,
                w.deleted_at,w.purge_requested_at,w.storage_namespace,w.endpoint,w.region,w.bucket,w.storage_key)
            IS NOT DISTINCT FROM ROW(m.owner_id,m.folder_id,m.file_version,m.file_status,m.file_created_at,m.file_updated_at,
                m.deleted_at,m.purge_requested_at,m.storage_namespace,m.endpoint,m.region,m.bucket,m.storage_key)
            AND ROW(a.owner_id,a.storage_key,a.storage_namespace,a.endpoint,a.region,a.bucket)
            IS NOT DISTINCT FROM ROW(w.owner_id,w.storage_key,w.storage_namespace,w.endpoint,w.region,w.bucket))
$$;

CREATE FUNCTION guard_copy_proof() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM file_storage_objects WHERE file_id = NEW.file_id AND upload_protocol = 'COPY') THEN
        IF TG_TABLE_NAME = 'file_purge_work' THEN
            IF NEW.copy_discarded_at IS NOT NULL THEN
                RAISE EXCEPTION 'copy proof requires copy allocation' USING ERRCODE = '23514';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME = 'upload_cleanup_intents' THEN
        RAISE EXCEPTION 'PUT cleanup cannot adopt copy' USING ERRCODE = '23514';
    ELSIF TG_TABLE_NAME = 'file_purge_targets' THEN
        IF NOT copy_completed_allocation(NEW.file_id) OR NOT EXISTS (SELECT 1 FROM files WHERE id = NEW.file_id AND status = 'READY') THEN
            RAISE EXCEPTION 'copy target requires completed READY' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.never_written_at IS NOT NULL OR NEW.multipart_discarded_at IS NOT NULL
            OR (NEW.bytes_deleted_at IS NOT NULL AND (NEW.file_status <> 'READY' OR NOT copy_completed_allocation(NEW.file_id))) THEN
            RAISE EXCEPTION 'copy physical proof requires completed READY' USING ERRCODE = '23514';
        END IF;
        IF TG_OP = 'UPDATE' AND OLD.copy_discarded_at IS NOT NULL AND NEW.copy_discarded_at IS DISTINCT FROM OLD.copy_discarded_at THEN
            RAISE EXCEPTION 'copy discard is immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.copy_discarded_at IS NOT NULL AND NOT copy_discard_verified(NEW) THEN
            RAISE EXCEPTION 'copy discard requires sealed identity' USING ERRCODE = '23514';
        END IF;
        IF NEW.copy_discarded_at IS NOT NULL AND (TG_OP = 'INSERT' OR OLD.copy_discarded_at IS NULL) THEN
            IF NOT EXISTS (SELECT 1 FROM files f WHERE f.id = NEW.file_id AND
                ROW(f.owner_id,f.folder_id,f.version,f.status,f.created_at,f.updated_at,f.deleted_at,f.purge_requested_at,f.storage_key)
                IS NOT DISTINCT FROM ROW(NEW.owner_id,NEW.folder_id,NEW.file_version,NEW.file_status,NEW.file_created_at,
                    NEW.file_updated_at,NEW.deleted_at,NEW.purge_requested_at,NEW.storage_key)) THEN
                RAISE EXCEPTION 'copy discard requires current metadata' USING ERRCODE = '23514';
            END IF;
            NEW.copy_discarded_at := clock_timestamp();
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_copy_purge_target BEFORE INSERT OR UPDATE ON file_purge_targets FOR EACH ROW EXECUTE FUNCTION guard_copy_proof();
CREATE TRIGGER trg_copy_purge_work BEFORE INSERT OR UPDATE ON file_purge_work FOR EACH ROW EXECUTE FUNCTION guard_copy_proof();
CREATE TRIGGER trg_copy_cleanup BEFORE INSERT OR UPDATE ON upload_cleanup_intents FOR EACH ROW EXECUTE FUNCTION guard_copy_proof();

ALTER FUNCTION folder_purge_file_verified(UUID,UUID) RENAME TO pre_copy_folder_file_verified;
CREATE FUNCTION folder_purge_file_verified(event UUID,file UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT pre_copy_folder_file_verified(event,file) OR EXISTS (
        SELECT 1 FROM file_purge_work w JOIN folder_purge_files m ON m.file_id = w.file_id
        WHERE m.event_id = event AND m.file_id = file AND w.copy_discarded_at IS NOT NULL AND copy_discard_verified(w))
$$;

CREATE FUNCTION fence_copy_file() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c file_copy_operations;
BEGIN
    SELECT * INTO c FROM file_copy_operations WHERE file_id = OLD.id;
    IF c.id IS NULL THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF NOT EXISTS (SELECT 1 FROM file_purge_work w WHERE w.file_id = OLD.id AND
            ROW(OLD.owner_id,OLD.folder_id,OLD.version,OLD.status,OLD.created_at,OLD.updated_at,OLD.deleted_at,OLD.purge_requested_at,OLD.storage_key)
            IS NOT DISTINCT FROM ROW(w.owner_id,w.folder_id,w.file_version,w.file_status,w.file_created_at,w.file_updated_at,w.deleted_at,w.purge_requested_at,w.storage_key)
            AND ((OLD.status = 'READY' AND w.bytes_deleted_at IS NOT NULL AND copy_completed_allocation(OLD.id))
                OR (w.copy_discarded_at IS NOT NULL AND copy_discard_verified(w) AND c.state IN ('CANCELLED','FAILED')))) THEN
            RAISE EXCEPTION 'copy removal lacks exact proof' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.status = 'READY' AND OLD.status <> 'READY' THEN
        IF OLD.status <> 'UPLOADING' OR c.state IN ('CANCELLED','FAILED') OR c.receipt_at IS NULL
            OR NOT copy_destination_eligible(c) OR NEW.version <> OLD.version+1
            OR (to_jsonb(NEW) - ARRAY['status','version','updated_at']) IS DISTINCT FROM
                (to_jsonb(OLD) - ARRAY['status','version','updated_at']) THEN
            RAISE EXCEPTION 'copy READY requires fresh receipt-backed metadata' USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.status = 'READY' AND NEW.status <> 'READY' THEN
        RAISE EXCEPTION 'copy readiness cannot regress' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_copy_file BEFORE UPDATE OR DELETE ON files FOR EACH ROW EXECUTE FUNCTION fence_copy_file();

ALTER TABLE resource_activity_events DROP CONSTRAINT resource_activity_events_event_type_check;
ALTER TABLE resource_activity_events ADD CONSTRAINT resource_activity_events_event_type_check CHECK (event_type IN
    ('UPLOAD_COMPLETED','COPIED','FOLDER_CREATED','RENAMED','MOVED','TRASHED','RESTORED','PERMANENTLY_DELETED'));
ALTER TABLE resource_activity_events DROP CONSTRAINT chk_resource_activity_shape;
ALTER TABLE resource_activity_events ADD CONSTRAINT chk_resource_activity_shape CHECK ((

        (event_type = 'COPIED' AND resource_type = 'FILE' AND before_name IS NULL
            AND after_name IS NOT NULL AND after_status = 'READY' AND after_trashed = false)
        OR (event_type = 'FOLDER_CREATED' AND resource_type = 'FOLDER' AND before_name IS NULL
            AND after_name IS NOT NULL AND after_trashed = false)
        OR (event_type = 'UPLOAD_COMPLETED' AND resource_type = 'FILE' AND before_status = 'UPLOADING'
            AND after_status = 'READY' AND before_name = after_name
            AND before_parent_id IS NOT DISTINCT FROM after_parent_id
            AND before_trashed = false AND after_trashed = false)
        OR (event_type = 'RENAMED' AND before_name <> after_name
            AND before_parent_id IS NOT DISTINCT FROM after_parent_id
            AND before_trashed = false AND after_trashed = false
            AND before_status IS NOT DISTINCT FROM after_status)
        OR (event_type = 'MOVED' AND before_name = after_name
            AND before_parent_id IS DISTINCT FROM after_parent_id
            AND before_trashed = false AND after_trashed = false
            AND before_status IS NOT DISTINCT FROM after_status)
        OR (event_type = 'TRASHED' AND before_name = after_name
            AND before_parent_id IS NOT DISTINCT FROM after_parent_id
            AND before_trashed = false AND after_trashed = true
            AND before_status IS NOT DISTINCT FROM after_status)
        OR (event_type = 'RESTORED' AND before_name IS NOT NULL AND after_name IS NOT NULL
            AND before_trashed = true AND after_trashed = false
            AND before_status IS NOT DISTINCT FROM after_status)
        OR (event_type = 'PERMANENTLY_DELETED' AND before_name IS NOT NULL AND after_name IS NULL
            AND after_status IS NULL)
    ) IS TRUE);
CREATE UNIQUE INDEX uq_resource_activity_copy ON resource_activity_events(resource_id) WHERE event_type='COPIED';
