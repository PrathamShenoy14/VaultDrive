-- Operator actions retain exact identities and prior budgets. No data/binding repair.
CREATE TABLE operator_actions (
    id UUID PRIMARY KEY,
    target_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL CHECK (action IN ('REPLAY_EVENT','EXTEND_JOB_BUDGET')),
    incident VARCHAR(80) NOT NULL CHECK (incident ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('REQUESTED','SUCCEEDED','UNKNOWN')),
    prior_attempts BIGINT,
    prior_failures INTEGER,
    prior_budget INTEGER,
    new_budget INTEGER,
    transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((action='REPLAY_EVENT' AND prior_attempts IS NULL AND prior_failures IS NULL
        AND prior_budget IS NULL AND new_budget IS NULL) OR
        (action='EXTEND_JOB_BUDGET' AND outcome='SUCCEEDED'
        AND prior_attempts IS NOT NULL AND prior_failures IS NOT NULL AND prior_budget IS NOT NULL AND new_budget IS NOT NULL
        AND prior_attempts >= prior_failures
        AND prior_failures=prior_budget AND new_budget BETWEEN prior_budget+1 AND least(prior_budget+10,1000)))
);
CREATE INDEX idx_operator_actions_target ON operator_actions(target_id,action,outcome);

CREATE FUNCTION guard_operator_action() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'operator audit is retained' USING ERRCODE='23514'; END IF;
    IF (to_jsonb(NEW)-'outcome') IS DISTINCT FROM (to_jsonb(OLD)-'outcome')
        OR OLD.outcome <> 'REQUESTED' OR NEW.outcome NOT IN ('SUCCEEDED','UNKNOWN') THEN
        RAISE EXCEPTION 'operator audit is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_operator_action BEFORE UPDATE OR DELETE ON operator_actions
FOR EACH ROW EXECUTE FUNCTION guard_operator_action();

-- Keep every execution/failure/checkpoint monotonic. Only an audited exact FAILED
-- job may receive another finite tranche. No active lease or receipt is replaced.
CREATE OR REPLACE FUNCTION preserve_background_job_counters() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attempt_count < OLD.attempt_count OR NEW.failure_count < OLD.failure_count
       OR NEW.progress_version < OLD.progress_version OR NEW.claim_progress_version < OLD.claim_progress_version THEN
        RAISE EXCEPTION 'job accounting and checkpoints cannot be reset' USING ERRCODE='23514';
    END IF;
    IF NEW.max_attempts <> OLD.max_attempts OR (OLD.status='FAILED' AND NEW.status <> 'FAILED') THEN
        IF OLD.status <> 'FAILED' OR NEW.status <> 'PENDING'
           OR ROW(NEW.attempt_count,NEW.failure_count,NEW.progress_version,NEW.claim_progress_version)
              IS DISTINCT FROM ROW(OLD.attempt_count,OLD.failure_count,OLD.progress_version,OLD.claim_progress_version)
           OR NOT EXISTS (SELECT 1 FROM operator_actions a WHERE a.target_id=OLD.id
               AND a.action='EXTEND_JOB_BUDGET' AND a.outcome='SUCCEEDED' AND a.transaction_id=txid_current()
               AND a.prior_attempts=OLD.attempt_count AND a.prior_failures=OLD.failure_count
               AND a.prior_budget=OLD.max_attempts AND a.new_budget=NEW.max_attempts) THEN
            RAISE EXCEPTION 'job retry budget requires bounded operator audit' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE INDEX idx_upload_sessions_operational_expiry ON upload_sessions(id,expires_at) WHERE decision_id IS NULL;
