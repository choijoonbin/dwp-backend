-- BASE-TFR-HRM-013 prerequisite persistence.
-- This migration composes the sealed V61 base manifest and its callback-
-- durability successor. Runtime routes, tenant enrollment, credentials and
-- the HRM-013 owner workflow remain deliberately absent.

CREATE TABLE ppl_separation_cases (
    separation_case_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    worker_id BIGINT NOT NULL,
    work_relationship_id BIGINT NOT NULL,
    separation_type VARCHAR(40) NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    effective_date DATE NOT NULL,
    last_working_date DATE NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    input_snapshot_hash CHAR(64) NOT NULL,
    task_template_public_id UUID NOT NULL,
    task_template_revision BIGINT NOT NULL,
    task_template_hash CHAR(64) NOT NULL,
    approval_request_id UUID,
    approval_decision_event_id UUID,
    approval_decision_revision BIGINT,
    approval_payload_hash CHAR(64),
    maker_principal_public_id UUID NOT NULL,
    approval_actor_public_id UUID,
    correlation_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    irreversible_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_separation_cases_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_separation_cases_id
        UNIQUE (tenant_id, separation_case_id),
    CONSTRAINT uk_ppl_separation_cases_correlation
        UNIQUE (tenant_id, correlation_id),
    CONSTRAINT fk_ppl_separation_cases_worker FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_ppl_separation_cases_relationship FOREIGN KEY
        (tenant_id, work_relationship_id)
        REFERENCES ppl_work_relationships(tenant_id, work_relationship_id),
    CONSTRAINT ck_ppl_separation_cases_type CHECK (
        separation_type IN (
            'RESIGNATION','TERMINATION','RETIREMENT','END_OF_CONTRACT')),
    CONSTRAINT ck_ppl_separation_cases_state CHECK (
        lifecycle_state IN (
            'DRAFT','TASKS_ACTIVE','READY_TO_PUBLISH','PUBLISHED',
            'CORRECTION_REQUIRED','CANCELLED')),
    CONSTRAINT ck_ppl_separation_cases_dates CHECK (
        last_working_date <= effective_date),
    CONSTRAINT ck_ppl_separation_cases_versions CHECK (
        version >= 0 AND task_template_revision > 0
        AND (approval_decision_revision IS NULL
             OR approval_decision_revision > 0)),
    CONSTRAINT ck_ppl_separation_cases_hashes CHECK (
        input_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND task_template_hash ~ '^[0-9a-f]{64}$'
        AND (approval_payload_hash IS NULL
             OR approval_payload_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ppl_separation_cases_published CHECK (
        lifecycle_state <> 'PUBLISHED'
        OR (approval_request_id IS NOT NULL
            AND approval_decision_event_id IS NOT NULL
            AND approval_decision_revision IS NOT NULL
            AND approval_decision_revision > 0
            AND approval_payload_hash IS NOT NULL
            AND approval_actor_public_id IS NOT NULL
            AND published_at IS NOT NULL
            AND irreversible_at IS NOT NULL)),
    CONSTRAINT ck_ppl_separation_cases_maker_checker CHECK (
        approval_actor_public_id IS NULL
        OR maker_principal_public_id <> approval_actor_public_id)
);

CREATE UNIQUE INDEX uk_ppl_separation_cases_approval_request
    ON ppl_separation_cases(tenant_id, approval_request_id)
    WHERE approval_request_id IS NOT NULL;

CREATE TABLE ppl_separation_tasks (
    separation_task_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    separation_case_id BIGINT NOT NULL,
    task_type VARCHAR(80) NOT NULL,
    owner_workload_id VARCHAR(120) NOT NULL,
    mandatory BOOLEAN NOT NULL,
    irreversible BOOLEAN NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    owner_revision BIGINT,
    external_receipt_public_id UUID,
    waiver_decision_event_id UUID,
    waiver_actor_public_id UUID,
    waiver_reason_code VARCHAR(80),
    waiver_approval_request_id UUID,
    waiver_decision VARCHAR(16),
    waiver_decision_revision BIGINT,
    waiver_step_up_evidence_id UUID,
    payload_hash CHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_separation_tasks_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_separation_tasks_id
        UNIQUE (tenant_id, separation_task_id),
    CONSTRAINT uk_ppl_separation_tasks_case_type
        UNIQUE (tenant_id, separation_case_id, task_type),
    CONSTRAINT fk_ppl_separation_tasks_case FOREIGN KEY
        (tenant_id, separation_case_id)
        REFERENCES ppl_separation_cases(tenant_id, separation_case_id),
    CONSTRAINT ck_ppl_separation_tasks_state CHECK (
        lifecycle_state IN (
            'PENDING','IN_PROGRESS','COMPLETED','WAIVED','FAILED')),
    CONSTRAINT ck_ppl_separation_tasks_versions CHECK (
        version >= 0
        AND (owner_revision IS NULL OR owner_revision > 0)
        AND (waiver_decision_revision IS NULL
             OR waiver_decision_revision > 0)),
    CONSTRAINT ck_ppl_separation_tasks_hash CHECK (
        payload_hash IS NULL OR payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_separation_tasks_waiver_decision CHECK (
        waiver_decision IS NULL OR waiver_decision IN ('APPROVED','REJECTED')),
    CONSTRAINT ck_ppl_separation_tasks_completed CHECK (
        (lifecycle_state = 'COMPLETED'
         AND owner_revision IS NOT NULL
         AND external_receipt_public_id IS NOT NULL
         AND payload_hash IS NOT NULL
         AND completed_at IS NOT NULL)
        OR (lifecycle_state <> 'COMPLETED'
            AND external_receipt_public_id IS NULL)),
    CONSTRAINT ck_ppl_separation_tasks_waived CHECK (
        (lifecycle_state = 'WAIVED'
         AND waiver_decision_event_id IS NOT NULL
         AND waiver_approval_request_id IS NOT NULL
         AND waiver_decision IS NOT NULL
         AND waiver_decision = 'APPROVED'
         AND waiver_decision_revision IS NOT NULL
         AND waiver_decision_revision > 0
         AND waiver_actor_public_id IS NOT NULL
         AND waiver_reason_code IS NOT NULL
         AND waiver_step_up_evidence_id IS NOT NULL
         AND payload_hash IS NOT NULL
         AND completed_at IS NOT NULL)
        OR (lifecycle_state <> 'WAIVED'
            AND waiver_decision_event_id IS NULL
            AND waiver_approval_request_id IS NULL
            AND waiver_decision IS NULL
            AND waiver_decision_revision IS NULL
            AND waiver_actor_public_id IS NULL
            AND waiver_reason_code IS NULL
            AND waiver_step_up_evidence_id IS NULL)),
    CONSTRAINT ck_ppl_separation_tasks_completion_time CHECK (
        lifecycle_state IN ('COMPLETED','WAIVED') OR completed_at IS NULL)
);

CREATE TABLE ppl_hrm013_callback_proof_replays (
    proof_replay_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    binding_id VARCHAR(40) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    token_kind VARCHAR(16) NOT NULL,
    token_digest CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_ppl_hrm013_callback_proof_digest
        UNIQUE (tenant_id, token_digest),
    CONSTRAINT ck_ppl_hrm013_callback_proof_kind CHECK (
        token_kind IN ('JTI','REQUEST_NONCE')),
    CONSTRAINT ck_ppl_hrm013_callback_proof_digest CHECK (
        token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_hrm013_callback_proof_binding CHECK (
        (binding_id = 'SVC-PEP-HRM-008'
         AND caller_workload_id IN (
             'dwp-time-server','dwp-payroll-server','dwp-auth-server'))
        OR (binding_id = 'SVC-PEP-HRM-009'
            AND caller_workload_id IN (
                'dwp-time-server','dwp-payroll-server'))
        OR (binding_id = 'SVC-PEP-HRM-010'
            AND caller_workload_id = 'dwp-approval-server')),
    CONSTRAINT ck_ppl_hrm013_callback_proof_window CHECK (
        expires_at > reserved_at
        AND expires_at <= reserved_at + INTERVAL '35 seconds')
);

CREATE TABLE ppl_separation_waiver_request_outbox (
    separation_waiver_request_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    separation_case_id BIGINT NOT NULL,
    separation_task_id BIGINT NOT NULL,
    approval_request_id UUID NOT NULL,
    case_public_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    request_revision BIGINT NOT NULL,
    case_maker_public_id UUID NOT NULL,
    requested_by_actor_public_id UUID NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    task_owner_revision BIGINT NOT NULL,
    task_version BIGINT NOT NULL,
    exact_request_body BYTEA NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    approval_request_payload_sha256 CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    dispatch_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ,
    exact_response_body BYTEA,
    response_body_sha256 CHAR(64),
    accepted_at TIMESTAMPTZ,
    last_failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_ppl_separation_waiver_outbox_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_separation_waiver_outbox_approval
        UNIQUE (tenant_id, approval_request_id),
    CONSTRAINT uk_ppl_separation_waiver_outbox_revision
        UNIQUE (tenant_id, case_public_id, task_public_id, request_revision),
    CONSTRAINT fk_ppl_separation_waiver_outbox_case FOREIGN KEY
        (tenant_id, separation_case_id)
        REFERENCES ppl_separation_cases(tenant_id, separation_case_id),
    CONSTRAINT fk_ppl_separation_waiver_outbox_task FOREIGN KEY
        (tenant_id, separation_task_id)
        REFERENCES ppl_separation_tasks(tenant_id, separation_task_id),
    CONSTRAINT ck_ppl_separation_waiver_outbox_versions CHECK (
        request_revision > 0 AND task_owner_revision > 0 AND task_version >= 0),
    CONSTRAINT ck_ppl_separation_waiver_outbox_request CHECK (
        octet_length(exact_request_body) BETWEEN 1 AND 32768
        AND request_body_sha256 ~ '^[0-9a-f]{64}$'
        AND approval_request_payload_sha256 ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'
        AND encode(pg_catalog.sha256(exact_request_body), 'hex')
            = request_body_sha256),
    CONSTRAINT ck_ppl_separation_waiver_outbox_state CHECK (
        dispatch_state IN (
            'PENDING','CLAIMED','RETRY','ACCEPTED','DEAD','RESULT_UNKNOWN')
        AND attempt_count BETWEEN 0 AND 10),
    CONSTRAINT ck_ppl_separation_waiver_outbox_lease CHECK (
        (dispatch_state = 'CLAIMED'
         AND lease_token IS NOT NULL
         AND lease_owner IS NOT NULL
         AND lease_until IS NOT NULL)
        OR (dispatch_state <> 'CLAIMED'
            AND lease_token IS NULL
            AND lease_owner IS NULL
            AND lease_until IS NULL)),
    CONSTRAINT ck_ppl_separation_waiver_outbox_response CHECK (
        (dispatch_state = 'ACCEPTED'
         AND exact_response_body IS NOT NULL
         AND octet_length(exact_response_body) BETWEEN 1 AND 16384
         AND response_body_sha256 ~ '^[0-9a-f]{64}$'
         AND encode(pg_catalog.sha256(exact_response_body), 'hex')
             = response_body_sha256
         AND accepted_at IS NOT NULL)
        OR (dispatch_state <> 'ACCEPTED'
            AND exact_response_body IS NULL
            AND response_body_sha256 IS NULL
            AND accepted_at IS NULL))
);

CREATE TABLE ppl_separation_callback_authorities (
    separation_callback_authority_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    case_public_id UUID NOT NULL,
    task_public_id UUID,
    separation_case_id BIGINT NOT NULL,
    separation_task_id BIGINT,
    callback_type VARCHAR(32) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    binding_id VARCHAR(40) NOT NULL,
    audience VARCHAR(120) NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    task_type VARCHAR(80),
    approval_request_id UUID,
    consumer_event_id UUID,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    expected_next_owner_revision BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    originating_payload_sha256 CHAR(64) NOT NULL,
    authority_revision BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    supersedes_authority_public_id UUID,
    authority_digest CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    CONSTRAINT uk_ppl_separation_callback_authorities_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_separation_callback_authorities_tenant_public
        UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_ppl_separation_callback_authorities_id
        UNIQUE (tenant_id, separation_callback_authority_id),
    CONSTRAINT uk_ppl_separation_callback_authorities_stream_revision
        UNIQUE NULLS NOT DISTINCT (
            tenant_id,binding_id,caller_workload_id,case_public_id,
            task_public_id,purpose_code,approval_request_id,authority_revision),
    CONSTRAINT fk_ppl_separation_callback_authorities_case FOREIGN KEY
        (tenant_id, separation_case_id)
        REFERENCES ppl_separation_cases(tenant_id, separation_case_id),
    CONSTRAINT fk_ppl_separation_callback_authorities_task FOREIGN KEY
        (tenant_id, separation_task_id)
        REFERENCES ppl_separation_tasks(tenant_id, separation_task_id),
    CONSTRAINT fk_ppl_separation_callback_authorities_approval FOREIGN KEY
        (tenant_id, approval_request_id)
        REFERENCES ppl_separation_waiver_request_outbox(
            tenant_id, approval_request_id),
    CONSTRAINT fk_ppl_separation_callback_authorities_supersedes FOREIGN KEY
        (tenant_id, supersedes_authority_public_id)
        REFERENCES ppl_separation_callback_authorities(tenant_id, public_id),
    CONSTRAINT ck_ppl_separation_callback_authorities_type CHECK (
        callback_type IN (
            'APPROVAL_DECISION','TASK_RECEIPT','RECONCILIATION_RESULT')),
    CONSTRAINT ck_ppl_separation_callback_authorities_decision CHECK (
        decision IN ('ADMIT','REVOKE')),
    CONSTRAINT ck_ppl_separation_callback_authorities_versions CHECK (
        authority_revision > 0
        AND expected_next_owner_revision > 0
        AND tenant_grant_revision > 0
        AND authorization_revision > 0),
    CONSTRAINT ck_ppl_separation_callback_authorities_hashes CHECK (
        originating_payload_sha256 ~ '^[0-9a-f]{64}$'
        AND authority_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_separation_callback_authorities_shape CHECK (
        (callback_type = 'TASK_RECEIPT'
         AND task_public_id IS NOT NULL
         AND separation_task_id IS NOT NULL
         AND task_type IS NOT NULL
         AND approval_request_id IS NULL
         AND consumer_event_id IS NULL)
        OR (callback_type = 'APPROVAL_DECISION'
            AND task_public_id IS NOT NULL
            AND separation_task_id IS NOT NULL
            AND task_type IS NOT NULL
            AND approval_request_id IS NOT NULL
            AND consumer_event_id IS NULL)
        OR (callback_type = 'RECONCILIATION_RESULT'
            AND task_public_id IS NULL
            AND separation_task_id IS NULL
            AND task_type IS NULL
            AND approval_request_id IS NULL
            AND consumer_event_id IS NOT NULL)),
    CONSTRAINT ck_ppl_separation_callback_authorities_open CHECK (
        (decision = 'ADMIT' AND closed_at IS NULL)
        OR (decision = 'REVOKE'
            AND closed_at IS NOT NULL
            AND closed_at >= created_at)),
    CONSTRAINT ck_ppl_separation_callback_authorities_chain CHECK (
        (authority_revision = 1
         AND supersedes_authority_public_id IS NULL
         AND expected_next_owner_revision = 1)
        OR (authority_revision > 1
            AND supersedes_authority_public_id IS NOT NULL)),
    CONSTRAINT ck_ppl_separation_callback_authorities_hrm010 CHECK (
        callback_type <> 'APPROVAL_DECISION'
        OR decision <> 'ADMIT'
        OR (authority_revision = 1 AND expected_next_owner_revision = 1))
);

CREATE TABLE ppl_separation_callbacks (
    separation_callback_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    separation_case_id BIGINT NOT NULL,
    separation_task_id BIGINT,
    callback_type VARCHAR(32) NOT NULL,
    callback_value VARCHAR(32) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    callback_event_id UUID NOT NULL,
    external_receipt_public_id UUID,
    owner_revision BIGINT NOT NULL,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    originating_payload_sha256 CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    audience VARCHAR(120) NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    correlation_id UUID NOT NULL,
    approval_request_id UUID,
    waiver_decision VARCHAR(16),
    decision_revision BIGINT,
    decision_actor_public_id UUID,
    case_maker_public_id UUID,
    requested_by_actor_public_id UUID,
    reason_code VARCHAR(80),
    step_up_evidence_id UUID,
    step_up_evidence_audience VARCHAR(120),
    step_up_evidence_purpose VARCHAR(120),
    step_up_evidence_nonce_sha256 CHAR(64),
    step_up_evidence_sha256 CHAR(64),
    step_up_verified_at TIMESTAMPTZ,
    step_up_consumed_at TIMESTAMPTZ,
    receipt_public_id UUID NOT NULL,
    exact_response_body BYTEA NOT NULL,
    response_body_sha256 CHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_ppl_separation_callbacks_identity
        UNIQUE (tenant_id, caller_workload_id, callback_event_id),
    CONSTRAINT uk_ppl_separation_callbacks_receipt
        UNIQUE (tenant_id, receipt_public_id),
    CONSTRAINT fk_ppl_separation_callbacks_case FOREIGN KEY
        (tenant_id, separation_case_id)
        REFERENCES ppl_separation_cases(tenant_id, separation_case_id),
    CONSTRAINT fk_ppl_separation_callbacks_task FOREIGN KEY
        (tenant_id, separation_task_id)
        REFERENCES ppl_separation_tasks(tenant_id, separation_task_id),
    CONSTRAINT fk_ppl_separation_callbacks_approval FOREIGN KEY
        (tenant_id, approval_request_id)
        REFERENCES ppl_separation_waiver_request_outbox(
            tenant_id, approval_request_id),
    CONSTRAINT ck_ppl_separation_callbacks_type CHECK (
        callback_type IN (
            'APPROVAL_DECISION','TASK_RECEIPT','RECONCILIATION_RESULT')),
    CONSTRAINT ck_ppl_separation_callbacks_value CHECK (
        callback_value IN (
            'COMPLETED','FAILED','APPROVED','REJECTED',
            'CONSISTENT','CORRECTION_REQUIRED')),
    CONSTRAINT ck_ppl_separation_callbacks_versions CHECK (
        owner_revision > 0
        AND tenant_grant_revision > 0
        AND authorization_revision > 0
        AND (decision_revision IS NULL OR decision_revision > 0)),
    CONSTRAINT ck_ppl_separation_callbacks_hashes CHECK (
        payload_hash ~ '^[0-9a-f]{64}$'
        AND originating_payload_sha256 ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'
        AND request_body_sha256 ~ '^[0-9a-f]{64}$'
        AND response_body_sha256 ~ '^[0-9a-f]{64}$'
        AND (step_up_evidence_nonce_sha256 IS NULL
             OR step_up_evidence_nonce_sha256 ~ '^[0-9a-f]{64}$')
        AND (step_up_evidence_sha256 IS NULL
             OR step_up_evidence_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ppl_separation_callbacks_response CHECK (
        octet_length(exact_response_body) BETWEEN 1 AND 16384
        AND encode(pg_catalog.sha256(exact_response_body), 'hex')
            = response_body_sha256),
    CONSTRAINT ck_ppl_separation_callbacks_outcome CHECK (
        outcome IN ('ACCEPTED','REJECTED','QUARANTINED')),
    CONSTRAINT ck_ppl_separation_callbacks_task_receipt CHECK (
        callback_type <> 'TASK_RECEIPT'
        OR (separation_task_id IS NOT NULL
            AND external_receipt_public_id IS NOT NULL
            AND callback_value IN ('COMPLETED','FAILED')
            AND approval_request_id IS NULL
            AND waiver_decision IS NULL
            AND decision_revision IS NULL
            AND decision_actor_public_id IS NULL
            AND case_maker_public_id IS NULL
            AND requested_by_actor_public_id IS NULL
            AND reason_code IS NULL
            AND step_up_evidence_id IS NULL
            AND step_up_evidence_audience IS NULL
            AND step_up_evidence_purpose IS NULL
            AND step_up_evidence_nonce_sha256 IS NULL
            AND step_up_evidence_sha256 IS NULL
            AND step_up_verified_at IS NULL
            AND step_up_consumed_at IS NULL)),
    CONSTRAINT ck_ppl_separation_callbacks_approval CHECK (
        callback_type <> 'APPROVAL_DECISION'
        OR (separation_task_id IS NOT NULL
            AND callback_value IN ('APPROVED','REJECTED')
            AND waiver_decision IS NOT NULL
            AND waiver_decision = callback_value
            AND approval_request_id IS NOT NULL
            AND decision_revision IS NOT NULL
            AND decision_revision = owner_revision
            AND decision_revision = 1
            AND owner_revision = 1
            AND decision_actor_public_id IS NOT NULL
            AND case_maker_public_id IS NOT NULL
            AND requested_by_actor_public_id IS NOT NULL
            AND decision_actor_public_id <> case_maker_public_id
            AND decision_actor_public_id <> requested_by_actor_public_id
            AND reason_code IS NOT NULL
            AND external_receipt_public_id IS NULL)),
    CONSTRAINT ck_ppl_separation_callbacks_step_up CHECK (
        (callback_type = 'APPROVAL_DECISION'
         AND waiver_decision = 'APPROVED'
         AND step_up_evidence_id IS NOT NULL
         AND step_up_evidence_audience = 'dwp-people-server'
         AND step_up_evidence_purpose
             = 'HRIS_SEPARATION_TASK_WAIVER_DECISION'
         AND step_up_evidence_nonce_sha256 IS NOT NULL
         AND step_up_evidence_sha256 IS NOT NULL
         AND step_up_verified_at IS NOT NULL
         AND step_up_consumed_at IS NOT NULL
         AND step_up_consumed_at >= step_up_verified_at)
        OR ((callback_type <> 'APPROVAL_DECISION'
             OR waiver_decision = 'REJECTED')
            AND step_up_evidence_id IS NULL
            AND step_up_evidence_audience IS NULL
            AND step_up_evidence_purpose IS NULL
            AND step_up_evidence_nonce_sha256 IS NULL
            AND step_up_evidence_sha256 IS NULL
            AND step_up_verified_at IS NULL
            AND step_up_consumed_at IS NULL)),
    CONSTRAINT ck_ppl_separation_callbacks_reconciliation CHECK (
        callback_type <> 'RECONCILIATION_RESULT'
        OR (separation_task_id IS NULL
            AND external_receipt_public_id IS NULL
            AND callback_value IN ('CONSISTENT','CORRECTION_REQUIRED')
            AND approval_request_id IS NULL
            AND waiver_decision IS NULL
            AND decision_revision IS NULL
            AND decision_actor_public_id IS NULL
            AND case_maker_public_id IS NULL
            AND requested_by_actor_public_id IS NULL
            AND reason_code IS NULL)),
    CONSTRAINT ck_ppl_separation_callbacks_audience CHECK (
        audience = 'dwp-people-server')
);

CREATE UNIQUE INDEX uk_ppl_separation_callbacks_external_receipt
    ON ppl_separation_callbacks(
        tenant_id, caller_workload_id, external_receipt_public_id)
    WHERE callback_type = 'TASK_RECEIPT';
CREATE UNIQUE INDEX uk_ppl_separation_callbacks_step_up_evidence
    ON ppl_separation_callbacks(tenant_id, step_up_evidence_id)
    WHERE callback_type = 'APPROVAL_DECISION'
      AND waiver_decision = 'APPROVED';
CREATE UNIQUE INDEX uk_ppl_separation_callbacks_step_up_nonce
    ON ppl_separation_callbacks(tenant_id, step_up_evidence_nonce_sha256)
    WHERE callback_type = 'APPROVAL_DECISION'
      AND waiver_decision = 'APPROVED';

ALTER TABLE ppl_assignment_events
    ADD COLUMN separation_case_id BIGINT,
    ADD COLUMN supersedes_assignment_event_id BIGINT,
    ADD COLUMN reverses_assignment_event_id BIGINT,
    ADD CONSTRAINT fk_ppl_assignment_events_separation_case FOREIGN KEY
        (tenant_id, separation_case_id)
        REFERENCES ppl_separation_cases(tenant_id, separation_case_id),
    ADD CONSTRAINT fk_ppl_assignment_events_supersedes FOREIGN KEY
        (tenant_id, supersedes_assignment_event_id)
        REFERENCES ppl_assignment_events(tenant_id, assignment_event_id),
    ADD CONSTRAINT fk_ppl_assignment_events_reverses FOREIGN KEY
        (tenant_id, reverses_assignment_event_id)
        REFERENCES ppl_assignment_events(tenant_id, assignment_event_id);

ALTER TABLE ppl_assignment_events
    DROP CONSTRAINT ck_ppl_assignment_events_type,
    ADD CONSTRAINT ck_ppl_assignment_events_type CHECK (
        event_type IN (
            'HIRE','CONCURRENT_EMPLOYMENT','REHIRE','TERMINATION','CORRECTION')),
    ADD CONSTRAINT ck_ppl_assignment_events_lineage CHECK (
        (event_type = 'TERMINATION'
         AND separation_case_id IS NOT NULL
         AND supersedes_assignment_event_id IS NULL
         AND reverses_assignment_event_id IS NULL)
        OR (event_type = 'REHIRE'
            AND separation_case_id IS NOT NULL
            AND supersedes_assignment_event_id IS NULL
            AND reverses_assignment_event_id IS NOT NULL)
        OR (event_type = 'CORRECTION'
            AND separation_case_id IS NOT NULL
            AND supersedes_assignment_event_id IS NOT NULL
            AND reverses_assignment_event_id IS NULL)
        OR (event_type IN ('HIRE','CONCURRENT_EMPLOYMENT')
            AND separation_case_id IS NULL
            AND supersedes_assignment_event_id IS NULL
            AND reverses_assignment_event_id IS NULL)),
    ADD CONSTRAINT ck_ppl_assignment_events_no_self_lineage CHECK (
        (supersedes_assignment_event_id IS NULL
         OR supersedes_assignment_event_id <> assignment_event_id)
        AND (reverses_assignment_event_id IS NULL
             OR reverses_assignment_event_id <> assignment_event_id));

CREATE INDEX idx_ppl_separation_cases_work
    ON ppl_separation_cases(
        tenant_id, lifecycle_state, effective_date, created_at)
    WHERE lifecycle_state IN (
        'DRAFT','TASKS_ACTIVE','READY_TO_PUBLISH','CORRECTION_REQUIRED');
CREATE INDEX idx_ppl_separation_cases_relationship
    ON ppl_separation_cases(
        tenant_id, work_relationship_id, effective_date DESC, created_at DESC);
CREATE INDEX idx_ppl_separation_tasks_case_state
    ON ppl_separation_tasks(
        tenant_id, separation_case_id, lifecycle_state, task_type);
CREATE INDEX idx_ppl_separation_callbacks_case_revision
    ON ppl_separation_callbacks(
        tenant_id, separation_case_id, owner_revision, received_at);
CREATE INDEX idx_ppl_assignment_events_separation_lineage
    ON ppl_assignment_events(tenant_id, separation_case_id, event_type)
    WHERE separation_case_id IS NOT NULL;
CREATE UNIQUE INDEX uk_sys_people_outbox_worker_separated
    ON sys_people_outbox_events(
        tenant_id, aggregate_type, aggregate_id, event_type)
    WHERE aggregate_type = 'SEPARATION_CASE'
      AND event_type = 'WorkerSeparated.v1';
CREATE INDEX idx_ppl_hrm013_callback_proof_expiry
    ON ppl_hrm013_callback_proof_replays(expires_at, proof_replay_id);
CREATE INDEX idx_ppl_separation_waiver_outbox_claim
    ON ppl_separation_waiver_request_outbox(
        tenant_id, dispatch_state, available_at, lease_until);
CREATE INDEX idx_ppl_separation_callback_authorities_latest
    ON ppl_separation_callback_authorities(
        tenant_id,binding_id,caller_workload_id,case_public_id,
        task_public_id NULLS FIRST,purpose_code,
        approval_request_id NULLS FIRST,authority_revision DESC);
CREATE INDEX idx_ppl_separation_callbacks_protocol_revision
    ON ppl_separation_callbacks(
        tenant_id,callback_type,caller_workload_id,separation_case_id,
        separation_task_id,owner_revision DESC);
CREATE INDEX idx_ppl_separation_callbacks_external_receipt
    ON ppl_separation_callbacks(
        tenant_id,caller_workload_id,external_receipt_public_id)
    WHERE callback_type = 'TASK_RECEIPT';
CREATE INDEX idx_ppl_separation_callbacks_step_up_evidence
    ON ppl_separation_callbacks(tenant_id,step_up_evidence_id)
    WHERE waiver_decision = 'APPROVED';
CREATE INDEX idx_ppl_separation_callbacks_step_up_nonce
    ON ppl_separation_callbacks(tenant_id,step_up_evidence_nonce_sha256)
    WHERE waiver_decision = 'APPROVED';

CREATE FUNCTION public.ppl_guard_separation_case()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.lifecycle_state IN ('PUBLISHED','CORRECTION_REQUIRED','CANCELLED') THEN
            RAISE EXCEPTION 'terminal separation case is immutable'
                USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF ROW(NEW.separation_case_id,NEW.public_id,NEW.tenant_id,NEW.worker_id,
           NEW.work_relationship_id,NEW.maker_principal_public_id,
           NEW.correlation_id,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.separation_case_id,OLD.public_id,OLD.tenant_id,OLD.worker_id,
           OLD.work_relationship_id,OLD.maker_principal_public_id,
           OLD.correlation_id,OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'separation case identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'separation case version must advance exactly once'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (
        (OLD.lifecycle_state='DRAFT'
         AND NEW.lifecycle_state IN ('DRAFT','TASKS_ACTIVE','CANCELLED'))
        OR (OLD.lifecycle_state='TASKS_ACTIVE'
            AND NEW.lifecycle_state IN (
                'TASKS_ACTIVE','READY_TO_PUBLISH','CANCELLED'))
        OR (OLD.lifecycle_state='READY_TO_PUBLISH'
            AND NEW.lifecycle_state IN ('READY_TO_PUBLISH','PUBLISHED'))
        OR (OLD.lifecycle_state='PUBLISHED'
            AND NEW.lifecycle_state IN ('PUBLISHED','CORRECTION_REQUIRED'))
        OR (OLD.lifecycle_state=NEW.lifecycle_state
            AND OLD.lifecycle_state IN ('CORRECTION_REQUIRED','CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'illegal separation case lifecycle transition'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('CORRECTION_REQUIRED','CANCELLED') THEN
        RAISE EXCEPTION 'terminal separation case is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.lifecycle_state='CANCELLED' AND OLD.irreversible_at IS NOT NULL THEN
        RAISE EXCEPTION 'irreversible separation case cannot be cancelled'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state='PUBLISHED'
       AND ROW(NEW.separation_type,NEW.reason_code,NEW.effective_date,
               NEW.last_working_date,NEW.input_snapshot_hash,
               NEW.task_template_public_id,NEW.task_template_revision,
               NEW.task_template_hash,NEW.approval_request_id,
               NEW.approval_decision_event_id,NEW.approval_decision_revision,
               NEW.approval_payload_hash,NEW.approval_actor_public_id,
               NEW.irreversible_at,NEW.published_at)
           IS DISTINCT FROM
           ROW(OLD.separation_type,OLD.reason_code,OLD.effective_date,
               OLD.last_working_date,OLD.input_snapshot_hash,
               OLD.task_template_public_id,OLD.task_template_revision,
               OLD.task_template_hash,OLD.approval_request_id,
               OLD.approval_decision_event_id,OLD.approval_decision_revision,
               OLD.approval_payload_hash,OLD.approval_actor_public_id,
               OLD.irreversible_at,OLD.published_at) THEN
        RAISE EXCEPTION 'published separation evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_separation_case() FROM PUBLIC;
CREATE TRIGGER trg_ppl_separation_case_guard
    BEFORE UPDATE OR DELETE ON ppl_separation_cases
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_separation_case();

CREATE FUNCTION public.ppl_guard_separation_task()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.lifecycle_state IN ('COMPLETED','WAIVED') THEN
            RAISE EXCEPTION 'terminal separation task is immutable'
                USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF ROW(NEW.separation_task_id,NEW.public_id,NEW.tenant_id,
           NEW.separation_case_id,NEW.task_type,NEW.owner_workload_id,
           NEW.mandatory,NEW.irreversible,NEW.created_at,NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.separation_task_id,OLD.public_id,OLD.tenant_id,
           OLD.separation_case_id,OLD.task_type,OLD.owner_workload_id,
           OLD.mandatory,OLD.irreversible,OLD.created_at,OLD.created_by) THEN
        RAISE EXCEPTION 'separation task identity and template are immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'separation task version must advance exactly once'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state IN ('COMPLETED','WAIVED') THEN
        RAISE EXCEPTION 'terminal separation task is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (
        (OLD.lifecycle_state='PENDING'
         AND NEW.lifecycle_state IN (
             'PENDING','IN_PROGRESS','COMPLETED','FAILED','WAIVED'))
        OR (OLD.lifecycle_state='IN_PROGRESS'
            AND NEW.lifecycle_state IN (
                'IN_PROGRESS','COMPLETED','FAILED','WAIVED'))
        OR (OLD.lifecycle_state='FAILED'
            AND NEW.lifecycle_state IN (
                'FAILED','IN_PROGRESS','COMPLETED','WAIVED'))
    ) THEN
        RAISE EXCEPTION 'illegal separation task lifecycle transition'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.owner_revision IS NOT NULL
       AND NEW.owner_revision IS DISTINCT FROM OLD.owner_revision
       AND NEW.owner_revision <> OLD.owner_revision + 1 THEN
        RAISE EXCEPTION 'separation task owner revision must advance exactly once'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_separation_task() FROM PUBLIC;
CREATE TRIGGER trg_ppl_separation_task_guard
    BEFORE UPDATE OR DELETE ON ppl_separation_tasks
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_separation_task();

CREATE FUNCTION public.ppl_guard_hrm013_proof_replay()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'HRM-013 proof replay evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.expires_at + INTERVAL '5 seconds' > pg_catalog.clock_timestamp() THEN
        RAISE EXCEPTION 'unexpired HRM-013 proof replay cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_hrm013_proof_replay() FROM PUBLIC;
CREATE TRIGGER trg_ppl_hrm013_proof_replay_guard
    BEFORE UPDATE OR DELETE ON ppl_hrm013_callback_proof_replays
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_hrm013_proof_replay();

CREATE FUNCTION public.ppl_guard_separation_waiver_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.dispatch_state <> 'PENDING' OR NEW.attempt_count <> 0 THEN
            RAISE EXCEPTION 'waiver request outbox must begin pending at zero'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'waiver request outbox evidence cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.separation_waiver_request_id,NEW.public_id,NEW.tenant_id,
           NEW.separation_case_id,NEW.separation_task_id,
           NEW.approval_request_id,NEW.case_public_id,NEW.task_public_id,
           NEW.request_revision,NEW.case_maker_public_id,
           NEW.requested_by_actor_public_id,NEW.reason_code,
           NEW.task_owner_revision,NEW.task_version,NEW.exact_request_body,
           NEW.request_body_sha256,NEW.approval_request_payload_sha256,
           NEW.command_digest,NEW.correlation_id,NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.separation_waiver_request_id,OLD.public_id,OLD.tenant_id,
           OLD.separation_case_id,OLD.separation_task_id,
           OLD.approval_request_id,OLD.case_public_id,OLD.task_public_id,
           OLD.request_revision,OLD.case_maker_public_id,
           OLD.requested_by_actor_public_id,OLD.reason_code,
           OLD.task_owner_revision,OLD.task_version,OLD.exact_request_body,
           OLD.request_body_sha256,OLD.approval_request_payload_sha256,
           OLD.command_digest,OLD.correlation_id,OLD.created_at) THEN
        RAISE EXCEPTION 'waiver request fence and exact bytes are immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.dispatch_state IN ('ACCEPTED','DEAD','RESULT_UNKNOWN') THEN
        RAISE EXCEPTION 'terminal waiver request delivery is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (
        (OLD.dispatch_state IN ('PENDING','RETRY')
         AND NEW.dispatch_state='CLAIMED'
         AND NEW.attempt_count=OLD.attempt_count+1)
        OR (OLD.dispatch_state='CLAIMED'
            AND NEW.dispatch_state IN (
                'ACCEPTED','RETRY','DEAD','RESULT_UNKNOWN')
            AND NEW.attempt_count=OLD.attempt_count)
        OR (OLD.dispatch_state='CLAIMED'
            AND OLD.lease_until <= pg_catalog.clock_timestamp()
            AND NEW.dispatch_state='CLAIMED'
            AND NEW.attempt_count=OLD.attempt_count+1)
    ) THEN
        RAISE EXCEPTION 'illegal waiver request delivery transition'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_separation_waiver_outbox() FROM PUBLIC;
CREATE TRIGGER trg_ppl_separation_waiver_outbox_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ppl_separation_waiver_request_outbox
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_separation_waiver_outbox();

CREATE FUNCTION public.ppl_guard_separation_callback_authority_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    prior ppl_separation_callback_authorities%ROWTYPE;
BEGIN
    IF NEW.authority_revision = 1 THEN
        RETURN NEW;
    END IF;
    SELECT * INTO prior
      FROM public.ppl_separation_callback_authorities
     WHERE tenant_id=NEW.tenant_id
       AND public_id=NEW.supersedes_authority_public_id
     FOR SHARE;
    IF NOT FOUND
       OR prior.authority_revision + 1 <> NEW.authority_revision
       OR prior.decision <> 'ADMIT'
       OR prior.created_at > NEW.created_at
       OR ROW(prior.binding_id,prior.caller_workload_id,prior.case_public_id,
              prior.task_public_id,prior.purpose_code,prior.approval_request_id)
          IS DISTINCT FROM
          ROW(NEW.binding_id,NEW.caller_workload_id,NEW.case_public_id,
              NEW.task_public_id,NEW.purpose_code,NEW.approval_request_id) THEN
        RAISE EXCEPTION 'callback authority must supersede the immediate same-tenant stream head'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_separation_callback_authority_insert()
    FROM PUBLIC;
CREATE TRIGGER trg_ppl_separation_callback_authority_insert
    BEFORE INSERT ON ppl_separation_callback_authorities
    FOR EACH ROW
    EXECUTE FUNCTION public.ppl_guard_separation_callback_authority_insert();

CREATE FUNCTION public.ppl_reject_hrm013_immutable_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'HRM-013 callback authority and receipt evidence are immutable'
        USING ERRCODE = '23514';
END
$$;

REVOKE ALL ON FUNCTION public.ppl_reject_hrm013_immutable_mutation()
    FROM PUBLIC;
CREATE TRIGGER trg_ppl_separation_callback_authority_immutable
    BEFORE UPDATE OR DELETE ON ppl_separation_callback_authorities
    FOR EACH ROW EXECUTE FUNCTION public.ppl_reject_hrm013_immutable_mutation();
CREATE TRIGGER trg_ppl_separation_callback_immutable
    BEFORE UPDATE OR DELETE ON ppl_separation_callbacks
    FOR EACH ROW EXECUTE FUNCTION public.ppl_reject_hrm013_immutable_mutation();

CREATE FUNCTION public.ppl_guard_assignment_event_separation_lineage()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF ROW(NEW.separation_case_id,NEW.supersedes_assignment_event_id,
           NEW.reverses_assignment_event_id)
       IS DISTINCT FROM
       ROW(OLD.separation_case_id,OLD.supersedes_assignment_event_id,
           OLD.reverses_assignment_event_id) THEN
        RAISE EXCEPTION 'assignment event separation lineage is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_assignment_event_separation_lineage()
    FROM PUBLIC;
CREATE TRIGGER trg_ppl_assignment_event_separation_lineage
    BEFORE UPDATE ON ppl_assignment_events
    FOR EACH ROW
    EXECUTE FUNCTION public.ppl_guard_assignment_event_separation_lineage();

ALTER TABLE ppl_separation_cases ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_separation_cases FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_separation_cases_tenant_policy ON ppl_separation_cases
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_separation_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_separation_tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_separation_tasks_tenant_policy ON ppl_separation_tasks
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_hrm013_callback_proof_replays ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_hrm013_callback_proof_replays FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_hrm013_callback_proof_replays_tenant_policy
    ON ppl_hrm013_callback_proof_replays
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_separation_waiver_request_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_separation_waiver_request_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_separation_waiver_request_outbox_tenant_policy
    ON ppl_separation_waiver_request_outbox
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_separation_callback_authorities ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_separation_callback_authorities FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_separation_callback_authorities_tenant_policy
    ON ppl_separation_callback_authorities
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_separation_callbacks ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_separation_callbacks FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_separation_callbacks_tenant_policy ON ppl_separation_callbacks
    USING (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

COMMENT ON TABLE ppl_hrm013_callback_proof_replays IS
    'Digest-only, tenant-scoped HRM-008 through HRM-010 proof replay namespace.';
COMMENT ON TABLE ppl_separation_waiver_request_outbox IS
    'Immutable People-owned APR-002 request fence and exact-byte delivery outbox.';
COMMENT ON TABLE ppl_separation_callback_authorities IS
    'Append-only HRM-013 callback protocol authority and owner-revision fence.';
COMMENT ON TABLE ppl_separation_callbacks IS
    'Immutable HRM-008 through HRM-010 receipt ledger with safe exact responses.';
