-- Keyset recovery reads only aged published events. Jobs/quarantine retain their
-- event-ID indexes; no event, proof, job state or accounting is rewritten.
CREATE INDEX idx_outbox_events_published_reconciliation
ON outbox_events(published_at, id) WHERE delivery_status = 'PUBLISHED';
