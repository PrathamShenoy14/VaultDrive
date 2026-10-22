-- Threshold state is derived from the locked quota row, not from delivery state.
-- Existing usage is the rollout baseline: no retrospective notification backfill.
CREATE TABLE storage_quota_events (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    threshold INTEGER NOT NULL CHECK (threshold IN (80,90,100)),
    logical_usage_bytes BIGINT NOT NULL CHECK (logical_usage_bytes > 0),
    limit_bytes BIGINT NOT NULL CHECK (limit_bytes >= 0),
    occurred_at TIMESTAMPTZ NOT NULL CHECK (isfinite(occurred_at)),
    CHECK (logical_usage_bytes::numeric * 100 >= limit_bytes::numeric * threshold)
);
CREATE FUNCTION guard_storage_quota_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Quota events are immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER storage_quota_event_immutable BEFORE UPDATE OR DELETE ON storage_quota_events
FOR EACH ROW EXECUTE FUNCTION guard_storage_quota_event();

CREATE FUNCTION quota_threshold_reached(used BIGINT, reserved BIGINT, quota_limit BIGINT, threshold INTEGER)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT used::numeric + reserved > 0
        AND (used::numeric + reserved) * 100 >= quota_limit::numeric * threshold
$$;

CREATE FUNCTION notify_storage_quota_crossings() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE threshold INTEGER; event_id UUID; event_time TIMESTAMPTZ;
BEGIN
    -- UPDATE already holds the accounting row lock. No existing resource locks
    -- follow: only new immutable source/outbox rows are inserted here.
    FOREACH threshold IN ARRAY ARRAY[80,90,100] LOOP
        IF quota_threshold_reached(NEW.used_bytes, NEW.reserved_bytes, NEW.limit_bytes, threshold)
            AND NOT quota_threshold_reached(OLD.used_bytes, OLD.reserved_bytes, OLD.limit_bytes, threshold) THEN
            event_id := gen_random_uuid(); event_time := clock_timestamp();
            INSERT INTO storage_quota_events VALUES (event_id, NEW.owner_id, threshold,
                NEW.used_bytes + NEW.reserved_bytes, NEW.limit_bytes, event_time);
            INSERT INTO outbox_events(id,event_type,aggregate_type,aggregate_id,payload_version,payload,created_at,next_attempt_at)
            VALUES (event_id,'STORAGE_QUOTA_THRESHOLD_CROSSED','STORAGE_QUOTA_EVENT',event_id,1,
                jsonb_build_object('quotaEventId',event_id::text),event_time,event_time);
        END IF;
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER storage_quota_crossings AFTER UPDATE OF used_bytes,reserved_bytes,limit_bytes ON storage_quotas
FOR EACH ROW EXECUTE FUNCTION notify_storage_quota_crossings();

-- Retain the existing notification table, indexes and event/recipient uniqueness.
-- Both immutable source tables forbid deletion; the conditional source guard
-- replaces the permission-only FK without losing source/recipient validation.
ALTER TABLE sharing_notifications DROP CONSTRAINT sharing_notifications_source_event_id_fkey;
ALTER TABLE sharing_notifications DROP CONSTRAINT sharing_notifications_event_type_check;
ALTER TABLE sharing_notifications ADD CONSTRAINT sharing_notifications_event_type_check CHECK (event_type IN (
    'GRANT_CREATED','GRANT_ROLE_CHANGED','GRANT_REVOKED','STORAGE_QUOTA_THRESHOLD_CROSSED'));
CREATE OR REPLACE FUNCTION guard_sharing_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.event_type = 'STORAGE_QUOTA_THRESHOLD_CROSSED' THEN
            IF NOT EXISTS (SELECT 1 FROM storage_quota_events e
                WHERE e.id = NEW.source_event_id AND e.owner_id = NEW.recipient_user_id) THEN
                RAISE EXCEPTION 'Notification source mismatch' USING ERRCODE = '23514';
            END IF;
        ELSIF NOT EXISTS (SELECT 1 FROM permission_activity_events e
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
