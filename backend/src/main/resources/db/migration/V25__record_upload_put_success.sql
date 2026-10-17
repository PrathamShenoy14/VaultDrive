-- No backfill: old admitted writes have no durable success/admission snapshot.
ALTER TABLE file_storage_objects ADD COLUMN admitted_file_version BIGINT;
ALTER TABLE file_storage_objects ADD COLUMN admitted_folder_id UUID;
ALTER TABLE file_storage_objects ADD COLUMN put_succeeded_at TIMESTAMPTZ;
ALTER TABLE file_storage_objects ADD CONSTRAINT chk_upload_success CHECK (
    (admitted_file_version IS NULL AND admitted_folder_id IS NULL AND put_succeeded_at IS NULL)
    OR (admitted_file_version IS NOT NULL AND admitted_file_version >= 0 AND write_started AND write_precluded_at IS NULL
        AND reservation_expired_at IS NULL
        AND num_nonnulls(storage_namespace, endpoint, region, bucket) = 4));

CREATE FUNCTION guard_upload_success() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.admitted_file_version IS NOT NULL OR NEW.admitted_folder_id IS NOT NULL
           OR NEW.put_succeeded_at IS NOT NULL THEN
            RAISE EXCEPTION 'upload evidence requires original admission' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.admitted_file_version IS NOT NULL THEN
        IF ROW(NEW.admitted_file_version, NEW.admitted_folder_id) IS DISTINCT FROM
           ROW(OLD.admitted_file_version, OLD.admitted_folder_id) THEN
            RAISE EXCEPTION 'upload admission identity is immutable' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.admitted_file_version IS NOT NULL THEN
        IF OLD.write_started OR NOT NEW.write_started OR NOT EXISTS (
            SELECT 1 FROM files f WHERE f.id = NEW.file_id AND f.owner_id = NEW.owner_id
                AND f.storage_key = NEW.storage_key AND f.status = 'UPLOADING'
                AND f.version = NEW.admitted_file_version
                AND f.folder_id IS NOT DISTINCT FROM NEW.admitted_folder_id
                AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL) THEN
            RAISE EXCEPTION 'upload evidence requires matching fresh admission' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF OLD.put_succeeded_at IS NOT NULL THEN
        IF NEW.put_succeeded_at IS DISTINCT FROM OLD.put_succeeded_at THEN
            RAISE EXCEPTION 'upload success receipt is immutable' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.put_succeeded_at IS NOT NULL THEN
        IF OLD.admitted_file_version IS NULL OR NOT OLD.write_started OR NOT EXISTS (
            SELECT 1 FROM files f WHERE f.id = NEW.file_id AND f.owner_id = NEW.owner_id
                AND f.storage_key = NEW.storage_key AND f.status = 'UPLOADING'
                AND f.version = NEW.admitted_file_version
                AND f.folder_id IS NOT DISTINCT FROM NEW.admitted_folder_id
                AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL) THEN
            RAISE EXCEPTION 'upload receipt requires admitted uploading metadata' USING ERRCODE = '23514';
        END IF;
        NEW.put_succeeded_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_upload_success BEFORE INSERT OR UPDATE ON file_storage_objects
FOR EACH ROW EXECUTE FUNCTION guard_upload_success();

CREATE INDEX idx_upload_success_recovery ON file_storage_objects(file_id)
WHERE put_succeeded_at IS NOT NULL;
