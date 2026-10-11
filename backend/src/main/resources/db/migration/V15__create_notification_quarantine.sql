-- A sanitized PostgreSQL quarantine avoids changing the existing RabbitMQ queue.
-- Raw bodies/headers and exception text are deliberately not retained.
CREATE TABLE notification_quarantine (
    body_sha256 VARCHAR(64) PRIMARY KEY,
    event_id UUID,
    reason_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_notification_quarantine_hash CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_notification_quarantine_reason CHECK (reason_code IN (
        'MALFORMED', 'UNSUPPORTED_VERSION', 'UNSUPPORTED_TYPE',
        'UNKNOWN_EVENT', 'EVENT_MISMATCH', 'IDENTITY_CONFLICT'))
);

CREATE INDEX idx_notification_quarantine_created ON notification_quarantine(created_at, body_sha256);
CREATE INDEX idx_notification_quarantine_event ON notification_quarantine(event_id) WHERE event_id IS NOT NULL;
