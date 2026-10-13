-- Claims remain monotonic; only failed/abandoned executions spend the budget.
ALTER TABLE background_jobs DROP CONSTRAINT chk_background_jobs_attempts;
ALTER TABLE background_jobs ALTER COLUMN attempt_count TYPE BIGINT;
ALTER TABLE background_jobs ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE background_jobs ADD COLUMN progress_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE background_jobs ADD COLUMN claim_progress_version BIGINT NOT NULL DEFAULT 0;

-- V14 counted every claim. RUNNING has one unresolved execution; SUCCEEDED
-- has one successful execution. All earlier executions failed or were abandoned.
-- Preserve existing terminal outcomes, claim tokens, timestamps and retry dates.
UPDATE background_jobs SET failure_count = attempt_count -
    CASE WHEN status IN ('RUNNING', 'SUCCEEDED') THEN 1 ELSE 0 END;

ALTER TABLE background_jobs ADD CONSTRAINT chk_background_jobs_attempts CHECK (
    max_attempts BETWEEN 1 AND 1000 AND attempt_count >= 0
    AND failure_count BETWEEN 0 AND max_attempts AND failure_count <= attempt_count
    AND (status <> 'RUNNING' OR failure_count < max_attempts));
ALTER TABLE background_jobs ADD CONSTRAINT chk_background_jobs_progress CHECK (
    progress_version >= 0 AND claim_progress_version BETWEEN 0 AND progress_version);
ALTER TABLE background_jobs DROP CONSTRAINT chk_background_jobs_failure_code;
ALTER TABLE background_jobs ADD CONSTRAINT chk_background_jobs_failure_code CHECK (
    last_failure_code IS NULL OR last_failure_code IN ('WORK_FAILED', 'LEASE_EXPIRED', 'NO_PROGRESS'));

CREATE FUNCTION preserve_background_job_counters() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attempt_count < OLD.attempt_count OR NEW.failure_count < OLD.failure_count
       OR NEW.progress_version < OLD.progress_version
       OR NEW.claim_progress_version < OLD.claim_progress_version
       OR NEW.max_attempts <> OLD.max_attempts THEN
        RAISE EXCEPTION 'job accounting and checkpoints cannot be reset' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_background_job_counters BEFORE UPDATE ON background_jobs
FOR EACH ROW EXECUTE FUNCTION preserve_background_job_counters();
