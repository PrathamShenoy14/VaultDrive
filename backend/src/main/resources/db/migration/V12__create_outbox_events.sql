CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,

    CONSTRAINT chk_outbox_events_event_type_not_empty
        CHECK (LENGTH(TRIM(event_type)) > 0),
    CONSTRAINT chk_outbox_events_aggregate_type_not_empty
        CHECK (LENGTH(TRIM(aggregate_type)) > 0),
    CONSTRAINT chk_outbox_events_payload_version_positive
        CHECK (payload_version > 0),
    CONSTRAINT chk_outbox_events_payload_object
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT chk_outbox_events_attempt_count_non_negative
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_outbox_events_delivery_status
        CHECK (delivery_status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT chk_outbox_events_publication_timestamp
        CHECK (
            (delivery_status = 'PENDING' AND published_at IS NULL)
            OR (delivery_status = 'PUBLISHED' AND published_at IS NOT NULL)
        )
);

-- Aggregate references deliberately have no foreign key: events must survive
-- future physical deletion of the file/folder they describe.
CREATE INDEX idx_outbox_events_pending_delivery
ON outbox_events(next_attempt_at, created_at, id)
WHERE delivery_status = 'PENDING';

CREATE INDEX idx_outbox_events_aggregate
ON outbox_events(aggregate_type, aggregate_id, created_at);
