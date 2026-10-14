-- Enum widening must not permit a shortcut around discovery or file proof.
CREATE FUNCTION guard_folder_purge_phase_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.phase <> OLD.phase AND NOT (
        (OLD.phase = 'DISCOVER' AND NEW.phase = 'DELETE_FILES')
        OR (OLD.phase = 'DELETE_FILES' AND NEW.phase = 'DELETE_FOLDERS')) THEN
        RAISE EXCEPTION 'invalid folder purge phase transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_folder_purge_phase_order BEFORE UPDATE ON folder_purge_runs
FOR EACH ROW EXECUTE FUNCTION guard_folder_purge_phase_order();

-- A never-written receipt can only be established from a precluded original
-- allocation, never from upload age/status or an admitted write.
CREATE FUNCTION verify_never_written_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.never_written_at IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM file_storage_objects a WHERE a.file_id = NEW.file_id
            AND NOT a.write_started AND a.write_precluded_at IS NOT NULL
            AND ROW(a.owner_id, a.storage_key, a.storage_namespace, a.endpoint, a.region, a.bucket)
            IS NOT DISTINCT FROM ROW(NEW.owner_id, NEW.storage_key, NEW.storage_namespace,
                NEW.endpoint, NEW.region, NEW.bucket)) THEN
        RAISE EXCEPTION 'never-written proof requires verified preclusion' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_verify_never_written_allocation BEFORE INSERT OR UPDATE ON file_purge_work
FOR EACH ROW EXECUTE FUNCTION verify_never_written_allocation();
