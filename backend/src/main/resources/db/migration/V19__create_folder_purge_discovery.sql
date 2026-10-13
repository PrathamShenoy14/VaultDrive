-- Retained identities have no resource/outbox/job FK and never cascade away.
CREATE TABLE folder_purge_runs (
    event_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    root_folder_id UUID NOT NULL UNIQUE,
    requested_at TIMESTAMPTZ NOT NULL,
    phase VARCHAR(32) NOT NULL DEFAULT 'DISCOVER' CHECK (phase IN ('DISCOVER', 'DELETE_FILES')),
    discovery_sealed_at TIMESTAMPTZ,
    CHECK ((phase = 'DISCOVER') = (discovery_sealed_at IS NULL))
);

CREATE TABLE folder_purge_frontier (
    event_id UUID NOT NULL REFERENCES folder_purge_runs(event_id),
    folder_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    parent_folder_id UUID,
    is_root BOOLEAN NOT NULL,
    deleted_at TIMESTAMPTZ,
    child_cursor UUID,
    file_cursor UUID,
    children_complete BOOLEAN NOT NULL DEFAULT FALSE,
    files_complete BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (event_id, folder_id)
);
CREATE UNIQUE INDEX uq_folder_purge_frontier_root ON folder_purge_frontier(event_id) WHERE is_root;
CREATE INDEX idx_folder_purge_frontier_open ON folder_purge_frontier(event_id, folder_id)
WHERE NOT children_complete OR NOT files_complete;
CREATE INDEX idx_folders_discovery_page ON folders(parent_folder_id, id);
CREATE INDEX idx_files_discovery_page ON files(folder_id, id);

-- Membership snapshots are evidence of discovery, not permission to delete bytes.
-- Unknown/missing legacy allocation bindings remain explicitly nullable.
CREATE TABLE folder_purge_files (
    event_id UUID NOT NULL,
    file_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    folder_id UUID NOT NULL,
    file_version BIGINT NOT NULL CHECK (file_version >= 0),
    file_status VARCHAR(32) NOT NULL CHECK (file_status IN ('READY', 'UPLOADING', 'FAILED')),
    file_created_at TIMESTAMPTZ NOT NULL,
    file_updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    purge_requested_at TIMESTAMPTZ,
    storage_key VARCHAR(1024) NOT NULL,
    storage_namespace VARCHAR(255),
    endpoint VARCHAR(1024),
    region VARCHAR(255),
    bucket VARCHAR(255),
    write_started BOOLEAN,
    standalone_event_id UUID,
    PRIMARY KEY (event_id, file_id),
    FOREIGN KEY (event_id, folder_id) REFERENCES folder_purge_frontier(event_id, folder_id),
    CHECK (num_nonnulls(storage_namespace, endpoint, region, bucket) IN (0, 4))
);

CREATE FUNCTION guard_folder_purge_discovery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run folder_purge_runs;
BEGIN
    IF TG_TABLE_NAME = 'folder_purge_runs' THEN
        IF ROW(NEW.event_id, NEW.owner_id, NEW.root_folder_id, NEW.requested_at)
           IS DISTINCT FROM ROW(OLD.event_id, OLD.owner_id, OLD.root_folder_id, OLD.requested_at)
           OR (OLD.discovery_sealed_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
            RAISE EXCEPTION 'folder purge run identity and seal are immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.phase = 'DELETE_FILES' AND OLD.phase = 'DISCOVER' AND
           (NOT EXISTS (SELECT 1 FROM folder_purge_frontier WHERE event_id = NEW.event_id AND is_root)
            OR EXISTS (SELECT 1 FROM folder_purge_frontier WHERE event_id = NEW.event_id
                       AND (NOT children_complete OR NOT files_complete))) THEN
            RAISE EXCEPTION 'folder discovery is incomplete' USING ERRCODE = '23514';
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
CREATE TRIGGER trg_folder_purge_run BEFORE UPDATE ON folder_purge_runs
FOR EACH ROW EXECUTE FUNCTION guard_folder_purge_discovery();
CREATE TRIGGER trg_folder_purge_frontier BEFORE INSERT OR UPDATE ON folder_purge_frontier
FOR EACH ROW EXECUTE FUNCTION guard_folder_purge_discovery();
CREATE TRIGGER trg_folder_purge_files BEFORE INSERT OR UPDATE ON folder_purge_files
FOR EACH ROW EXECUTE FUNCTION guard_folder_purge_discovery();
