-- Retained key allocations have no cascading resource FK. Never reuse an old key.
CREATE TABLE file_storage_objects (
    storage_key VARCHAR(1024) PRIMARY KEY CHECK (length(trim(storage_key)) > 0),
    file_id UUID NOT NULL UNIQUE,
    owner_id UUID NOT NULL,
    storage_namespace VARCHAR(255),
    endpoint VARCHAR(1024),
    region VARCHAR(255),
    bucket VARCHAR(255),
    write_started BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_file_storage_binding CHECK (
        (num_nonnulls(storage_namespace, endpoint, region, bucket) = 0 AND write_started)
        OR (num_nonnulls(storage_namespace, endpoint, region, bucket) = 4
            AND length(trim(storage_namespace)) > 0 AND length(trim(endpoint)) > 0
            AND length(trim(region)) > 0 AND length(trim(bucket)) > 0))
);

-- Legacy keys are reserved, but their original storage location is unknown.
-- Do not infer a deletion binding from today's configuration.
INSERT INTO file_storage_objects(storage_key, file_id, owner_id, write_started)
SELECT storage_key, id, owner_id, TRUE FROM files;

CREATE TABLE file_purge_targets (
    event_id UUID PRIMARY KEY,
    file_id UUID NOT NULL UNIQUE,
    owner_id UUID NOT NULL,
    file_version BIGINT NOT NULL CHECK (file_version >= 0),
    requested_at TIMESTAMPTZ NOT NULL,
    storage_namespace VARCHAR(255) NOT NULL CHECK (length(trim(storage_namespace)) > 0),
    endpoint VARCHAR(1024) NOT NULL CHECK (length(trim(endpoint)) > 0),
    region VARCHAR(255) NOT NULL CHECK (length(trim(region)) > 0),
    bucket VARCHAR(255) NOT NULL CHECK (length(trim(bucket)) > 0),
    storage_key VARCHAR(1024) NOT NULL CHECK (length(trim(storage_key)) > 0)
);

CREATE FUNCTION preserve_file_storage_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.storage_key, NEW.file_id, NEW.owner_id, NEW.storage_namespace,
           NEW.endpoint, NEW.region, NEW.bucket)
       IS DISTINCT FROM
       ROW(OLD.storage_key, OLD.file_id, OLD.owner_id, OLD.storage_namespace,
           OLD.endpoint, OLD.region, OLD.bucket)
       OR (OLD.write_started AND NOT NEW.write_started) THEN
        RAISE EXCEPTION 'file storage identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_file_storage_identity BEFORE UPDATE ON file_storage_objects
FOR EACH ROW EXECUTE FUNCTION preserve_file_storage_identity();

CREATE FUNCTION preserve_file_purge_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'file purge target is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_file_purge_target BEFORE UPDATE ON file_purge_targets
FOR EACH ROW EXECUTE FUNCTION preserve_file_purge_target();
