-- BASE-TFR-PAY-011 / HRIS-WAVE5-START-BASE-TFR-PAY-011-R1.
-- The ten table names below are the exhaustive V50 lease. All successful facts,
-- transition evidence, receipts and outbox payloads are append-only.

CREATE TABLE pay_input_snapshots (
    input_snapshot_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    input_snapshot_id UUID NOT NULL,
    run_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    source_snapshot_id UUID NOT NULL,
    source_snapshot_revision BIGINT NOT NULL,
    source_payload_digest CHAR(64) NOT NULL,
    formula_set_digest CHAR(64) NOT NULL,
    formula_bindings JSONB NOT NULL,
    engine_version VARCHAR(64) NOT NULL,
    function_set_version VARCHAR(64) NOT NULL,
    semantic_binding_digest CHAR(64) NOT NULL,
    target_population_digest CHAR(64) NOT NULL,
    compensation_input_digest CHAR(64) NOT NULL,
    source_line_count INTEGER NOT NULL,
    pinned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_input_snapshot_public_unique UNIQUE (tenant_id, input_snapshot_id),
    CONSTRAINT pay_input_snapshot_internal_tenant_unique UNIQUE (
        tenant_id, input_snapshot_internal_id),
    CONSTRAINT pay_input_snapshot_run_internal_unique UNIQUE (
        tenant_id, input_snapshot_internal_id, run_id, attempt_no),
    CONSTRAINT pay_input_snapshot_attempt_unique UNIQUE (tenant_id, run_id, attempt_no),
    CONSTRAINT pay_input_snapshot_positive CHECK (
        tenant_id > 0 AND attempt_no > 0
        AND source_snapshot_revision > 0 AND source_line_count > 0),
    CONSTRAINT pay_input_snapshot_digest_shape CHECK (
        source_payload_digest ~ '^[0-9a-f]{64}$'
        AND formula_set_digest ~ '^[0-9a-f]{64}$'
        AND semantic_binding_digest ~ '^[0-9a-f]{64}$'
        AND target_population_digest ~ '^[0-9a-f]{64}$'
        AND compensation_input_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_input_snapshot_runtime_shape CHECK (
        engine_version ~ '^[A-Za-z0-9._:-]{1,64}$'
        AND function_set_version ~ '^[A-Za-z0-9._:-]{1,64}$'),
    CONSTRAINT pay_input_snapshot_formula_shape CHECK (
        jsonb_typeof(formula_bindings) = 'array')
);

CREATE TABLE pay_input_snapshot_compensation_refs (
    compensation_ref_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    input_snapshot_internal_id BIGINT NOT NULL,
    input_ordinal INTEGER NOT NULL,
    compensation_plan_input_id BIGINT NOT NULL,
    source_payload_digest CHAR(64) NOT NULL,
    worker_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    component_code VARCHAR(80) NOT NULL,
    currency CHAR(3) NOT NULL,
    approved_amount NUMERIC(19,4) NOT NULL,
    semantic_binding JSONB NOT NULL,
    semantic_binding_digest CHAR(64) NOT NULL,
    CONSTRAINT pay_input_snapshot_ref_parent_fk FOREIGN KEY (
        tenant_id, input_snapshot_internal_id)
        REFERENCES pay_input_snapshots (tenant_id, input_snapshot_internal_id),
    CONSTRAINT pay_input_snapshot_ref_source_fk FOREIGN KEY (
        tenant_id, compensation_plan_input_id)
        REFERENCES pay_compensation_plan_inputs (tenant_id, compensation_plan_input_id),
    CONSTRAINT pay_input_snapshot_ref_ordinal_unique UNIQUE (
        tenant_id, input_snapshot_internal_id, input_ordinal),
    CONSTRAINT pay_input_snapshot_ref_source_unique UNIQUE (
        tenant_id, input_snapshot_internal_id, compensation_plan_input_id),
    CONSTRAINT pay_input_snapshot_ref_binding_unique UNIQUE (
        tenant_id, input_snapshot_internal_id, compensation_plan_input_id,
        semantic_binding_digest),
    CONSTRAINT pay_input_snapshot_ref_positive CHECK (
        tenant_id > 0 AND input_ordinal > 0 AND approved_amount >= 0),
    CONSTRAINT pay_input_snapshot_ref_shape CHECK (
        source_payload_digest ~ '^[0-9a-f]{64}$'
        AND semantic_binding_digest ~ '^[0-9a-f]{64}$'
        AND jsonb_typeof(semantic_binding) = 'object'
        AND semantic_binding ->> 'bindingDigest' = semantic_binding_digest
        AND component_code ~ '^[A-Z0-9][A-Z0-9._-]{0,79}$'
        AND currency ~ '^[A-Z]{3}$')
);

CREATE TABLE pay_payroll_runs (
    payroll_run_revision_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    run_revision BIGINT NOT NULL,
    run_type VARCHAR(24) NOT NULL,
    pay_period_id UUID NOT NULL,
    source_snapshot_id UUID NOT NULL,
    source_snapshot_revision BIGINT NOT NULL,
    source_payload_digest CHAR(64) NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    population_kind VARCHAR(32) NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    maker_actor_id BIGINT NOT NULL,
    maker_subject_public_id UUID NOT NULL,
    latest_attempt_no INTEGER NOT NULL,
    worker_count INTEGER NOT NULL,
    gross_amount NUMERIC(19,4) NOT NULL,
    net_amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    result_digest CHAR(64),
    approval_case_id UUID,
    decision_digest CHAR(64),
    approved_by_actor_id BIGINT,
    approved_at TIMESTAMPTZ,
    audit_bundle_digest CHAR(64),
    command_receipt_id UUID,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_payroll_run_revision_unique UNIQUE (tenant_id, run_id, run_revision),
    CONSTRAINT pay_payroll_run_positive CHECK (
        tenant_id > 0 AND run_revision > 0 AND maker_actor_id > 0
        AND source_snapshot_revision > 0
        AND latest_attempt_no >= 0 AND worker_count >= 0
        AND gross_amount >= 0 AND net_amount >= 0),
    CONSTRAINT pay_payroll_run_type_valid CHECK (
        run_type IN ('REGULAR', 'OFF_CYCLE', 'RETRO', 'CORRECTION')),
    CONSTRAINT pay_payroll_run_state_valid CHECK (state IN (
        'PREPARING', 'READY', 'CALCULATING', 'VALIDATED',
        'APPROVED', 'FINALIZED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT pay_payroll_run_population_valid CHECK (
        population_kind IN ('LEGAL_ENTITY', 'PAY_GROUP', 'NAMED_POPULATION')),
    CONSTRAINT pay_payroll_run_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT pay_payroll_run_digest_shape CHECK (
        population_scope_digest ~ '^[0-9a-f]{64}$'
        AND source_payload_digest ~ '^[0-9a-f]{64}$'
        AND (result_digest IS NULL OR result_digest ~ '^[0-9a-f]{64}$')
        AND (decision_digest IS NULL OR decision_digest ~ '^[0-9a-f]{64}$')
        AND (audit_bundle_digest IS NULL OR audit_bundle_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT pay_payroll_run_result_consistent CHECK (
        state NOT IN ('VALIDATED', 'APPROVED', 'FINALIZED') OR result_digest IS NOT NULL),
    CONSTRAINT pay_payroll_run_approval_consistent CHECK (
        state NOT IN ('APPROVED', 'FINALIZED') OR (
            approval_case_id IS NOT NULL AND decision_digest IS NOT NULL
            AND approved_by_actor_id IS NOT NULL AND approved_at IS NOT NULL))
);

CREATE INDEX pay_payroll_run_latest_idx
    ON pay_payroll_runs (tenant_id, run_id, run_revision DESC);

CREATE TABLE pay_payroll_run_attempts (
    attempt_transition_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_id UUID NOT NULL,
    predecessor_attempt_no INTEGER,
    status_sequence INTEGER NOT NULL,
    attempt_state VARCHAR(24) NOT NULL,
    engine_version VARCHAR(64) NOT NULL,
    command_receipt_id UUID NOT NULL,
    failure_code VARCHAR(80),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_payroll_attempt_transition_unique UNIQUE (
        tenant_id, run_id, attempt_no, status_sequence),
    CONSTRAINT pay_payroll_attempt_id_sequence_unique UNIQUE (
        tenant_id, attempt_id, status_sequence),
    CONSTRAINT pay_payroll_attempt_snapshot_fk FOREIGN KEY (
        tenant_id, run_id, attempt_no)
        REFERENCES pay_input_snapshots (tenant_id, run_id, attempt_no),
    CONSTRAINT pay_payroll_attempt_positive CHECK (
        tenant_id > 0 AND attempt_no > 0 AND status_sequence > 0
        AND (predecessor_attempt_no IS NULL
            OR (predecessor_attempt_no > 0 AND predecessor_attempt_no < attempt_no))),
    CONSTRAINT pay_payroll_attempt_state_valid CHECK (attempt_state IN (
        'READY', 'CALCULATING', 'VALIDATED', 'FAILED',
        'RESULT_UNKNOWN', 'SUPERSEDED')),
    CONSTRAINT pay_payroll_attempt_failure_consistent CHECK (
        (attempt_state IN ('FAILED', 'RESULT_UNKNOWN')
            AND failure_code IS NOT NULL AND btrim(failure_code) <> '')
        OR (attempt_state NOT IN ('FAILED', 'RESULT_UNKNOWN') AND failure_code IS NULL)),
    CONSTRAINT pay_payroll_attempt_engine_present CHECK (btrim(engine_version) <> '')
);

CREATE TABLE pay_payroll_run_workers (
    run_worker_record_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    attempt_id UUID NOT NULL,
    record_kind VARCHAR(16) NOT NULL,
    claim_no INTEGER NOT NULL,
    lease_token UUID NOT NULL,
    worker_instance_id VARCHAR(160) NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    checkpoint_no INTEGER,
    next_input_ordinal INTEGER,
    completed_worker_count INTEGER,
    checkpoint_digest CHAR(64),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_payroll_run_worker_attempt_fk FOREIGN KEY (
        tenant_id, run_id, attempt_no)
        REFERENCES pay_input_snapshots (tenant_id, run_id, attempt_no),
    CONSTRAINT pay_payroll_run_worker_positive CHECK (
        tenant_id > 0 AND attempt_no > 0 AND claim_no > 0),
    CONSTRAINT pay_payroll_run_worker_kind_valid CHECK (
        record_kind IN ('LEASE', 'RENEWAL', 'CHECKPOINT')),
    CONSTRAINT pay_payroll_run_worker_lease_window CHECK (
        btrim(worker_instance_id) <> '' AND expires_at > claimed_at),
    CONSTRAINT pay_payroll_run_worker_checkpoint_consistent CHECK (
        (record_kind IN ('LEASE', 'RENEWAL') AND checkpoint_no IS NULL
            AND next_input_ordinal IS NULL AND completed_worker_count IS NULL
            AND checkpoint_digest IS NULL)
        OR (record_kind = 'CHECKPOINT' AND checkpoint_no > 0
            AND next_input_ordinal > 0 AND completed_worker_count >= 0
            AND checkpoint_digest ~ '^[0-9a-f]{64}$'))
);

CREATE UNIQUE INDEX pay_payroll_run_worker_lease_unique
    ON pay_payroll_run_workers (tenant_id, run_id, attempt_no, claim_no)
    WHERE record_kind = 'LEASE';
CREATE UNIQUE INDEX pay_payroll_run_worker_checkpoint_unique
    ON pay_payroll_run_workers (tenant_id, run_id, attempt_no, checkpoint_no)
    WHERE record_kind = 'CHECKPOINT';
CREATE UNIQUE INDEX pay_payroll_run_worker_lease_token_unique
    ON pay_payroll_run_workers (tenant_id, lease_token)
    WHERE record_kind = 'LEASE';
CREATE UNIQUE INDEX pay_payroll_run_worker_renewal_unique
    ON pay_payroll_run_workers (
        tenant_id, run_id, attempt_no, claim_no, expires_at)
    WHERE record_kind = 'RENEWAL';

CREATE TABLE pay_worker_results (
    worker_result_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    worker_result_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    gross_amount NUMERIC(19,4) NOT NULL,
    deduction_amount NUMERIC(19,4) NOT NULL,
    net_amount NUMERIC(19,4) NOT NULL,
    employer_cost_amount NUMERIC(19,4) NOT NULL,
    line_count INTEGER NOT NULL,
    worker_result_digest CHAR(64) NOT NULL,
    calculation_result_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_worker_result_identity_unique UNIQUE (
        tenant_id, run_id, attempt_no, worker_id),
    CONSTRAINT pay_worker_result_public_unique UNIQUE (
        tenant_id, worker_result_id),
    CONSTRAINT pay_worker_result_attempt_public_unique UNIQUE (
        tenant_id, run_id, attempt_no, worker_result_id),
    CONSTRAINT pay_worker_result_attempt_fk FOREIGN KEY (
        tenant_id, run_id, attempt_no)
        REFERENCES pay_input_snapshots (tenant_id, run_id, attempt_no),
    CONSTRAINT pay_worker_result_positive CHECK (
        tenant_id > 0 AND attempt_no > 0 AND gross_amount >= 0
        AND deduction_amount >= 0 AND net_amount >= 0
        AND employer_cost_amount >= 0 AND line_count > 0),
    CONSTRAINT pay_worker_result_reconciles CHECK (
        net_amount = gross_amount - deduction_amount),
    CONSTRAINT pay_worker_result_shape CHECK (
        currency ~ '^[A-Z]{3}$'
        AND worker_result_digest ~ '^[0-9a-f]{64}$'
        AND calculation_result_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE pay_result_lines (
    result_line_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    input_snapshot_internal_id BIGINT NOT NULL,
    result_line_id UUID NOT NULL,
    compensation_plan_input_id BIGINT NOT NULL,
    worker_id UUID NOT NULL,
    worker_result_id UUID NOT NULL,
    component_code VARCHAR(80) NOT NULL,
    source_payload_digest CHAR(64) NOT NULL,
    semantic_binding_digest CHAR(64) NOT NULL,
    element_id UUID NOT NULL,
    element_version BIGINT NOT NULL,
    formula_id UUID NOT NULL,
    formula_version BIGINT NOT NULL,
    formula_ast_digest CHAR(64) NOT NULL,
    line_category VARCHAR(32) NOT NULL,
    formula_input_digest CHAR(64) NOT NULL,
    formula_output_digest CHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    terminal_trace_id UUID NOT NULL,
    trace_digest CHAR(64) NOT NULL,
    result_line_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_result_line_public_unique UNIQUE (tenant_id, result_line_id),
    CONSTRAINT pay_result_line_source_unique UNIQUE (
        tenant_id, run_id, attempt_no, compensation_plan_input_id),
    CONSTRAINT pay_result_line_attempt_fk FOREIGN KEY (
        tenant_id, input_snapshot_internal_id, run_id, attempt_no)
        REFERENCES pay_input_snapshots (
            tenant_id, input_snapshot_internal_id, run_id, attempt_no),
    CONSTRAINT pay_result_line_frozen_source_fk FOREIGN KEY (
        tenant_id, input_snapshot_internal_id, compensation_plan_input_id,
        semantic_binding_digest)
        REFERENCES pay_input_snapshot_compensation_refs (
            tenant_id, input_snapshot_internal_id, compensation_plan_input_id,
            semantic_binding_digest),
    CONSTRAINT pay_result_line_positive CHECK (
        tenant_id > 0 AND attempt_no > 0 AND element_version > 0
        AND formula_version > 0 AND amount >= 0),
    CONSTRAINT pay_result_line_category CHECK (
        line_category IN ('EARNING', 'DEDUCTION', 'EMPLOYER_COST')),
    CONSTRAINT pay_result_line_shape CHECK (
        component_code ~ '^[A-Z0-9][A-Z0-9._-]{0,79}$'
        AND source_payload_digest ~ '^[0-9a-f]{64}$'
        AND semantic_binding_digest ~ '^[0-9a-f]{64}$'
        AND currency ~ '^[A-Z]{3}$'
        AND formula_ast_digest ~ '^[0-9a-f]{64}$'
        AND formula_input_digest ~ '^[0-9a-f]{64}$'
        AND formula_output_digest ~ '^[0-9a-f]{64}$'
        AND trace_digest ~ '^[0-9a-f]{64}$'
        AND result_line_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE pay_calculation_trace_nodes (
    trace_node_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    input_snapshot_internal_id BIGINT NOT NULL,
    trace_id UUID NOT NULL,
    trace_sequence INTEGER NOT NULL,
    parent_trace_id UUID,
    semantic_binding_digest CHAR(64) NOT NULL,
    worker_id UUID NOT NULL,
    compensation_plan_input_id BIGINT NOT NULL,
    element_id UUID NOT NULL,
    element_version BIGINT NOT NULL,
    formula_id UUID,
    formula_version BIGINT,
    formula_ast_digest CHAR(64),
    formula_input_digest CHAR(64) NOT NULL,
    formula_output_digest CHAR(64) NOT NULL,
    line_category VARCHAR(32) NOT NULL,
    source_payload_digest CHAR(64) NOT NULL,
    unrounded_output_amount NUMERIC(24,8) NOT NULL,
    rounded_output_amount NUMERIC(24,8) NOT NULL,
    currency CHAR(3) NOT NULL,
    rounding_stage VARCHAR(32) NOT NULL,
    rounding_scale SMALLINT NOT NULL,
    rounding_mode VARCHAR(16) NOT NULL,
    rounding_increment NUMERIC(24,8) NOT NULL,
    rounding_policy_digest CHAR(64) NOT NULL,
    trace_digest CHAR(64) NOT NULL,
    engine_version VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_trace_node_public_unique UNIQUE (tenant_id, trace_id),
    CONSTRAINT pay_trace_node_lineage_unique UNIQUE (
        tenant_id, run_id, attempt_no, input_snapshot_internal_id,
        compensation_plan_input_id, trace_id),
    CONSTRAINT pay_trace_node_source_sequence_unique UNIQUE (
        tenant_id, run_id, attempt_no, compensation_plan_input_id, trace_sequence),
    CONSTRAINT pay_trace_node_attempt_fk FOREIGN KEY (
        tenant_id, input_snapshot_internal_id, run_id, attempt_no)
        REFERENCES pay_input_snapshots (
            tenant_id, input_snapshot_internal_id, run_id, attempt_no),
    CONSTRAINT pay_trace_node_frozen_source_fk FOREIGN KEY (
        tenant_id, input_snapshot_internal_id, compensation_plan_input_id,
        semantic_binding_digest)
        REFERENCES pay_input_snapshot_compensation_refs (
            tenant_id, input_snapshot_internal_id, compensation_plan_input_id,
            semantic_binding_digest),
    CONSTRAINT pay_trace_node_parent_fk FOREIGN KEY (
        tenant_id, run_id, attempt_no, input_snapshot_internal_id,
        compensation_plan_input_id, parent_trace_id)
        REFERENCES pay_calculation_trace_nodes (
            tenant_id, run_id, attempt_no, input_snapshot_internal_id,
            compensation_plan_input_id, trace_id),
    CONSTRAINT pay_trace_node_positive CHECK (
        tenant_id > 0 AND attempt_no > 0 AND trace_sequence > 0
        AND element_version > 0
        AND unrounded_output_amount >= 0 AND rounded_output_amount >= 0
        AND rounding_increment > 0),
    CONSTRAINT pay_trace_node_formula_identity CHECK (
        (formula_id IS NULL AND formula_version IS NULL
            AND formula_ast_digest IS NULL)
        OR (formula_id IS NOT NULL AND formula_version > 0
            AND formula_ast_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT pay_trace_node_parent_shape CHECK (
        (trace_sequence = 1 AND parent_trace_id IS NULL)
        OR (trace_sequence > 1 AND parent_trace_id IS NOT NULL)),
    CONSTRAINT pay_trace_node_category CHECK (
        line_category IN ('EARNING', 'DEDUCTION', 'EMPLOYER_COST')),
    CONSTRAINT pay_trace_node_rounding_stage CHECK (
        rounding_stage IN ('SOURCE_NORMALIZE', 'FORMULA_DECLARED', 'ELEMENT',
            'WORKER_RESULT', 'SETTLEMENT_OR_STATUTORY')),
    CONSTRAINT pay_trace_node_rounding_scale CHECK (
        rounding_scale BETWEEN 0 AND 8),
    CONSTRAINT pay_trace_node_rounding_mode CHECK (
        rounding_mode IN ('HALF_UP', 'HALF_EVEN', 'DOWN', 'UP', 'FLOOR', 'CEILING')),
    CONSTRAINT pay_trace_node_shape CHECK (
        formula_input_digest ~ '^[0-9a-f]{64}$'
        AND semantic_binding_digest ~ '^[0-9a-f]{64}$'
        AND formula_output_digest ~ '^[0-9a-f]{64}$'
        AND source_payload_digest ~ '^[0-9a-f]{64}$'
        AND rounding_policy_digest ~ '^[0-9a-f]{64}$'
        AND currency ~ '^[A-Z]{3}$'
        AND trace_digest ~ '^[0-9a-f]{64}$' AND btrim(engine_version) <> '')
);

ALTER TABLE pay_result_lines
    ADD CONSTRAINT pay_result_line_terminal_trace_fk
    FOREIGN KEY (tenant_id, run_id, attempt_no, input_snapshot_internal_id,
        compensation_plan_input_id, terminal_trace_id)
    REFERENCES pay_calculation_trace_nodes (
        tenant_id, run_id, attempt_no, input_snapshot_internal_id,
        compensation_plan_input_id, trace_id);

CREATE TABLE pay_command_receipts (
    command_receipt_record_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    status_sequence INTEGER NOT NULL,
    receipt_status VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    originating_action VARCHAR(32) NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    actor_id BIGINT NOT NULL,
    run_id UUID NOT NULL,
    population_kind VARCHAR(32) NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    context_scope_key VARCHAR(160) NOT NULL,
    field_policy_revision BIGINT NOT NULL,
    purpose_code VARCHAR(64) NOT NULL,
    authorization_revision BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    request_digest CHAR(64) NOT NULL,
    aggregate_version BIGINT,
    result_ref VARCHAR(240),
    step_up_challenge_id VARCHAR(160),
    step_up_nonce VARCHAR(160),
    step_up_issuer VARCHAR(300),
    step_up_expires_at TIMESTAMPTZ,
    step_up_payload_sha256 CHAR(64),
    step_up_decision_revision VARCHAR(200),
    audit_type VARCHAR(80) NOT NULL,
    audit_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_command_receipt_status_unique UNIQUE (
        tenant_id, receipt_id, status_sequence),
    CONSTRAINT pay_command_receipt_positive CHECK (
        tenant_id > 0 AND status_sequence > 0 AND actor_id > 0
        AND field_policy_revision > 0 AND authorization_revision > 0
        AND (aggregate_version IS NULL OR aggregate_version >= 0)),
    CONSTRAINT pay_command_receipt_status_valid CHECK (receipt_status IN (
        'ACCEPTED', 'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT pay_command_receipt_command_valid CHECK (
        (command_type = 'RUN_CALCULATE' AND originating_action = 'PAY_RUN_CALCULATE')
        OR (command_type = 'RUN_APPROVE' AND originating_action = 'PAY_RUN_APPROVE')
        OR (command_type = 'RUN_FINALIZE' AND originating_action = 'PAY_RUN_FINALIZE')),
    CONSTRAINT pay_command_receipt_population_valid CHECK (
        population_kind IN ('LEGAL_ENTITY', 'PAY_GROUP', 'NAMED_POPULATION')),
    CONSTRAINT pay_command_receipt_digest_shape CHECK (
        population_scope_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^[0-9a-f]{64}$'
        AND (step_up_payload_sha256 IS NULL
            OR step_up_payload_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT pay_command_receipt_step_up_consistent CHECK (
        (step_up_challenge_id IS NULL AND step_up_nonce IS NULL
            AND step_up_issuer IS NULL AND step_up_expires_at IS NULL
            AND step_up_payload_sha256 IS NULL AND step_up_decision_revision IS NULL)
        OR (step_up_challenge_id IS NOT NULL AND step_up_nonce IS NOT NULL
            AND step_up_issuer IS NOT NULL AND step_up_expires_at IS NOT NULL
            AND step_up_payload_sha256 IS NOT NULL
            AND step_up_decision_revision IS NOT NULL)),
    CONSTRAINT pay_command_receipt_audit_shape CHECK (
        btrim(audit_type) <> '' AND jsonb_typeof(audit_payload) = 'object')
);

CREATE UNIQUE INDEX pay_command_receipt_idempotency_unique
    ON pay_command_receipts (
        tenant_id, subject_principal_public_id, originating_action, idempotency_key)
    WHERE status_sequence = 1;
CREATE UNIQUE INDEX pay_command_receipt_challenge_unique
    ON pay_command_receipts (tenant_id, step_up_issuer, step_up_challenge_id)
    WHERE status_sequence = 1 AND step_up_challenge_id IS NOT NULL;
CREATE UNIQUE INDEX pay_command_receipt_nonce_unique
    ON pay_command_receipts (tenant_id, step_up_issuer, step_up_nonce)
    WHERE status_sequence = 1 AND step_up_nonce IS NOT NULL;
CREATE INDEX pay_command_receipt_lookup_idx
    ON pay_command_receipts (tenant_id, receipt_id, status_sequence DESC);

CREATE TABLE pay_outbox_events (
    outbox_event_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    run_id UUID NOT NULL,
    attempt_no INTEGER,
    run_revision BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    event_payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_outbox_event_public_unique UNIQUE (tenant_id, event_id),
    CONSTRAINT pay_outbox_event_effect_unique UNIQUE (
        tenant_id, run_id, event_type, run_revision),
    CONSTRAINT pay_outbox_event_sequence_unique UNIQUE (
        tenant_id, run_id, aggregate_sequence),
    CONSTRAINT pay_outbox_event_run_revision_fk FOREIGN KEY (
        tenant_id, run_id, run_revision)
        REFERENCES pay_payroll_runs (tenant_id, run_id, run_revision),
    CONSTRAINT pay_outbox_event_positive CHECK (
        tenant_id > 0 AND run_revision > 0 AND aggregate_sequence > 0),
    CONSTRAINT pay_outbox_event_contract CHECK (
        schema_version = 1 AND event_type IN (
            'PayrollCalculated', 'PayrollRunApproved', 'PayrollFinalized')),
    CONSTRAINT pay_outbox_event_envelope_shape CHECK (
        jsonb_typeof(event_payload) = 'object'
        AND event_payload ?& ARRAY[
            'specVersion', 'id', 'source', 'type', 'schemaVersion', 'time',
            'tenantId', 'aggregateType', 'aggregateId', 'aggregateSequence',
            'correlationId', 'data'])
);

CREATE FUNCTION pay_v50_reject_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v50_immutable$
BEGIN
    RAISE EXCEPTION 'PAY-011 V50 facts are append-only';
END
$pay_v50_immutable$;

CREATE FUNCTION pay_v50_guard_run_revision()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v50_run_revision$
DECLARE
    prior_run pay_payroll_runs%ROWTYPE;
    attempt_head_state TEXT;
    expected_line_count INTEGER;
    frozen_ref_count INTEGER;
    actual_line_count INTEGER;
    actual_worker_count INTEGER;
    line_worker_count INTEGER;
    fact_gross NUMERIC(19,4);
    fact_deductions NUMERIC(19,4);
    fact_net NUMERIC(19,4);
    fact_employer_cost NUMERIC(19,4);
    line_gross NUMERIC(19,4);
    line_deductions NUMERIC(19,4);
    line_employer_cost NUMERIC(19,4);
    attempt_receipt_id UUID;
    receipt_head_status TEXT;
    receipt_head_command TEXT;
    receipt_head_run_id UUID;
    receipt_head_action TEXT;
    receipt_head_actor BIGINT;
    receipt_head_aggregate_version BIGINT;
    receipt_head_challenge_id TEXT;
    receipt_head_nonce TEXT;
    receipt_head_issuer TEXT;
    receipt_head_payload_digest TEXT;
    receipt_head_decision_revision TEXT;
    attempt_engine_version TEXT;
    attempt_snapshot pay_input_snapshots%ROWTYPE;
    attempt_snapshot_ref_count INTEGER;
BEGIN
    SELECT * INTO prior_run
      FROM pay_payroll_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
     ORDER BY run_revision DESC LIMIT 1;

    IF NOT FOUND THEN
        IF NEW.run_revision <> 1 OR NEW.state <> 'PREPARING'
           OR NEW.latest_attempt_no <> 0 OR NEW.worker_count <> 0
           OR NEW.gross_amount <> 0 OR NEW.net_amount <> 0
           OR NEW.result_digest IS NOT NULL
           OR NEW.approval_case_id IS NOT NULL
           OR NEW.decision_digest IS NOT NULL
           OR NEW.approved_by_actor_id IS NOT NULL
           OR NEW.approved_at IS NOT NULL
           OR NEW.audit_bundle_digest IS NOT NULL
           OR NEW.command_receipt_id IS NOT NULL THEN
            RAISE EXCEPTION 'Payroll run must start at PREPARING revision one';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.run_revision <> prior_run.run_revision + 1
       OR ROW(NEW.run_type, NEW.pay_period_id, NEW.source_snapshot_id,
              NEW.source_snapshot_revision, NEW.source_payload_digest,
              NEW.legal_entity_id, NEW.payroll_group_id, NEW.population_kind,
              NEW.population_scope_digest, NEW.maker_actor_id,
              NEW.maker_subject_public_id, NEW.currency)
          IS DISTINCT FROM
          ROW(prior_run.run_type, prior_run.pay_period_id,
              prior_run.source_snapshot_id, prior_run.source_snapshot_revision,
              prior_run.source_payload_digest, prior_run.legal_entity_id,
              prior_run.payroll_group_id, prior_run.population_kind,
              prior_run.population_scope_digest, prior_run.maker_actor_id,
              prior_run.maker_subject_public_id, prior_run.currency)
       OR NEW.latest_attempt_no < prior_run.latest_attempt_no
       OR NEW.latest_attempt_no > prior_run.latest_attempt_no + 1 THEN
        RAISE EXCEPTION 'Payroll run identity, attempt or revision changed illegally';
    END IF;

    IF NOT ((prior_run.state IN ('PREPARING', 'FAILED')
                AND NEW.state = 'READY'
                AND NEW.latest_attempt_no = prior_run.latest_attempt_no + 1)
         OR (prior_run.state = 'READY' AND NEW.state = 'CALCULATING'
                AND NEW.latest_attempt_no = prior_run.latest_attempt_no)
         OR (prior_run.state = 'CALCULATING'
                AND NEW.state IN ('VALIDATED', 'FAILED', 'RESULT_UNKNOWN')
                AND NEW.latest_attempt_no = prior_run.latest_attempt_no)
         OR (prior_run.state = 'RESULT_UNKNOWN' AND NEW.state = 'READY'
                AND NEW.latest_attempt_no = prior_run.latest_attempt_no + 1)
         OR (prior_run.state = 'RESULT_UNKNOWN' AND NEW.state = 'VALIDATED'
                AND NEW.latest_attempt_no = prior_run.latest_attempt_no)
         OR (prior_run.state = 'VALIDATED' AND NEW.state = 'APPROVED'
                AND NEW.latest_attempt_no = prior_run.latest_attempt_no)
         OR (prior_run.state = 'APPROVED' AND NEW.state = 'FINALIZED'
                AND NEW.latest_attempt_no = prior_run.latest_attempt_no)) THEN
        RAISE EXCEPTION 'Illegal payroll run state transition';
    END IF;

    IF NEW.state IN ('READY', 'CALCULATING')
       AND (NEW.command_receipt_id IS NULL OR NEW.worker_count <> 0
            OR NEW.gross_amount <> 0 OR NEW.net_amount <> 0
            OR NEW.result_digest IS NOT NULL) THEN
        RAISE EXCEPTION 'Payroll active calculation state is inconsistent';
    END IF;
    IF NEW.state IN ('READY', 'CALCULATING', 'FAILED', 'RESULT_UNKNOWN')
       AND (NEW.approval_case_id IS NOT NULL OR NEW.decision_digest IS NOT NULL
            OR NEW.approved_by_actor_id IS NOT NULL OR NEW.approved_at IS NOT NULL
            OR NEW.audit_bundle_digest IS NOT NULL) THEN
        RAISE EXCEPTION 'Payroll pre-approval state contains approval evidence';
    END IF;
    IF NEW.state IN ('CALCULATING', 'FAILED', 'RESULT_UNKNOWN')
       AND ROW(NEW.worker_count, NEW.gross_amount, NEW.net_amount,
               NEW.result_digest)
           IS DISTINCT FROM
           ROW(prior_run.worker_count, prior_run.gross_amount,
               prior_run.net_amount, prior_run.result_digest) THEN
        RAISE EXCEPTION 'Payroll non-success transition changed result facts';
    END IF;
    IF NEW.state = 'VALIDATED' AND (
            NEW.command_receipt_id IS NULL OR NEW.worker_count < 1
            OR NEW.result_digest IS NULL
            OR NEW.approval_case_id IS NOT NULL OR NEW.decision_digest IS NOT NULL
            OR NEW.approved_by_actor_id IS NOT NULL OR NEW.approved_at IS NOT NULL
            OR NEW.audit_bundle_digest IS NOT NULL) THEN
        RAISE EXCEPTION 'Payroll validated state is inconsistent';
    END IF;
    IF NEW.state = 'APPROVED' AND (
            NEW.command_receipt_id IS NULL OR NEW.result_digest IS NULL
            OR NEW.approval_case_id IS NULL OR NEW.decision_digest IS NULL
            OR NEW.approved_by_actor_id IS NULL OR NEW.approved_at IS NULL
            OR NEW.audit_bundle_digest IS NOT NULL) THEN
        RAISE EXCEPTION 'Payroll approved state is inconsistent';
    END IF;
    IF NEW.state = 'APPROVED'
       AND ROW(NEW.worker_count, NEW.gross_amount, NEW.net_amount,
               NEW.result_digest)
           IS DISTINCT FROM
           ROW(prior_run.worker_count, prior_run.gross_amount,
               prior_run.net_amount, prior_run.result_digest) THEN
        RAISE EXCEPTION 'Payroll approval changed validated result facts';
    END IF;
    IF NEW.state = 'FINALIZED' AND (
            NEW.command_receipt_id IS NULL OR NEW.result_digest IS NULL
            OR NEW.approval_case_id IS NULL OR NEW.decision_digest IS NULL
            OR NEW.approved_by_actor_id IS NULL OR NEW.approved_at IS NULL
            OR NEW.audit_bundle_digest IS NULL) THEN
        RAISE EXCEPTION 'Payroll finalized state is inconsistent';
    END IF;
    IF NEW.state = 'FINALIZED'
       AND ROW(NEW.worker_count, NEW.gross_amount, NEW.net_amount,
               NEW.result_digest, NEW.approval_case_id, NEW.decision_digest,
               NEW.approved_by_actor_id, NEW.approved_at)
           IS DISTINCT FROM
           ROW(prior_run.worker_count, prior_run.gross_amount,
               prior_run.net_amount, prior_run.result_digest,
               prior_run.approval_case_id, prior_run.decision_digest,
               prior_run.approved_by_actor_id, prior_run.approved_at) THEN
        RAISE EXCEPTION 'Payroll finalization changed approved facts';
    END IF;

    IF NEW.state IN ('READY', 'CALCULATING', 'FAILED',
                     'RESULT_UNKNOWN', 'VALIDATED') THEN
        SELECT * INTO attempt_snapshot
          FROM pay_input_snapshots snapshot
         WHERE snapshot.tenant_id = NEW.tenant_id
           AND snapshot.run_id = NEW.run_id
           AND snapshot.attempt_no = NEW.latest_attempt_no;
        IF NOT FOUND
           OR attempt_snapshot.source_snapshot_id IS DISTINCT FROM
                NEW.source_snapshot_id
           OR attempt_snapshot.source_snapshot_revision IS DISTINCT FROM
                NEW.source_snapshot_revision
           OR attempt_snapshot.source_payload_digest IS DISTINCT FROM
                NEW.source_payload_digest
           OR attempt_snapshot.target_population_digest IS DISTINCT FROM
                NEW.population_scope_digest THEN
            RAISE EXCEPTION 'Payroll run attempt snapshot is unavailable or mismatched';
        END IF;
        SELECT COUNT(*) INTO attempt_snapshot_ref_count
          FROM pay_input_snapshot_compensation_refs ref
         WHERE ref.tenant_id = NEW.tenant_id
           AND ref.input_snapshot_internal_id =
               attempt_snapshot.input_snapshot_internal_id;
        IF attempt_snapshot_ref_count IS DISTINCT FROM
                attempt_snapshot.source_line_count THEN
            RAISE EXCEPTION 'Payroll run attempt snapshot source set is incomplete';
        END IF;
        SELECT attempt_state, command_receipt_id, engine_version
          INTO attempt_head_state, attempt_receipt_id, attempt_engine_version
          FROM pay_payroll_run_attempts
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND attempt_no = NEW.latest_attempt_no
         ORDER BY status_sequence DESC LIMIT 1;
        IF attempt_head_state IS DISTINCT FROM NEW.state
           OR attempt_receipt_id IS DISTINCT FROM NEW.command_receipt_id
           OR attempt_engine_version IS DISTINCT FROM
                attempt_snapshot.engine_version THEN
            RAISE EXCEPTION 'Payroll run attempt head is unavailable or mismatched';
        END IF;
        SELECT receipt_status, command_type, run_id, originating_action,
               actor_id, aggregate_version, step_up_challenge_id,
               step_up_nonce, step_up_issuer, step_up_payload_sha256,
               step_up_decision_revision
          INTO receipt_head_status, receipt_head_command, receipt_head_run_id,
               receipt_head_action, receipt_head_actor,
               receipt_head_aggregate_version, receipt_head_challenge_id,
               receipt_head_nonce, receipt_head_issuer,
               receipt_head_payload_digest, receipt_head_decision_revision
          FROM pay_command_receipts
         WHERE tenant_id = NEW.tenant_id AND receipt_id = NEW.command_receipt_id
         ORDER BY status_sequence DESC LIMIT 1;
        IF receipt_head_run_id IS DISTINCT FROM NEW.run_id
           OR receipt_head_command IS DISTINCT FROM 'RUN_CALCULATE'
           OR receipt_head_status IS DISTINCT FROM (CASE NEW.state
                WHEN 'READY' THEN 'ACCEPTED'
                WHEN 'CALCULATING' THEN 'RUNNING'
                WHEN 'FAILED' THEN 'FAILED'
                WHEN 'RESULT_UNKNOWN' THEN 'RESULT_UNKNOWN'
                WHEN 'VALIDATED' THEN 'SUCCEEDED'
              END) THEN
            RAISE EXCEPTION 'Payroll calculation receipt head is unavailable or mismatched';
        END IF;
    ELSIF NEW.state IN ('APPROVED', 'FINALIZED') THEN
        SELECT receipt_status, command_type, run_id, originating_action,
               actor_id, aggregate_version, step_up_challenge_id,
               step_up_nonce, step_up_issuer, step_up_payload_sha256,
               step_up_decision_revision
          INTO receipt_head_status, receipt_head_command, receipt_head_run_id,
               receipt_head_action, receipt_head_actor,
               receipt_head_aggregate_version, receipt_head_challenge_id,
               receipt_head_nonce, receipt_head_issuer,
               receipt_head_payload_digest, receipt_head_decision_revision
          FROM pay_command_receipts
         WHERE tenant_id = NEW.tenant_id AND receipt_id = NEW.command_receipt_id
         ORDER BY status_sequence DESC LIMIT 1;
        IF receipt_head_run_id IS DISTINCT FROM NEW.run_id
           OR receipt_head_status IS DISTINCT FROM 'SUCCEEDED'
           OR receipt_head_command IS DISTINCT FROM (CASE NEW.state
                WHEN 'APPROVED' THEN 'RUN_APPROVE'
                WHEN 'FINALIZED' THEN 'RUN_FINALIZE'
              END)
           OR receipt_head_action IS DISTINCT FROM (CASE NEW.state
                WHEN 'APPROVED' THEN 'PAY_RUN_APPROVE'
                WHEN 'FINALIZED' THEN 'PAY_RUN_FINALIZE'
              END)
           OR receipt_head_aggregate_version IS DISTINCT FROM NEW.run_revision
           OR receipt_head_challenge_id IS NULL
           OR receipt_head_nonce IS NULL
           OR receipt_head_issuer IS NULL
           OR receipt_head_payload_digest IS NULL
           OR receipt_head_decision_revision IS NULL
           OR (NEW.state = 'APPROVED'
               AND NEW.approved_by_actor_id IS DISTINCT FROM receipt_head_actor) THEN
            RAISE EXCEPTION 'Payroll high-risk receipt head is unavailable or mismatched';
        END IF;
        IF receipt_head_actor = NEW.maker_actor_id THEN
            RAISE EXCEPTION 'Payroll run maker cannot approve or finalize';
        END IF;
    END IF;

    IF NEW.state = 'VALIDATED' THEN
        SELECT source_line_count INTO expected_line_count
          FROM pay_input_snapshots
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND attempt_no = NEW.latest_attempt_no;
        SELECT COUNT(*), COUNT(DISTINCT worker_result_id),
               COALESCE(SUM(amount) FILTER (WHERE line_category = 'EARNING'), 0),
               COALESCE(SUM(amount) FILTER (WHERE line_category = 'DEDUCTION'), 0),
               COALESCE(SUM(amount) FILTER (
                   WHERE line_category = 'EMPLOYER_COST'), 0)
          INTO actual_line_count, line_worker_count, line_gross,
               line_deductions, line_employer_cost
          FROM pay_result_lines
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND attempt_no = NEW.latest_attempt_no;
        SELECT COUNT(*) INTO frozen_ref_count
          FROM pay_input_snapshot_compensation_refs ref
          JOIN pay_input_snapshots snapshot
            ON snapshot.tenant_id = ref.tenant_id
           AND snapshot.input_snapshot_internal_id = ref.input_snapshot_internal_id
         WHERE snapshot.tenant_id = NEW.tenant_id
           AND snapshot.run_id = NEW.run_id
           AND snapshot.attempt_no = NEW.latest_attempt_no;
        SELECT COUNT(*), COALESCE(SUM(gross_amount), 0),
               COALESCE(SUM(deduction_amount), 0),
               COALESCE(SUM(net_amount), 0),
               COALESCE(SUM(employer_cost_amount), 0)
          INTO actual_worker_count, fact_gross, fact_deductions, fact_net,
               fact_employer_cost
          FROM pay_worker_results
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND attempt_no = NEW.latest_attempt_no;
        SELECT attempt_state INTO attempt_head_state
          FROM pay_payroll_run_attempts
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND attempt_no = NEW.latest_attempt_no
         ORDER BY status_sequence DESC LIMIT 1;
        IF expected_line_count IS NULL
           OR actual_line_count IS DISTINCT FROM expected_line_count
           OR frozen_ref_count IS DISTINCT FROM expected_line_count
           OR actual_worker_count IS DISTINCT FROM NEW.worker_count
           OR line_worker_count IS DISTINCT FROM actual_worker_count
           OR fact_gross IS DISTINCT FROM NEW.gross_amount
           OR fact_net IS DISTINCT FROM NEW.net_amount
           OR fact_gross IS DISTINCT FROM line_gross
           OR fact_deductions IS DISTINCT FROM line_deductions
           OR fact_employer_cost IS DISTINCT FROM line_employer_cost
           OR EXISTS (
                SELECT 1 FROM pay_worker_results worker
                 WHERE worker.tenant_id = NEW.tenant_id
                   AND worker.run_id = NEW.run_id
                   AND worker.attempt_no = NEW.latest_attempt_no
                   AND worker.currency IS DISTINCT FROM NEW.currency)
           OR EXISTS (
                SELECT 1 FROM pay_result_lines line
                 WHERE line.tenant_id = NEW.tenant_id
                   AND line.run_id = NEW.run_id
                   AND line.attempt_no = NEW.latest_attempt_no
                   AND line.currency IS DISTINCT FROM NEW.currency)
           OR attempt_head_state IS DISTINCT FROM 'VALIDATED'
           OR EXISTS (
                SELECT 1
                  FROM pay_result_lines line
                  JOIN pay_input_snapshots snapshot
                    ON snapshot.tenant_id = line.tenant_id
                   AND snapshot.input_snapshot_internal_id =
                       line.input_snapshot_internal_id
                   AND snapshot.run_id = line.run_id
                   AND snapshot.attempt_no = line.attempt_no
                  JOIN pay_input_snapshot_compensation_refs ref
                    ON ref.tenant_id = line.tenant_id
                   AND ref.input_snapshot_internal_id =
                       line.input_snapshot_internal_id
                   AND ref.compensation_plan_input_id =
                       line.compensation_plan_input_id
                 WHERE line.tenant_id = NEW.tenant_id
                   AND line.run_id = NEW.run_id
                   AND line.attempt_no = NEW.latest_attempt_no
                   AND (line.worker_id IS DISTINCT FROM ref.worker_id
                        OR line.component_code IS DISTINCT FROM ref.component_code
                        OR line.source_payload_digest IS DISTINCT FROM
                            ref.source_payload_digest
                        OR line.currency IS DISTINCT FROM ref.currency
                        OR line.semantic_binding_digest IS DISTINCT FROM
                            ref.semantic_binding_digest
                        OR line.element_id::TEXT IS DISTINCT FROM
                            ref.semantic_binding ->> 'elementId'
                        OR line.element_version IS DISTINCT FROM
                            (ref.semantic_binding ->> 'elementVersion')::BIGINT
                        OR line.line_category IS DISTINCT FROM
                            ref.semantic_binding ->> 'category'
                        OR line.formula_id::TEXT IS DISTINCT FROM
                            ref.semantic_binding -> 'quantizationPolicies'
                                -> (jsonb_array_length(
                                    ref.semantic_binding ->
                                        'quantizationPolicies') - 1)
                                -> 'formula' ->> 'formulaId'
                        OR line.formula_version IS DISTINCT FROM
                            (ref.semantic_binding -> 'quantizationPolicies'
                                -> (jsonb_array_length(
                                    ref.semantic_binding ->
                                        'quantizationPolicies') - 1)
                                -> 'formula' ->> 'version')::BIGINT
                        OR line.formula_ast_digest IS DISTINCT FROM
                            ref.semantic_binding -> 'quantizationPolicies'
                                -> (jsonb_array_length(
                                    ref.semantic_binding ->
                                        'quantizationPolicies') - 1)
                                -> 'formula' ->> 'astDigest'))
           OR EXISTS (
                SELECT 1
                  FROM pay_calculation_trace_nodes trace
                  JOIN pay_input_snapshot_compensation_refs ref
                    ON ref.tenant_id = trace.tenant_id
                   AND ref.input_snapshot_internal_id =
                       trace.input_snapshot_internal_id
                   AND ref.compensation_plan_input_id =
                       trace.compensation_plan_input_id
                  CROSS JOIN LATERAL (
                    SELECT ref.semantic_binding -> 'quantizationPolicies'
                        -> (trace.trace_sequence - 1) AS policy
                  ) binding
                 WHERE trace.tenant_id = NEW.tenant_id
                   AND trace.run_id = NEW.run_id
                   AND trace.attempt_no = NEW.latest_attempt_no
                   AND (binding.policy IS NULL
                        OR trace.worker_id IS DISTINCT FROM ref.worker_id
                        OR trace.source_payload_digest IS DISTINCT FROM
                            ref.source_payload_digest
                        OR trace.currency IS DISTINCT FROM ref.currency
                        OR trace.semantic_binding_digest IS DISTINCT FROM
                            ref.semantic_binding_digest
                        OR trace.element_id::TEXT IS DISTINCT FROM
                            ref.semantic_binding ->> 'elementId'
                        OR trace.element_version IS DISTINCT FROM
                            (ref.semantic_binding ->> 'elementVersion')::BIGINT
                        OR trace.line_category IS DISTINCT FROM
                            ref.semantic_binding ->> 'category'
                        OR trace.rounding_stage IS DISTINCT FROM
                            binding.policy ->> 'stage'
                        OR trace.rounding_scale IS DISTINCT FROM
                            (binding.policy ->> 'scale')::INTEGER
                        OR trace.rounding_mode IS DISTINCT FROM
                            binding.policy ->> 'mode'
                        OR trace.rounding_increment IS DISTINCT FROM
                            (binding.policy ->> 'increment')::NUMERIC
                        OR trace.rounding_policy_digest IS DISTINCT FROM
                            binding.policy ->> 'policyDigest'
                        OR ((trace.formula_id IS NULL) IS DISTINCT FROM
                            ((binding.policy -> 'formula') IS NULL
                             OR binding.policy -> 'formula' = 'null'::JSONB))
                        OR (trace.formula_id IS NOT NULL AND (
                            trace.formula_id::TEXT IS DISTINCT FROM
                                binding.policy -> 'formula' ->> 'formulaId'
                            OR trace.formula_version IS DISTINCT FROM
                                (binding.policy -> 'formula' ->> 'version')::BIGINT
                            OR trace.formula_ast_digest IS DISTINCT FROM
                                binding.policy -> 'formula' ->> 'astDigest'))))
           OR EXISTS (
                SELECT 1 FROM pay_worker_results worker
                 WHERE worker.tenant_id = NEW.tenant_id
                   AND worker.run_id = NEW.run_id
                   AND worker.attempt_no = NEW.latest_attempt_no
                   AND worker.calculation_result_digest IS DISTINCT FROM
                        NEW.result_digest)
           OR EXISTS (
                SELECT 1
                  FROM pay_worker_results worker
                  LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS line_count,
                           COALESCE(SUM(amount) FILTER (
                               WHERE line_category = 'EARNING'), 0) AS gross,
                           COALESCE(SUM(amount) FILTER (
                               WHERE line_category = 'DEDUCTION'), 0) AS deductions,
                           COALESCE(SUM(amount) FILTER (
                               WHERE line_category = 'EMPLOYER_COST'), 0) AS employer
                      FROM pay_result_lines line
                     WHERE line.tenant_id = worker.tenant_id
                       AND line.run_id = worker.run_id
                       AND line.attempt_no = worker.attempt_no
                       AND line.worker_result_id = worker.worker_result_id
                       AND line.worker_id = worker.worker_id
                  ) facts ON TRUE
                 WHERE worker.tenant_id = NEW.tenant_id
                   AND worker.run_id = NEW.run_id
                   AND worker.attempt_no = NEW.latest_attempt_no
                   AND (worker.line_count IS DISTINCT FROM facts.line_count
                        OR worker.gross_amount IS DISTINCT FROM facts.gross
                        OR worker.deduction_amount IS DISTINCT FROM facts.deductions
                        OR worker.employer_cost_amount IS DISTINCT FROM facts.employer
                        OR worker.net_amount IS DISTINCT FROM
                            facts.gross - facts.deductions))
           OR EXISTS (
                SELECT 1
                  FROM pay_calculation_trace_nodes trace
                  LEFT JOIN pay_calculation_trace_nodes parent
                    ON parent.tenant_id = trace.tenant_id
                   AND parent.run_id = trace.run_id
                   AND parent.attempt_no = trace.attempt_no
                   AND parent.input_snapshot_internal_id =
                       trace.input_snapshot_internal_id
                   AND parent.compensation_plan_input_id =
                       trace.compensation_plan_input_id
                   AND parent.trace_id = trace.parent_trace_id
                   AND parent.trace_sequence = trace.trace_sequence - 1
                 WHERE trace.tenant_id = NEW.tenant_id
                   AND trace.run_id = NEW.run_id
                   AND trace.attempt_no = NEW.latest_attempt_no
                   AND ((trace.trace_sequence = 1
                            AND trace.parent_trace_id IS NOT NULL)
                        OR (trace.trace_sequence > 1 AND parent.trace_id IS NULL)
                        OR (trace.trace_sequence > 1
                            AND parent.formula_output_digest IS DISTINCT FROM
                                trace.formula_input_digest)))
           OR EXISTS (
                SELECT 1
                  FROM pay_input_snapshot_compensation_refs ref
                  JOIN pay_input_snapshots snapshot
                    ON snapshot.tenant_id = ref.tenant_id
                   AND snapshot.input_snapshot_internal_id =
                       ref.input_snapshot_internal_id
                  LEFT JOIN pay_calculation_trace_nodes trace
                    ON trace.tenant_id = ref.tenant_id
                   AND trace.input_snapshot_internal_id =
                       ref.input_snapshot_internal_id
                   AND trace.compensation_plan_input_id =
                       ref.compensation_plan_input_id
                 WHERE snapshot.tenant_id = NEW.tenant_id
                   AND snapshot.run_id = NEW.run_id
                   AND snapshot.attempt_no = NEW.latest_attempt_no
                 GROUP BY ref.compensation_ref_internal_id,
                          ref.semantic_binding
                HAVING COUNT(trace.trace_id) IS DISTINCT FROM
                       jsonb_array_length(
                           ref.semantic_binding -> 'quantizationPolicies')
                    OR MAX(trace.trace_sequence) IS DISTINCT FROM
                       jsonb_array_length(
                           ref.semantic_binding -> 'quantizationPolicies'))
           OR EXISTS (
                SELECT 1
                  FROM pay_result_lines line
                  JOIN pay_calculation_trace_nodes terminal
                    ON terminal.tenant_id = line.tenant_id
                   AND terminal.run_id = line.run_id
                   AND terminal.attempt_no = line.attempt_no
                   AND terminal.input_snapshot_internal_id =
                       line.input_snapshot_internal_id
                   AND terminal.compensation_plan_input_id =
                       line.compensation_plan_input_id
                   AND terminal.trace_id = line.terminal_trace_id
                 WHERE line.tenant_id = NEW.tenant_id
                   AND line.run_id = NEW.run_id
                   AND line.attempt_no = NEW.latest_attempt_no
                   AND (terminal.trace_sequence IS DISTINCT FROM (
                        SELECT MAX(all_trace.trace_sequence)
                          FROM pay_calculation_trace_nodes all_trace
                         WHERE all_trace.tenant_id = line.tenant_id
                           AND all_trace.run_id = line.run_id
                           AND all_trace.attempt_no = line.attempt_no
                           AND all_trace.compensation_plan_input_id =
                               line.compensation_plan_input_id)
                        OR line.worker_id IS DISTINCT FROM terminal.worker_id
                        OR line.semantic_binding_digest IS DISTINCT FROM
                            terminal.semantic_binding_digest
                        OR line.source_payload_digest IS DISTINCT FROM
                            terminal.source_payload_digest
                        OR line.element_id IS DISTINCT FROM terminal.element_id
                        OR line.element_version IS DISTINCT FROM
                            terminal.element_version
                        OR line.line_category IS DISTINCT FROM
                            terminal.line_category
                        OR line.currency IS DISTINCT FROM terminal.currency
                        OR line.formula_id IS DISTINCT FROM terminal.formula_id
                        OR line.formula_version IS DISTINCT FROM
                            terminal.formula_version
                        OR line.formula_ast_digest IS DISTINCT FROM
                            terminal.formula_ast_digest
                        OR line.formula_input_digest IS DISTINCT FROM
                            terminal.formula_input_digest
                        OR line.formula_output_digest IS DISTINCT FROM
                            terminal.formula_output_digest
                        OR line.amount IS DISTINCT FROM
                            terminal.rounded_output_amount)) THEN
            RAISE EXCEPTION 'Payroll validated facts do not reconcile';
        END IF;
    END IF;
    RETURN NEW;
END
$pay_v50_run_revision$;

CREATE FUNCTION pay_v50_guard_attempt_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v50_attempt_transition$
DECLARE
    previous pay_payroll_run_attempts%ROWTYPE;
    current_run pay_payroll_runs%ROWTYPE;
    predecessor_state TEXT;
BEGIN
    SELECT * INTO previous
      FROM pay_payroll_run_attempts
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
       AND attempt_no = NEW.attempt_no
     ORDER BY status_sequence DESC LIMIT 1;
    IF NOT FOUND THEN
        SELECT * INTO current_run
          FROM pay_payroll_runs
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
         ORDER BY run_revision DESC LIMIT 1;
        IF NOT FOUND
           OR NEW.status_sequence <> 1 OR NEW.attempt_state <> 'READY'
           OR NEW.attempt_no <> current_run.latest_attempt_no + 1
           OR (NEW.attempt_no = 1 AND NEW.predecessor_attempt_no IS NOT NULL)
           OR (NEW.attempt_no > 1
               AND NEW.predecessor_attempt_no IS DISTINCT FROM NEW.attempt_no - 1) THEN
            RAISE EXCEPTION 'Payroll attempt must start once in READY';
        END IF;
        IF NEW.predecessor_attempt_no IS NOT NULL THEN
            SELECT attempt_state INTO predecessor_state
              FROM pay_payroll_run_attempts
             WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
               AND attempt_no = NEW.predecessor_attempt_no
             ORDER BY status_sequence DESC LIMIT 1;
            IF predecessor_state IS NULL
               OR predecessor_state NOT IN ('FAILED', 'SUPERSEDED') THEN
                RAISE EXCEPTION 'Payroll attempt predecessor is not terminal';
            END IF;
        END IF;
    ELSE
        IF NEW.status_sequence <> previous.status_sequence + 1
           OR NEW.attempt_id IS DISTINCT FROM previous.attempt_id
           OR NEW.predecessor_attempt_no IS DISTINCT FROM previous.predecessor_attempt_no
           OR NEW.engine_version IS DISTINCT FROM previous.engine_version
           OR NEW.command_receipt_id IS DISTINCT FROM previous.command_receipt_id THEN
            RAISE EXCEPTION 'Payroll attempt identity or transition sequence changed';
        END IF;
        IF NOT ((previous.attempt_state = 'READY' AND NEW.attempt_state = 'CALCULATING')
             OR (previous.attempt_state = 'CALCULATING'
                 AND NEW.attempt_state IN ('VALIDATED', 'FAILED', 'RESULT_UNKNOWN'))
             OR (previous.attempt_state = 'RESULT_UNKNOWN'
                 AND NEW.attempt_state IN ('VALIDATED', 'SUPERSEDED'))) THEN
            RAISE EXCEPTION 'Illegal payroll attempt transition';
        END IF;
    END IF;
    RETURN NEW;
END
$pay_v50_attempt_transition$;

CREATE FUNCTION pay_v50_guard_snapshot_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v50_snapshot_insert$
DECLARE
    current_run pay_payroll_runs%ROWTYPE;
BEGIN
    SELECT * INTO current_run
      FROM pay_payroll_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
     ORDER BY run_revision DESC LIMIT 1;
    IF NOT FOUND OR current_run.state NOT IN (
            'PREPARING', 'FAILED', 'RESULT_UNKNOWN')
       OR NEW.attempt_no <> current_run.latest_attempt_no + 1
       OR NEW.source_snapshot_id IS DISTINCT FROM current_run.source_snapshot_id
       OR NEW.source_snapshot_revision IS DISTINCT FROM
            current_run.source_snapshot_revision
       OR NEW.source_payload_digest IS DISTINCT FROM
            current_run.source_payload_digest
       OR NEW.target_population_digest IS DISTINCT FROM
            current_run.population_scope_digest
       OR EXISTS (
            SELECT 1 FROM pay_payroll_run_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.run_id = NEW.run_id
               AND attempt.attempt_no = NEW.attempt_no) THEN
        RAISE EXCEPTION 'Payroll input snapshot is outside the open freeze boundary';
    END IF;
    RETURN NEW;
END
$pay_v50_snapshot_insert$;

CREATE FUNCTION pay_v50_guard_snapshot_ref_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v50_snapshot_ref_insert$
DECLARE
    snapshot pay_input_snapshots%ROWTYPE;
    current_run pay_payroll_runs%ROWTYPE;
BEGIN
    SELECT * INTO snapshot
      FROM pay_input_snapshots
     WHERE tenant_id = NEW.tenant_id
       AND input_snapshot_internal_id = NEW.input_snapshot_internal_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Payroll input snapshot reference has no snapshot';
    END IF;
    SELECT * INTO current_run
      FROM pay_payroll_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = snapshot.run_id
     ORDER BY run_revision DESC LIMIT 1;
    IF NOT FOUND OR current_run.state NOT IN (
            'PREPARING', 'FAILED', 'RESULT_UNKNOWN')
       OR snapshot.attempt_no <> current_run.latest_attempt_no + 1
       OR EXISTS (
            SELECT 1 FROM pay_payroll_run_attempts attempt
             WHERE attempt.tenant_id = NEW.tenant_id
               AND attempt.run_id = snapshot.run_id
               AND attempt.attempt_no = snapshot.attempt_no) THEN
        RAISE EXCEPTION 'Payroll input snapshot references are sealed';
    END IF;
    RETURN NEW;
END
$pay_v50_snapshot_ref_insert$;

CREATE FUNCTION pay_v50_guard_worker_record()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v50_worker_record$
DECLARE
    latest_claim INTEGER;
    latest_token UUID;
    latest_worker_instance_id VARCHAR(160);
    latest_claimed_at TIMESTAMPTZ;
    latest_expiry TIMESTAMPTZ;
    latest_checkpoint INTEGER;
    expected_attempt_id UUID;
    current_run_state TEXT;
    current_run_attempt INTEGER;
    current_attempt_state TEXT;
BEGIN
    SELECT claim_no, lease_token, worker_instance_id, claimed_at, expires_at
      INTO latest_claim, latest_token, latest_worker_instance_id,
           latest_claimed_at, latest_expiry
      FROM pay_payroll_run_workers
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
       AND attempt_no = NEW.attempt_no
       AND record_kind IN ('LEASE', 'RENEWAL')
     ORDER BY claim_no DESC, run_worker_record_internal_id DESC LIMIT 1;
    SELECT attempt_id INTO expected_attempt_id
      FROM pay_payroll_run_attempts
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
       AND attempt_no = NEW.attempt_no
     ORDER BY status_sequence DESC LIMIT 1;
    SELECT state, latest_attempt_no INTO current_run_state, current_run_attempt
      FROM pay_payroll_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
     ORDER BY run_revision DESC LIMIT 1;
    SELECT attempt_state INTO current_attempt_state
      FROM pay_payroll_run_attempts
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
       AND attempt_no = NEW.attempt_no
     ORDER BY status_sequence DESC LIMIT 1;
    IF expected_attempt_id IS NULL
       OR NEW.attempt_id IS DISTINCT FROM expected_attempt_id THEN
        RAISE EXCEPTION 'Payroll worker attempt identity is stale';
    END IF;
    IF NEW.record_kind = 'LEASE' THEN
        IF current_run_state NOT IN ('READY', 'CALCULATING')
           OR current_run_attempt IS DISTINCT FROM NEW.attempt_no
           OR current_attempt_state NOT IN ('READY', 'CALCULATING')
           OR NEW.claim_no <> COALESCE(latest_claim, 0) + 1
           OR (latest_expiry IS NOT NULL AND latest_expiry > NEW.claimed_at) THEN
            RAISE EXCEPTION 'Payroll worker lease is not the next expired fenced claim';
        END IF;
    ELSIF NEW.record_kind = 'RENEWAL' THEN
        IF current_run_state IS DISTINCT FROM 'CALCULATING'
           OR current_run_attempt IS DISTINCT FROM NEW.attempt_no
           OR current_attempt_state IS DISTINCT FROM 'CALCULATING'
           OR NEW.claim_no IS DISTINCT FROM latest_claim
           OR NEW.lease_token IS DISTINCT FROM latest_token
           OR NEW.worker_instance_id IS DISTINCT FROM latest_worker_instance_id
           OR NEW.claimed_at IS DISTINCT FROM latest_claimed_at
           OR latest_expiry <= NEW.recorded_at
           OR NEW.expires_at <= latest_expiry THEN
            RAISE EXCEPTION 'Payroll worker lease renewal is stale or invalid';
        END IF;
    ELSE
        SELECT COALESCE(MAX(checkpoint_no), 0) INTO latest_checkpoint
          FROM pay_payroll_run_workers
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND attempt_no = NEW.attempt_no AND record_kind = 'CHECKPOINT';
        IF current_run_state IS DISTINCT FROM 'CALCULATING'
           OR current_run_attempt IS DISTINCT FROM NEW.attempt_no
           OR current_attempt_state IS DISTINCT FROM 'CALCULATING'
           OR NEW.claim_no IS DISTINCT FROM latest_claim
           OR NEW.lease_token IS DISTINCT FROM latest_token
           OR NEW.worker_instance_id IS DISTINCT FROM latest_worker_instance_id
           OR NEW.claimed_at IS DISTINCT FROM latest_claimed_at
           OR NEW.expires_at IS DISTINCT FROM latest_expiry
           OR NEW.expires_at <= NEW.recorded_at
           OR NEW.checkpoint_no <> latest_checkpoint + 1 THEN
            RAISE EXCEPTION 'Payroll checkpoint lease is stale or out of sequence';
        END IF;
    END IF;
    RETURN NEW;
END
$pay_v50_worker_record$;

CREATE FUNCTION pay_v50_guard_receipt_record()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v50_receipt_record$
DECLARE
    origin pay_command_receipts%ROWTYPE;
    latest pay_command_receipts%ROWTYPE;
BEGIN
    SELECT * INTO origin FROM pay_command_receipts
     WHERE tenant_id = NEW.tenant_id AND receipt_id = NEW.receipt_id
     ORDER BY status_sequence LIMIT 1;
    IF NOT FOUND THEN
        IF NEW.status_sequence <> 1
           OR (NEW.command_type = 'RUN_CALCULATE'
               AND NEW.receipt_status <> 'ACCEPTED')
           OR (NEW.command_type IN ('RUN_APPROVE', 'RUN_FINALIZE')
               AND NEW.receipt_status <> 'SUCCEEDED') THEN
            RAISE EXCEPTION 'Payroll receipt must start at sequence one';
        END IF;
    ELSE
        SELECT * INTO latest FROM pay_command_receipts
         WHERE tenant_id = NEW.tenant_id AND receipt_id = NEW.receipt_id
         ORDER BY status_sequence DESC LIMIT 1;
        IF NEW.status_sequence <> latest.status_sequence + 1
           OR ROW(NEW.idempotency_key, NEW.command_type, NEW.originating_action,
                  NEW.subject_principal_public_id, NEW.actor_id, NEW.run_id,
                  NEW.population_kind, NEW.population_scope_digest,
                  NEW.context_scope_key, NEW.field_policy_revision,
                  NEW.purpose_code, NEW.authorization_revision,
                  NEW.correlation_id, NEW.request_digest, NEW.aggregate_version,
                  NEW.result_ref, NEW.step_up_challenge_id, NEW.step_up_nonce,
                  NEW.step_up_issuer, NEW.step_up_expires_at,
                  NEW.step_up_payload_sha256, NEW.step_up_decision_revision,
                  NEW.created_at)
              IS DISTINCT FROM
              ROW(origin.idempotency_key, origin.command_type,
                  origin.originating_action, origin.subject_principal_public_id,
                  origin.actor_id, origin.run_id, origin.population_kind,
                  origin.population_scope_digest, origin.context_scope_key,
                  origin.field_policy_revision, origin.purpose_code,
                  origin.authorization_revision, origin.correlation_id,
                  origin.request_digest, origin.aggregate_version, origin.result_ref,
                  origin.step_up_challenge_id, origin.step_up_nonce,
                  origin.step_up_issuer, origin.step_up_expires_at,
                  origin.step_up_payload_sha256, origin.step_up_decision_revision,
                  origin.created_at) THEN
            RAISE EXCEPTION 'Payroll receipt sealed context changed';
        END IF;
        IF NEW.receipt_status <> latest.receipt_status
           AND NOT (origin.command_type = 'RUN_CALCULATE' AND (
                (latest.receipt_status = 'ACCEPTED'
                    AND NEW.receipt_status = 'RUNNING')
                OR (latest.receipt_status = 'RUNNING'
                    AND NEW.receipt_status IN (
                        'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN'))
                OR (latest.receipt_status = 'RESULT_UNKNOWN'
                    AND NEW.receipt_status IN ('SUCCEEDED', 'ACCEPTED')))) THEN
            RAISE EXCEPTION 'Illegal Payroll receipt status transition';
        END IF;
    END IF;
    RETURN NEW;
END
$pay_v50_receipt_record$;

-- Calculation facts may be appended only by the current, unfenced worker while
-- the aggregate and logical attempt are both CALCULATING. Once a terminal run
-- revision is present the monetary facts and traces are sealed permanently.
CREATE FUNCTION pay_v50_guard_calculation_fact_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v50_calculation_fact_insert$
DECLARE
    run_state TEXT;
    run_attempt INTEGER;
    attempt_state TEXT;
    lease_expiry TIMESTAMPTZ;
BEGIN
    SELECT state, latest_attempt_no INTO run_state, run_attempt
      FROM pay_payroll_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
     ORDER BY run_revision DESC LIMIT 1;
    SELECT current_attempt.attempt_state INTO attempt_state
      FROM pay_payroll_run_attempts current_attempt
     WHERE current_attempt.tenant_id = NEW.tenant_id
       AND current_attempt.run_id = NEW.run_id
       AND current_attempt.attempt_no = NEW.attempt_no
     ORDER BY current_attempt.status_sequence DESC LIMIT 1;
    SELECT worker.expires_at INTO lease_expiry
      FROM pay_payroll_run_workers worker
     WHERE worker.tenant_id = NEW.tenant_id
       AND worker.run_id = NEW.run_id
       AND worker.attempt_no = NEW.attempt_no
       AND worker.record_kind IN ('LEASE', 'RENEWAL')
     ORDER BY worker.claim_no DESC,
              worker.run_worker_record_internal_id DESC LIMIT 1;
    IF run_state IS DISTINCT FROM 'CALCULATING'
       OR run_attempt IS DISTINCT FROM NEW.attempt_no
       OR attempt_state IS DISTINCT FROM 'CALCULATING'
       OR lease_expiry IS NULL OR lease_expiry <= clock_timestamp() THEN
        RAISE EXCEPTION 'Payroll calculation facts are sealed or unfenced';
    END IF;
    RETURN NEW;
END
$pay_v50_calculation_fact_insert$;

CREATE FUNCTION pay_v50_guard_outbox_event()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v50_outbox_event$
DECLARE
    run_fact pay_payroll_runs%ROWTYPE;
    receipt_head pay_command_receipts%ROWTYPE;
    data_payload JSONB;
    worker_count INTEGER;
    fact_gross NUMERIC(19,4);
    fact_deductions NUMERIC(19,4);
    fact_net NUMERIC(19,4);
    fact_employer_cost NUMERIC(19,4);
    fact_currency TEXT;
    currency_count INTEGER;
    expected_control_totals JSONB;
BEGIN
    IF jsonb_typeof(NEW.event_payload) <> 'object'
       OR NOT (NEW.event_payload ?& ARRAY[
            'specVersion', 'id', 'source', 'type', 'schemaVersion', 'time',
            'tenantId', 'aggregateType', 'aggregateId', 'aggregateSequence',
            'correlationId', 'data'])
       OR NEW.event_payload - ARRAY[
            'specVersion', 'id', 'source', 'type', 'schemaVersion', 'time',
            'tenantId', 'aggregateType', 'aggregateId', 'aggregateSequence',
            'correlationId', 'data']::TEXT[] <> '{}'::JSONB
       OR NEW.event_payload ->> 'specVersion' <> '1.0'
       OR NEW.event_payload ->> 'id' <> NEW.event_id::TEXT
       OR NEW.event_payload ->> 'source' <> 'urn:dwp:payroll:calculation'
       OR NEW.event_payload ->> 'type' <> NEW.event_type || '.v1'
       OR (NEW.event_payload ->> 'schemaVersion')::INTEGER <> NEW.schema_version
       OR (NEW.event_payload ->> 'tenantId')::BIGINT <> NEW.tenant_id
       OR NEW.event_payload ->> 'aggregateType' <> 'PAYROLL_RUN'
       OR NEW.event_payload ->> 'aggregateId' <> NEW.run_id::TEXT
       OR (NEW.event_payload ->> 'aggregateSequence')::BIGINT <>
            NEW.aggregate_sequence
       OR NEW.event_payload ->> 'correlationId' <> NEW.correlation_id::TEXT
       OR (NEW.event_payload ->> 'time')::TIMESTAMPTZ IS DISTINCT FROM
            NEW.occurred_at
       OR NEW.aggregate_sequence <> NEW.run_revision
       OR jsonb_typeof(NEW.event_payload -> 'data') <> 'object' THEN
        RAISE EXCEPTION 'Payroll outbox envelope is not exact';
    END IF;
    data_payload := NEW.event_payload -> 'data';

    SELECT * INTO run_fact
      FROM pay_payroll_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
       AND run_revision = NEW.run_revision;
    IF NOT FOUND OR run_fact.command_receipt_id IS DISTINCT FROM NEW.receipt_id THEN
        RAISE EXCEPTION 'Payroll outbox run revision is unavailable or mismatched';
    END IF;
    SELECT * INTO receipt_head
      FROM pay_command_receipts
     WHERE tenant_id = NEW.tenant_id AND receipt_id = NEW.receipt_id
     ORDER BY status_sequence DESC LIMIT 1;
    IF NOT FOUND OR receipt_head.receipt_status <> 'SUCCEEDED'
       OR receipt_head.run_id IS DISTINCT FROM NEW.run_id
       OR receipt_head.correlation_id IS DISTINCT FROM NEW.correlation_id
       OR receipt_head.result_ref IS DISTINCT FROM
            'payroll-run:' || NEW.run_id::TEXT THEN
        RAISE EXCEPTION 'Payroll outbox receipt proof is unavailable or mismatched';
    END IF;

    IF NEW.event_type = 'PayrollCalculated' THEN
        IF run_fact.state <> 'VALIDATED' OR NEW.attempt_no IS NULL
           OR receipt_head.command_type <> 'RUN_CALCULATE'
           OR receipt_head.originating_action <> 'PAY_RUN_CALCULATE'
           OR receipt_head.audit_type <> 'PAYROLL_CALCULATION_COMPLETED'
           OR NOT (receipt_head.audit_payload ? 'runRevision')
           OR (receipt_head.audit_payload ->> 'runRevision')::BIGINT <>
                NEW.run_revision
           OR NEW.attempt_no <> run_fact.latest_attempt_no
           OR NOT EXISTS (
                SELECT 1 FROM pay_payroll_run_attempts attempt
                 WHERE attempt.tenant_id = NEW.tenant_id
                   AND attempt.run_id = NEW.run_id
                   AND attempt.attempt_no = NEW.attempt_no
                   AND attempt.status_sequence = 1
                   AND attempt.command_receipt_id = NEW.receipt_id)
           OR NOT EXISTS (
                SELECT 1 FROM pay_payroll_runs accepted
                 WHERE accepted.tenant_id = NEW.tenant_id
                   AND accepted.run_id = NEW.run_id
                   AND accepted.run_revision = receipt_head.aggregate_version
                   AND accepted.state = 'READY'
                   AND accepted.command_receipt_id = NEW.receipt_id)
           OR NOT EXISTS (
                SELECT 1 FROM pay_payroll_runs attempt_ready
                 WHERE attempt_ready.tenant_id = NEW.tenant_id
                   AND attempt_ready.run_id = NEW.run_id
                   AND attempt_ready.state = 'READY'
                   AND attempt_ready.latest_attempt_no = NEW.attempt_no
                   AND attempt_ready.command_receipt_id = NEW.receipt_id)
           OR NOT (data_payload ?& ARRAY[
                'runId', 'attemptNo', 'workerCount', 'resultDigest',
                'controlTotals'])
           OR data_payload - ARRAY[
                'runId', 'attemptNo', 'workerCount', 'resultDigest',
                'controlTotals']::TEXT[] <> '{}'::JSONB
           OR data_payload ->> 'runId' <> NEW.run_id::TEXT
           OR (data_payload ->> 'attemptNo')::INTEGER <> NEW.attempt_no
           OR (data_payload ->> 'workerCount')::INTEGER <> run_fact.worker_count
           OR data_payload ->> 'resultDigest' <> run_fact.result_digest THEN
            RAISE EXCEPTION 'PayrollCalculated payload is not exact';
        END IF;
        SELECT COUNT(*), COALESCE(SUM(gross_amount), 0),
               COALESCE(SUM(deduction_amount), 0),
               COALESCE(SUM(net_amount), 0),
               COALESCE(SUM(employer_cost_amount), 0),
               MIN(currency), COUNT(DISTINCT currency)
          INTO worker_count, fact_gross, fact_deductions, fact_net,
               fact_employer_cost, fact_currency, currency_count
          FROM pay_worker_results
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND attempt_no = NEW.attempt_no;
        expected_control_totals := jsonb_build_array(
            jsonb_build_object('code', 'GROSS',
                'amount', fact_gross::TEXT, 'currency', fact_currency),
            jsonb_build_object('code', 'DEDUCTIONS',
                'amount', fact_deductions::TEXT, 'currency', fact_currency),
            jsonb_build_object('code', 'NET',
                'amount', fact_net::TEXT, 'currency', fact_currency),
            jsonb_build_object('code', 'EMPLOYER_COST',
                'amount', fact_employer_cost::TEXT, 'currency', fact_currency));
        IF worker_count IS DISTINCT FROM run_fact.worker_count
           OR currency_count <> 1
           OR data_payload -> 'controlTotals' IS DISTINCT FROM
                expected_control_totals THEN
            RAISE EXCEPTION 'PayrollCalculated control totals do not reconcile';
        END IF;
    ELSIF NEW.event_type = 'PayrollRunApproved' THEN
        IF run_fact.state <> 'APPROVED' OR NEW.attempt_no IS NOT NULL
           OR receipt_head.command_type <> 'RUN_APPROVE'
           OR receipt_head.originating_action <> 'PAY_RUN_APPROVE'
           OR receipt_head.audit_type <> 'PAYROLL_RUN_APPROVED'
           OR NOT (receipt_head.audit_payload ? 'runRevision')
           OR (receipt_head.audit_payload ->> 'runRevision')::BIGINT <>
                NEW.run_revision
           OR receipt_head.aggregate_version IS DISTINCT FROM NEW.run_revision
           OR NOT (data_payload ?& ARRAY[
                'runId', 'runRevision', 'approvalCaseId', 'decisionDigest'])
           OR data_payload - ARRAY[
                'runId', 'runRevision', 'approvalCaseId',
                'decisionDigest']::TEXT[] <> '{}'::JSONB
           OR data_payload ->> 'runId' <> NEW.run_id::TEXT
           OR (data_payload ->> 'runRevision')::BIGINT <> NEW.run_revision
           OR data_payload ->> 'approvalCaseId' <>
                run_fact.approval_case_id::TEXT
           OR data_payload ->> 'decisionDigest' <> run_fact.decision_digest THEN
            RAISE EXCEPTION 'PayrollRunApproved payload is not exact';
        END IF;
    ELSIF NEW.event_type = 'PayrollFinalized' THEN
        IF run_fact.state <> 'FINALIZED' OR NEW.attempt_no IS NOT NULL
           OR receipt_head.command_type <> 'RUN_FINALIZE'
           OR receipt_head.originating_action <> 'PAY_RUN_FINALIZE'
           OR receipt_head.audit_type <> 'PAYROLL_RUN_FINALIZED'
           OR NOT (receipt_head.audit_payload ? 'runRevision')
           OR (receipt_head.audit_payload ->> 'runRevision')::BIGINT <>
                NEW.run_revision
           OR receipt_head.aggregate_version IS DISTINCT FROM NEW.run_revision
           OR NOT (data_payload ?& ARRAY[
                'runId', 'runRevision', 'resultDigest', 'auditBundleDigest'])
           OR data_payload - ARRAY[
                'runId', 'runRevision', 'resultDigest',
                'auditBundleDigest']::TEXT[] <> '{}'::JSONB
           OR data_payload ->> 'runId' <> NEW.run_id::TEXT
           OR (data_payload ->> 'runRevision')::BIGINT <> NEW.run_revision
           OR data_payload ->> 'resultDigest' <> run_fact.result_digest
           OR data_payload ->> 'auditBundleDigest' <>
                run_fact.audit_bundle_digest THEN
            RAISE EXCEPTION 'PayrollFinalized payload is not exact';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported Payroll outbox event type';
    END IF;
    RETURN NEW;
END
$pay_v50_outbox_event$;

CREATE TRIGGER pay_payroll_run_revision_guard
    BEFORE INSERT ON pay_payroll_runs
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_run_revision();
CREATE TRIGGER pay_input_snapshot_insert_guard
    BEFORE INSERT ON pay_input_snapshots
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_snapshot_insert();
CREATE TRIGGER pay_input_snapshot_ref_insert_guard
    BEFORE INSERT ON pay_input_snapshot_compensation_refs
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_snapshot_ref_insert();
CREATE TRIGGER pay_payroll_attempt_transition_guard
    BEFORE INSERT ON pay_payroll_run_attempts
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_attempt_transition();
CREATE TRIGGER pay_payroll_worker_record_guard
    BEFORE INSERT ON pay_payroll_run_workers
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_worker_record();
CREATE TRIGGER pay_command_receipt_record_guard
    BEFORE INSERT ON pay_command_receipts
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_receipt_record();
CREATE TRIGGER pay_worker_result_insert_guard
    BEFORE INSERT ON pay_worker_results
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_calculation_fact_insert();
CREATE TRIGGER pay_result_line_insert_guard
    BEFORE INSERT ON pay_result_lines
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_calculation_fact_insert();
CREATE TRIGGER pay_trace_node_insert_guard
    BEFORE INSERT ON pay_calculation_trace_nodes
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_calculation_fact_insert();
CREATE TRIGGER pay_outbox_event_guard
    BEFORE INSERT ON pay_outbox_events
    FOR EACH ROW EXECUTE FUNCTION pay_v50_guard_outbox_event();

-- The runtime role cannot scan across tenant RLS. This narrowly scoped owner
-- boundary installs the requested tenant context, atomically advances the
-- append-only lease fence, and returns only the identity required to resume.
CREATE FUNCTION pay_v50_claim_attempts(
    p_tenant_id BIGINT,
    p_worker_instance_id TEXT,
    p_limit INTEGER,
    p_lease_millis BIGINT)
RETURNS TABLE (
    tenant_id BIGINT,
    run_id UUID,
    attempt_no INTEGER,
    attempt_id UUID,
    claim_no INTEGER,
    lease_token UUID,
    worker_instance_id TEXT,
    claimed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $pay_v50_claim_attempts$
DECLARE
    candidate RECORD;
    current_attempt_id UUID;
    current_claim_no INTEGER;
    current_expiry TIMESTAMPTZ;
    emitted INTEGER := 0;
    claim_time TIMESTAMPTZ;
BEGIN
    IF p_tenant_id < 1 OR p_limit < 1 OR p_limit > 100
       OR p_worker_instance_id IS NULL OR btrim(p_worker_instance_id) = ''
       OR length(p_worker_instance_id) > 160
       OR p_lease_millis < 100 OR p_lease_millis > 300000 THEN
        RAISE EXCEPTION 'Invalid bounded Payroll claim request';
    END IF;
    PERFORM pg_catalog.set_config(
        'dwp.payroll_tenant_id', p_tenant_id::TEXT, TRUE);

    FOR candidate IN
        WITH run_heads AS (
            SELECT DISTINCT ON (run_record.tenant_id, run_record.run_id)
                   run_record.tenant_id, run_record.run_id,
                   run_record.latest_attempt_no, run_record.state,
                   run_record.recorded_at
              FROM public.pay_payroll_runs run_record
             WHERE run_record.tenant_id = p_tenant_id
             ORDER BY run_record.tenant_id, run_record.run_id,
                      run_record.run_revision DESC
        ), attempt_heads AS (
            SELECT DISTINCT ON (
                    attempt_record.tenant_id, attempt_record.run_id,
                    attempt_record.attempt_no)
                   attempt_record.tenant_id, attempt_record.run_id,
                   attempt_record.attempt_no, attempt_record.attempt_id,
                   attempt_record.attempt_state
              FROM public.pay_payroll_run_attempts attempt_record
             WHERE attempt_record.tenant_id = p_tenant_id
             ORDER BY attempt_record.tenant_id, attempt_record.run_id,
                      attempt_record.attempt_no,
                      attempt_record.status_sequence DESC
        )
        SELECT run_head.run_id, run_head.latest_attempt_no,
               attempt_head.attempt_id
          FROM run_heads run_head
          JOIN attempt_heads attempt_head
            ON attempt_head.tenant_id = run_head.tenant_id
           AND attempt_head.run_id = run_head.run_id
           AND attempt_head.attempt_no = run_head.latest_attempt_no
          LEFT JOIN LATERAL (
            SELECT owner_record.expires_at
              FROM public.pay_payroll_run_workers owner_record
             WHERE owner_record.tenant_id = run_head.tenant_id
               AND owner_record.run_id = run_head.run_id
               AND owner_record.attempt_no = run_head.latest_attempt_no
               AND owner_record.record_kind IN ('LEASE', 'RENEWAL')
             ORDER BY owner_record.claim_no DESC,
                      owner_record.run_worker_record_internal_id DESC LIMIT 1
          ) current_owner ON TRUE
         WHERE run_head.state IN ('READY', 'CALCULATING')
           AND attempt_head.attempt_state IN ('READY', 'CALCULATING')
           AND (current_owner.expires_at IS NULL
                OR current_owner.expires_at <= pg_catalog.clock_timestamp())
         ORDER BY run_head.recorded_at, run_head.run_id
         LIMIT p_limit * 8
    LOOP
        EXIT WHEN emitted >= p_limit;
        IF NOT pg_catalog.pg_try_advisory_xact_lock(
                pg_catalog.hashtextextended(
                    p_tenant_id::TEXT || ':PAY011:' || candidate.run_id::TEXT, 0)) THEN
            CONTINUE;
        END IF;

        SELECT attempt_head.attempt_id,
               COALESCE(current_owner.claim_no, 0), current_owner.expires_at
          INTO current_attempt_id, current_claim_no, current_expiry
          FROM LATERAL (
            SELECT run_record.latest_attempt_no, run_record.state
              FROM public.pay_payroll_runs run_record
             WHERE run_record.tenant_id = p_tenant_id
               AND run_record.run_id = candidate.run_id
             ORDER BY run_record.run_revision DESC LIMIT 1
          ) run_head
          JOIN LATERAL (
            SELECT attempt_record.attempt_id, attempt_record.attempt_state
              FROM public.pay_payroll_run_attempts attempt_record
             WHERE attempt_record.tenant_id = p_tenant_id
               AND attempt_record.run_id = candidate.run_id
               AND attempt_record.attempt_no = run_head.latest_attempt_no
             ORDER BY attempt_record.status_sequence DESC LIMIT 1
          ) attempt_head ON TRUE
          LEFT JOIN LATERAL (
            SELECT owner_record.claim_no, owner_record.expires_at
              FROM public.pay_payroll_run_workers owner_record
             WHERE owner_record.tenant_id = p_tenant_id
               AND owner_record.run_id = candidate.run_id
               AND owner_record.attempt_no = run_head.latest_attempt_no
               AND owner_record.record_kind IN ('LEASE', 'RENEWAL')
             ORDER BY owner_record.claim_no DESC,
                      owner_record.run_worker_record_internal_id DESC LIMIT 1
          ) current_owner ON TRUE
         WHERE run_head.latest_attempt_no = candidate.latest_attempt_no
           AND run_head.state IN ('READY', 'CALCULATING')
           AND attempt_head.attempt_id = candidate.attempt_id
           AND attempt_head.attempt_state IN ('READY', 'CALCULATING')
           AND (current_owner.expires_at IS NULL
                OR current_owner.expires_at <= pg_catalog.clock_timestamp());
        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        tenant_id := p_tenant_id;
        run_id := candidate.run_id;
        attempt_no := candidate.latest_attempt_no;
        attempt_id := current_attempt_id;
        claim_no := current_claim_no + 1;
        lease_token := pg_catalog.gen_random_uuid();
        worker_instance_id := p_worker_instance_id;
        claim_time := pg_catalog.clock_timestamp();
        claimed_at := claim_time;
        expires_at := claim_time
            + p_lease_millis * INTERVAL '1 millisecond';
        INSERT INTO public.pay_payroll_run_workers (
            tenant_id, run_id, attempt_no, attempt_id, record_kind, claim_no,
            lease_token, worker_instance_id, claimed_at, expires_at, recorded_at)
        VALUES (tenant_id, run_id, attempt_no, attempt_id, 'LEASE', claim_no,
            lease_token, worker_instance_id, claimed_at, expires_at, claimed_at);
        emitted := emitted + 1;
        RETURN NEXT;
    END LOOP;
END
$pay_v50_claim_attempts$;

-- Recovery polling is separately minimized. A candidate is returned only when
-- the run, attempt and receipt durably agree on RESULT_UNKNOWN, the frozen
-- snapshot exists, and no successful calculation event is present.
CREATE FUNCTION pay_v50_recovery_candidates(
    p_tenant_id BIGINT,
    p_limit INTEGER)
RETURNS TABLE (tenant_id BIGINT, run_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $pay_v50_recovery_candidates$
BEGIN
    IF p_tenant_id < 1 OR p_limit < 1 OR p_limit > 100 THEN
        RAISE EXCEPTION 'Invalid bounded Payroll recovery poll';
    END IF;
    PERFORM pg_catalog.set_config(
        'dwp.payroll_tenant_id', p_tenant_id::TEXT, TRUE);
    RETURN QUERY
    WITH run_heads AS (
        SELECT DISTINCT ON (run_record.tenant_id, run_record.run_id)
               run_record.*
          FROM public.pay_payroll_runs run_record
         WHERE run_record.tenant_id = p_tenant_id
         ORDER BY run_record.tenant_id, run_record.run_id,
                  run_record.run_revision DESC
    ), attempt_heads AS (
        SELECT DISTINCT ON (
                attempt_record.tenant_id, attempt_record.run_id,
                attempt_record.attempt_no)
               attempt_record.*
          FROM public.pay_payroll_run_attempts attempt_record
         WHERE attempt_record.tenant_id = p_tenant_id
         ORDER BY attempt_record.tenant_id, attempt_record.run_id,
                  attempt_record.attempt_no,
                  attempt_record.status_sequence DESC
    ), receipt_heads AS (
        SELECT DISTINCT ON (receipt_record.tenant_id, receipt_record.receipt_id)
               receipt_record.*
          FROM public.pay_command_receipts receipt_record
         WHERE receipt_record.tenant_id = p_tenant_id
         ORDER BY receipt_record.tenant_id, receipt_record.receipt_id,
                  receipt_record.status_sequence DESC
    )
    SELECT run_head.tenant_id, run_head.run_id
      FROM run_heads run_head
      JOIN attempt_heads attempt_head
        ON attempt_head.tenant_id = run_head.tenant_id
       AND attempt_head.run_id = run_head.run_id
       AND attempt_head.attempt_no = run_head.latest_attempt_no
      JOIN receipt_heads receipt_head
        ON receipt_head.tenant_id = run_head.tenant_id
       AND receipt_head.receipt_id = run_head.command_receipt_id
      JOIN public.pay_input_snapshots snapshot
        ON snapshot.tenant_id = run_head.tenant_id
       AND snapshot.run_id = run_head.run_id
       AND snapshot.attempt_no = run_head.latest_attempt_no
     WHERE run_head.state = 'RESULT_UNKNOWN'
       AND attempt_head.attempt_state = 'RESULT_UNKNOWN'
       AND receipt_head.receipt_status = 'RESULT_UNKNOWN'
       AND NOT EXISTS (
           SELECT 1 FROM public.pay_outbox_events event_record
            WHERE event_record.tenant_id = run_head.tenant_id
              AND event_record.run_id = run_head.run_id
              AND event_record.attempt_no = run_head.latest_attempt_no
              AND event_record.event_type = 'PayrollCalculated')
     ORDER BY run_head.recorded_at, run_head.run_id
     LIMIT p_limit;
END
$pay_v50_recovery_candidates$;

DO $pay_v50_security$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'pay_input_snapshots', 'pay_input_snapshot_compensation_refs',
        'pay_payroll_runs', 'pay_payroll_run_workers',
        'pay_payroll_run_attempts', 'pay_worker_results', 'pay_result_lines',
        'pay_calculation_trace_nodes', 'pay_command_receipts', 'pay_outbox_events']
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I '
            || 'FOR EACH ROW EXECUTE FUNCTION pay_v50_reject_mutation()',
            table_name || '_immutable', table_name);
        EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC', table_name);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I '
            || 'USING (tenant_id = NULLIF(current_setting(''dwp.payroll_tenant_id'', true), '''')::BIGINT) '
            || 'WITH CHECK (tenant_id = NULLIF(current_setting(''dwp.payroll_tenant_id'', true), '''')::BIGINT)',
            table_name || '_tenant_policy', table_name);
    END LOOP;
END
$pay_v50_security$;

REVOKE ALL ON FUNCTION pay_v50_reject_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_guard_run_revision() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_guard_snapshot_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_guard_snapshot_ref_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_guard_attempt_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_guard_worker_record() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_guard_receipt_record() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_guard_calculation_fact_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_guard_outbox_event() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_claim_attempts(BIGINT, TEXT, INTEGER, BIGINT)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v50_recovery_candidates(BIGINT, INTEGER)
    FROM PUBLIC;

DO $pay_v50_runtime_access$
DECLARE runtime_role_name TEXT := '${payrollRuntimeRole}'; runtime_role RECORD;
        table_name TEXT; sequence_name TEXT; sequence_binding RECORD;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid payroll runtime role name';
    END IF;
    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
      INTO runtime_role FROM pg_roles WHERE rolname = runtime_role_name;
    IF NOT FOUND OR runtime_role.rolsuper OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION 'Payroll runtime role is missing or unsafe';
    END IF;
    FOREACH table_name IN ARRAY ARRAY[
        'pay_input_snapshots', 'pay_input_snapshot_compensation_refs',
        'pay_payroll_runs', 'pay_payroll_run_workers',
        'pay_payroll_run_attempts', 'pay_worker_results', 'pay_result_lines',
        'pay_calculation_trace_nodes', 'pay_command_receipts', 'pay_outbox_events']
    LOOP
        EXECUTE format('GRANT SELECT, INSERT ON TABLE %I TO %I',
            table_name, runtime_role_name);
    END LOOP;
    FOR sequence_binding IN
        SELECT * FROM (VALUES
            ('pay_input_snapshots', 'input_snapshot_internal_id'),
            ('pay_input_snapshot_compensation_refs', 'compensation_ref_internal_id'),
            ('pay_payroll_runs', 'payroll_run_revision_internal_id'),
            ('pay_payroll_run_attempts', 'attempt_transition_internal_id'),
            ('pay_payroll_run_workers', 'run_worker_record_internal_id'),
            ('pay_worker_results', 'worker_result_internal_id'),
            ('pay_result_lines', 'result_line_internal_id'),
            ('pay_calculation_trace_nodes', 'trace_node_internal_id'),
            ('pay_command_receipts', 'command_receipt_record_internal_id'),
            ('pay_outbox_events', 'outbox_event_internal_id')) AS bindings(table_name, column_name)
    LOOP
        sequence_name := pg_get_serial_sequence(
            sequence_binding.table_name, sequence_binding.column_name);
        IF sequence_name IS NULL THEN
            RAISE EXCEPTION 'Missing V50 identity sequence for %.%',
                sequence_binding.table_name, sequence_binding.column_name;
        END IF;
        EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC', sequence_name);
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
            sequence_name, runtime_role_name);
    END LOOP;
    EXECUTE format(
        'GRANT EXECUTE ON FUNCTION '
        || 'pay_v50_claim_attempts(BIGINT, TEXT, INTEGER, BIGINT) TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT EXECUTE ON FUNCTION '
        || 'pay_v50_recovery_candidates(BIGINT, INTEGER) TO %I',
        runtime_role_name);
END
$pay_v50_runtime_access$;

COMMENT ON TABLE pay_input_snapshots IS
    'V50 freeze boundary for exact V49 source rows, formula set and target population.';
COMMENT ON TABLE pay_payroll_run_attempts IS
    'Append-only status history for one logical attempt identity and attempt number.';
COMMENT ON TABLE pay_payroll_run_workers IS
    'Append-only fenced lease claims and monotonic durable checkpoints.';
COMMENT ON TABLE pay_command_receipts IS
    'Append-only sealed receipt status/audit history with one-time step-up proof.';
COMMENT ON TABLE pay_outbox_events IS
    'Exactly-once minimized PayrollCalculated, PayrollRunApproved and PayrollFinalized envelopes.';
