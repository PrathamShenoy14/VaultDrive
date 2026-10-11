CREATE TABLE background_jobs (
    -- One logical job per stable notification event, including redeliveries.
    id UUID PRIMARY KEY,
    job_type VARCHAR(32) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 10,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claim_token UUID,
    claim_expires_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_failure_code VARCHAR(48),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,

    CONSTRAINT chk_background_jobs_type CHECK (
        (job_type = 'FILE_PURGE' AND aggregate_type = 'FILE') OR
        (job_type = 'FOLDER_PURGE' AND aggregate_type = 'FOLDER')),
    CONSTRAINT chk_background_jobs_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_background_jobs_attempts CHECK (
        max_attempts BETWEEN 1 AND 1000 AND attempt_count BETWEEN 0 AND max_attempts),
    CONSTRAINT chk_background_jobs_claim CHECK (
        (status = 'RUNNING' AND claim_token IS NOT NULL AND claim_expires_at IS NOT NULL) OR
        (status <> 'RUNNING' AND claim_token IS NULL AND claim_expires_at IS NULL)),
    CONSTRAINT chk_background_jobs_completion CHECK (
        (status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL) OR
        (status IN ('PENDING', 'RUNNING') AND completed_at IS NULL)),
    CONSTRAINT chk_background_jobs_attempt_time CHECK (
        (attempt_count = 0 AND last_attempt_at IS NULL) OR
        (attempt_count > 0 AND last_attempt_at IS NOT NULL)),
    CONSTRAINT chk_background_jobs_started CHECK (status = 'PENDING' OR attempt_count > 0),
    CONSTRAINT chk_background_jobs_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code IN ('WORK_FAILED', 'LEASE_EXPIRED'))
);

-- No aggregate/event foreign keys: work and deduplication survive metadata or
-- outbox retention. Terminal job retention must preserve logical identity.
CREATE INDEX idx_background_jobs_due
ON background_jobs(next_attempt_at, created_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_background_jobs_expired
ON background_jobs(claim_expires_at, next_attempt_at, id) WHERE status = 'RUNNING';
CREATE INDEX idx_background_jobs_aggregate
ON background_jobs(aggregate_type, aggregate_id, created_at);
