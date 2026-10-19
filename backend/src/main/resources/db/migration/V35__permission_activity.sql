-- Historical snapshots deliberately have no foreign keys to mutable/deletable
-- resources, accounts, grants or links. They confer no current resource authority.
CREATE TABLE permission_activity_events (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL,
    actor_display_name VARCHAR(100) NOT NULL,
    owner_id UUID NOT NULL,
    resource_type VARCHAR(16) NOT NULL CHECK (resource_type IN ('FILE', 'FOLDER')),
    resource_id UUID NOT NULL,
    resource_name VARCHAR(255) NOT NULL CHECK (length(trim(resource_name)) > 0),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'GRANT_CREATED', 'GRANT_ROLE_CHANGED', 'GRANT_REVOKED', 'LINK_CREATED', 'LINK_REVOKED')),
    subject_id UUID NOT NULL,
    subject_version BIGINT NOT NULL CHECK (subject_version >= 0),
    recipient_id UUID,
    recipient_email VARCHAR(255),
    old_role VARCHAR(16) CHECK (old_role IN ('VIEWER', 'EDITOR')),
    new_role VARCHAR(16) CHECK (new_role IN ('VIEWER', 'EDITOR')),
    link_expires_at TIMESTAMPTZ CHECK (isfinite(link_expires_at)),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(occurred_at)),
    CONSTRAINT uq_permission_activity_revision UNIQUE (subject_id, subject_version),
    CONSTRAINT chk_permission_activity_actor CHECK (actor_id = owner_id),
    CONSTRAINT chk_permission_activity_shape CHECK (
        (event_type = 'GRANT_CREATED' AND recipient_id IS NOT NULL AND recipient_email IS NOT NULL
            AND recipient_id <> owner_id AND old_role IS NULL AND new_role IS NOT NULL
            AND subject_version = 0 AND link_expires_at IS NULL)
        OR (event_type = 'GRANT_ROLE_CHANGED' AND recipient_id IS NOT NULL AND recipient_email IS NOT NULL
            AND recipient_id <> owner_id AND old_role IS NOT NULL AND new_role IS NOT NULL
            AND old_role <> new_role AND subject_version > 0 AND link_expires_at IS NULL)
        OR (event_type = 'GRANT_REVOKED' AND recipient_id IS NOT NULL AND recipient_email IS NOT NULL
            AND recipient_id <> owner_id AND old_role IS NOT NULL AND new_role IS NULL
            AND subject_version > 0 AND link_expires_at IS NULL)
        OR (event_type IN ('LINK_CREATED', 'LINK_REVOKED') AND resource_type = 'FILE'
            AND recipient_id IS NULL AND recipient_email IS NULL AND old_role IS NULL AND new_role IS NULL
            AND link_expires_at IS NOT NULL
            AND ((event_type = 'LINK_CREATED' AND subject_version = 0)
                OR (event_type = 'LINK_REVOKED' AND subject_version = 1)))
    )
);
CREATE INDEX idx_permission_activity_owner ON permission_activity_events(owner_id, occurred_at DESC, id DESC);
CREATE INDEX idx_permission_activity_resource ON permission_activity_events(
    owner_id, resource_type, resource_id, occurred_at DESC, id DESC);

CREATE FUNCTION guard_permission_activity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Permission activity is immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER permission_activity_immutable BEFORE UPDATE OR DELETE ON permission_activity_events
    FOR EACH ROW EXECUTE FUNCTION guard_permission_activity();
