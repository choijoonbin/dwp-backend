-- Bounded persistence prerequisite for the HRM-013 separation-task waiver
-- handshake. Runtime ingress, native Approval wiring, delivery scheduling,
-- tenant enrolment and credentials remain deliberately absent.

CREATE TABLE apr_hrm013_tenant_grants (
    grant_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    grant_revision BIGINT NOT NULL,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    people_spiffe_id VARCHAR(240) NOT NULL,
    people_signing_key_id VARCHAR(128) NOT NULL,
    approval_spiffe_id VARCHAR(240) NOT NULL,
    approval_signing_key_id VARCHAR(128) NOT NULL,
    request_binding_id VARCHAR(40) NOT NULL,
    request_purpose_code VARCHAR(80) NOT NULL,
    callback_binding_id VARCHAR(40) NOT NULL,
    callback_purpose_code VARCHAR(80) NOT NULL,
    supersedes_grant_id UUID,
    grant_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_apr_hrm013_grant_scope UNIQUE (tenant_id, grant_id),
    CONSTRAINT uk_apr_hrm013_grant_fence UNIQUE (
        tenant_id, grant_id, tenant_grant_revision, authorization_revision),
    CONSTRAINT uk_apr_hrm013_grant_revision UNIQUE (tenant_id, grant_revision),
    CONSTRAINT fk_apr_hrm013_grant_supersedes FOREIGN KEY (
        tenant_id, supersedes_grant_id)
        REFERENCES apr_hrm013_tenant_grants(tenant_id, grant_id),
    CONSTRAINT ck_apr_hrm013_grant_revisions CHECK (
        grant_revision > 0 AND tenant_grant_revision > 0
        AND authorization_revision > 0),
    CONSTRAINT ck_apr_hrm013_grant_decision CHECK (decision IN ('ADMIT', 'REVOKE')),
    CONSTRAINT ck_apr_hrm013_grant_people_identity CHECK (
        people_spiffe_id = 'spiffe://dwp/service/dwp-people-server'
        AND people_signing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$'),
    CONSTRAINT ck_apr_hrm013_grant_approval_identity CHECK (
        approval_spiffe_id = 'spiffe://dwp/service/dwp-approval-server'
        AND approval_signing_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$'),
    CONSTRAINT ck_apr_hrm013_grant_request_contract CHECK (
        request_binding_id = 'SVC-PEP-APR-002'
        AND request_purpose_code = 'HRIS_SEPARATION_TASK_WAIVER_REQUEST'),
    CONSTRAINT ck_apr_hrm013_grant_callback_contract CHECK (
        callback_binding_id = 'SVC-PEP-HRM-010'
        AND callback_purpose_code = 'HRIS_SEPARATION_TASK_WAIVER_DECISION'),
    CONSTRAINT ck_apr_hrm013_grant_chain_shape CHECK (
        (grant_revision = 1 AND supersedes_grant_id IS NULL)
        OR (grant_revision > 1 AND supersedes_grant_id IS NOT NULL)),
    CONSTRAINT ck_apr_hrm013_grant_digest CHECK (
        grant_digest ~ '^[a-f0-9]{64}$')
);

CREATE FUNCTION apr_hrm013_length_prefixed_utf8(p_value TEXT)
RETURNS BYTEA
LANGUAGE SQL
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog
AS $$
    SELECT int4send(octet_length(convert_to(p_value, 'UTF8')))
        || convert_to(p_value, 'UTF8')
$$;

CREATE FUNCTION apr_hrm013_grant_digest(
    p_tenant_id BIGINT,
    p_grant_revision BIGINT,
    p_tenant_grant_revision BIGINT,
    p_authorization_revision BIGINT,
    p_decision TEXT,
    p_people_spiffe_id TEXT,
    p_people_signing_key_id TEXT,
    p_approval_spiffe_id TEXT,
    p_approval_signing_key_id TEXT,
    p_request_binding_id TEXT,
    p_request_purpose_code TEXT,
    p_callback_binding_id TEXT,
    p_callback_purpose_code TEXT,
    p_supersedes_grant_id UUID,
    p_recorded_at TIMESTAMPTZ)
RETURNS TEXT
LANGUAGE SQL
STABLE
PARALLEL SAFE
SET search_path = pg_catalog
AS $$
    SELECT encode(sha256(
          public.apr_hrm013_length_prefixed_utf8(p_tenant_id::text)
        || public.apr_hrm013_length_prefixed_utf8(p_grant_revision::text)
        || public.apr_hrm013_length_prefixed_utf8(p_tenant_grant_revision::text)
        || public.apr_hrm013_length_prefixed_utf8(p_authorization_revision::text)
        || public.apr_hrm013_length_prefixed_utf8(p_decision)
        || public.apr_hrm013_length_prefixed_utf8(p_people_spiffe_id)
        || public.apr_hrm013_length_prefixed_utf8(p_people_signing_key_id)
        || public.apr_hrm013_length_prefixed_utf8(p_approval_spiffe_id)
        || public.apr_hrm013_length_prefixed_utf8(p_approval_signing_key_id)
        || public.apr_hrm013_length_prefixed_utf8(p_request_binding_id)
        || public.apr_hrm013_length_prefixed_utf8(p_request_purpose_code)
        || public.apr_hrm013_length_prefixed_utf8(p_callback_binding_id)
        || public.apr_hrm013_length_prefixed_utf8(p_callback_purpose_code)
        || public.apr_hrm013_length_prefixed_utf8(
            COALESCE(p_supersedes_grant_id::text, ''))
        || public.apr_hrm013_length_prefixed_utf8(to_char(
            p_recorded_at AT TIME ZONE 'UTC',
            'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
    ), 'hex')
$$;

CREATE FUNCTION enforce_apr_hrm013_grant_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    prior_grant_id UUID;
    prior_revision BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'DWP_APPROVAL_HRM013_GRANT_V1:' || NEW.tenant_id::text, 0));

    SELECT grant_id, grant_revision
      INTO prior_grant_id, prior_revision
      FROM public.apr_hrm013_tenant_grants
     WHERE tenant_id = NEW.tenant_id
     ORDER BY grant_revision DESC
     LIMIT 1
     FOR SHARE;

    IF prior_grant_id IS NULL THEN
        IF NEW.grant_revision <> 1 OR NEW.supersedes_grant_id IS NOT NULL THEN
            RAISE EXCEPTION 'The first HRM-013 tenant grant must be revision one'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.grant_revision <> prior_revision + 1
       OR NEW.supersedes_grant_id IS DISTINCT FROM prior_grant_id THEN
        RAISE EXCEPTION 'The HRM-013 tenant grant must name the immediate prior revision'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.grant_digest IS DISTINCT FROM public.apr_hrm013_grant_digest(
            NEW.tenant_id, NEW.grant_revision, NEW.tenant_grant_revision,
            NEW.authorization_revision, NEW.decision, NEW.people_spiffe_id,
            NEW.people_signing_key_id, NEW.approval_spiffe_id,
            NEW.approval_signing_key_id, NEW.request_binding_id,
            NEW.request_purpose_code, NEW.callback_binding_id,
            NEW.callback_purpose_code, NEW.supersedes_grant_id,
            NEW.recorded_at) THEN
        RAISE EXCEPTION 'The HRM-013 tenant grant digest is invalid'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_apr_hrm013_grant_insert
    BEFORE INSERT ON apr_hrm013_tenant_grants
    FOR EACH ROW EXECUTE FUNCTION enforce_apr_hrm013_grant_insert();

CREATE FUNCTION reject_apr_hrm013_grant_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'HRM-013 tenant grant revisions are append-only'
        USING ERRCODE = '23514';
END $$;

CREATE TRIGGER trg_apr_hrm013_grant_immutable
    BEFORE UPDATE OR DELETE ON apr_hrm013_tenant_grants
    FOR EACH ROW EXECUTE FUNCTION reject_apr_hrm013_grant_mutation();

CREATE TABLE apr_hrm013_proof_replays (
    proof_replay_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    binding_id VARCHAR(40) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    token_kind VARCHAR(16) NOT NULL,
    token_digest CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_apr_hrm013_proof_digest UNIQUE (tenant_id, token_digest),
    CONSTRAINT ck_apr_hrm013_proof_kind CHECK (
        token_kind IN ('JTI', 'REQUEST_NONCE')),
    CONSTRAINT ck_apr_hrm013_proof_binding CHECK (
        (binding_id = 'SVC-PEP-APR-002'
            AND caller_workload_id = 'dwp-people-server')
        OR (binding_id = 'SVC-PEP-HRM-010'
            AND caller_workload_id = 'dwp-approval-server')),
    CONSTRAINT ck_apr_hrm013_proof_digest CHECK (
        token_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_apr_hrm013_proof_window CHECK (
        expires_at > reserved_at
        AND expires_at <= reserved_at + INTERVAL '35 seconds')
);

CREATE INDEX idx_apr_hrm013_proof_expiry
    ON apr_hrm013_proof_replays (tenant_id, expires_at, proof_replay_id);

CREATE FUNCTION protect_apr_hrm013_proof_replay()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'HRM-013 proof replay rows cannot be updated'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.expires_at + INTERVAL '5 seconds' > clock_timestamp() THEN
        RAISE EXCEPTION 'A retained HRM-013 proof replay row cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END $$;

CREATE TRIGGER trg_apr_hrm013_proof_replay_protected
    BEFORE UPDATE OR DELETE ON apr_hrm013_proof_replays
    FOR EACH ROW EXECUTE FUNCTION protect_apr_hrm013_proof_replay();

CREATE TABLE apr_hrm013_waiver_request_bindings (
    waiver_binding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    grant_id UUID NOT NULL,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    approval_request_id UUID NOT NULL,
    case_public_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    task_type VARCHAR(80) NOT NULL,
    case_maker_public_id UUID NOT NULL,
    requested_by_actor_public_id UUID NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    requested_task_owner_revision BIGINT NOT NULL,
    requested_task_version BIGINT NOT NULL,
    workflow_version_id UUID NOT NULL,
    form_version_id UUID NOT NULL,
    frozen_policy_revision BIGINT NOT NULL,
    required_step_up_audience VARCHAR(120) NOT NULL,
    required_step_up_purpose VARCHAR(80) NOT NULL,
    original_correlation_id UUID NOT NULL,
    approval_request_payload_sha256 CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    exact_request_body BYTEA NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    exact_response_body BYTEA NOT NULL,
    response_body_sha256 CHAR(64) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_apr_hrm013_waiver_request UNIQUE (
        tenant_id, approval_request_id),
    CONSTRAINT uk_apr_hrm013_waiver_request_grant UNIQUE (
        tenant_id, approval_request_id, grant_id,
        tenant_grant_revision, authorization_revision),
    CONSTRAINT uk_apr_hrm013_waiver_idempotency UNIQUE (
        tenant_id, idempotency_key),
    CONSTRAINT fk_apr_hrm013_waiver_grant FOREIGN KEY (
        tenant_id, grant_id, tenant_grant_revision, authorization_revision)
        REFERENCES apr_hrm013_tenant_grants(
            tenant_id, grant_id, tenant_grant_revision, authorization_revision),
    CONSTRAINT fk_apr_hrm013_waiver_request FOREIGN KEY (
        tenant_id, approval_request_id)
        REFERENCES apr_requests(tenant_id, request_id),
    CONSTRAINT fk_apr_hrm013_waiver_workflow FOREIGN KEY (
        tenant_id, workflow_version_id)
        REFERENCES apr_workflow_versions(tenant_id, workflow_version_id),
    CONSTRAINT fk_apr_hrm013_waiver_form FOREIGN KEY (
        tenant_id, form_version_id)
        REFERENCES apr_form_versions(tenant_id, form_version_id),
    CONSTRAINT ck_apr_hrm013_waiver_revisions CHECK (
        tenant_grant_revision > 0 AND authorization_revision > 0
        AND requested_task_owner_revision > 0
        AND requested_task_version >= 0 AND frozen_policy_revision > 0),
    CONSTRAINT ck_apr_hrm013_waiver_codes CHECK (
        task_type ~ '^[A-Z][A-Z0-9._-]{0,79}$'
        AND reason_code ~ '^[A-Z][A-Z0-9._-]{0,79}$'),
    CONSTRAINT ck_apr_hrm013_waiver_step_up_contract CHECK (
        required_step_up_audience = 'dwp-people-server'
        AND required_step_up_purpose = 'HRIS_SEPARATION_TASK_WAIVER_DECISION'),
    CONSTRAINT ck_apr_hrm013_waiver_idempotency CHECK (
        idempotency_key = approval_request_id::text),
    CONSTRAINT ck_apr_hrm013_waiver_digests CHECK (
        approval_request_payload_sha256 ~ '^[a-f0-9]{64}$'
        AND request_body_sha256 ~ '^[a-f0-9]{64}$'
        AND command_digest ~ '^[a-f0-9]{64}$'
        AND response_body_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_apr_hrm013_waiver_request_body CHECK (
        octet_length(exact_request_body) BETWEEN 1 AND 32768
        AND encode(sha256(exact_request_body), 'hex') = request_body_sha256),
    CONSTRAINT ck_apr_hrm013_waiver_response_body CHECK (
        octet_length(exact_response_body) BETWEEN 1 AND 16384
        AND encode(sha256(exact_response_body), 'hex') = response_body_sha256)
);

CREATE INDEX idx_apr_hrm013_waiver_target
    ON apr_hrm013_waiver_request_bindings (
        tenant_id, case_public_id, task_public_id, created_at, approval_request_id);

CREATE FUNCTION reject_apr_hrm013_waiver_binding_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'HRM-013 waiver request bindings are immutable'
        USING ERRCODE = '23514';
END $$;

CREATE TRIGGER trg_apr_hrm013_waiver_binding_immutable
    BEFORE UPDATE OR DELETE ON apr_hrm013_waiver_request_bindings
    FOR EACH ROW EXECUTE FUNCTION reject_apr_hrm013_waiver_binding_mutation();

CREATE TABLE apr_hrm013_waiver_decision_outbox (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    grant_id UUID NOT NULL,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    approval_request_id UUID NOT NULL,
    case_public_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    callback_event_id UUID NOT NULL,
    decision VARCHAR(16) NOT NULL,
    decision_revision BIGINT NOT NULL,
    owner_revision BIGINT NOT NULL,
    decision_actor_public_id UUID NOT NULL,
    case_maker_public_id UUID NOT NULL,
    requested_by_actor_public_id UUID NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    step_up_evidence_id UUID,
    step_up_evidence_audience VARCHAR(120),
    step_up_evidence_purpose VARCHAR(120),
    step_up_evidence_nonce_sha256 CHAR(64),
    step_up_evidence_sha256 CHAR(64),
    step_up_verified_at TIMESTAMPTZ,
    step_up_consumed_at TIMESTAMPTZ,
    decision_occurred_at TIMESTAMPTZ NOT NULL,
    callback_path VARCHAR(320) NOT NULL,
    original_correlation_id UUID NOT NULL,
    approval_request_payload_sha256 CHAR(64) NOT NULL,
    callback_payload_sha256 CHAR(64) NOT NULL,
    exact_callback_body BYTEA NOT NULL,
    callback_body_sha256 CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    delivery_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ,
    exact_receipt_body BYTEA,
    receipt_body_sha256 CHAR(64),
    last_failure_code VARCHAR(80),
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_apr_hrm013_decision_request UNIQUE (
        tenant_id, approval_request_id),
    CONSTRAINT uk_apr_hrm013_decision_event UNIQUE (
        tenant_id, callback_event_id),
    CONSTRAINT fk_apr_hrm013_decision_grant FOREIGN KEY (
        tenant_id, grant_id, tenant_grant_revision, authorization_revision)
        REFERENCES apr_hrm013_tenant_grants(
            tenant_id, grant_id, tenant_grant_revision, authorization_revision),
    CONSTRAINT fk_apr_hrm013_decision_binding FOREIGN KEY (
        tenant_id, approval_request_id, grant_id,
        tenant_grant_revision, authorization_revision)
        REFERENCES apr_hrm013_waiver_request_bindings(
            tenant_id, approval_request_id, grant_id,
            tenant_grant_revision, authorization_revision),
    CONSTRAINT ck_apr_hrm013_decision_value CHECK (
        decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_apr_hrm013_decision_revision CHECK (
        decision_revision = 1 AND owner_revision = 1),
    CONSTRAINT ck_apr_hrm013_decision_grant_revisions CHECK (
        tenant_grant_revision > 0 AND authorization_revision > 0),
    CONSTRAINT ck_apr_hrm013_decision_maker_checker CHECK (
        decision_actor_public_id <> case_maker_public_id
        AND decision_actor_public_id <> requested_by_actor_public_id),
    CONSTRAINT ck_apr_hrm013_decision_reason CHECK (
        reason_code ~ '^[A-Z][A-Z0-9._-]{0,79}$'),
    CONSTRAINT ck_apr_hrm013_decision_path CHECK (
        callback_path = '/internal/hris-people/v1/separation-cases/'
            || case_public_id::text || '/tasks/' || task_public_id::text
            || '/waiver-decisions'),
    CONSTRAINT ck_apr_hrm013_decision_step_up CHECK (
        (decision = 'APPROVED'
            AND step_up_evidence_id IS NOT NULL
            AND step_up_evidence_audience IS NOT NULL
            AND step_up_evidence_audience = 'dwp-people-server'
            AND step_up_evidence_purpose IS NOT NULL
            AND step_up_evidence_purpose = 'HRIS_SEPARATION_TASK_WAIVER_DECISION'
            AND step_up_evidence_nonce_sha256 IS NOT NULL
            AND step_up_evidence_nonce_sha256 ~ '^[a-f0-9]{64}$'
            AND step_up_evidence_sha256 IS NOT NULL
            AND step_up_evidence_sha256 ~ '^[a-f0-9]{64}$'
            AND step_up_verified_at IS NOT NULL
            AND step_up_consumed_at IS NOT NULL
            AND step_up_verified_at <= step_up_consumed_at
            AND step_up_consumed_at <= decision_occurred_at
            AND decision_occurred_at - step_up_verified_at <= INTERVAL '600 seconds')
        OR (decision = 'REJECTED'
            AND step_up_evidence_id IS NULL
            AND step_up_evidence_audience IS NULL
            AND step_up_evidence_purpose IS NULL
            AND step_up_evidence_nonce_sha256 IS NULL
            AND step_up_evidence_sha256 IS NULL
            AND step_up_verified_at IS NULL
            AND step_up_consumed_at IS NULL)),
    CONSTRAINT ck_apr_hrm013_decision_delivery CHECK (
        delivery_state IN (
            'PENDING', 'CLAIMED', 'RETRY', 'DELIVERED', 'DEAD', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_apr_hrm013_decision_attempt CHECK (
        attempt_count BETWEEN 0 AND 10),
    CONSTRAINT ck_apr_hrm013_decision_lease CHECK (
        (delivery_state = 'CLAIMED' AND lease_token IS NOT NULL
            AND lease_owner IS NOT NULL AND lease_owner <> ''
            AND lease_until IS NOT NULL)
        OR (delivery_state <> 'CLAIMED' AND lease_token IS NULL
            AND lease_owner IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_apr_hrm013_decision_digests CHECK (
        approval_request_payload_sha256 ~ '^[a-f0-9]{64}$'
        AND callback_payload_sha256 ~ '^[a-f0-9]{64}$'
        AND callback_body_sha256 ~ '^[a-f0-9]{64}$'
        AND command_digest ~ '^[a-f0-9]{64}$'
        AND approval_request_payload_sha256 <> callback_payload_sha256
        AND approval_request_payload_sha256 <> callback_body_sha256
        AND approval_request_payload_sha256 <> command_digest
        AND callback_payload_sha256 <> callback_body_sha256
        AND callback_payload_sha256 <> command_digest
        AND callback_body_sha256 <> command_digest
        AND (receipt_body_sha256 IS NULL
            OR receipt_body_sha256 ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_apr_hrm013_decision_callback_body CHECK (
        octet_length(exact_callback_body) BETWEEN 1 AND 32768
        AND encode(sha256(exact_callback_body), 'hex') = callback_body_sha256),
    CONSTRAINT ck_apr_hrm013_decision_receipt CHECK (
        (delivery_state = 'DELIVERED'
            AND exact_receipt_body IS NOT NULL
            AND receipt_body_sha256 IS NOT NULL
            AND octet_length(exact_receipt_body) BETWEEN 1 AND 16384
            AND encode(sha256(exact_receipt_body), 'hex') = receipt_body_sha256
            AND delivered_at IS NOT NULL)
        OR (delivery_state <> 'DELIVERED'
            AND exact_receipt_body IS NULL
            AND receipt_body_sha256 IS NULL
            AND delivered_at IS NULL))
);

CREATE UNIQUE INDEX uk_apr_hrm013_decision_step_up_evidence
    ON apr_hrm013_waiver_decision_outbox (tenant_id, step_up_evidence_id)
    WHERE decision = 'APPROVED';

CREATE UNIQUE INDEX uk_apr_hrm013_decision_step_up_nonce
    ON apr_hrm013_waiver_decision_outbox (
        tenant_id, step_up_evidence_nonce_sha256)
    WHERE decision = 'APPROVED';

CREATE INDEX idx_apr_hrm013_decision_claim
    ON apr_hrm013_waiver_decision_outbox (
        tenant_id, available_at, lease_until, created_at, outbox_id)
    WHERE delivery_state IN ('PENDING', 'RETRY', 'CLAIMED');

CREATE FUNCTION protect_apr_hrm013_decision_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM-013 waiver decision rows cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.outbox_id IS DISTINCT FROM NEW.outbox_id
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.grant_id IS DISTINCT FROM NEW.grant_id
       OR OLD.tenant_grant_revision IS DISTINCT FROM NEW.tenant_grant_revision
       OR OLD.authorization_revision IS DISTINCT FROM NEW.authorization_revision
       OR OLD.approval_request_id IS DISTINCT FROM NEW.approval_request_id
       OR OLD.case_public_id IS DISTINCT FROM NEW.case_public_id
       OR OLD.task_public_id IS DISTINCT FROM NEW.task_public_id
       OR OLD.callback_event_id IS DISTINCT FROM NEW.callback_event_id
       OR OLD.decision IS DISTINCT FROM NEW.decision
       OR OLD.decision_revision IS DISTINCT FROM NEW.decision_revision
       OR OLD.owner_revision IS DISTINCT FROM NEW.owner_revision
       OR OLD.decision_actor_public_id IS DISTINCT FROM NEW.decision_actor_public_id
       OR OLD.case_maker_public_id IS DISTINCT FROM NEW.case_maker_public_id
       OR OLD.requested_by_actor_public_id IS DISTINCT FROM NEW.requested_by_actor_public_id
       OR OLD.reason_code IS DISTINCT FROM NEW.reason_code
       OR OLD.step_up_evidence_id IS DISTINCT FROM NEW.step_up_evidence_id
       OR OLD.step_up_evidence_audience IS DISTINCT FROM NEW.step_up_evidence_audience
       OR OLD.step_up_evidence_purpose IS DISTINCT FROM NEW.step_up_evidence_purpose
       OR OLD.step_up_evidence_nonce_sha256 IS DISTINCT FROM NEW.step_up_evidence_nonce_sha256
       OR OLD.step_up_evidence_sha256 IS DISTINCT FROM NEW.step_up_evidence_sha256
       OR OLD.step_up_verified_at IS DISTINCT FROM NEW.step_up_verified_at
       OR OLD.step_up_consumed_at IS DISTINCT FROM NEW.step_up_consumed_at
       OR OLD.decision_occurred_at IS DISTINCT FROM NEW.decision_occurred_at
       OR OLD.callback_path IS DISTINCT FROM NEW.callback_path
       OR OLD.original_correlation_id IS DISTINCT FROM NEW.original_correlation_id
       OR OLD.approval_request_payload_sha256 IS DISTINCT FROM NEW.approval_request_payload_sha256
       OR OLD.callback_payload_sha256 IS DISTINCT FROM NEW.callback_payload_sha256
       OR OLD.exact_callback_body IS DISTINCT FROM NEW.exact_callback_body
       OR OLD.callback_body_sha256 IS DISTINCT FROM NEW.callback_body_sha256
       OR OLD.command_digest IS DISTINCT FROM NEW.command_digest
       OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'HRM-013 waiver decision source and callback bytes are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.delivery_state IN ('DELIVERED', 'DEAD', 'RESULT_UNKNOWN') THEN
        RAISE EXCEPTION 'A terminal HRM-013 delivery cannot be changed'
            USING ERRCODE = '23514';
    ELSIF OLD.delivery_state IN ('PENDING', 'RETRY')
       AND NEW.delivery_state = 'CLAIMED' THEN
        IF NEW.attempt_count <> OLD.attempt_count + 1
           OR NEW.attempt_count > 10
           OR NEW.lease_until <= clock_timestamp()
           OR NEW.last_failure_code IS NOT NULL
           OR NEW.available_at IS DISTINCT FROM OLD.available_at THEN
            RAISE EXCEPTION 'The HRM-013 delivery claim transition is invalid'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.delivery_state = 'CLAIMED'
       AND NEW.delivery_state = 'CLAIMED' THEN
        IF OLD.lease_until > clock_timestamp()
           OR NEW.attempt_count <> OLD.attempt_count + 1
           OR NEW.attempt_count > 10
           OR NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token
           OR NEW.lease_until <= clock_timestamp()
           OR NEW.last_failure_code IS NOT NULL
           OR NEW.available_at IS DISTINCT FROM OLD.available_at THEN
            RAISE EXCEPTION 'The HRM-013 expired lease replacement is invalid'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.delivery_state = 'CLAIMED'
       AND NEW.delivery_state = 'RETRY' THEN
        IF NEW.attempt_count <> OLD.attempt_count
           OR NEW.attempt_count >= 10
           OR NEW.available_at <= clock_timestamp()
           OR NEW.last_failure_code IS NULL OR NEW.last_failure_code = '' THEN
            RAISE EXCEPTION 'The HRM-013 retry transition is invalid'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.delivery_state = 'CLAIMED'
       AND NEW.delivery_state = 'DELIVERED' THEN
        IF NEW.attempt_count <> OLD.attempt_count
           OR NEW.available_at IS DISTINCT FROM OLD.available_at
           OR NEW.last_failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'The HRM-013 delivery completion is invalid'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.delivery_state = 'CLAIMED'
       AND NEW.delivery_state IN ('DEAD', 'RESULT_UNKNOWN') THEN
        IF NEW.attempt_count <> OLD.attempt_count
           OR NEW.available_at IS DISTINCT FROM OLD.available_at
           OR NEW.last_failure_code IS NULL OR NEW.last_failure_code = '' THEN
            RAISE EXCEPTION 'The HRM-013 terminal failure transition is invalid'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'The HRM-013 delivery state transition is invalid'
            USING ERRCODE = '23514';
    END IF;

    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE TRIGGER trg_apr_hrm013_decision_outbox_protected
    BEFORE UPDATE OR DELETE ON apr_hrm013_waiver_decision_outbox
    FOR EACH ROW EXECUTE FUNCTION protect_apr_hrm013_decision_outbox();

ALTER TABLE apr_hrm013_tenant_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_hrm013_tenant_grants FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_hrm013_proof_replays ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_hrm013_proof_replays FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_hrm013_waiver_request_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_hrm013_waiver_request_bindings FORCE ROW LEVEL SECURITY;
ALTER TABLE apr_hrm013_waiver_decision_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_hrm013_waiver_decision_outbox FORCE ROW LEVEL SECURITY;

CREATE POLICY apr_hrm013_grant_tenant_scope ON apr_hrm013_tenant_grants
    USING (tenant_id = NULLIF(
        current_setting('dwp.approval.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.approval.hrm013.tenant', true), '')::BIGINT);

CREATE POLICY apr_hrm013_proof_tenant_scope ON apr_hrm013_proof_replays
    USING (tenant_id = NULLIF(
        current_setting('dwp.approval.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.approval.hrm013.tenant', true), '')::BIGINT);

CREATE POLICY apr_hrm013_waiver_tenant_scope ON apr_hrm013_waiver_request_bindings
    USING (tenant_id = NULLIF(
        current_setting('dwp.approval.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.approval.hrm013.tenant', true), '')::BIGINT);

CREATE POLICY apr_hrm013_decision_tenant_scope ON apr_hrm013_waiver_decision_outbox
    USING (tenant_id = NULLIF(
        current_setting('dwp.approval.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.approval.hrm013.tenant', true), '')::BIGINT);

COMMENT ON TABLE apr_hrm013_tenant_grants IS
    'Append-only current grant revisions dedicated to APR-002 and HRM-010.';
COMMENT ON TABLE apr_hrm013_proof_replays IS
    'Digest-only two-token replay namespace dedicated to the HRM-013 handshake.';
COMMENT ON TABLE apr_hrm013_waiver_request_bindings IS
    'Immutable APR-002 request, native Approval pins and exact receipt bytes.';
COMMENT ON TABLE apr_hrm013_waiver_decision_outbox IS
    'Sole immutable HRM-010 terminal decision sink with bounded CAS delivery state.';
