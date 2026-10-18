-- M5: logical metadata disposal is deliberately separate from physical reclamation.
ALTER TABLE file_purge_work ADD COLUMN multipart_discarded_at TIMESTAMPTZ;
ALTER TABLE file_purge_work ADD CONSTRAINT chk_multipart_discard_exclusive CHECK (
    multipart_discarded_at IS NULL OR (file_status <> 'READY' AND bytes_deleted_at IS NULL AND never_written_at IS NULL));
ALTER TABLE file_purge_work DROP CONSTRAINT chk_file_purge_finalization;
ALTER TABLE file_purge_work ADD CONSTRAINT chk_file_purge_finalization CHECK (
    metadata_finalized_at IS NULL OR
    (bytes_deleted_at IS NOT NULL AND metadata_finalized_at >= bytes_deleted_at) OR
    (never_written_at IS NOT NULL AND metadata_finalized_at >= never_written_at) OR
    (multipart_discarded_at IS NOT NULL AND metadata_finalized_at >= multipart_discarded_at));

-- Retained session + original binding + positive receipt, never HEAD absence or abort ACK.
CREATE FUNCTION multipart_completed_allocation(file UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM file_storage_objects a JOIN upload_sessions s ON s.file_id = a.file_id
        JOIN multipart_completions c ON c.session_id = s.id
        JOIN multipart_completion_receipts r ON r.session_id = s.id
        WHERE a.file_id = file AND a.upload_protocol = 'DIRECT_MULTIPART' AND a.write_started
            AND s.state = 'COMPLETION_DECIDED' AND r.file_id = a.file_id
            AND r.decision_id = s.decision_id AND r.dispatch_id = c.dispatch_id AND r.manifest_hash = c.manifest_hash
            AND r.storage_identity = multipart_storage_identity(s)
            AND ROW(a.owner_id,a.storage_key,a.storage_namespace,a.endpoint,a.region,a.bucket)
                IS NOT DISTINCT FROM ROW(s.owner_id,s.storage_key,s.storage_namespace,s.endpoint,s.region,s.bucket))
$$;

-- Namespace release changes only readiness/version/time. Cancellation remains publicly readable
-- at its original destination while accessible. This does not authorize new parts or completion.
CREATE FUNCTION multipart_original_ancestry_accessible(s upload_sessions) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    WITH RECURSIVE ancestry AS (
        SELECT p.id,p.parent_folder_id,p.deleted_at,p.purge_requested_at,ARRAY[p.id] AS path,false AS cycle,1 AS depth
        FROM folders p WHERE p.id = s.original_folder_id AND p.owner_id = s.owner_id
        UNION ALL
        SELECT p.id,p.parent_folder_id,p.deleted_at,p.purge_requested_at,a.path || p.id,p.id = ANY(a.path),a.depth+1
        FROM ancestry a JOIN folders p ON p.id = a.parent_folder_id AND p.owner_id = s.owner_id
        WHERE NOT a.cycle AND a.depth < 128)
    SELECT (s.original_folder_id IS NULL OR EXISTS (SELECT 1 FROM ancestry WHERE parent_folder_id IS NULL))
        AND NOT EXISTS (SELECT 1 FROM ancestry WHERE cycle OR deleted_at IS NOT NULL OR purge_requested_at IS NOT NULL)
$$;

ALTER FUNCTION multipart_session_eligible(upload_sessions) RENAME TO multipart_upload_eligible;
CREATE FUNCTION multipart_session_eligible(s upload_sessions) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT multipart_upload_eligible(s) OR (s.state = 'ABANDONMENT_DECIDED' AND EXISTS (
        SELECT 1 FROM files f WHERE f.id = s.file_id AND f.status = 'FAILED'
            AND f.version = s.original_file_version + 1
            AND EXISTS (SELECT 1 FROM multipart_cancellations WHERE session_id = s.id)
            AND f.owner_id = s.owner_id AND f.folder_id IS NOT DISTINCT FROM s.original_folder_id
            AND f.name = s.file_name AND f.storage_key = s.storage_key
            AND f.size_bytes = s.declared_size AND f.content_type = s.declared_type
            AND f.deleted_at IS NULL AND f.purge_requested_at IS NULL
            AND EXISTS (SELECT 1 FROM users WHERE id = s.owner_id)
            AND EXISTS (SELECT 1 FROM file_storage_objects a WHERE a.file_id = f.id AND a.upload_protocol = 'DIRECT_MULTIPART'
                AND ROW(a.owner_id,a.storage_key,a.storage_namespace,a.endpoint,a.region,a.bucket)
                IS NOT DISTINCT FROM ROW(s.owner_id,s.storage_key,s.storage_namespace,s.endpoint,s.region,s.bucket))
            AND NOT EXISTS (SELECT 1 FROM file_purge_work WHERE file_id = f.id)
            AND NOT EXISTS (SELECT 1 FROM folder_purge_files WHERE file_id = f.id)
            AND NOT EXISTS (SELECT 1 FROM file_purge_targets WHERE file_id = f.id)
            AND NOT EXISTS (SELECT 1 FROM upload_cleanup_intents WHERE file_id = f.id)
            AND multipart_original_ancestry_accessible(s)))
$$;

-- Retained manifest and full binding establish a logical decision only, including an
-- already permitted Create/Complete/Abort whose response may arrive after removal.
CREATE FUNCTION multipart_discard_verified(w file_purge_work) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT w.file_status <> 'READY' AND EXISTS (
        SELECT 1 FROM file_storage_objects a JOIN upload_sessions s ON s.file_id = a.file_id
        JOIN folder_purge_files m ON m.file_id = a.file_id
        JOIN folder_purge_runs r ON r.event_id = m.event_id
        WHERE a.file_id = w.file_id AND a.upload_protocol = 'DIRECT_MULTIPART'
            AND r.discovery_sealed_at IS NOT NULL AND r.phase IN ('DELETE_FILES','DELETE_FOLDERS','FINALIZED')
            AND ROW(a.owner_id,a.storage_key,a.storage_namespace,a.endpoint,a.region,a.bucket)
                IS NOT DISTINCT FROM ROW(s.owner_id,s.storage_key,s.storage_namespace,s.endpoint,s.region,s.bucket)
            AND ROW(w.owner_id,w.folder_id,w.file_version,w.file_status,w.file_created_at,w.file_updated_at,
                w.deleted_at,w.purge_requested_at,w.storage_namespace,w.endpoint,w.region,w.bucket,w.storage_key)
            IS NOT DISTINCT FROM ROW(m.owner_id,m.folder_id,m.file_version,m.file_status,m.file_created_at,m.file_updated_at,
                m.deleted_at,m.purge_requested_at,m.storage_namespace,m.endpoint,m.region,m.bucket,m.storage_key)
            AND ROW(a.owner_id,a.storage_key,a.storage_namespace,a.endpoint,a.region,a.bucket)
                IS NOT DISTINCT FROM ROW(w.owner_id,w.storage_key,w.storage_namespace,w.endpoint,w.region,w.bucket))
$$;

CREATE FUNCTION guard_multipart_discard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.multipart_discarded_at IS NOT NULL
        AND NEW.multipart_discarded_at IS DISTINCT FROM OLD.multipart_discarded_at THEN
        RAISE EXCEPTION 'logical discard is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.multipart_discarded_at IS NOT NULL AND NOT multipart_discard_verified(NEW) THEN
        RAISE EXCEPTION 'logical discard requires retained sealed identity' USING ERRCODE = '23514';
    END IF;
    IF NEW.multipart_discarded_at IS NOT NULL AND (TG_OP = 'INSERT' OR OLD.multipart_discarded_at IS NULL) THEN
        IF NOT EXISTS (SELECT 1 FROM files f WHERE f.id = NEW.file_id
            AND ROW(f.owner_id,f.folder_id,f.version,f.status,f.created_at,f.updated_at,f.deleted_at,f.purge_requested_at,f.storage_key)
            IS NOT DISTINCT FROM ROW(NEW.owner_id,NEW.folder_id,NEW.file_version,NEW.file_status,NEW.file_created_at,
                NEW.file_updated_at,NEW.deleted_at,NEW.purge_requested_at,NEW.storage_key))
            OR NOT EXISTS (SELECT 1 FROM folder_purge_files m JOIN folder_purge_runs r ON r.event_id = m.event_id
                JOIN folders f ON f.id = r.root_folder_id WHERE m.file_id = NEW.file_id AND r.phase = 'DELETE_FILES'
                    AND f.owner_id = r.owner_id AND f.deleted_at IS NOT NULL AND f.purge_requested_at = r.requested_at) THEN
            RAISE EXCEPTION 'logical discard requires current pending folder intent' USING ERRCODE = '23514';
        END IF;
        NEW.multipart_discarded_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_multipart_discard BEFORE INSERT OR UPDATE ON file_purge_work
FOR EACH ROW EXECUTE FUNCTION guard_multipart_discard();

-- V25/V26 remain excluded. V17 work may represent multipart, but only READY plus
-- completion receipt may acquire a byte-deletion receipt. Never use never-written proof.
CREATE OR REPLACE FUNCTION fence_multipart_legacy_proof() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM file_storage_objects WHERE file_id = NEW.file_id AND upload_protocol = 'DIRECT_MULTIPART') THEN
        IF TG_TABLE_NAME = 'upload_cleanup_intents' THEN
            RAISE EXCEPTION 'PUT cleanup cannot adopt multipart' USING ERRCODE = '23514';
        ELSIF TG_TABLE_NAME = 'file_purge_targets' THEN
            IF NOT multipart_completed_allocation(NEW.file_id) OR NOT EXISTS (
                SELECT 1 FROM files WHERE id = NEW.file_id AND status = 'READY') THEN
                RAISE EXCEPTION 'multipart target requires READY completion' USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.never_written_at IS NOT NULL OR (NEW.bytes_deleted_at IS NOT NULL AND
            (NEW.file_status <> 'READY' OR NOT multipart_completed_allocation(NEW.file_id))) THEN
            RAISE EXCEPTION 'multipart physical proof requires completed READY allocation' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION folder_purge_file_verified(event UUID, file UUID) RETURNS BOOLEAN
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
                    AND a.write_precluded_at IS NOT NULL)
                OR (w.multipart_discarded_at IS NOT NULL AND multipart_discard_verified(w))))
$$;


CREATE OR REPLACE FUNCTION fence_unfinished_multipart_file() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s upload_sessions;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM file_storage_objects WHERE file_id = CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END
        AND upload_protocol = 'DIRECT_MULTIPART') THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF NOT EXISTS (SELECT 1 FROM file_purge_work w WHERE w.file_id = OLD.id
            AND ROW(OLD.owner_id,OLD.folder_id,OLD.version,OLD.status,OLD.created_at,OLD.updated_at,
                OLD.deleted_at,OLD.purge_requested_at,OLD.storage_key)
            IS NOT DISTINCT FROM ROW(w.owner_id,w.folder_id,w.file_version,w.file_status,w.file_created_at,w.file_updated_at,
                w.deleted_at,w.purge_requested_at,w.storage_key)
            AND ((OLD.status = 'READY' AND w.bytes_deleted_at IS NOT NULL AND multipart_completed_allocation(OLD.id))
                OR (w.multipart_discarded_at IS NOT NULL AND multipart_discard_verified(w)))) THEN
            RAISE EXCEPTION 'multipart removal lacks exact durable proof' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.status = 'READY' THEN
        SELECT * INTO s FROM upload_sessions WHERE file_id = NEW.id;
        IF NOT EXISTS (SELECT 1 FROM multipart_completion_receipts r JOIN multipart_completions c ON c.session_id = r.session_id
            WHERE r.session_id = s.id AND r.file_id = NEW.id AND r.manifest_hash = c.manifest_hash
                AND r.dispatch_id = c.dispatch_id AND s.state = 'COMPLETION_DECIDED') THEN
            RAISE EXCEPTION 'multipart READY requires protocol-specific receipt' USING ERRCODE = '23514';
        END IF;
        IF TG_OP = 'INSERT' OR (OLD.status <> 'READY' AND
            (OLD.status <> 'UPLOADING' OR NOT multipart_session_eligible(s)
                OR OLD.version <> s.original_file_version OR NEW.version <> s.original_file_version + 1
                OR (to_jsonb(NEW) - ARRAY['status','version','updated_at'])
                    IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status','version','updated_at']))) THEN
            RAISE EXCEPTION 'multipart READY metadata is not freshly eligible' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_OP = 'UPDATE' AND OLD.status = 'READY' THEN
        RAISE EXCEPTION 'multipart readiness cannot regress' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
