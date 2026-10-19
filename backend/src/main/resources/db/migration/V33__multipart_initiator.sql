-- Only the new initiator column is backfilled. Retained storage identities,
-- fingerprints, manifests, receipts, decisions and dispatch IDs are untouched.
ALTER TABLE upload_sessions ADD COLUMN initiator_user_id UUID;
-- V27's whole-row identity guard deliberately rejects even this backfill.
-- Flyway applies this migration atomically under its migration lock.
ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session;
UPDATE upload_sessions SET initiator_user_id = owner_id;
-- Flush V27's deferred pair checks before ALTER TABLE; populated installations
-- otherwise retain pending trigger events. This validates existing pair identity
-- without re-running lifecycle admission against historical terminal sessions.
SET CONSTRAINTS trg_session_multipart_pair IMMEDIATE;
SET CONSTRAINTS trg_session_multipart_pair DEFERRED;
ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session;
ALTER TABLE upload_sessions ALTER COLUMN initiator_user_id SET NOT NULL;
-- No user FK: sessions/evidence must outlive account/resource removal, like owner_id.
ALTER TABLE upload_sessions DROP CONSTRAINT upload_sessions_owner_id_idempotency_key_key;
ALTER TABLE upload_sessions ADD CONSTRAINT uq_upload_session_owner_initiator_key
    UNIQUE (owner_id, initiator_user_id, idempotency_key);
-- The existing whole-row guard automatically makes initiator_user_id immutable,
-- including after a permanent completion/abandonment decision.
