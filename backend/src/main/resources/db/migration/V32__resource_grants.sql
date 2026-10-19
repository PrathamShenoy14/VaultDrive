ALTER TABLE files ADD CONSTRAINT uq_files_id_owner UNIQUE (id, owner_id);

CREATE TABLE resource_grants (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    file_id UUID,
    folder_id UUID,
    grantee_user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('VIEWER', 'EDITOR')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT chk_grant_target CHECK (num_nonnulls(file_id, folder_id) = 1),
    CONSTRAINT chk_grant_not_self CHECK (owner_id <> grantee_user_id),
    CONSTRAINT fk_grant_file_owner FOREIGN KEY (file_id, owner_id)
        REFERENCES files(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT fk_grant_folder_owner FOREIGN KEY (folder_id, owner_id)
        REFERENCES folders(id, owner_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_grants_file_recipient ON resource_grants(file_id, grantee_user_id)
    WHERE file_id IS NOT NULL;
CREATE UNIQUE INDEX uq_grants_folder_recipient ON resource_grants(folder_id, grantee_user_id)
    WHERE folder_id IS NOT NULL;
CREATE INDEX idx_grants_recipient ON resource_grants(grantee_user_id, created_at, id);

CREATE FUNCTION guard_resource_grant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.owner_id, NEW.file_id, NEW.folder_id, NEW.grantee_user_id, NEW.created_at)
        IS DISTINCT FROM
       ROW(OLD.id, OLD.owner_id, OLD.file_id, OLD.folder_id, OLD.grantee_user_id, OLD.created_at)
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Immutable grant identity or invalid revision' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER resource_grant_identity BEFORE UPDATE ON resource_grants
    FOR EACH ROW EXECUTE FUNCTION guard_resource_grant();

-- One statement snapshot binds file location, full ancestry, lifecycle and grants.
-- Depth > 256, cycles, missing/cross-owner ancestry and any pending purge fail closed.
-- include_trash is exclusively for owner grant inspection/reduction/revocation.
CREATE FUNCTION resolve_resource_access(actor UUID, target_file UUID, target_folder UUID,
                                        include_trash BOOLEAN DEFAULT FALSE)
RETURNS TABLE(id UUID, owner_id UUID, parent_id UUID, name VARCHAR, content_type VARCHAR,
              size_bytes BIGINT, storage_key VARCHAR, created_at TIMESTAMPTZ,
              updated_at TIMESTAMPTZ, effective_role TEXT, parent_visible BOOLEAN,
              active BOOLEAN, folder_path UUID[])
LANGUAGE sql STABLE AS $$
WITH RECURSIVE target AS (
    SELECT f.id, f.owner_id, f.folder_id parent_id, f.name, f.content_type, f.size_bytes,
           f.storage_key, f.created_at, f.updated_at, f.deleted_at, f.purge_requested_at
    FROM files f WHERE f.id = target_file AND target_folder IS NULL AND f.status = 'READY'
    UNION ALL
    SELECT d.id, d.owner_id, d.parent_folder_id, d.name, NULL::VARCHAR, 0::BIGINT,
           NULL::VARCHAR, d.created_at, d.updated_at, d.deleted_at, d.purge_requested_at
    FROM folders d WHERE d.id = target_folder AND target_file IS NULL
), ancestry AS (
    SELECT d.id, d.parent_folder_id, d.deleted_at, d.purge_requested_at, ARRAY[d.id] path, 1 depth
    FROM target t JOIN folders d ON d.id = t.parent_id AND d.owner_id = t.owner_id
    UNION ALL
    SELECT d.id, d.parent_folder_id, d.deleted_at, d.purge_requested_at, a.path || d.id, a.depth + 1
    FROM ancestry a CROSS JOIN target t
    JOIN folders d ON d.id = a.parent_folder_id AND d.owner_id = t.owner_id
    WHERE a.depth < 256 AND NOT d.id = ANY(a.path)
), state AS (
    SELECT t.*,
        t.deleted_at IS NULL AND NOT EXISTS (
            SELECT 1 FROM ancestry a WHERE a.deleted_at IS NOT NULL) AS active,
        ARRAY(SELECT a.id FROM ancestry a ORDER BY a.depth) AS ancestors
    FROM target t
    WHERE t.purge_requested_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM ancestry a WHERE a.purge_requested_at IS NOT NULL)
      AND (t.parent_id IS NULL OR EXISTS (
          SELECT 1 FROM ancestry a WHERE a.parent_folder_id IS NULL))
), permissions AS (
    SELECT s.*, COALESCE((SELECT max(CASE g.role WHEN 'EDITOR' THEN 2 ELSE 1 END)
        FROM resource_grants g WHERE g.grantee_user_id = actor AND g.owner_id = s.owner_id
        AND (g.file_id = target_file OR g.folder_id = target_folder
             OR g.folder_id = ANY(s.ancestors))), 0) rank,
        EXISTS (SELECT 1 FROM resource_grants g WHERE g.grantee_user_id = actor
            AND g.owner_id = s.owner_id AND g.folder_id = ANY(s.ancestors)) parent_visible
    FROM state s
)
SELECT p.id, p.owner_id, p.parent_id, p.name, p.content_type, p.size_bytes, p.storage_key,
       p.created_at, p.updated_at,
       CASE WHEN actor = p.owner_id THEN 'OWNER' WHEN p.rank = 2 THEN 'EDITOR' ELSE 'VIEWER' END,
       actor = p.owner_id OR p.parent_visible, p.active,
       CASE WHEN target_folder IS NOT NULL THEN ARRAY[target_folder] || p.ancestors ELSE p.ancestors END
FROM permissions p
WHERE (p.active OR (include_trash AND actor = p.owner_id))
  AND (actor = p.owner_id OR p.rank > 0)
$$;
