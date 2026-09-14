-- V18 evidence remains append-only; a response creates a new generation, never reopens an old stage.
CREATE TABLE apr_quorum_information_rounds (
    round_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    source_generation BIGINT NOT NULL CHECK (source_generation > 0),
    target_generation BIGINT NOT NULL CHECK (target_generation = source_generation + 1),
    step_id UUID NOT NULL,
    task_id UUID NOT NULL,
    source_stage_version BIGINT NOT NULL CHECK (source_stage_version > 0),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    actor_person_id UUID NOT NULL,
    principal_user_id BIGINT NOT NULL CHECK (principal_user_id > 0),
    principal_person_id UUID NOT NULL,
    delegation_id UUID,
    context JSONB NOT NULL CHECK (jsonb_typeof(context) = 'object'),
    retained_snapshots JSONB NOT NULL CHECK (jsonb_typeof(retained_snapshots) = 'object'),
    reason VARCHAR(2000) NOT NULL CHECK (length(btrim(reason)) >= 4 AND btrim(reason) = reason),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESPONDED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    opened_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    material_change BOOLEAN,
    response_payload_revision INTEGER CHECK (response_payload_revision > 0),
    response_payload_sha256 CHAR(64) CHECK (response_payload_sha256 ~ '^[a-f0-9]{64}$'),
    PRIMARY KEY (tenant_id, request_id, source_generation),
    FOREIGN KEY (tenant_id, request_id, step_id, source_generation)
        REFERENCES apr_quorum_stage_runtime(tenant_id, request_id, step_id, generation),
    FOREIGN KEY (tenant_id, request_id, step_id, task_id)
        REFERENCES apr_tasks(tenant_id, request_id, step_id, task_id),
    FOREIGN KEY (tenant_id, request_id, step_id, source_generation, principal_user_id, principal_person_id, task_id)
        REFERENCES apr_quorum_candidates(tenant_id, request_id, step_id, generation,
            principal_user_id, principal_person_id, task_id),
    CHECK (((context->'pins'->>'tenantId')::bigint = tenant_id) IS TRUE),
    CHECK ((actor_user_id = principal_user_id) = (delegation_id IS NULL)),
    CHECK ((status = 'OPEN' AND responded_at IS NULL AND material_change IS NULL
            AND response_payload_revision IS NULL AND response_payload_sha256 IS NULL)
        OR (status = 'RESPONDED' AND responded_at >= opened_at AND material_change IS NOT NULL
            AND response_payload_revision IS NOT NULL AND response_payload_sha256 IS NOT NULL))
);

CREATE TABLE apr_quorum_information_commands (
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,120}$'),
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('REQUEST_INFO', 'REPLY')),
    command_sha256 CHAR(64) NOT NULL CHECK (command_sha256 ~ '^[a-f0-9]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('UNKNOWN', 'COMPLETED')),
    receipt JSONB CHECK (jsonb_typeof(receipt) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, request_id, idempotency_key),
    FOREIGN KEY (tenant_id, request_id) REFERENCES apr_requests(tenant_id, request_id),
    CHECK ((status = 'UNKNOWN' AND receipt IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND receipt IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE FUNCTION protect_apr_quorum_information_round() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Information round evidence is immutable'; END IF;
    IF (NEW.round_id, NEW.tenant_id, NEW.request_id, NEW.source_generation, NEW.target_generation,
        NEW.step_id, NEW.task_id, NEW.source_stage_version, NEW.actor_user_id, NEW.actor_person_id,
        NEW.principal_user_id, NEW.principal_person_id, NEW.delegation_id, NEW.context,
        NEW.retained_snapshots, NEW.reason, NEW.opened_at) IS DISTINCT FROM
       (OLD.round_id, OLD.tenant_id, OLD.request_id, OLD.source_generation, OLD.target_generation,
        OLD.step_id, OLD.task_id, OLD.source_stage_version, OLD.actor_user_id, OLD.actor_person_id,
        OLD.principal_user_id, OLD.principal_person_id, OLD.delegation_id, OLD.context,
        OLD.retained_snapshots, OLD.reason, OLD.opened_at)
       OR OLD.status <> 'OPEN' OR NEW.status <> 'RESPONDED' OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Invalid information round pins, transition or CAS version';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apr_quorum_information_round_frozen BEFORE UPDATE OR DELETE ON apr_quorum_information_rounds
    FOR EACH ROW EXECUTE FUNCTION protect_apr_quorum_information_round();

CREATE FUNCTION protect_apr_quorum_information_command() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Information command evidence is immutable'; END IF;
    IF (NEW.tenant_id, NEW.request_id, NEW.idempotency_key, NEW.operation, NEW.command_sha256, NEW.created_at)
       IS DISTINCT FROM (OLD.tenant_id, OLD.request_id, OLD.idempotency_key, OLD.operation, OLD.command_sha256, OLD.created_at)
       OR OLD.status <> 'UNKNOWN' OR NEW.status <> 'COMPLETED' THEN
        RAISE EXCEPTION 'Invalid information command replay or transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apr_quorum_information_command_frozen BEFORE UPDATE OR DELETE ON apr_quorum_information_commands
    FOR EACH ROW EXECUTE FUNCTION protect_apr_quorum_information_command();
