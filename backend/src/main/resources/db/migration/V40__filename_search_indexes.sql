-- pg_trgm supports literal substring LIKE, including leading wildcards. Short terms
-- may still scan; the API has a statement budget and makes no latency guarantee.
-- Use a stable extension schema across isolated Flyway test schemas.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE INDEX idx_files_search_name ON files USING gin (lower(name) public.gin_trgm_ops)
    WHERE status = 'READY' AND deleted_at IS NULL AND purge_requested_at IS NULL;
CREATE INDEX idx_folders_search_name ON folders USING gin (lower(name) public.gin_trgm_ops)
    WHERE deleted_at IS NULL AND purge_requested_at IS NULL;
