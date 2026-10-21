-- Logical accounting only. Retained storage identities/proofs are never reclaimed here.
CREATE TABLE storage_quotas (
    owner_id UUID PRIMARY KEY REFERENCES users(id),
    limit_bytes BIGINT NOT NULL DEFAULT 10737418240 CHECK (limit_bytes >= 0),
    used_bytes BIGINT NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
    reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
    CHECK (used_bytes <= 9223372036854775807 - reserved_bytes)
);

-- Preserve existing uploads; grandfather only owners already above the default.
INSERT INTO storage_quotas(owner_id, limit_bytes, used_bytes, reserved_bytes)
SELECT u.id, greatest(10737418240, coalesce(sum(f.size_bytes) FILTER
        (WHERE f.status IN ('READY','UPLOADING')), 0)),
    coalesce(sum(f.size_bytes) FILTER (WHERE f.status = 'READY'), 0),
    coalesce(sum(f.size_bytes) FILTER (WHERE f.status = 'UPLOADING'), 0)
FROM users u LEFT JOIN files f ON f.owner_id = u.id GROUP BY u.id;

CREATE FUNCTION initialize_storage_quota() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO storage_quotas(owner_id) VALUES (NEW.id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_initialize_storage_quota AFTER INSERT ON users
FOR EACH ROW EXECUTE FUNCTION initialize_storage_quota();

CREATE INDEX idx_files_quota ON files(owner_id, status) INCLUDE (size_bytes);
CREATE INDEX idx_allocations_quota ON file_storage_objects(owner_id, file_id);

-- Known logical lengths are evidence, not a measurement/bound of physical blocks.
-- Missing historical lengths remain explicitly unknown, never fabricated as zero.
ALTER TABLE file_storage_objects ADD COLUMN quota_size_bytes BIGINT CHECK (quota_size_bytes >= 0);
UPDATE file_storage_objects a SET quota_size_bytes = coalesce(
    (SELECT size_bytes FROM files f WHERE f.id = a.file_id),
    (SELECT declared_size FROM upload_sessions s WHERE s.file_id = a.file_id));

CREATE FUNCTION preserve_quota_size() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT size_bytes INTO NEW.quota_size_bytes FROM files WHERE id = NEW.file_id AND owner_id = NEW.owner_id;
    ELSIF NEW.quota_size_bytes IS DISTINCT FROM OLD.quota_size_bytes THEN
        RAISE EXCEPTION 'allocation logical length is retained' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_quota_size BEFORE INSERT OR UPDATE ON file_storage_objects
FOR EACH ROW EXECUTE FUNCTION preserve_quota_size();

CREATE FUNCTION account_file_storage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner UUID; old_used BIGINT := 0; old_reserved BIGINT := 0;
    new_used BIGINT := 0; new_reserved BIGINT := 0; q storage_quotas;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        owner := OLD.owner_id;
        IF OLD.status = 'READY' THEN old_used := OLD.size_bytes; END IF;
        IF OLD.status = 'UPLOADING' THEN old_reserved := OLD.size_bytes; END IF;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        owner := NEW.owner_id;
        IF TG_OP = 'UPDATE' AND ROW(NEW.owner_id, NEW.size_bytes) IS DISTINCT FROM ROW(OLD.owner_id, OLD.size_bytes) THEN
            RAISE EXCEPTION 'quota ownership and length are immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.status = 'READY' THEN new_used := NEW.size_bytes; END IF;
        IF NEW.status = 'UPLOADING' THEN new_reserved := NEW.size_bytes; END IF;
    END IF;
    IF ROW(old_used,old_reserved) IS DISTINCT FROM ROW(new_used,new_reserved) THEN
        -- Final accounting lock: never acquire another existing resource afterward.
        SELECT * INTO STRICT q FROM storage_quotas WHERE owner_id = owner FOR UPDATE;
        IF new_used::numeric + new_reserved > old_used::numeric + old_reserved
            AND q.used_bytes::numeric + q.reserved_bytes + new_used + new_reserved - old_used - old_reserved > q.limit_bytes THEN
            RAISE EXCEPTION 'Storage quota exceeded' USING ERRCODE = '23514', CONSTRAINT = 'storage_quota_exceeded';
        END IF;
        UPDATE storage_quotas SET used_bytes = used_bytes - old_used + new_used,
            reserved_bytes = reserved_bytes - old_reserved + new_reserved WHERE owner_id = owner;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
-- AFTER means all existing lifecycle/proof guards must succeed; rollback restores counters too.
CREATE TRIGGER trg_account_file_storage AFTER INSERT OR UPDATE OR DELETE ON files
FOR EACH ROW EXECUTE FUNCTION account_file_storage();
