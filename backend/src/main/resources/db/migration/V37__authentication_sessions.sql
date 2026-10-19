CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    csrf_hash VARCHAR(64) NOT NULL CHECK (csrf_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX auth_sessions_user_idx ON auth_sessions(user_id);
CREATE INDEX auth_sessions_expiry_idx ON auth_sessions(expires_at);

CREATE TABLE auth_refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    session_id UUID NOT NULL REFERENCES auth_sessions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    consumed_at TIMESTAMPTZ
);
CREATE INDEX auth_refresh_tokens_session_idx ON auth_refresh_tokens(session_id);
CREATE UNIQUE INDEX auth_refresh_tokens_one_active_idx ON auth_refresh_tokens(session_id)
    WHERE consumed_at IS NULL;

CREATE TABLE auth_access_revocations (
    jti UUID PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX auth_access_revocations_expiry_idx ON auth_access_revocations(expires_at, jti);
