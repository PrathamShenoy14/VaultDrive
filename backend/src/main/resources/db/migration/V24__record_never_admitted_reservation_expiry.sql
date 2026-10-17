-- Evidence belongs to the permanently retained allocation, not a purge receipt or upload success.
ALTER TABLE file_storage_objects ADD COLUMN reservation_expired_at TIMESTAMPTZ;
ALTER TABLE file_storage_objects ADD COLUMN expired_file_version BIGINT;
ALTER TABLE file_storage_objects ADD CONSTRAINT chk_reservation_expiry CHECK (
    (reservation_expired_at IS NULL) = (expired_file_version IS NULL)
    AND (reservation_expired_at IS NULL OR
        (NOT write_started AND write_precluded_at IS NOT NULL AND expired_file_version > 0
            AND reservation_expired_at >= write_precluded_at)));

CREATE FUNCTION guard_reservation_expiry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.reservation_expired_at IS NOT NULL THEN
        IF ROW(NEW.reservation_expired_at, NEW.expired_file_version) IS DISTINCT FROM
           ROW(OLD.reservation_expired_at, OLD.expired_file_version) THEN
            RAISE EXCEPTION 'reservation expiry evidence is immutable' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.reservation_expired_at IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM files f WHERE f.id = NEW.file_id AND f.owner_id = NEW.owner_id
            AND f.storage_key = NEW.storage_key AND f.status = 'FAILED'
            AND f.version = NEW.expired_file_version
            AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL) THEN
        RAISE EXCEPTION 'reservation expiry requires matching failed metadata' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_reservation_expiry BEFORE INSERT OR UPDATE ON file_storage_objects
FOR EACH ROW EXECUTE FUNCTION guard_reservation_expiry();
