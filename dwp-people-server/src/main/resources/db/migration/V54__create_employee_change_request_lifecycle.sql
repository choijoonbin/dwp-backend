-- Control-owned durability boundary for the HRM employee-change lifecycle,
-- People -> Approval atomic create-and-submit, and Approval -> People callback.
-- This introduces a new exact family at V54. There is no legacy employee-change
-- row shape to backfill; immutable V53 assignment-proposal evidence is left intact.

CREATE TABLE ppl_employee_change_requests (
    employee_change_request_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    requester_person_id BIGINT NOT NULL,
    target_person_id BIGINT NOT NULL,
    target_aggregate_type VARCHAR(50) NOT NULL,
    target_aggregate_public_id UUID NOT NULL,
    change_type VARCHAR(80) NOT NULL,
    effective_date DATE NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    expected_target_version BIGINT NOT NULL,
    patch_schema_key VARCHAR(120) NOT NULL,
    patch_schema_version INTEGER NOT NULL,
    encrypted_patch BYTEA NOT NULL,
    patch_hash CHAR(64) NOT NULL,
    key_reference VARCHAR(255) NOT NULL,
    field_groups JSONB NOT NULL,
    approval_case_public_id UUID,
    approval_workflow_key VARCHAR(128),
    approval_workflow_version BIGINT,
    approval_subject_revision BIGINT,
    approval_policy_revision BIGINT,
    approval_decision_event_id UUID,
    population_scope_digest CHAR(64) NOT NULL,
    field_policy_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    originating_context_digest CHAR(64) NOT NULL,
    applied_target_version BIGINT,
    supersedes_request_id BIGINT,
    correlation_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_employee_change_requests_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_employee_change_requests_id
        UNIQUE (tenant_id, employee_change_request_id),
    CONSTRAINT uk_ppl_employee_change_requests_tenant_public
        UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_ppl_employee_change_requests_identity
        UNIQUE (tenant_id, employee_change_request_id, public_id),
    CONSTRAINT uk_ppl_employee_change_requests_correlation
        UNIQUE (tenant_id, correlation_id),
    CONSTRAINT fk_ppl_employee_change_requests_requester
        FOREIGN KEY (tenant_id, requester_person_id)
        REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT fk_ppl_employee_change_requests_target
        FOREIGN KEY (tenant_id, target_person_id)
        REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT fk_ppl_employee_change_requests_supersedes
        FOREIGN KEY (tenant_id, supersedes_request_id)
        REFERENCES ppl_employee_change_requests(tenant_id, employee_change_request_id),
    CONSTRAINT ck_ppl_employee_change_requests_state CHECK (
        lifecycle_state IN ('DRAFT','SUBMITTED','IN_REVIEW','APPROVED','REJECTED',
                            'CANCELLED','APPLYING','APPLIED','APPLY_FAILED')),
    CONSTRAINT ck_ppl_employee_change_requests_versions CHECK (
        expected_target_version >= 0 AND patch_schema_version > 0 AND version >= 0
        AND field_policy_revision > 0 AND authorization_revision > 0
        AND (approval_workflow_version IS NULL OR approval_workflow_version > 0)
        AND (approval_subject_revision IS NULL OR approval_subject_revision > 0)
        AND (approval_policy_revision IS NULL OR approval_policy_revision > 0)),
    CONSTRAINT ck_ppl_employee_change_requests_patch CHECK (
        octet_length(encrypted_patch) > 0
        AND patch_hash ~ '^[0-9a-f]{64}$'
        AND jsonb_typeof(field_groups) = 'array'
        AND jsonb_array_length(field_groups) BETWEEN 1 AND 32),
    CONSTRAINT ck_ppl_employee_change_requests_authority CHECK (
        population_scope_digest ~ '^[0-9a-f]{64}$'
        AND originating_context_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_employee_change_requests_approval_shape CHECK (
        (lifecycle_state IN ('DRAFT','CANCELLED')
            AND approval_case_public_id IS NULL
            AND approval_workflow_key IS NULL
            AND approval_workflow_version IS NULL
            AND approval_subject_revision IS NULL
            AND submitted_at IS NULL)
        OR
        (lifecycle_state <> 'DRAFT'
            AND approval_case_public_id IS NOT NULL
            AND approval_workflow_key ~ '^[a-z][a-z0-9._-]{2,127}$'
            AND approval_workflow_version > 0
            AND approval_subject_revision > 0
            AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_ppl_employee_change_requests_decision_shape CHECK (
        (lifecycle_state IN ('APPROVED','REJECTED','APPLYING','APPLIED','APPLY_FAILED')
            AND approval_policy_revision IS NOT NULL
            AND approval_decision_event_id IS NOT NULL
            AND decided_at IS NOT NULL)
        OR lifecycle_state NOT IN ('APPROVED','REJECTED','APPLYING','APPLIED','APPLY_FAILED')),
    CONSTRAINT ck_ppl_employee_change_requests_applied_shape CHECK (
        (lifecycle_state = 'APPLIED'
            AND applied_target_version IS NOT NULL
            AND applied_target_version > 0
            AND applied_at IS NOT NULL)
        OR (lifecycle_state <> 'APPLIED'
            AND applied_target_version IS NULL
            AND applied_at IS NULL))
);

CREATE INDEX idx_ppl_employee_change_requests_work
    ON ppl_employee_change_requests(tenant_id, lifecycle_state, effective_date, created_at)
    WHERE lifecycle_state IN ('SUBMITTED','IN_REVIEW','APPROVED','APPLYING','APPLY_FAILED');
CREATE INDEX idx_ppl_employee_change_requests_target
    ON ppl_employee_change_requests(tenant_id, target_person_id, effective_date DESC, created_at DESC);

CREATE TABLE ppl_employee_change_evidence (
    employee_change_evidence_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    employee_change_request_id BIGINT NOT NULL,
    object_public_id UUID NOT NULL,
    object_version INTEGER NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    classification VARCHAR(30) NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    retention_until DATE,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_employee_change_evidence_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_employee_change_evidence_object UNIQUE
        (tenant_id, employee_change_request_id, object_public_id, object_version),
    CONSTRAINT fk_ppl_employee_change_evidence_request FOREIGN KEY
        (tenant_id, employee_change_request_id)
        REFERENCES ppl_employee_change_requests(tenant_id, employee_change_request_id),
    CONSTRAINT ck_ppl_employee_change_evidence_value CHECK (
        object_version > 0 AND content_sha256 ~ '^[0-9a-f]{64}$'
        AND classification IN ('INTERNAL','CONFIDENTIAL','RESTRICTED','HIGHLY_RESTRICTED'))
);

CREATE TABLE ppl_command_receipts (
    command_receipt_id UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    command_type VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    actor_public_id UUID NOT NULL,
    originating_action VARCHAR(160) NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    field_policy_revision BIGINT NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    authorization_revision BIGINT NOT NULL,
    originating_context_sealed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    originating_context_digest CHAR(64) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_public_id UUID,
    expected_version BIGINT,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    result_code VARCHAR(80),
    result_reference JSONB,
    correlation_id UUID NOT NULL,
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_ppl_command_receipts_tenant_id
        UNIQUE (tenant_id, command_receipt_id),
    CONSTRAINT uk_ppl_command_receipts_key UNIQUE
        (tenant_id, subject_principal_public_id, originating_action, idempotency_key),
    CONSTRAINT uk_ppl_command_receipts_correlation UNIQUE
        (tenant_id, command_type, correlation_id),
    CONSTRAINT ck_ppl_command_receipts_state CHECK (
        lifecycle_state IN ('RECEIVED','VALIDATING','RUNNING','SUCCEEDED','REJECTED',
                            'FAILED_RETRYABLE','DEAD','CANCELLED')),
    CONSTRAINT ck_ppl_command_receipts_values CHECK (
        attempt_count >= 0 AND field_policy_revision > 0 AND authorization_revision > 0
        AND request_hash ~ '^[0-9a-f]{64}$'
        AND population_scope_digest ~ '^[0-9a-f]{64}$'
        AND originating_context_digest ~ '^[0-9a-f]{64}$'
        AND (result_reference IS NULL OR jsonb_typeof(result_reference) = 'object')),
    CONSTRAINT ck_ppl_command_receipts_completed CHECK (
        (lifecycle_state = 'SUCCEEDED' AND completed_at IS NOT NULL AND result_code IS NOT NULL)
        OR lifecycle_state <> 'SUCCEEDED')
);

CREATE INDEX idx_ppl_command_receipts_work
    ON ppl_command_receipts(lifecycle_state, next_attempt_at, lease_until, created_at)
    WHERE lifecycle_state IN ('RECEIVED','FAILED_RETRYABLE','RUNNING');
CREATE INDEX idx_ppl_command_receipts_target
    ON ppl_command_receipts(tenant_id, target_type, target_public_id, created_at DESC);

CREATE TABLE ppl_hris_bridge_proof_replays (
    token_id UUID PRIMARY KEY,
    token_kind VARCHAR(16) NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    binding_id VARCHAR(40) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_ppl_hris_bridge_proof_kind
        CHECK (token_kind IN ('JTI','REQUEST_NONCE')),
    CONSTRAINT ck_ppl_hris_bridge_proof_binding
        CHECK (binding_id = 'SVC-PEP-HRM-001'),
    CONSTRAINT ck_ppl_hris_bridge_proof_caller
        CHECK (caller_workload_id = 'dwp-approval-server'),
    CONSTRAINT ck_ppl_hris_bridge_proof_window CHECK (expires_at > reserved_at)
);

CREATE INDEX idx_ppl_hris_bridge_proof_expiry
    ON ppl_hris_bridge_proof_replays(expires_at, token_id);

CREATE TABLE ppl_employee_change_approval_request_outbox (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    employee_change_request_id BIGINT NOT NULL,
    employee_change_request_public_id UUID NOT NULL,
    approval_request_id UUID NOT NULL,
    subject_revision BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    approval_path VARCHAR(160) NOT NULL,
    original_correlation_id UUID NOT NULL,
    original_payload_digest CHAR(64) NOT NULL,
    exact_request_body BYTEA NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    delivery_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ,
    exact_receipt_body BYTEA,
    receipt_body_sha256 CHAR(64),
    approval_request_revision BIGINT,
    last_failure_code VARCHAR(80),
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_ppl_employee_change_approval_outbox_request
        UNIQUE (tenant_id, employee_change_request_id, subject_revision),
    CONSTRAINT uk_ppl_employee_change_approval_outbox_approval
        UNIQUE (tenant_id, approval_request_id),
    CONSTRAINT fk_ppl_employee_change_approval_outbox_request FOREIGN KEY
        (tenant_id, employee_change_request_id, employee_change_request_public_id)
        REFERENCES ppl_employee_change_requests(
            tenant_id, employee_change_request_id, public_id),
    CONSTRAINT ck_ppl_employee_change_approval_outbox_identity CHECK (
        subject_revision > 0
        AND idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'
        AND approval_path = '/internal/hris-approval/v1/approval-requests'),
    CONSTRAINT ck_ppl_employee_change_approval_outbox_body CHECK (
        octet_length(exact_request_body) > 0
        AND encode(pg_catalog.sha256(exact_request_body), 'hex') = request_body_sha256
        AND original_payload_digest ~ '^[0-9a-f]{64}$'
        AND request_body_sha256 ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_employee_change_approval_outbox_delivery CHECK (
        delivery_state IN ('PENDING','CLAIMED','RETRY','DELIVERED','DEAD','RESULT_UNKNOWN')
        AND attempt_count BETWEEN 0 AND 100),
    CONSTRAINT ck_ppl_employee_change_approval_outbox_lease CHECK (
        (delivery_state = 'CLAIMED' AND lease_token IS NOT NULL
            AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (delivery_state <> 'CLAIMED' AND lease_token IS NULL
            AND lease_owner IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_ppl_employee_change_approval_outbox_receipt CHECK (
        (delivery_state <> 'DELIVERED' AND exact_receipt_body IS NULL
            AND receipt_body_sha256 IS NULL AND approval_request_revision IS NULL
            AND delivered_at IS NULL)
        OR (delivery_state = 'DELIVERED' AND exact_receipt_body IS NOT NULL
            AND encode(pg_catalog.sha256(exact_receipt_body), 'hex') = receipt_body_sha256
            AND receipt_body_sha256 ~ '^[0-9a-f]{64}$'
            AND approval_request_revision > 0 AND delivered_at IS NOT NULL))
);

CREATE INDEX idx_ppl_employee_change_approval_outbox_claim
    ON ppl_employee_change_approval_request_outbox(
        available_at, lease_until, created_at, outbox_id)
    WHERE delivery_state IN ('PENDING','RETRY','CLAIMED');

CREATE TABLE ppl_employee_change_approval_bindings (
    binding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    employee_change_request_id BIGINT NOT NULL,
    employee_change_request_public_id UUID NOT NULL,
    approval_request_id UUID NOT NULL,
    subject_revision BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    workflow_key VARCHAR(128) NOT NULL,
    workflow_version BIGINT NOT NULL,
    original_correlation_id UUID NOT NULL,
    original_payload_digest CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    exact_request_body BYTEA NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    approval_request_revision BIGINT NOT NULL,
    exact_response_body BYTEA NOT NULL,
    response_body_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_ppl_employee_change_approval_binding_request
        UNIQUE (tenant_id, employee_change_request_id, subject_revision),
    CONSTRAINT uk_ppl_employee_change_approval_binding_approval
        UNIQUE (tenant_id, approval_request_id),
    CONSTRAINT uk_ppl_employee_change_approval_binding_idempotency
        UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_ppl_employee_change_approval_binding_request FOREIGN KEY
        (tenant_id, employee_change_request_id, employee_change_request_public_id)
        REFERENCES ppl_employee_change_requests(
            tenant_id, employee_change_request_id, public_id),
    CONSTRAINT ck_ppl_employee_change_approval_binding_values CHECK (
        subject_revision > 0 AND workflow_version > 0 AND approval_request_revision > 0
        AND idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'
        AND workflow_key ~ '^[a-z][a-z0-9._-]{2,127}$'
        AND original_payload_digest ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'
        AND octet_length(exact_request_body) > 0
        AND octet_length(exact_response_body) > 0
        AND encode(pg_catalog.sha256(exact_request_body), 'hex') = request_body_sha256
        AND encode(pg_catalog.sha256(exact_response_body), 'hex') = response_body_sha256
        AND request_body_sha256 ~ '^[0-9a-f]{64}$'
        AND response_body_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE ppl_employee_change_approval_callback_ledger (
    callback_ledger_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    decision_event_id UUID NOT NULL,
    command_digest CHAR(64) NOT NULL,
    exact_request_body BYTEA NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    command_receipt_id UUID NOT NULL,
    exact_response_body BYTEA NOT NULL,
    response_body_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_ppl_employee_change_callback_event
        UNIQUE (tenant_id, decision_event_id),
    CONSTRAINT uk_ppl_employee_change_callback_receipt
        UNIQUE (tenant_id, command_receipt_id),
    CONSTRAINT fk_ppl_employee_change_callback_receipt FOREIGN KEY
        (tenant_id, command_receipt_id)
        REFERENCES ppl_command_receipts(tenant_id, command_receipt_id),
    CONSTRAINT ck_ppl_employee_change_callback_values CHECK (
        command_digest ~ '^[0-9a-f]{64}$'
        AND octet_length(exact_request_body) > 0
        AND octet_length(exact_response_body) > 0
        AND encode(pg_catalog.sha256(exact_request_body), 'hex') = request_body_sha256
        AND encode(pg_catalog.sha256(exact_response_body), 'hex') = response_body_sha256
        AND request_body_sha256 ~ '^[0-9a-f]{64}$'
        AND response_body_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION public.protect_ppl_employee_change_request()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM employee-change history is forward-corrected, not deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('APPLIED','REJECTED','CANCELLED') THEN
        RAISE EXCEPTION 'HRM terminal employee-change request is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.employee_change_request_id, NEW.public_id, NEW.tenant_id,
           NEW.requester_person_id, NEW.target_person_id,
           NEW.target_aggregate_type, NEW.target_aggregate_public_id,
           NEW.supersedes_request_id, NEW.correlation_id,
           NEW.created_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.employee_change_request_id, OLD.public_id, OLD.tenant_id,
           OLD.requester_person_id, OLD.target_person_id,
           OLD.target_aggregate_type, OLD.target_aggregate_public_id,
           OLD.supersedes_request_id, OLD.correlation_id,
           OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'HRM employee-change originating identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state <> 'DRAFT'
       AND ROW(NEW.change_type, NEW.effective_date, NEW.expected_target_version,
               NEW.patch_schema_key, NEW.patch_schema_version,
               NEW.encrypted_patch, NEW.patch_hash, NEW.key_reference,
               NEW.field_groups, NEW.approval_case_public_id,
               NEW.approval_workflow_key, NEW.approval_workflow_version,
               NEW.approval_subject_revision, NEW.population_scope_digest,
               NEW.field_policy_revision, NEW.authorization_revision,
               NEW.originating_context_digest, NEW.submitted_at)
           IS DISTINCT FROM
           ROW(OLD.change_type, OLD.effective_date, OLD.expected_target_version,
               OLD.patch_schema_key, OLD.patch_schema_version,
               OLD.encrypted_patch, OLD.patch_hash, OLD.key_reference,
               OLD.field_groups, OLD.approval_case_public_id,
               OLD.approval_workflow_key, OLD.approval_workflow_version,
               OLD.approval_subject_revision, OLD.population_scope_digest,
               OLD.field_policy_revision, OLD.authorization_revision,
               OLD.originating_context_digest, OLD.submitted_at) THEN
        RAISE EXCEPTION 'HRM submitted employee-change source is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('APPROVED','APPLYING','APPLY_FAILED')
       AND ROW(NEW.approval_policy_revision, NEW.approval_decision_event_id,
               NEW.decided_at)
           IS DISTINCT FROM
           ROW(OLD.approval_policy_revision, OLD.approval_decision_event_id,
               OLD.decided_at) THEN
        RAISE EXCEPTION 'HRM approval decision evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.protect_ppl_employee_change_request() FROM PUBLIC;

CREATE TRIGGER trg_ppl_employee_change_request_guard
    BEFORE UPDATE OR DELETE ON ppl_employee_change_requests
    FOR EACH ROW EXECUTE FUNCTION public.protect_ppl_employee_change_request();

CREATE FUNCTION public.ppl_guard_command_receipt_originating_auth()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM command receipt cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('SUCCEEDED','REJECTED','DEAD','CANCELLED') THEN
        RAISE EXCEPTION 'HRM terminal command receipt is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.command_receipt_id, NEW.tenant_id, NEW.command_type,
           NEW.idempotency_key, NEW.request_hash, NEW.actor_public_id,
           NEW.originating_action, NEW.subject_principal_public_id,
           NEW.population_scope_digest, NEW.field_policy_revision,
           NEW.purpose_code, NEW.authorization_revision,
           NEW.originating_context_sealed_at, NEW.originating_context_digest,
           NEW.target_type, NEW.target_public_id, NEW.expected_version,
           NEW.correlation_id, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.command_receipt_id, OLD.tenant_id, OLD.command_type,
           OLD.idempotency_key, OLD.request_hash, OLD.actor_public_id,
           OLD.originating_action, OLD.subject_principal_public_id,
           OLD.population_scope_digest, OLD.field_policy_revision,
           OLD.purpose_code, OLD.authorization_revision,
           OLD.originating_context_sealed_at, OLD.originating_context_digest,
           OLD.target_type, OLD.target_public_id, OLD.expected_version,
           OLD.correlation_id, OLD.created_at) THEN
        RAISE EXCEPTION 'HRM command receipt originating authorization is immutable'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_command_receipt_originating_auth() FROM PUBLIC;

CREATE TRIGGER trg_ppl_command_receipt_originating_auth
    BEFORE UPDATE OR DELETE ON ppl_command_receipts
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_command_receipt_originating_auth();

CREATE FUNCTION public.reject_ppl_hris_immutable_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'HRIS bridge evidence and exact wire bytes are immutable'
        USING ERRCODE = '23514';
END
$$;

REVOKE ALL ON FUNCTION public.reject_ppl_hris_immutable_mutation() FROM PUBLIC;

CREATE TRIGGER trg_ppl_employee_change_approval_binding_immutable
    BEFORE UPDATE OR DELETE ON ppl_employee_change_approval_bindings
    FOR EACH ROW EXECUTE FUNCTION public.reject_ppl_hris_immutable_mutation();
CREATE TRIGGER trg_ppl_employee_change_callback_immutable
    BEFORE UPDATE OR DELETE ON ppl_employee_change_approval_callback_ledger
    FOR EACH ROW EXECUTE FUNCTION public.reject_ppl_hris_immutable_mutation();

CREATE FUNCTION public.protect_ppl_employee_change_approval_outbox_payload()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRIS approval request outbox evidence cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.delivery_state = 'DELIVERED' THEN
        RAISE EXCEPTION 'HRIS delivered approval request receipt is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.outbox_id IS DISTINCT FROM NEW.outbox_id
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.employee_change_request_id IS DISTINCT FROM NEW.employee_change_request_id
       OR OLD.employee_change_request_public_id IS DISTINCT FROM NEW.employee_change_request_public_id
       OR OLD.approval_request_id IS DISTINCT FROM NEW.approval_request_id
       OR OLD.subject_revision IS DISTINCT FROM NEW.subject_revision
       OR OLD.idempotency_key IS DISTINCT FROM NEW.idempotency_key
       OR OLD.approval_path IS DISTINCT FROM NEW.approval_path
       OR OLD.original_correlation_id IS DISTINCT FROM NEW.original_correlation_id
       OR OLD.original_payload_digest IS DISTINCT FROM NEW.original_payload_digest
       OR OLD.exact_request_body IS DISTINCT FROM NEW.exact_request_body
       OR OLD.request_body_sha256 IS DISTINCT FROM NEW.request_body_sha256
       OR OLD.command_digest IS DISTINCT FROM NEW.command_digest
       OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'HRIS approval request source and exact body are immutable'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.protect_ppl_employee_change_approval_outbox_payload()
    FROM PUBLIC;

CREATE TRIGGER trg_ppl_employee_change_approval_outbox_payload
    BEFORE UPDATE OR DELETE ON ppl_employee_change_approval_request_outbox
    FOR EACH ROW EXECUTE FUNCTION public.protect_ppl_employee_change_approval_outbox_payload();

ALTER TABLE ppl_employee_change_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_employee_change_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_employee_change_requests_tenant_policy
    ON ppl_employee_change_requests
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_employee_change_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_employee_change_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_employee_change_evidence_tenant_policy
    ON ppl_employee_change_evidence
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_command_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_command_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_command_receipts_tenant_policy
    ON ppl_command_receipts
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_hris_bridge_proof_replays ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_hris_bridge_proof_replays FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_hris_bridge_proof_replays_tenant_policy
    ON ppl_hris_bridge_proof_replays
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_employee_change_approval_request_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_employee_change_approval_request_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_employee_change_approval_outbox_tenant_policy
    ON ppl_employee_change_approval_request_outbox
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_employee_change_approval_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_employee_change_approval_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_employee_change_approval_bindings_tenant_policy
    ON ppl_employee_change_approval_bindings
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_employee_change_approval_callback_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_employee_change_approval_callback_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_employee_change_callback_ledger_tenant_policy
    ON ppl_employee_change_approval_callback_ledger
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT);

COMMENT ON TABLE ppl_hris_bridge_proof_replays IS
    'Single durable namespace for Approval callback workload-proof jti and requestNonce.';
COMMENT ON TABLE ppl_employee_change_approval_request_outbox IS
    'Stable PDX-010 request bytes with fresh-proof delivery, bounded lease, and exact receipt.';
COMMENT ON TABLE ppl_employee_change_approval_bindings IS
    'Immutable People-side Approval identity and exact create-and-submit request/response evidence.';
COMMENT ON TABLE ppl_employee_change_approval_callback_ledger IS
    'Atomic callback event idempotency with exact request and byte-identical receipt replay.';
