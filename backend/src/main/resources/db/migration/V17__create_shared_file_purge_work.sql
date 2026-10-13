-- Shared by standalone jobs and future folder manifests. No resource/job FK:
-- identity and receipts must survive metadata removal and job retention.
CREATE TABLE file_purge_work (
    file_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    folder_id UUID,
    file_version BIGINT NOT NULL CHECK (file_version >= 0),
    file_status VARCHAR(32) NOT NULL CHECK (file_status IN ('READY', 'UPLOADING', 'FAILED')),
    file_created_at TIMESTAMPTZ NOT NULL,
    file_updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    purge_requested_at TIMESTAMPTZ,
    storage_namespace VARCHAR(255) NOT NULL CHECK (length(trim(storage_namespace)) > 0),
    endpoint VARCHAR(1024) NOT NULL CHECK (length(trim(endpoint)) > 0),
    region VARCHAR(255) NOT NULL CHECK (length(trim(region)) > 0),
    bucket VARCHAR(255) NOT NULL CHECK (length(trim(bucket)) > 0),
    storage_key VARCHAR(1024) NOT NULL UNIQUE CHECK (length(trim(storage_key)) > 0),
    bytes_deleted_at TIMESTAMPTZ,
    byte_receipt_job_id UUID,
    metadata_finalized_at TIMESTAMPTZ,
    CONSTRAINT chk_file_purge_byte_receipt CHECK (
        (bytes_deleted_at IS NULL) = (byte_receipt_job_id IS NULL)),
    CONSTRAINT chk_file_purge_finalization CHECK (
        metadata_finalized_at IS NULL OR
        (bytes_deleted_at IS NOT NULL AND metadata_finalized_at >= bytes_deleted_at))
);

CREATE INDEX idx_file_purge_work_unfinalized ON file_purge_work(file_id)
WHERE bytes_deleted_at IS NOT NULL AND metadata_finalized_at IS NULL;
CREATE INDEX idx_background_jobs_file_success ON background_jobs(id)
WHERE job_type = 'FILE_PURGE' AND status = 'SUCCEEDED';

CREATE FUNCTION preserve_file_purge_work() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.file_id, NEW.owner_id, NEW.folder_id, NEW.file_version, NEW.file_status,
           NEW.file_created_at, NEW.file_updated_at, NEW.deleted_at, NEW.purge_requested_at,
           NEW.storage_namespace, NEW.endpoint, NEW.region, NEW.bucket, NEW.storage_key)
       IS DISTINCT FROM
       ROW(OLD.file_id, OLD.owner_id, OLD.folder_id, OLD.file_version, OLD.file_status,
           OLD.file_created_at, OLD.file_updated_at, OLD.deleted_at, OLD.purge_requested_at,
           OLD.storage_namespace, OLD.endpoint, OLD.region, OLD.bucket, OLD.storage_key)
       OR (OLD.bytes_deleted_at IS NOT NULL AND
           ROW(NEW.bytes_deleted_at, NEW.byte_receipt_job_id) IS DISTINCT FROM
           ROW(OLD.bytes_deleted_at, OLD.byte_receipt_job_id))
       OR (OLD.metadata_finalized_at IS NOT NULL AND
           NEW.metadata_finalized_at IS DISTINCT FROM OLD.metadata_finalized_at) THEN
        RAISE EXCEPTION 'file purge identity and receipts are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_file_purge_work BEFORE UPDATE ON file_purge_work
FOR EACH ROW EXECUTE FUNCTION preserve_file_purge_work();
