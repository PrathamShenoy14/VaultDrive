ALTER TABLE outbox_events
ADD COLUMN claim_token UUID,
ADD COLUMN claim_expires_at TIMESTAMPTZ,
ADD COLUMN last_failure_code VARCHAR(48);

ALTER TABLE outbox_events
DROP CONSTRAINT chk_outbox_events_delivery_status,
DROP CONSTRAINT chk_outbox_events_publication_timestamp;

ALTER TABLE outbox_events
ADD CONSTRAINT chk_outbox_events_delivery_status
    CHECK (delivery_status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
ADD CONSTRAINT chk_outbox_events_publication_timestamp
    CHECK (
        (delivery_status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (delivery_status <> 'PUBLISHED' AND published_at IS NULL)
    ),
ADD CONSTRAINT chk_outbox_events_claim
    CHECK (
        (delivery_status = 'PUBLISHING'
            AND claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
        OR (delivery_status <> 'PUBLISHING'
            AND claim_token IS NULL AND claim_expires_at IS NULL)
    );

CREATE INDEX idx_outbox_events_expired_claims
ON outbox_events(claim_expires_at, created_at, id)
WHERE delivery_status = 'PUBLISHING';
