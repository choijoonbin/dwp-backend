-- HRM-013 Phase 6 / SVC-PEP-TIM-004 and SVC-PEP-HRM-008.
-- Durable admission is isolated from task execution; exact receipt bytes are immutable.

CREATE TABLE public.hris_hrm013_time_proof_replay (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    binding_id VARCHAR(32) NOT NULL,
    caller_workload_id VARCHAR(80) NOT NULL,
    digest_kind VARCHAR(24) NOT NULL,
    digest_sha256 CHAR(64) NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, binding_id, digest_sha256),
    CONSTRAINT ck_hrm013_tim_replay_binding CHECK (
        binding_id = 'SVC-PEP-TIM-004'),
    CONSTRAINT ck_hrm013_tim_replay_caller CHECK (
        caller_workload_id = 'dwp-people-server'),
    CONSTRAINT ck_hrm013_tim_replay_kind CHECK (
        digest_kind IN ('PROOF_ID', 'REQUEST_NONCE')),
    CONSTRAINT ck_hrm013_tim_replay_digest CHECK (
        digest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_hrm013_tim_replay_retention CHECK (retain_until > created_at)
);

CREATE INDEX idx_hrm013_tim_replay_retention
    ON public.hris_hrm013_time_proof_replay (retain_until);

CREATE TABLE public.hris_hrm013_time_task_dispatch_inbox (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    dispatch_event_id UUID NOT NULL,
    case_public_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    task_owner_revision BIGINT NOT NULL CHECK (task_owner_revision = 1),
    task_version BIGINT NOT NULL CHECK (task_version >= 0),
    worker_public_id UUID NOT NULL,
    relationship_public_id UUID NOT NULL,
    separation_type VARCHAR(80) NOT NULL,
    effective_date DATE NOT NULL,
    last_working_date DATE NOT NULL,
    dispatched_at TIMESTAMPTZ NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    proof_sha256 CHAR(64) NOT NULL,
    exact_request_body BYTEA NOT NULL,
    exact_receipt_body BYTEA NOT NULL,
    receipt_proof TEXT NOT NULL,
    tenant_grant_revision BIGINT NOT NULL CHECK (tenant_grant_revision > 0),
    authorization_revision BIGINT NOT NULL CHECK (authorization_revision > 0),
    grant_use_mode VARCHAR(24) NOT NULL,
    correlation_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ADMITTED',
    drain_claims_json JSONB,
    admitted_at TIMESTAMPTZ NOT NULL,
    terminal_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, dispatch_event_id),
    UNIQUE (tenant_id, task_public_id, task_version),
    UNIQUE (tenant_id, request_body_sha256),
    CONSTRAINT ck_hrm013_tim_task_type CHECK (task_type = 'TIME_FINALIZATION'),
    CONSTRAINT ck_hrm013_tim_digest_shapes CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$'
        AND request_body_sha256 ~ '^[0-9a-f]{64}$'
        AND proof_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_hrm013_tim_body_bounds CHECK (
        octet_length(exact_request_body) BETWEEN 1 AND 32768
        AND octet_length(exact_receipt_body) BETWEEN 1 AND 16384
        AND octet_length(receipt_proof) BETWEEN 1 AND 16384),
    CONSTRAINT ck_hrm013_tim_status CHECK (
        status IN ('ADMITTED', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_hrm013_tim_terminal_state CHECK (
        (status = 'ADMITTED' AND terminal_at IS NULL)
        OR (status IN ('COMPLETED', 'FAILED') AND terminal_at IS NOT NULL)),
    CONSTRAINT ck_hrm013_tim_grant_mode CHECK (
        (grant_use_mode = 'NEW_WORK' AND drain_claims_json IS NULL)
        OR (grant_use_mode = 'DRAIN_EXISTING'
            AND jsonb_typeof(drain_claims_json) = 'object'))
);

CREATE INDEX idx_hrm013_tim_inbox_execution
    ON public.hris_hrm013_time_task_dispatch_inbox (status, admitted_at)
    WHERE status = 'ADMITTED';

CREATE TABLE public.hris_hrm013_time_task_dispatch_quarantine (
    quarantine_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    dispatch_event_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    request_body_sha256 CHAR(64) NOT NULL
        CHECK (request_body_sha256 ~ '^[0-9a-f]{64}$'),
    proof_sha256 CHAR(64) NOT NULL CHECK (proof_sha256 ~ '^[0-9a-f]{64}$'),
    reason_code VARCHAR(80) NOT NULL CHECK (
        reason_code IN ('DISPATCH_EVENT_DIGEST_CONFLICT',
                        'DIGEST_RESERVATION_CONFLICT')),
    exact_request_body BYTEA NOT NULL CHECK (
        octet_length(exact_request_body) BETWEEN 1 AND 32768),
    correlation_id UUID NOT NULL,
    quarantined_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, dispatch_event_id, request_body_sha256)
);

CREATE INDEX idx_hrm013_tim_quarantine_review
    ON public.hris_hrm013_time_task_dispatch_quarantine (
        tenant_id, quarantined_at, dispatch_event_id);

CREATE TABLE public.hris_hrm013_time_callback_quarantine (
    quarantine_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    callback_event_id UUID NOT NULL,
    dispatch_event_id UUID NOT NULL,
    stored_callback_payload_sha256 CHAR(64) NOT NULL
        CHECK (stored_callback_payload_sha256 ~ '^[0-9a-f]{64}$'),
    rejected_callback_payload_sha256 CHAR(64) NOT NULL
        CHECK (rejected_callback_payload_sha256 ~ '^[0-9a-f]{64}$'),
    stored_exact_callback_body BYTEA NOT NULL CHECK (
        octet_length(stored_exact_callback_body) BETWEEN 1 AND 32768),
    rejected_exact_callback_body BYTEA NOT NULL CHECK (
        octet_length(rejected_exact_callback_body) BETWEEN 1 AND 32768),
    correlation_id UUID NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (
        reason_code = 'CALLBACK_IDENTITY_CONFLICT'),
    quarantined_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, callback_event_id, rejected_callback_payload_sha256)
);

CREATE TABLE public.hris_hrm013_time_task_receipt_outbox (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    outbox_id UUID NOT NULL,
    dispatch_event_id UUID NOT NULL,
    callback_event_id UUID NOT NULL,
    case_public_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    owner_revision BIGINT NOT NULL CHECK (owner_revision = 2),
    status VARCHAR(16) NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    external_receipt_public_id UUID NOT NULL,
    originating_payload_sha256 CHAR(64) NOT NULL
        CHECK (originating_payload_sha256 ~ '^[0-9a-f]{64}$'),
    terminal_parent_created_at TIMESTAMPTZ NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    exact_callback_body BYTEA NOT NULL,
    callback_payload_sha256 CHAR(64) NOT NULL,
    grant_use_mode VARCHAR(24) NOT NULL,
    drain_claims_json JSONB,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 10),
    available_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(120),
    locked_until TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, outbox_id),
    UNIQUE (tenant_id, callback_event_id),
    UNIQUE (tenant_id, dispatch_event_id),
    CONSTRAINT fk_hrm013_tim_receipt_dispatch FOREIGN KEY (
        tenant_id, dispatch_event_id) REFERENCES
        public.hris_hrm013_time_task_dispatch_inbox (tenant_id, dispatch_event_id),
    CONSTRAINT ck_hrm013_tim_receipt_grant_mode CHECK (
        (grant_use_mode = 'NEW_WORK' AND drain_claims_json IS NULL)
        OR (grant_use_mode = 'DRAIN_EXISTING'
            AND jsonb_typeof(drain_claims_json) = 'object')),
    CONSTRAINT ck_hrm013_tim_receipt_digest_body CHECK (
        callback_payload_sha256 ~ '^[0-9a-f]{64}$'
        AND octet_length(exact_callback_body) BETWEEN 1 AND 32768),
    CONSTRAINT ck_hrm013_tim_delivery_status CHECK (
        delivery_status IN ('PENDING', 'SENDING', 'RETRY', 'DELIVERED', 'DEAD')),
    CONSTRAINT ck_hrm013_tim_delivery_lock CHECK (
        (delivery_status = 'SENDING' AND locked_by IS NOT NULL AND locked_until IS NOT NULL)
        OR (delivery_status <> 'SENDING' AND locked_by IS NULL AND locked_until IS NULL)),
    CONSTRAINT ck_hrm013_tim_delivered_at CHECK (
        (delivery_status = 'DELIVERED') = (delivered_at IS NOT NULL))
);

CREATE INDEX idx_hrm013_tim_receipt_delivery
    ON public.hris_hrm013_time_task_receipt_outbox (
        delivery_status, available_at, created_at)
    WHERE delivery_status IN ('PENDING', 'RETRY', 'SENDING');

ALTER TABLE public.hris_hrm013_time_proof_replay ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hris_hrm013_time_proof_replay FORCE ROW LEVEL SECURITY;
ALTER TABLE public.hris_hrm013_time_task_dispatch_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hris_hrm013_time_task_dispatch_inbox FORCE ROW LEVEL SECURITY;
ALTER TABLE public.hris_hrm013_time_task_dispatch_quarantine ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hris_hrm013_time_task_dispatch_quarantine FORCE ROW LEVEL SECURITY;
ALTER TABLE public.hris_hrm013_time_callback_quarantine ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hris_hrm013_time_callback_quarantine FORCE ROW LEVEL SECURITY;
ALTER TABLE public.hris_hrm013_time_task_receipt_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hris_hrm013_time_task_receipt_outbox FORCE ROW LEVEL SECURITY;

CREATE POLICY hris_hrm013_time_proof_replay_tenant_isolation
    ON public.hris_hrm013_time_proof_replay
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_hrm013_time_dispatch_inbox_tenant_isolation
    ON public.hris_hrm013_time_task_dispatch_inbox
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_hrm013_time_dispatch_quarantine_tenant_isolation
    ON public.hris_hrm013_time_task_dispatch_quarantine
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_hrm013_time_callback_quarantine_tenant_isolation
    ON public.hris_hrm013_time_callback_quarantine
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_hrm013_time_receipt_outbox_tenant_isolation
    ON public.hris_hrm013_time_task_receipt_outbox
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT);

REVOKE ALL ON TABLE public.hris_hrm013_time_proof_replay FROM PUBLIC;
REVOKE ALL ON TABLE public.hris_hrm013_time_task_dispatch_inbox FROM PUBLIC;
REVOKE ALL ON TABLE public.hris_hrm013_time_task_dispatch_quarantine FROM PUBLIC;
REVOKE ALL ON TABLE public.hris_hrm013_time_callback_quarantine FROM PUBLIC;
REVOKE ALL ON TABLE public.hris_hrm013_time_task_receipt_outbox FROM PUBLIC;

CREATE OR REPLACE FUNCTION public.hris_hrm013_time_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $hrm013_time_reject_mutation$
BEGIN
    RAISE EXCEPTION 'HRM-013 immutable evidence cannot be updated or deleted';
END
$hrm013_time_reject_mutation$;

CREATE OR REPLACE FUNCTION public.hris_hrm013_time_guard_inbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $hrm013_time_guard_inbox$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ADMITTED' OR NEW.terminal_at IS NOT NULL
           OR NEW.updated_at IS DISTINCT FROM NEW.created_at THEN
            RAISE EXCEPTION 'HRM-013 inbox must start admitted';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM-013 inbox evidence cannot be deleted';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status', 'terminal_at', 'updated_at'])
            IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status', 'terminal_at', 'updated_at']) THEN
        RAISE EXCEPTION 'HRM-013 inbox snapshot is immutable';
    END IF;
    IF NOT (OLD.status = 'ADMITTED' AND OLD.terminal_at IS NULL
            AND NEW.status IN ('COMPLETED', 'FAILED')
            AND NEW.terminal_at IS NOT NULL
            AND NEW.updated_at >= OLD.updated_at) THEN
        RAISE EXCEPTION 'Illegal HRM-013 inbox transition';
    END IF;
    RETURN NEW;
END
$hrm013_time_guard_inbox$;

CREATE OR REPLACE FUNCTION public.hris_hrm013_time_guard_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $hrm013_time_guard_outbox$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.delivery_status <> 'PENDING' OR NEW.attempt_count <> 0
           OR NEW.locked_by IS NOT NULL OR NEW.locked_until IS NOT NULL
           OR NEW.delivered_at IS NOT NULL OR NEW.last_error_code IS NOT NULL
           OR NEW.available_at < NEW.created_at
           OR NEW.updated_at IS DISTINCT FROM NEW.created_at
           OR NEW.updated_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN
            RAISE EXCEPTION 'HRM-013 outbox must start pending';
        END IF;
        IF NOT EXISTS (
            SELECT 1
              FROM public.hris_hrm013_time_task_dispatch_inbox inbox
             WHERE inbox.tenant_id = NEW.tenant_id
               AND inbox.dispatch_event_id = NEW.dispatch_event_id
               AND inbox.case_public_id = NEW.case_public_id
               AND inbox.task_public_id = NEW.task_public_id
               AND inbox.task_owner_revision + 1 = NEW.owner_revision
               AND inbox.status = NEW.status
               AND inbox.payload_sha256 = NEW.originating_payload_sha256
               AND inbox.dispatched_at = NEW.terminal_parent_created_at
               AND inbox.terminal_at = NEW.occurred_at
               AND inbox.correlation_id = NEW.correlation_id
               AND inbox.grant_use_mode = NEW.grant_use_mode
               AND inbox.drain_claims_json IS NOT DISTINCT FROM NEW.drain_claims_json
               AND inbox.status IN ('COMPLETED', 'FAILED')
        ) THEN
            RAISE EXCEPTION 'HRM-013 callback requires its exact terminal dispatch';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM-013 outbox evidence cannot be deleted';
    END IF;
    IF (to_jsonb(NEW) - ARRAY[
            'delivery_status', 'attempt_count', 'available_at', 'locked_by',
            'locked_until', 'delivered_at', 'last_error_code', 'updated_at'])
            IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY[
            'delivery_status', 'attempt_count', 'available_at', 'locked_by',
            'locked_until', 'delivered_at', 'last_error_code', 'updated_at']) THEN
        RAISE EXCEPTION 'HRM-013 outbox snapshot is immutable';
    END IF;
    IF OLD.delivery_status IN ('PENDING', 'RETRY')
       AND OLD.available_at <= CURRENT_TIMESTAMP
       AND OLD.attempt_count < 10
       AND NEW.delivery_status = 'SENDING'
       AND NEW.attempt_count = OLD.attempt_count + 1
       AND NEW.available_at = OLD.available_at
       AND NEW.locked_by IS NOT NULL
       AND NEW.locked_until > CURRENT_TIMESTAMP
       AND NEW.locked_until <= CURRENT_TIMESTAMP + INTERVAL '300 seconds'
       AND NEW.delivered_at IS NULL
       AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code
       AND NEW.updated_at = CURRENT_TIMESTAMP
       AND NEW.updated_at >= OLD.updated_at THEN
        RETURN NEW;
    END IF;
    IF OLD.delivery_status = 'SENDING'
       AND OLD.locked_until <= CURRENT_TIMESTAMP
       AND OLD.attempt_count < 10
       AND NEW.delivery_status = 'SENDING'
       AND NEW.attempt_count = OLD.attempt_count + 1
       AND NEW.available_at = OLD.available_at
       AND NEW.locked_by IS NOT NULL
       AND NEW.locked_until > CURRENT_TIMESTAMP
       AND NEW.locked_until <= CURRENT_TIMESTAMP + INTERVAL '300 seconds'
       AND NEW.delivered_at IS NULL
       AND NEW.last_error_code IS NOT DISTINCT FROM OLD.last_error_code
       AND NEW.updated_at = CURRENT_TIMESTAMP
       AND NEW.updated_at >= OLD.updated_at THEN
        RETURN NEW;
    END IF;
    IF OLD.delivery_status = 'SENDING'
       AND OLD.locked_until > CURRENT_TIMESTAMP
       AND NEW.delivery_status = 'DELIVERED'
       AND NEW.attempt_count = OLD.attempt_count
       AND NEW.available_at = OLD.available_at
       AND NEW.locked_by IS NULL AND NEW.locked_until IS NULL
       AND NEW.delivered_at = CURRENT_TIMESTAMP
       AND NEW.last_error_code IS NULL
       AND NEW.updated_at = CURRENT_TIMESTAMP
       AND NEW.updated_at >= OLD.updated_at THEN
        RETURN NEW;
    END IF;
    IF OLD.delivery_status = 'SENDING'
       AND OLD.locked_until > CURRENT_TIMESTAMP
       AND OLD.attempt_count < 10
       AND NEW.delivery_status = 'RETRY'
       AND NEW.attempt_count = OLD.attempt_count
       AND NEW.available_at >= CURRENT_TIMESTAMP
       AND NEW.available_at <= CURRENT_TIMESTAMP + INTERVAL '300 seconds'
       AND NEW.locked_by IS NULL AND NEW.locked_until IS NULL
       AND NEW.delivered_at IS NULL
       AND NEW.last_error_code IS NOT NULL
       AND NEW.last_error_code <> 'RESULT_UNKNOWN'
       AND NEW.updated_at = CURRENT_TIMESTAMP
       AND NEW.updated_at >= OLD.updated_at THEN
        RETURN NEW;
    END IF;
    IF OLD.delivery_status = 'SENDING'
       AND OLD.locked_until > CURRENT_TIMESTAMP
       AND OLD.attempt_count = 10
       AND NEW.delivery_status = 'DEAD'
       AND NEW.attempt_count = OLD.attempt_count
       AND NEW.available_at = OLD.available_at
       AND NEW.locked_by IS NULL AND NEW.locked_until IS NULL
       AND NEW.delivered_at IS NULL
       AND NEW.last_error_code = 'RESULT_UNKNOWN'
       AND NEW.updated_at = CURRENT_TIMESTAMP
       AND NEW.updated_at >= OLD.updated_at THEN
        RETURN NEW;
    END IF;
    IF OLD.delivery_status = 'SENDING'
       AND OLD.locked_until <= CURRENT_TIMESTAMP
       AND OLD.attempt_count = 10
       AND NEW.delivery_status = 'DEAD'
       AND NEW.attempt_count = OLD.attempt_count
       AND NEW.available_at = OLD.available_at
       AND NEW.locked_by IS NULL AND NEW.locked_until IS NULL
       AND NEW.delivered_at IS NULL
       AND NEW.last_error_code = 'RESULT_UNKNOWN'
       AND NEW.updated_at = CURRENT_TIMESTAMP
       AND NEW.updated_at >= OLD.updated_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Illegal HRM-013 outbox transition';
END
$hrm013_time_guard_outbox$;

CREATE TRIGGER hris_hrm013_time_inbox_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON public.hris_hrm013_time_task_dispatch_inbox
    FOR EACH ROW EXECUTE FUNCTION public.hris_hrm013_time_guard_inbox();
CREATE TRIGGER hris_hrm013_time_dispatch_quarantine_immutable
    BEFORE UPDATE OR DELETE
    ON public.hris_hrm013_time_task_dispatch_quarantine
    FOR EACH ROW EXECUTE FUNCTION public.hris_hrm013_time_reject_mutation();
CREATE TRIGGER hris_hrm013_time_callback_quarantine_immutable
    BEFORE UPDATE OR DELETE
    ON public.hris_hrm013_time_callback_quarantine
    FOR EACH ROW EXECUTE FUNCTION public.hris_hrm013_time_reject_mutation();
CREATE TRIGGER hris_hrm013_time_outbox_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON public.hris_hrm013_time_task_receipt_outbox
    FOR EACH ROW EXECUTE FUNCTION public.hris_hrm013_time_guard_outbox();

ALTER FUNCTION public.hris_hrm013_time_reject_mutation() SECURITY DEFINER;
ALTER FUNCTION public.hris_hrm013_time_reject_mutation()
    SET search_path TO pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.hris_hrm013_time_reject_mutation() FROM PUBLIC;
ALTER FUNCTION public.hris_hrm013_time_guard_inbox() SECURITY DEFINER;
ALTER FUNCTION public.hris_hrm013_time_guard_inbox()
    SET search_path TO pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.hris_hrm013_time_guard_inbox() FROM PUBLIC;
ALTER FUNCTION public.hris_hrm013_time_guard_outbox() SECURITY DEFINER;
ALTER FUNCTION public.hris_hrm013_time_guard_outbox()
    SET search_path TO pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.hris_hrm013_time_guard_outbox() FROM PUBLIC;

DO $runtime_access$
DECLARE
    runtime_role_name TEXT := '${timeRuntimeRole}';
    runtime_role RECORD;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Valid existing Time runtime role is required';
    END IF;
    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
      INTO runtime_role FROM pg_catalog.pg_roles WHERE rolname = runtime_role_name;
    IF NOT FOUND OR runtime_role.rolsuper OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION 'Time runtime role is missing or unsafe';
    END IF;
    EXECUTE format(
        'GRANT SELECT ON TABLE public.hris_hrm013_time_proof_replay TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT INSERT (tenant_id, binding_id, caller_workload_id, digest_kind, digest_sha256, retain_until) ON TABLE public.hris_hrm013_time_proof_replay TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT ON TABLE public.hris_hrm013_time_task_dispatch_inbox TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT INSERT (tenant_id, dispatch_event_id, case_public_id, task_public_id, task_type, task_owner_revision, task_version, worker_public_id, relationship_public_id, separation_type, effective_date, last_working_date, dispatched_at, payload_sha256, request_body_sha256, proof_sha256, exact_request_body, exact_receipt_body, receipt_proof, tenant_grant_revision, authorization_revision, grant_use_mode, correlation_id, drain_claims_json, admitted_at) ON TABLE public.hris_hrm013_time_task_dispatch_inbox TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT UPDATE (status, terminal_at, updated_at) ON TABLE public.hris_hrm013_time_task_dispatch_inbox TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE public.hris_hrm013_time_task_dispatch_quarantine TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE public.hris_hrm013_time_callback_quarantine TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT ON TABLE public.hris_hrm013_time_task_receipt_outbox TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT INSERT (tenant_id, outbox_id, dispatch_event_id, callback_event_id, case_public_id, task_public_id, owner_revision, status, external_receipt_public_id, originating_payload_sha256, terminal_parent_created_at, occurred_at, correlation_id, exact_callback_body, callback_payload_sha256, grant_use_mode, drain_claims_json, available_at) ON TABLE public.hris_hrm013_time_task_receipt_outbox TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT UPDATE (delivery_status, attempt_count, available_at, locked_by, locked_until, delivered_at, last_error_code, updated_at) ON TABLE public.hris_hrm013_time_task_receipt_outbox TO %I',
        runtime_role_name);
END
$runtime_access$;

COMMENT ON TABLE public.hris_hrm013_time_task_dispatch_inbox IS
    'HRM-013 Time receipt-first immutable task admission inbox; feature default OFF.';
COMMENT ON TABLE public.hris_hrm013_time_task_dispatch_quarantine IS
    'HRM-013 conflicting dispatch evidence; never eligible for execution.';
COMMENT ON TABLE public.hris_hrm013_time_callback_quarantine IS
    'HRM-013 conflicting terminal callback identity evidence; append only.';
COMMENT ON TABLE public.hris_hrm013_time_task_receipt_outbox IS
    'Durable current-authority HRM-008 completion/failure callback outbox.';
