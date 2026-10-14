-- Retain removal proof separately: sealed V19 frontiers remain immutable.
ALTER TABLE folder_purge_runs ADD COLUMN manifest_folder_count BIGINT;
ALTER TABLE folder_purge_runs ADD COLUMN manifest_file_count BIGINT;
ALTER TABLE folder_purge_runs ADD COLUMN finalized_at TIMESTAMPTZ;
UPDATE folder_purge_runs r SET
    manifest_folder_count = (SELECT count(*) FROM folder_purge_frontier m WHERE m.event_id = r.event_id),
    manifest_file_count = (SELECT count(*) FROM folder_purge_files m WHERE m.event_id = r.event_id)
WHERE discovery_sealed_at IS NOT NULL;
ALTER TABLE folder_purge_runs DROP CONSTRAINT folder_purge_runs_phase_check;
ALTER TABLE folder_purge_runs ADD CONSTRAINT folder_purge_runs_phase_check
    CHECK (phase IN ('DISCOVER', 'DELETE_FILES', 'DELETE_FOLDERS', 'FINALIZED'));
ALTER TABLE folder_purge_runs ADD CONSTRAINT chk_folder_manifest_counts CHECK (
    (phase = 'DISCOVER' AND manifest_folder_count IS NULL AND manifest_file_count IS NULL)
    OR (phase <> 'DISCOVER' AND manifest_folder_count > 0 AND manifest_file_count >= 0));
ALTER TABLE folder_purge_runs ADD CONSTRAINT chk_folder_finalization
    CHECK ((phase = 'FINALIZED') = (finalized_at IS NOT NULL));
ALTER TABLE file_purge_work DROP CONSTRAINT chk_file_purge_finalization;
ALTER TABLE file_purge_work ADD CONSTRAINT chk_file_purge_finalization CHECK (
    metadata_finalized_at IS NULL OR
    (bytes_deleted_at IS NOT NULL AND metadata_finalized_at >= bytes_deleted_at) OR
    (never_written_at IS NOT NULL AND metadata_finalized_at >= never_written_at));

CREATE TABLE folder_purge_removals (
    event_id UUID NOT NULL,
    folder_id UUID NOT NULL,
    removed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (event_id, folder_id),
    FOREIGN KEY (event_id, folder_id) REFERENCES folder_purge_frontier(event_id, folder_id)
);
CREATE INDEX idx_folder_manifest_parent ON folder_purge_frontier(event_id, parent_folder_id, folder_id);
CREATE INDEX idx_folder_manifest_files ON folder_purge_files(event_id, folder_id, file_id);

CREATE OR REPLACE FUNCTION guard_folder_purge_discovery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run folder_purge_runs;
BEGIN
    IF TG_TABLE_NAME = 'folder_purge_runs' THEN
        IF ROW(NEW.event_id, NEW.owner_id, NEW.root_folder_id, NEW.requested_at)
           IS DISTINCT FROM ROW(OLD.event_id, OLD.owner_id, OLD.root_folder_id, OLD.requested_at)
           OR (OLD.discovery_sealed_at IS NOT NULL AND
               (NEW.discovery_sealed_at IS DISTINCT FROM OLD.discovery_sealed_at
                OR (NEW.phase <> OLD.phase AND NOT
                    ((OLD.phase = 'DELETE_FILES' AND NEW.phase = 'DELETE_FOLDERS')
                    OR (OLD.phase = 'DELETE_FOLDERS' AND NEW.phase = 'FINALIZED'))))) THEN
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

CREATE OR REPLACE FUNCTION guard_folder_purge_phase_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.phase <> OLD.phase AND NOT (
        (OLD.phase = 'DISCOVER' AND NEW.phase = 'DELETE_FILES')
        OR (OLD.phase = 'DELETE_FILES' AND NEW.phase = 'DELETE_FOLDERS')
        OR (OLD.phase = 'DELETE_FOLDERS' AND NEW.phase = 'FINALIZED')) THEN
        RAISE EXCEPTION 'invalid folder purge phase transition' USING ERRCODE = '23514';
    END IF;
    IF OLD.discovery_sealed_at IS NOT NULL AND
        ROW(NEW.manifest_folder_count, NEW.manifest_file_count) IS DISTINCT FROM
        ROW(OLD.manifest_folder_count, OLD.manifest_file_count)
        OR (OLD.finalized_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'folder manifest counts and finalization are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.phase = 'DELETE_FILES' AND OLD.phase = 'DISCOVER' THEN
        NEW.manifest_folder_count := (SELECT count(*) FROM folder_purge_frontier WHERE event_id = NEW.event_id);
        NEW.manifest_file_count := (SELECT count(*) FROM folder_purge_files WHERE event_id = NEW.event_id);
    END IF;
    IF NEW.phase = 'FINALIZED' AND (
        NEW.manifest_folder_count <> (SELECT count(*) FROM folder_purge_frontier WHERE event_id = NEW.event_id)
        OR NEW.manifest_file_count <> (SELECT count(*) FROM folder_purge_files WHERE event_id = NEW.event_id)
        OR NOT EXISTS (SELECT 1 FROM folder_purge_frontier WHERE event_id = NEW.event_id
            AND is_root AND folder_id = NEW.root_folder_id AND owner_id = NEW.owner_id)
        OR EXISTS (SELECT 1 FROM folder_purge_frontier m LEFT JOIN folder_purge_removals d
            ON d.event_id = m.event_id AND d.folder_id = m.folder_id
            WHERE m.event_id = NEW.event_id AND (d.removed_at IS NULL OR NOT m.children_complete
                OR NOT m.files_complete OR m.owner_id <> NEW.owner_id
                OR EXISTS (SELECT 1 FROM folders WHERE id = m.folder_id)))
        OR EXISTS (SELECT 1 FROM folder_purge_files m LEFT JOIN file_purge_work w ON w.file_id = m.file_id
            LEFT JOIN folder_purge_file_progress p ON p.event_id = m.event_id AND p.file_id = m.file_id
            WHERE m.event_id = NEW.event_id AND (w.metadata_finalized_at IS NULL OR p.completed_at IS NULL
                OR NOT folder_purge_file_verified(m.event_id, m.file_id)
                OR EXISTS (SELECT 1 FROM files WHERE id = m.file_id)))) THEN
        RAISE EXCEPTION 'folder finalization lacks durable removal proof' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_folder_removal_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'folder removal receipt is immutable' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM folder_purge_runs r JOIN folder_purge_frontier m ON m.event_id = r.event_id
        WHERE r.event_id = NEW.event_id AND m.folder_id = NEW.folder_id AND r.phase = 'DELETE_FOLDERS'
            AND m.owner_id = r.owner_id AND m.children_complete AND m.files_complete)
        OR EXISTS (SELECT 1 FROM folders WHERE id = NEW.folder_id OR parent_folder_id = NEW.folder_id)
        OR EXISTS (SELECT 1 FROM files WHERE folder_id = NEW.folder_id)
        OR EXISTS (SELECT 1 FROM folder_purge_frontier c LEFT JOIN folder_purge_removals d
            ON d.event_id = c.event_id AND d.folder_id = c.folder_id
            WHERE c.event_id = NEW.event_id AND c.parent_folder_id = NEW.folder_id AND d.removed_at IS NULL)
        OR EXISTS (SELECT 1 FROM folder_purge_files m LEFT JOIN file_purge_work w ON w.file_id = m.file_id
            LEFT JOIN folder_purge_file_progress p ON p.event_id = m.event_id AND p.file_id = m.file_id
            WHERE m.event_id = NEW.event_id AND m.folder_id = NEW.folder_id
                AND (w.metadata_finalized_at IS NULL OR p.completed_at IS NULL
                    OR NOT folder_purge_file_verified(m.event_id, m.file_id))) THEN
        RAISE EXCEPTION 'folder removal lacks finalized descendants' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_folder_removal BEFORE INSERT OR UPDATE ON folder_purge_removals
FOR EACH ROW EXECUTE FUNCTION guard_folder_removal_receipt();
