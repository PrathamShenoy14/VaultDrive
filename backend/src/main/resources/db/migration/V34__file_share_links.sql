CREATE TABLE share_links (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    file_id UUID NOT NULL,
    token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_share_link_file_owner FOREIGN KEY (file_id, owner_id)
        REFERENCES files(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT chk_share_link_expiry CHECK (isfinite(created_at) AND isfinite(expires_at)
        AND expires_at > created_at AND expires_at <= created_at + INTERVAL '7 days'),
    CONSTRAINT chk_share_link_revocation CHECK (
        (revoked_at IS NULL AND version = 0) OR
        (revoked_at IS NOT NULL AND isfinite(revoked_at) AND revoked_at >= created_at AND version = 1))
);
CREATE INDEX idx_share_links_file ON share_links(file_id, created_at, id);

-- Only the first permanent revocation can change a link. Renewal creates a new identity.
CREATE FUNCTION guard_share_link() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.owner_id, NEW.file_id, NEW.token_hash, NEW.created_at, NEW.expires_at)
        IS DISTINCT FROM
       ROW(OLD.id, OLD.owner_id, OLD.file_id, OLD.token_hash, OLD.created_at, OLD.expires_at)
       OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Immutable share link or invalid revocation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER share_link_identity BEFORE UPDATE ON share_links
    FOR EACH ROW EXECUTE FUNCTION guard_share_link();
