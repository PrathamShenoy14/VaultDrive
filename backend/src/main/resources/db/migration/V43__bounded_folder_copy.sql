-- Retained bounded snapshot. No resource FK/cascade may erase historical membership.
CREATE TABLE folder_copy_jobs (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    actor_display_name VARCHAR(100) NOT NULL,
    source_folder_id UUID NOT NULL,
    destination_folder_id UUID,
    folder_id UUID NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[!-~]{1,128}$'),
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','BLOCKED','FAILED','CANCELLED','UNKNOWN','COMPLETED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    checkpoint INTEGER NOT NULL DEFAULT 0 CHECK (checkpoint BETWEEN 0 AND 1000),
    member_count INTEGER NOT NULL CHECK (member_count BETWEEN 1 AND 1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    disposal_event_id UUID,
    UNIQUE(owner_id,idempotency_key),
    CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE TABLE folder_copy_members (
    job_id UUID NOT NULL REFERENCES folder_copy_jobs(id),
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 1000),
    kind VARCHAR(6) NOT NULL CHECK (kind IN ('FILE','FOLDER')),
    source_id UUID NOT NULL,
    destination_id UUID NOT NULL UNIQUE,
    parent_id UUID,
    name VARCHAR(255) NOT NULL,
    size_bytes BIGINT,
    content_type VARCHAR(255),
    operation_id UUID UNIQUE REFERENCES file_copy_operations(id),
    PRIMARY KEY(job_id,ordinal),
    UNIQUE(job_id,source_id),
    CHECK (source_id <> destination_id),
    CHECK ((kind='FILE' AND size_bytes BETWEEN 1 AND 5368709120 AND content_type IS NOT NULL)
        OR (kind='FOLDER' AND size_bytes IS NULL AND content_type IS NULL AND operation_id IS NULL))
);
ALTER TABLE file_copy_operations ADD COLUMN folder_copy_job_id UUID REFERENCES folder_copy_jobs(id);
-- Keep every previously valid public M1 key/replay. Internal keys have a separate
-- uniqueness namespace and cannot collide with caller-selected public keys.
ALTER TABLE file_copy_operations DROP CONSTRAINT file_copy_operations_owner_id_idempotency_key_key;
CREATE UNIQUE INDEX uq_copy_public_idempotency ON file_copy_operations(owner_id,idempotency_key)
    WHERE folder_copy_job_id IS NULL;
CREATE UNIQUE INDEX uq_copy_folder_idempotency ON file_copy_operations(folder_copy_job_id,source_file_id)
    WHERE folder_copy_job_id IS NOT NULL;

-- Public access always hides a staging member, including READY files and direct grants.
-- The old resolver remains private to the existing file-copy destination protocol.
ALTER FUNCTION resolve_resource_access(UUID,UUID,UUID,BOOLEAN) RENAME TO pre_folder_copy_access;
CREATE FUNCTION resolve_resource_access(actor UUID, target_file UUID, target_folder UUID,
                                        include_trash BOOLEAN DEFAULT FALSE)
RETURNS TABLE(id UUID, owner_id UUID, parent_id UUID, name VARCHAR, content_type VARCHAR,
              size_bytes BIGINT, storage_key VARCHAR, created_at TIMESTAMPTZ,
              updated_at TIMESTAMPTZ, effective_role TEXT, parent_visible BOOLEAN,
              active BOOLEAN, folder_path UUID[])
LANGUAGE sql STABLE AS $$
    SELECT a.* FROM pre_folder_copy_access(actor,target_file,target_folder,include_trash) a
    WHERE NOT EXISTS (SELECT 1 FROM folder_copy_members m JOIN folder_copy_jobs j ON j.id=m.job_id
        WHERE j.state <> 'COMPLETED' AND (m.destination_id=a.id OR m.destination_id=ANY(a.folder_path)))
$$;

-- Only a linked M1 operation may use the private staging destination. All lifecycle
-- checks from M1 remain; cancellation closes publication/dispatch before disposal.
CREATE OR REPLACE FUNCTION copy_destination_eligible(c file_copy_operations) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM files f JOIN file_storage_objects a ON a.file_id=f.id
        WHERE f.id=c.file_id AND f.owner_id=c.owner_id AND f.status='UPLOADING' AND f.version=0
            AND f.folder_id IS NOT DISTINCT FROM c.folder_id AND f.name=c.name
            AND f.size_bytes=c.size_bytes AND f.content_type=c.content_type
            AND f.storage_key=a.storage_key AND a.upload_protocol='COPY' AND a.owner_id=c.owner_id
            AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL
            AND EXISTS (SELECT 1 FROM users WHERE id=c.owner_id)
            AND (c.folder_id IS NULL OR EXISTS (SELECT 1 FROM resolve_resource_access(c.owner_id,NULL,c.folder_id))
                OR (c.folder_copy_job_id IS NOT NULL AND EXISTS (
                    SELECT 1 FROM folder_copy_members m JOIN folder_copy_jobs j ON j.id=m.job_id
                    JOIN pre_folder_copy_access(c.owner_id,NULL,c.folder_id) p ON true
                    WHERE m.job_id=c.folder_copy_job_id AND j.owner_id=c.owner_id
                        AND j.state IN ('ACTIVE','BLOCKED','UNKNOWN') AND m.kind='FOLDER' AND m.destination_id=c.folder_id)))
            AND NOT EXISTS (SELECT 1 FROM file_purge_work WHERE file_id=f.id)
            AND NOT EXISTS (SELECT 1 FROM folder_purge_files WHERE file_id=f.id))
$$;

CREATE FUNCTION guard_folder_copy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE snapshot_job folder_copy_jobs;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'folder copy evidence is retained' USING ERRCODE='23514'; END IF;
    IF TG_TABLE_NAME='folder_copy_members' THEN
        IF TG_OP='INSERT' THEN
            SELECT * INTO snapshot_job FROM folder_copy_jobs WHERE id=NEW.job_id;
            IF NEW.ordinal>snapshot_job.member_count
                OR (NEW.ordinal=1 AND ROW(NEW.source_id,NEW.destination_id,NEW.parent_id,NEW.name,NEW.kind)
                    IS DISTINCT FROM ROW(snapshot_job.source_folder_id,snapshot_job.folder_id,snapshot_job.destination_folder_id,snapshot_job.name,'FOLDER'::varchar))
                OR (NEW.ordinal>1 AND NOT EXISTS (SELECT 1 FROM folder_copy_members p WHERE p.job_id=NEW.job_id
                    AND p.kind='FOLDER' AND p.destination_id=NEW.parent_id AND p.ordinal<NEW.ordinal)) THEN
                RAISE EXCEPTION 'folder member mapping is invalid' USING ERRCODE='23514';
            END IF;
        END IF;
        IF TG_OP='UPDATE' AND ((to_jsonb(NEW)-'operation_id') IS DISTINCT FROM (to_jsonb(OLD)-'operation_id')
            OR OLD.operation_id IS NOT NULL) THEN
            RAISE EXCEPTION 'folder snapshot is immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.operation_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM file_copy_operations c
            JOIN folder_copy_jobs j ON j.id=NEW.job_id WHERE c.id=NEW.operation_id
                AND c.folder_copy_job_id=j.id AND c.owner_id=j.owner_id AND c.source_file_id=NEW.source_id
                AND c.file_id=NEW.destination_id AND c.folder_id=NEW.parent_id AND c.name=NEW.name
                AND c.size_bytes=NEW.size_bytes AND c.content_type=NEW.content_type) THEN
            RAISE EXCEPTION 'folder member requires matching M1 operation' USING ERRCODE='23514';
        END IF;
    ELSIF TG_OP='UPDATE' THEN
        IF (to_jsonb(NEW)-ARRAY['state','revision','checkpoint','completed_at','disposal_event_id']) IS DISTINCT FROM
           (to_jsonb(OLD)-ARRAY['state','revision','checkpoint','completed_at','disposal_event_id'])
            OR NEW.revision<>OLD.revision+1 OR NEW.checkpoint<OLD.checkpoint
            OR (OLD.state IN ('COMPLETED','CANCELLED','FAILED') AND NEW.state<>OLD.state)
            OR (OLD.disposal_event_id IS NOT NULL AND NEW.disposal_event_id IS DISTINCT FROM OLD.disposal_event_id)
            OR (OLD.completed_at IS NOT NULL AND NEW.completed_at IS DISTINCT FROM OLD.completed_at) THEN
            RAISE EXCEPTION 'folder copy progress cannot regress' USING ERRCODE='23514';
        END IF;
        IF NEW.state='COMPLETED' AND (NEW.checkpoint<>NEW.member_count OR EXISTS (
            SELECT 1 FROM folder_copy_members m LEFT JOIN file_copy_operations c ON c.id=m.operation_id
            LEFT JOIN files f ON f.id=m.destination_id
            WHERE m.job_id=NEW.id AND m.kind='FILE' AND (c.state IS DISTINCT FROM 'READY' OR c.receipt_at IS NULL
                OR f.status IS DISTINCT FROM 'READY' OR f.owner_id IS DISTINCT FROM NEW.owner_id
                OR f.folder_id IS DISTINCT FROM m.parent_id OR f.name IS DISTINCT FROM m.name
                OR f.size_bytes IS DISTINCT FROM m.size_bytes OR f.content_type IS DISTINCT FROM m.content_type))
            OR EXISTS (SELECT 1 FROM folder_copy_members m WHERE m.job_id=NEW.id AND m.kind='FOLDER'
                AND NOT EXISTS (SELECT 1 FROM pre_folder_copy_access(NEW.owner_id,NULL,m.destination_id) f
                    WHERE f.owner_id=NEW.owner_id AND f.parent_id IS NOT DISTINCT FROM m.parent_id AND f.name=m.name))) THEN
            RAISE EXCEPTION 'folder publication requires all receipts' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_folder_copy_job BEFORE UPDATE OR DELETE ON folder_copy_jobs FOR EACH ROW EXECUTE FUNCTION guard_folder_copy();
CREATE TRIGGER trg_folder_copy_member BEFORE INSERT OR UPDATE OR DELETE ON folder_copy_members FOR EACH ROW EXECUTE FUNCTION guard_folder_copy();

CREATE FUNCTION verify_folder_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE job UUID; j folder_copy_jobs;
BEGIN
    IF TG_TABLE_NAME='folder_copy_jobs' THEN job:=NEW.id;
    ELSE
        job:=NEW.folder_copy_job_id;
        IF job IS NOT NULL AND NOT EXISTS (SELECT 1 FROM folder_copy_members m WHERE m.job_id=job AND m.operation_id=NEW.id) THEN
            RAISE EXCEPTION 'folder operation must be linked' USING ERRCODE='23514';
        END IF;
        RETURN NULL;
    END IF;
    IF job IS NULL THEN RETURN NULL; END IF;
    SELECT * INTO j FROM folder_copy_jobs WHERE id=job;
    IF (SELECT count(*) FROM folder_copy_members WHERE job_id=job)<>j.member_count THEN
        RAISE EXCEPTION 'folder snapshot must be complete and linked' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_folder_snapshot_job AFTER INSERT ON folder_copy_jobs
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_folder_snapshot();
CREATE CONSTRAINT TRIGGER trg_folder_snapshot_operation AFTER INSERT ON file_copy_operations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_folder_snapshot();

-- Owner mutation paths which use scalar/JPA ancestry checks must also fail closed.
CREATE FUNCTION folder_copy_hidden(resource UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM folder_copy_members m JOIN folder_copy_jobs j ON j.id=m.job_id
        WHERE m.destination_id=resource AND j.state<>'COMPLETED')
$$;

-- Folder COPIED is a publication event, distinct from staging creation.
ALTER TABLE resource_activity_events DROP CONSTRAINT chk_resource_activity_shape;
ALTER TABLE resource_activity_events ADD CONSTRAINT chk_resource_activity_shape CHECK ((

        (event_type = 'COPIED' AND resource_type IN ('FILE','FOLDER') AND before_name IS NULL
            AND after_name IS NOT NULL AND ((resource_type='FILE' AND after_status='READY') OR (resource_type='FOLDER' AND after_status IS NULL)) AND after_trashed = false)
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
