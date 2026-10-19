-- Immutable source snapshots remain in permission_activity_events. No cascading
-- resource/account references: delivery and history survive physical removal.
CREATE TABLE sharing_notifications (
    id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL REFERENCES permission_activity_events(id),
    recipient_user_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'GRANT_CREATED', 'GRANT_ROLE_CHANGED', 'GRANT_REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(created_at)),
    read_at TIMESTAMPTZ CHECK (isfinite(read_at) AND read_at >= created_at),
    CONSTRAINT uq_sharing_notification_recipient UNIQUE (source_event_id, recipient_user_id)
);
CREATE INDEX idx_sharing_notifications_recipient ON sharing_notifications(
    recipient_user_id, created_at DESC, id DESC);
CREATE INDEX idx_sharing_notifications_unread ON sharing_notifications(recipient_user_id)
    WHERE read_at IS NULL;

CREATE FUNCTION guard_sharing_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM permission_activity_events e
            WHERE e.id = NEW.source_event_id AND e.recipient_id = NEW.recipient_user_id
                AND e.event_type = NEW.event_type AND e.owner_id <> NEW.recipient_user_id) THEN
            RAISE EXCEPTION 'Notification source mismatch' USING ERRCODE = '23514';
        END IF;
    ELSIF ROW(NEW.id, NEW.source_event_id, NEW.recipient_user_id, NEW.event_type, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id, OLD.source_event_id, OLD.recipient_user_id, OLD.event_type, OLD.created_at)
        OR (OLD.read_at IS NOT NULL AND NEW.read_at IS DISTINCT FROM OLD.read_at) THEN
        RAISE EXCEPTION 'Notification identity and read transition are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER sharing_notification_guard BEFORE INSERT OR UPDATE ON sharing_notifications
    FOR EACH ROW EXECUTE FUNCTION guard_sharing_notification();
