-- Production durability boundary for HRIS People -> Approval create-and-submit
-- and Approval -> People terminal-decision delivery. People callback receipts
-- remain owned by the HRM migration in dwp-people-server.

CREATE TABLE apr_hris_bridge_proof_replays (
    token_id UUID PRIMARY KEY,
    token_kind VARCHAR(16) NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    binding_id VARCHAR(40) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_apr_hris_bridge_proof_kind CHECK (token_kind IN ('JTI', 'REQUEST_NONCE')),
    CONSTRAINT ck_apr_hris_bridge_proof_binding CHECK (
        binding_id IN ('SVC-PEP-APR-001', 'SVC-PEP-HRM-001')),
    CONSTRAINT ck_apr_hris_bridge_proof_caller CHECK (
        caller_workload_id IN ('dwp-people-server', 'dwp-approval-server')),
    CONSTRAINT ck_apr_hris_bridge_proof_window CHECK (expires_at > reserved_at)
);

CREATE INDEX idx_apr_hris_bridge_proof_expiry
    ON apr_hris_bridge_proof_replays (expires_at, token_id);

CREATE TABLE apr_hris_employee_change_approval_bindings (
    binding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    approval_request_id UUID NOT NULL,
    employee_change_request_public_id UUID NOT NULL,
    subject_ref VARCHAR(512) NOT NULL,
    subject_revision BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    requester_person_public_id UUID NOT NULL,
    requester_user_id BIGINT NOT NULL,
    workflow_key VARCHAR(128) NOT NULL,
    workflow_version BIGINT NOT NULL,
    workflow_version_id UUID NOT NULL,
    form_version_id UUID NOT NULL,
    frozen_policy_version BIGINT NOT NULL,
    original_correlation_id UUID NOT NULL,
    original_payload_digest CHAR(64) NOT NULL,
    exact_request_body BYTEA NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    exact_response_body BYTEA NOT NULL,
    response_body_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_apr_hris_employee_change_semantic
        UNIQUE (tenant_id, subject_ref, subject_revision),
    CONSTRAINT uk_apr_hris_employee_change_request
        UNIQUE (tenant_id, approval_request_id),
    CONSTRAINT uk_apr_hris_employee_change_idempotency
        UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_apr_hris_employee_change_request
        FOREIGN KEY (tenant_id, approval_request_id)
        REFERENCES apr_requests(tenant_id, request_id) ON DELETE CASCADE,
    CONSTRAINT fk_apr_hris_employee_change_workflow_version
        FOREIGN KEY (tenant_id, workflow_version_id)
        REFERENCES apr_workflow_versions(tenant_id, workflow_version_id),
    CONSTRAINT fk_apr_hris_employee_change_form_version
        FOREIGN KEY (tenant_id, form_version_id)
        REFERENCES apr_form_versions(tenant_id, form_version_id),
    CONSTRAINT ck_apr_hris_employee_change_revisions CHECK (
        subject_revision > 0 AND workflow_version > 0
        AND frozen_policy_version > 0 AND requester_user_id > 0),
    CONSTRAINT ck_apr_hris_employee_change_request_body CHECK (
        octet_length(exact_request_body) > 0
        AND encode(sha256(exact_request_body), 'hex') = request_body_sha256),
    CONSTRAINT ck_apr_hris_employee_change_response_body CHECK (
        octet_length(exact_response_body) > 0
        AND encode(sha256(exact_response_body), 'hex') = response_body_sha256),
    CONSTRAINT ck_apr_hris_employee_change_digests CHECK (
        original_payload_digest ~ '^[a-f0-9]{64}$'
        AND request_body_sha256 ~ '^[a-f0-9]{64}$'
        AND command_digest ~ '^[a-f0-9]{64}$'
        AND response_body_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_apr_hris_employee_change_requester_ref CHECK (
        requester_person_public_id::text ~ '^[a-f0-9-]{36}$'),
    CONSTRAINT ck_apr_hris_employee_change_subject_ref CHECK (
        subject_ref = 'employee-change:' || employee_change_request_public_id::text)
);

CREATE TABLE apr_hris_employee_change_decision_outbox (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    approval_request_id UUID NOT NULL,
    employee_change_request_public_id UUID NOT NULL,
    decision_event_id UUID NOT NULL,
    callback_path VARCHAR(240) NOT NULL,
    frozen_policy_version BIGINT NOT NULL,
    terminal_vote_actor_public_id UUID NOT NULL,
    original_correlation_id UUID NOT NULL,
    original_payload_digest CHAR(64) NOT NULL,
    exact_callback_body BYTEA NOT NULL,
    callback_body_sha256 CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    delivery_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ,
    exact_receipt_body BYTEA,
    receipt_body_sha256 CHAR(64),
    last_failure_code VARCHAR(80),
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_apr_hris_decision_approval
        UNIQUE (tenant_id, approval_request_id),
    CONSTRAINT uk_apr_hris_decision_event
        UNIQUE (tenant_id, decision_event_id),
    CONSTRAINT fk_apr_hris_decision_binding
        FOREIGN KEY (tenant_id, approval_request_id)
        REFERENCES apr_hris_employee_change_approval_bindings(
            tenant_id, approval_request_id) ON DELETE CASCADE,
    CONSTRAINT ck_apr_hris_decision_path CHECK (
        callback_path = '/internal/hris-people/v1/employee-change-requests/'
            || employee_change_request_public_id::text || '/approval-decisions'),
    CONSTRAINT ck_apr_hris_decision_frozen_source CHECK (
        frozen_policy_version > 0
        AND original_payload_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_apr_hris_decision_callback_body CHECK (
        octet_length(exact_callback_body) > 0
        AND encode(sha256(exact_callback_body), 'hex') = callback_body_sha256
        AND callback_body_sha256 ~ '^[a-f0-9]{64}$'
        AND command_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_apr_hris_decision_delivery CHECK (
        delivery_state IN ('PENDING', 'CLAIMED', 'RETRY', 'DELIVERED', 'DEAD', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_apr_hris_decision_attempt CHECK (attempt_count BETWEEN 0 AND 100),
    CONSTRAINT ck_apr_hris_decision_lease CHECK (
        (delivery_state = 'CLAIMED' AND lease_token IS NOT NULL
            AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (delivery_state <> 'CLAIMED' AND lease_token IS NULL
            AND lease_owner IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_apr_hris_decision_receipt CHECK (
        (exact_receipt_body IS NULL AND receipt_body_sha256 IS NULL
            AND delivered_at IS NULL AND delivery_state <> 'DELIVERED')
        OR (exact_receipt_body IS NOT NULL
            AND encode(sha256(exact_receipt_body), 'hex') = receipt_body_sha256
            AND receipt_body_sha256 ~ '^[a-f0-9]{64}$'
            AND delivered_at IS NOT NULL AND delivery_state = 'DELIVERED'))
);

CREATE INDEX idx_apr_hris_decision_claim
    ON apr_hris_employee_change_decision_outbox (
        available_at, lease_until, created_at, outbox_id)
    WHERE delivery_state IN ('PENDING', 'RETRY', 'CLAIMED');

CREATE FUNCTION protect_apr_hris_decision_stable_payload()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.approval_request_id IS DISTINCT FROM NEW.approval_request_id
       OR OLD.employee_change_request_public_id IS DISTINCT FROM NEW.employee_change_request_public_id
       OR OLD.decision_event_id IS DISTINCT FROM NEW.decision_event_id
       OR OLD.callback_path IS DISTINCT FROM NEW.callback_path
       OR OLD.frozen_policy_version IS DISTINCT FROM NEW.frozen_policy_version
       OR OLD.terminal_vote_actor_public_id IS DISTINCT FROM NEW.terminal_vote_actor_public_id
       OR OLD.original_correlation_id IS DISTINCT FROM NEW.original_correlation_id
       OR OLD.original_payload_digest IS DISTINCT FROM NEW.original_payload_digest
       OR OLD.exact_callback_body IS DISTINCT FROM NEW.exact_callback_body
       OR OLD.callback_body_sha256 IS DISTINCT FROM NEW.callback_body_sha256
       OR OLD.command_digest IS DISTINCT FROM NEW.command_digest
       OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'HRIS employee-change decision source and callback bytes are immutable'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE TRIGGER trg_apr_hris_decision_stable_payload
    BEFORE UPDATE ON apr_hris_employee_change_decision_outbox
    FOR EACH ROW EXECUTE FUNCTION protect_apr_hris_decision_stable_payload();

CREATE FUNCTION reject_apr_hris_binding_mutation()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'HRIS employee-change approval binding and exact wire bytes are immutable'
        USING ERRCODE = '23514';
END $$;

CREATE TRIGGER trg_apr_hris_binding_immutable
    BEFORE UPDATE ON apr_hris_employee_change_approval_bindings
    FOR EACH ROW EXECUTE FUNCTION reject_apr_hris_binding_mutation();

COMMENT ON TABLE apr_hris_bridge_proof_replays IS
    'Single replay namespace for verified HRIS workload-proof jti and requestNonce tokens.';
COMMENT ON TABLE apr_hris_employee_change_approval_bindings IS
    'Immutable PDX-010 request/response bytes, all idempotency aliases, and frozen requester/workflow authority.';
COMMENT ON TABLE apr_hris_employee_change_decision_outbox IS
    'Durable terminal-decision callback with frozen quorum source, stable exact body, bounded lease/retry, and exact receipt.';
