-- Retain the original allocation and permanently close never-admitted writes.
ALTER TABLE file_storage_objects ADD COLUMN write_precluded_at TIMESTAMPTZ;
ALTER TABLE file_storage_objects ADD CONSTRAINT chk_file_storage_write_precluded
    CHECK (write_precluded_at IS NULL OR (NOT write_started AND storage_namespace IS NOT NULL));
ALTER TABLE file_purge_work ADD COLUMN never_written_at TIMESTAMPTZ;
ALTER TABLE file_purge_work ADD COLUMN never_written_job_id UUID;
ALTER TABLE file_purge_work ADD CONSTRAINT chk_file_purge_never_written CHECK (
    (never_written_at IS NULL) = (never_written_job_id IS NULL)
    AND (never_written_at IS NULL OR bytes_deleted_at IS NULL));

CREATE TABLE folder_purge_file_progress (
    event_id UUID NOT NULL,
    file_id UUID NOT NULL,
    blocked_reason VARCHAR(32) CHECK (blocked_reason IN
        ('UNSAFE_WRITER', 'UNKNOWN_BINDING', 'IDENTITY_MISMATCH', 'UNKNOWN_TARGET')),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (event_id, file_id),
    FOREIGN KEY (event_id, file_id) REFERENCES folder_purge_files(event_id, file_id)
);
-- Unexamined files are scanned first; blocked files cannot starve later members.
CREATE INDEX idx_folder_purge_file_incomplete ON folder_purge_file_progress
    (event_id, file_id) WHERE completed_at IS NULL;

ALTER TABLE folder_purge_runs DROP CONSTRAINT folder_purge_runs_phase_check;
ALTER TABLE folder_purge_runs ADD CONSTRAINT folder_purge_runs_phase_check
    CHECK (phase IN ('DISCOVER', 'DELETE_FILES', 'DELETE_FOLDERS'));

-- An attachment is proof only when the shared immutable work matches the sealed member
-- and its retained allocation. No current settings or inferred storage keys participate.
CREATE FUNCTION folder_purge_file_verified(event UUID, file UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM folder_purge_files m
        JOIN file_purge_work w ON w.file_id = m.file_id
        JOIN file_storage_objects a ON a.file_id = m.file_id
        WHERE m.event_id = event AND m.file_id = file
            AND ROW(w.owner_id, w.folder_id, w.file_version, w.file_status,
                w.file_created_at, w.file_updated_at, w.deleted_at, w.purge_requested_at,
                w.storage_namespace, w.endpoint, w.region, w.bucket, w.storage_key)
            IS NOT DISTINCT FROM ROW(m.owner_id, m.folder_id, m.file_version, m.file_status,
                m.file_created_at, m.file_updated_at, m.deleted_at, m.purge_requested_at,
                m.storage_namespace, m.endpoint, m.region, m.bucket, m.storage_key)
            AND ROW(a.owner_id, a.storage_key, a.storage_namespace, a.endpoint, a.region, a.bucket)
            IS NOT DISTINCT FROM ROW(w.owner_id, w.storage_key, w.storage_namespace, w.endpoint, w.region, w.bucket)
            AND ((w.bytes_deleted_at IS NOT NULL AND w.file_status = 'READY' AND a.write_started)
                OR (w.never_written_at IS NOT NULL AND NOT a.write_started
                    AND a.write_precluded_at IS NOT NULL)))
$$;

CREATE OR REPLACE FUNCTION guard_folder_purge_discovery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run folder_purge_runs;
BEGIN
    IF TG_TABLE_NAME = 'folder_purge_runs' THEN
        IF ROW(NEW.event_id, NEW.owner_id, NEW.root_folder_id, NEW.requested_at)
           IS DISTINCT FROM ROW(OLD.event_id, OLD.owner_id, OLD.root_folder_id, OLD.requested_at)
           OR (OLD.discovery_sealed_at IS NOT NULL AND
               (NEW.discovery_sealed_at IS DISTINCT FROM OLD.discovery_sealed_at
                OR (NEW.phase <> OLD.phase AND NOT
                    (OLD.phase = 'DELETE_FILES' AND NEW.phase = 'DELETE_FOLDERS')))) THEN
            RAISE EXCEPTION 'folder purge run identity and seal are immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.phase = 'DELETE_FILES' AND OLD.phase = 'DISCOVER' AND
           (NOT EXISTS (SELECT 1 FROM folder_purge_frontier WHERE event_id = NEW.event_id AND is_root)
            OR EXISTS (SELECT 1 FROM folder_purge_frontier WHERE event_id = NEW.event_id
                       AND (NOT children_complete OR NOT files_complete))) THEN
            RAISE EXCEPTION 'folder discovery is incomplete' USING ERRCODE = '23514';
        END IF;
        IF NEW.phase = 'DELETE_FOLDERS' AND OLD.phase = 'DELETE_FILES' AND EXISTS (
            SELECT 1 FROM folder_purge_files m LEFT JOIN folder_purge_file_progress p
                ON p.event_id = m.event_id AND p.file_id = m.file_id
            WHERE m.event_id = NEW.event_id AND (p.completed_at IS NULL
                OR NOT folder_purge_file_verified(m.event_id, m.file_id))) THEN
            RAISE EXCEPTION 'folder file deletion is incomplete' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT run FROM folder_purge_runs WHERE event_id = NEW.event_id;
    IF run.phase <> 'DISCOVER' OR NEW.owner_id <> run.owner_id THEN
        RAISE EXCEPTION 'invalid or sealed folder membership' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'folder_purge_frontier' THEN
        IF NEW.is_root THEN
            IF NEW.folder_id <> run.root_folder_id THEN
                RAISE EXCEPTION 'invalid root membership' USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.folder_id = run.root_folder_id OR NOT EXISTS (
            SELECT 1 FROM folder_purge_frontier p WHERE p.event_id = NEW.event_id
                AND p.folder_id = NEW.parent_folder_id AND p.owner_id = NEW.owner_id) THEN
            RAISE EXCEPTION 'invalid parent membership' USING ERRCODE = '23514';
        END IF;
        IF TG_OP = 'UPDATE' AND
           (ROW(NEW.event_id, NEW.folder_id, NEW.owner_id, NEW.parent_folder_id, NEW.is_root, NEW.deleted_at)
            IS DISTINCT FROM ROW(OLD.event_id, OLD.folder_id, OLD.owner_id, OLD.parent_folder_id, OLD.is_root, OLD.deleted_at)
            OR (OLD.children_complete AND NOT NEW.children_complete)
            OR (OLD.files_complete AND NOT NEW.files_complete)
            OR (OLD.child_cursor IS NOT NULL AND (NEW.child_cursor IS NULL OR NEW.child_cursor < OLD.child_cursor))
            OR (OLD.file_cursor IS NOT NULL AND (NEW.file_cursor IS NULL OR NEW.file_cursor < OLD.file_cursor))) THEN
            RAISE EXCEPTION 'folder frontier cannot regress' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_OP = 'UPDATE' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'file membership is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_folder_file_progress() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM folder_purge_runs WHERE event_id = NEW.event_id
        AND phase = 'DELETE_FILES' AND discovery_sealed_at IS NOT NULL)
       OR (TG_OP = 'UPDATE' AND
           (ROW(NEW.event_id, NEW.file_id) IS DISTINCT FROM ROW(OLD.event_id, OLD.file_id)
            OR (OLD.completed_at IS NOT NULL AND NEW IS DISTINCT FROM OLD)))
       OR (NEW.completed_at IS NOT NULL AND
           (NEW.blocked_reason IS NOT NULL OR NOT folder_purge_file_verified(NEW.event_id, NEW.file_id))) THEN
        RAISE EXCEPTION 'invalid folder file progress' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_folder_file_progress BEFORE INSERT OR UPDATE ON folder_purge_file_progress
FOR EACH ROW EXECUTE FUNCTION guard_folder_file_progress();

CREATE FUNCTION preserve_never_written_proof() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'file_storage_objects' THEN
        IF OLD.write_precluded_at IS NOT NULL AND NEW.write_precluded_at IS DISTINCT FROM OLD.write_precluded_at THEN
            RAISE EXCEPTION 'write preclusion is immutable' USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.never_written_at IS NOT NULL AND
          ROW(NEW.never_written_at, NEW.never_written_job_id) IS DISTINCT FROM
          ROW(OLD.never_written_at, OLD.never_written_job_id) THEN
        RAISE EXCEPTION 'never-written proof is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_storage_write_preclusion BEFORE UPDATE ON file_storage_objects
FOR EACH ROW EXECUTE FUNCTION preserve_never_written_proof();
CREATE TRIGGER trg_file_never_written_proof BEFORE UPDATE ON file_purge_work
FOR EACH ROW EXECUTE FUNCTION preserve_never_written_proof();
