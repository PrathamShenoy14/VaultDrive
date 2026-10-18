-- Dispatch permission is permanent, not a lease. Absence of a result never permits retry.
CREATE TABLE multipart_initiation_dispatches (
    session_id UUID PRIMARY KEY REFERENCES upload_sessions(id),
    initiation_id UUID NOT NULL UNIQUE,
    dispatch_decided_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION guard_multipart_dispatch() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE session upload_sessions;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'multipart dispatch permission is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO session FROM upload_sessions WHERE id = NEW.session_id FOR UPDATE;
    IF session.state <> 'INITIATING' OR session.initiation_id IS DISTINCT FROM NEW.initiation_id
        OR session.expires_at <= clock_timestamp() OR NOT multipart_session_eligible(session) THEN
        RAISE EXCEPTION 'multipart dispatch is not eligible' USING ERRCODE = '23514';
    END IF;
    NEW.dispatch_decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_multipart_dispatch BEFORE INSERT OR UPDATE OR DELETE ON multipart_initiation_dispatches
FOR EACH ROW EXECUTE FUNCTION guard_multipart_dispatch();

CREATE TABLE upload_session_parts (
    session_id UUID NOT NULL REFERENCES upload_sessions(id),
    part_number INTEGER NOT NULL CHECK (part_number BETWEEN 1 AND 640),
    expected_length BIGINT NOT NULL CHECK (expected_length BETWEEN 1 AND 8388608),
    expected_sha256 VARCHAR(44) NOT NULL CHECK (expected_sha256 ~ '^[A-Za-z0-9+/]{43}=$'
        AND encode(decode(expected_sha256, 'base64'), 'base64') = expected_sha256),
    registration_id UUID NOT NULL UNIQUE,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (session_id, part_number)
);

-- Caller order: hierarchy -> fresh file -> allocation -> session -> parts ascending.
CREATE FUNCTION guard_multipart_part() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE session upload_sessions;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'multipart part authorization is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO session FROM upload_sessions WHERE id = NEW.session_id FOR UPDATE;
    IF session.state <> 'OPEN' OR session.garage_upload_id IS NULL
        OR session.expires_at <= clock_timestamp() OR NOT multipart_session_eligible(session)
        OR NEW.part_number > session.part_count
        OR NEW.expected_length <> least(session.part_size,
            session.declared_size - (NEW.part_number - 1)::bigint * session.part_size) THEN
        RAISE EXCEPTION 'multipart part geometry or authorization is invalid' USING ERRCODE = '23514';
    END IF;
    NEW.registered_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_multipart_part BEFORE INSERT OR UPDATE OR DELETE ON upload_session_parts
FOR EACH ROW EXECUTE FUNCTION guard_multipart_part();
