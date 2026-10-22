-- Historical evidence follows permission activity retention: no account/resource
-- cascades, no pruning API, and no current authority derived from these snapshots.
CREATE TABLE resource_activity_events (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL,
    actor_display_name VARCHAR(100) NOT NULL,
    actor_attribution VARCHAR(24) NOT NULL CHECK (actor_attribution IN ('CALLER', 'UPLOAD_INITIATOR', 'DELETION_REQUESTER')),
    owner_id UUID NOT NULL,
    resource_type VARCHAR(16) NOT NULL CHECK (resource_type IN ('FILE', 'FOLDER')),
    resource_id UUID NOT NULL,
    resource_name VARCHAR(255) NOT NULL CHECK (length(btrim(resource_name)) > 0),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'UPLOAD_COMPLETED', 'FOLDER_CREATED', 'RENAMED', 'MOVED', 'TRASHED', 'RESTORED', 'PERMANENTLY_DELETED')),
    before_name VARCHAR(255), after_name VARCHAR(255),
    before_parent_id UUID, after_parent_id UUID,
    before_trashed BOOLEAN, after_trashed BOOLEAN,
    before_status VARCHAR(32) CHECK (before_status IN ('UPLOADING', 'READY', 'FAILED')),
    after_status VARCHAR(32) CHECK (after_status IN ('UPLOADING', 'READY', 'FAILED')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(occurred_at)),
    CHECK (resource_name = coalesce(after_name, before_name)),
    CHECK ((resource_type = 'FOLDER' AND before_status IS NULL AND after_status IS NULL)
        OR resource_type = 'FILE'),
    CHECK (resource_type <> 'FILE' OR ((before_name IS NULL) = (before_status IS NULL)
        AND (after_name IS NULL) = (after_status IS NULL))),
    CHECK ((before_name IS NULL) = (before_trashed IS NULL)),
    CHECK ((after_name IS NULL) = (after_trashed IS NULL)),
    CHECK (before_name IS NOT NULL OR before_parent_id IS NULL),
    CHECK (after_name IS NOT NULL OR after_parent_id IS NULL),
    CONSTRAINT chk_resource_activity_shape CHECK ((
        (event_type = 'FOLDER_CREATED' AND resource_type = 'FOLDER' AND before_name IS NULL
            AND after_name IS NOT NULL AND after_trashed = false)
        OR (event_type = 'UPLOAD_COMPLETED' AND resource_type = 'FILE' AND before_status = 'UPLOADING'
            AND after_status = 'READY' AND before_name = after_name
            AND before_parent_id IS NOT DISTINCT FROM after_parent_id
            AND before_trashed = false AND after_trashed = false)
        OR (event_type = 'RENAMED' AND before_name <> after_name
            AND before_parent_id IS NOT DISTINCT FROM after_parent_id
            AND before_trashed = false AND after_trashed = false
            AND before_status IS NOT DISTINCT FROM after_status)
        OR (event_type = 'MOVED' AND before_name = after_name
            AND before_parent_id IS DISTINCT FROM after_parent_id
            AND before_trashed = false AND after_trashed = false
            AND before_status IS NOT DISTINCT FROM after_status)
        OR (event_type = 'TRASHED' AND before_name = after_name
            AND before_parent_id IS NOT DISTINCT FROM after_parent_id
            AND before_trashed = false AND after_trashed = true
            AND before_status IS NOT DISTINCT FROM after_status)
        OR (event_type = 'RESTORED' AND before_name IS NOT NULL AND after_name IS NOT NULL
            AND before_trashed = true AND after_trashed = false
            AND before_status IS NOT DISTINCT FROM after_status)
        OR (event_type = 'PERMANENTLY_DELETED' AND before_name IS NOT NULL AND after_name IS NULL
            AND after_status IS NULL)
    ) IS TRUE)
);
CREATE INDEX idx_resource_activity_owner ON resource_activity_events(owner_id, occurred_at DESC, id DESC);
CREATE INDEX idx_resource_activity_resource ON resource_activity_events(
    owner_id, resource_type, resource_id, occurred_at DESC, id DESC);
-- Stable resource identity cannot be created/completed/deleted twice. Ordinary
-- mutations can recur; their exactly-once boundary is the coordinated transition.
CREATE UNIQUE INDEX uq_resource_activity_terminal ON resource_activity_events(resource_type, resource_id, event_type)
    WHERE event_type IN ('UPLOAD_COMPLETED', 'FOLDER_CREATED', 'PERMANENTLY_DELETED');
CREATE FUNCTION guard_resource_activity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Resource activity is immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER resource_activity_immutable BEFORE UPDATE OR DELETE ON resource_activity_events
    FOR EACH ROW EXECUTE FUNCTION guard_resource_activity();

-- New frozen completion decisions retain their authenticated actor, even when
-- a different caller later recovers READY. Existing immutable decisions stay
-- untouched: their known original initiator is the documented legacy attribution.
ALTER TABLE multipart_completions ADD COLUMN activity_actor_id UUID;
ALTER TABLE multipart_completions ADD COLUMN activity_actor_display_name VARCHAR(100);
ALTER TABLE multipart_completions ADD CONSTRAINT chk_completion_activity_actor
    CHECK ((activity_actor_id IS NULL) = (activity_actor_display_name IS NULL));
-- V29's whole-row identity guard automatically makes both columns immutable.
