-- BASE-TFR-TIM-007 / HRIS-WAVE7-R7-BATCH-01-TIM-007-R2
-- Forward-only close-period, immutable closed-time result and Payroll handoff owner schema.

CREATE TABLE tme_close_periods (
    close_period_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    close_scope VARCHAR(20) NOT NULL,
    legal_entity_public_id UUID NOT NULL,
    time_group_public_id UUID NOT NULL,
    payroll_entity_public_id UUID NOT NULL,
    pay_period_public_id UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(24) NOT NULL,
    close_revision BIGINT NOT NULL DEFAULT 0,
    ledger_revision BIGINT,
    latest_result_public_id UUID,
    closed_by_subject_public_id UUID,
    close_approval_actor_public_id UUID,
    latest_correction_maker_subject_public_id UUID,
    closed_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_tme_close_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_close_internal UNIQUE (tenant_id, close_period_id),
    CONSTRAINT uk_tme_close_internal_public UNIQUE (
        tenant_id, close_period_id, public_id),
    CONSTRAINT uk_tme_close_scope_period UNIQUE (
        tenant_id, close_scope, legal_entity_public_id,
        time_group_public_id, period_start, period_end),
    CONSTRAINT ck_tme_close_period CHECK (period_end >= period_start),
    CONSTRAINT ck_tme_close_scope CHECK (
        close_scope IN ('DAY', 'MONTH', 'PAYROLL_PERIOD')),
    CONSTRAINT ck_tme_close_status CHECK (status IN (
        'OPEN', 'VALIDATING', 'CLOSING', 'CLOSED',
        'REOPEN_REQUESTED', 'REOPENED', 'FAILED')),
    CONSTRAINT ck_tme_close_revisions CHECK (
        close_revision >= 0 AND row_version > 0
        AND (ledger_revision IS NULL OR ledger_revision > 0)),
    CONSTRAINT ck_tme_close_actor CHECK (created_by > 0 AND updated_by > 0),
    CONSTRAINT ck_tme_close_closed_evidence CHECK (
        (status = 'CLOSED'
            AND close_revision > 0
            AND ledger_revision IS NOT NULL
            AND latest_result_public_id IS NOT NULL
            AND closed_by_subject_public_id IS NOT NULL
            AND close_approval_actor_public_id IS NOT NULL
            AND closed_at IS NOT NULL)
        OR status <> 'CLOSED')
);

CREATE TABLE tme_close_command_receipts (
    command_receipt_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    route_contract_key VARCHAR(160) NOT NULL,
    command_type VARCHAR(40) NOT NULL,
    originating_action VARCHAR(80) NOT NULL,
    originating_population_kind VARCHAR(24) NOT NULL,
    actor_id BIGINT NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    population_public_id UUID NOT NULL,
    population_revision BIGINT NOT NULL,
    grant_revision BIGINT NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    field_policy_revision BIGINT NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    authorization_revision BIGINT NOT NULL,
    required_permission VARCHAR(80) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    aggregate_public_id UUID NOT NULL,
    aggregate_version BIGINT,
    status VARCHAR(24) NOT NULL,
    result_ref TEXT,
    error_code VARCHAR(80),
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_tme_close_receipt_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_close_receipt_idem UNIQUE (
        tenant_id, subject_principal_public_id, route_contract_key, idempotency_key),
    CONSTRAINT ck_tme_close_receipt_idem CHECK (
        length(idempotency_key) BETWEEN 16 AND 128
        AND idempotency_key = btrim(idempotency_key)),
    CONSTRAINT ck_tme_close_receipt_pair CHECK (
        (command_type = 'PERIOD_VALIDATE'
            AND originating_action = 'TIME_PERIOD_VALIDATE'
            AND route_contract_key = 'route.hcm.operations.time-close-validate.action'
            AND required_permission = 'ACTION.HRIS_TIME_CLOSE:EXECUTE'
            AND purpose_code = 'WORK_TIME_PERIOD_CLOSURE')
        OR (command_type = 'PERIOD_CLOSE'
            AND originating_action = 'TIME_PERIOD_CLOSE'
            AND route_contract_key = 'route.hcm.operations.time-close.action'
            AND required_permission = 'ACTION.HRIS_TIME_CLOSE:EXECUTE'
            AND purpose_code = 'WORK_TIME_PERIOD_CLOSURE')
        OR (command_type = 'PERIOD_REOPEN'
            AND originating_action = 'TIME_PERIOD_REOPEN'
            AND route_contract_key = 'route.hcm.operations.time-reopen.action'
            AND required_permission = 'ACTION.HRIS_TIME_REOPEN:MANAGE'
            AND purpose_code = 'WORK_TIME_PERIOD_CLOSURE')
        OR (command_type = 'PAYROLL_HANDOFF'
            AND originating_action = 'TIME_PAYROLL_HANDOFF'
            AND route_contract_key = 'route.hcm.operations.time-payroll-handoff-create.action'
            AND required_permission = 'ACTION.HRIS_TIME_CLOSE:EXECUTE'
            AND purpose_code = 'CLOSED_TIME_PAYROLL_TRANSFER')),
    CONSTRAINT ck_tme_close_receipt_population CHECK (
        originating_population_kind = 'TARGET_POPULATION'),
    CONSTRAINT ck_tme_close_receipt_revisions CHECK (
        actor_id > 0 AND population_revision > 0 AND grant_revision > 0
        AND field_policy_revision > 0 AND authorization_revision > 0
        AND (aggregate_version IS NULL OR aggregate_version > 0)),
    CONSTRAINT ck_tme_close_receipt_digests CHECK (
        population_scope_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_close_receipt_status CHECK (status IN (
        'ACCEPTED', 'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_tme_close_receipt_result_ref CHECK (
        result_ref IS NULL OR length(result_ref) >= 1),
    CONSTRAINT ck_tme_close_receipt_completion CHECK (
        (status IN ('SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN')
            AND completed_at IS NOT NULL)
        OR (status IN ('ACCEPTED', 'RUNNING')
            AND completed_at IS NULL))
);

CREATE TABLE tme_close_attempts (
    close_attempt_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    close_period_id BIGINT NOT NULL,
    command_receipt_public_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    requested_action VARCHAR(20) NOT NULL,
    status VARCHAR(24) NOT NULL,
    expected_row_version BIGINT NOT NULL,
    blocker_snapshot JSONB NOT NULL,
    blocker_count INTEGER NOT NULL,
    input_digest CHAR(64) NOT NULL,
    output_digest CHAR(64) NOT NULL,
    approval_receipt_public_id UUID,
    approval_actor_subject_public_id UUID,
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    requested_by_subject_public_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT uk_tme_close_attempt_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_close_attempt_no UNIQUE (
        tenant_id, close_period_id, attempt_no),
    CONSTRAINT fk_tme_close_attempt_period FOREIGN KEY (tenant_id, close_period_id)
        REFERENCES tme_close_periods (tenant_id, close_period_id),
    CONSTRAINT fk_tme_close_attempt_receipt FOREIGN KEY (
        tenant_id, command_receipt_public_id)
        REFERENCES tme_close_command_receipts (tenant_id, public_id),
    CONSTRAINT ck_tme_close_attempt_action CHECK (
        requested_action IN ('VALIDATE', 'CLOSE', 'REOPEN')),
    CONSTRAINT ck_tme_close_attempt_status CHECK (
        status IN ('SUCCEEDED', 'BLOCKED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_tme_close_attempt_values CHECK (
        attempt_no > 0 AND expected_row_version > 0 AND blocker_count >= 0
        AND jsonb_typeof(blocker_snapshot) = 'array'
        AND jsonb_array_length(blocker_snapshot) = blocker_count),
    CONSTRAINT ck_tme_close_attempt_digests CHECK (
        input_digest ~ '^[0-9a-f]{64}$'
        AND output_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_close_attempt_approval CHECK (
        ((requested_action IN ('CLOSE', 'REOPEN'))
            = (approval_receipt_public_id IS NOT NULL))
        AND ((requested_action IN ('CLOSE', 'REOPEN'))
            = (approval_actor_subject_public_id IS NOT NULL)))
);

CREATE TABLE tme_closed_time_results (
    closed_time_result_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    close_period_id BIGINT NOT NULL,
    close_revision BIGINT NOT NULL,
    payroll_entity_public_id UUID NOT NULL,
    pay_period_public_id UUID NOT NULL,
    ledger_revision_from BIGINT NOT NULL,
    ledger_revision_to BIGINT NOT NULL,
    worker_count INTEGER NOT NULL,
    line_count INTEGER NOT NULL,
    source_digest CHAR(64) NOT NULL,
    rule_digest CHAR(64) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    supersedes_result_public_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_subject_public_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT uk_tme_closed_result_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_closed_result_internal UNIQUE (
        tenant_id, closed_time_result_id),
    CONSTRAINT uk_tme_closed_result_period_public UNIQUE (
        tenant_id, close_period_id, public_id),
    CONSTRAINT uk_tme_closed_result_period_revision_public UNIQUE (
        tenant_id, close_period_id, close_revision, public_id),
    CONSTRAINT uk_tme_closed_result_handoff_facts UNIQUE (
        tenant_id, closed_time_result_id, close_period_id, close_revision,
        payroll_entity_public_id, pay_period_public_id, worker_count, line_count,
        source_digest, rule_digest, payload_digest),
    CONSTRAINT uk_tme_closed_result_revision UNIQUE (
        tenant_id, close_period_id, close_revision),
    CONSTRAINT fk_tme_closed_result_period FOREIGN KEY (tenant_id, close_period_id)
        REFERENCES tme_close_periods (tenant_id, close_period_id),
    CONSTRAINT fk_tme_closed_result_supersedes FOREIGN KEY (
        tenant_id, close_period_id, supersedes_result_public_id)
        REFERENCES tme_closed_time_results (
            tenant_id, close_period_id, public_id),
    CONSTRAINT ck_tme_closed_result_revision CHECK (
        close_revision > 0 AND ledger_revision_from > 0
        AND ledger_revision_to >= ledger_revision_from),
    CONSTRAINT ck_tme_closed_result_counts CHECK (
        worker_count >= 0 AND worker_count <= 1000000
        AND line_count >= 0 AND line_count <= 1000000),
    CONSTRAINT ck_tme_closed_result_digests CHECK (
        source_digest ~ '^[0-9a-f]{64}$'
        AND rule_digest ~ '^[0-9a-f]{64}$'
        AND payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_closed_result_not_self CHECK (
        supersedes_result_public_id IS NULL
        OR supersedes_result_public_id <> public_id)
);

ALTER TABLE tme_close_periods
    ADD CONSTRAINT fk_tme_close_latest_result FOREIGN KEY (
        tenant_id, close_period_id, close_revision, latest_result_public_id)
        REFERENCES tme_closed_time_results (
            tenant_id, close_period_id, close_revision, public_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE tme_closed_time_result_lines (
    closed_time_result_line_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    closed_time_result_id BIGINT NOT NULL,
    line_no INTEGER NOT NULL,
    worker_public_id UUID NOT NULL,
    assignment_public_id UUID NOT NULL,
    pay_code VARCHAR(80) NOT NULL,
    unit VARCHAR(12) NOT NULL,
    quantity NUMERIC(19,6) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    source_entry_count INTEGER NOT NULL,
    source_entry_digest CHAR(64) NOT NULL,
    CONSTRAINT uk_tme_closed_result_line_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_closed_result_line_internal UNIQUE (
        tenant_id, closed_time_result_line_id),
    CONSTRAINT uk_tme_closed_result_line_no UNIQUE (
        tenant_id, closed_time_result_id, line_no),
    CONSTRAINT fk_tme_closed_result_line_header FOREIGN KEY (
        tenant_id, closed_time_result_id)
        REFERENCES tme_closed_time_results (tenant_id, closed_time_result_id),
    CONSTRAINT ck_tme_closed_result_line_no CHECK (line_no > 0),
    CONSTRAINT ck_tme_closed_result_line_unit CHECK (
        unit IN ('MINUTE', 'HOUR', 'DAY', 'COUNT', 'AMOUNT')),
    CONSTRAINT ck_tme_closed_result_line_currency CHECK (
        (unit = 'AMOUNT' AND currency_code ~ '^[A-Z]{3}$' AND currency_code <> 'XXX')
        OR (unit <> 'AMOUNT' AND currency_code = 'XXX')),
    CONSTRAINT ck_tme_closed_result_line_sources CHECK (
        source_entry_count > 0 AND source_entry_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE tme_closed_time_result_sources (
    closed_time_result_source_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    closed_time_result_line_id BIGINT NOT NULL,
    source_sequence INTEGER NOT NULL,
    ledger_entry_public_id UUID NOT NULL,
    ledger_entry_posted_at TIMESTAMPTZ NOT NULL,
    ledger_revision BIGINT NOT NULL,
    contribution_quantity NUMERIC(19,6) NOT NULL,
    contribution_unit VARCHAR(12) NOT NULL,
    entry_digest CHAR(64) NOT NULL,
    CONSTRAINT uk_tme_closed_result_source UNIQUE (
        tenant_id, closed_time_result_line_id, source_sequence),
    CONSTRAINT uk_tme_closed_result_source_ledger UNIQUE (
        tenant_id, closed_time_result_line_id, ledger_entry_public_id),
    CONSTRAINT fk_tme_closed_result_source_line FOREIGN KEY (
        tenant_id, closed_time_result_line_id)
        REFERENCES tme_closed_time_result_lines (
            tenant_id, closed_time_result_line_id),
    CONSTRAINT fk_tme_closed_result_source_ledger FOREIGN KEY (
        tenant_id, ledger_entry_public_id, ledger_entry_posted_at)
        REFERENCES tme_time_ledger_entries (tenant_id, public_id, posted_at),
    CONSTRAINT ck_tme_closed_result_source_values CHECK (
        source_sequence > 0 AND ledger_revision > 0
        AND contribution_unit IN ('MINUTE', 'HOUR', 'DAY', 'COUNT', 'AMOUNT')
        AND entry_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE tme_payroll_handoffs (
    payroll_handoff_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    closed_time_result_id BIGINT NOT NULL,
    close_period_id BIGINT NOT NULL,
    close_period_public_id UUID NOT NULL,
    close_revision BIGINT NOT NULL,
    payroll_entity_public_id UUID NOT NULL,
    pay_period_public_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL,
    worker_count INTEGER NOT NULL,
    line_count INTEGER NOT NULL,
    source_digest CHAR(64) NOT NULL,
    rule_digest CHAR(64) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    receiver_receipt_public_id UUID,
    receiver_receipt_revision BIGINT,
    receiver_receipt_digest CHAR(64),
    receiver_reason_code VARCHAR(80),
    outcome_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_subject_public_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_handoff_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_handoff_result UNIQUE (tenant_id, closed_time_result_id),
    CONSTRAINT uk_tme_handoff_revision UNIQUE (
        tenant_id, close_period_public_id, close_revision,
        payroll_entity_public_id, pay_period_public_id),
    CONSTRAINT fk_tme_handoff_closed_result FOREIGN KEY (
        tenant_id, closed_time_result_id, close_period_id, close_revision,
        payroll_entity_public_id, pay_period_public_id, worker_count, line_count,
        source_digest, rule_digest, payload_digest)
        REFERENCES tme_closed_time_results (
            tenant_id, closed_time_result_id, close_period_id, close_revision,
            payroll_entity_public_id, pay_period_public_id, worker_count, line_count,
            source_digest, rule_digest, payload_digest),
    CONSTRAINT fk_tme_handoff_close_period FOREIGN KEY (
        tenant_id, close_period_id, close_period_public_id)
        REFERENCES tme_close_periods (
            tenant_id, close_period_id, public_id),
    CONSTRAINT ck_tme_handoff_status CHECK (status IN (
        'PENDING', 'ACKNOWLEDGED', 'REJECTED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_tme_handoff_values CHECK (
        close_revision > 0
        AND worker_count BETWEEN 0 AND 1000000
        AND line_count BETWEEN 0 AND 1000000
        AND row_version > 0),
    CONSTRAINT ck_tme_handoff_digests CHECK (
        source_digest ~ '^[0-9a-f]{64}$'
        AND rule_digest ~ '^[0-9a-f]{64}$'
        AND payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_handoff_outcome CHECK (
        (status = 'PENDING'
            AND receiver_receipt_public_id IS NULL
            AND receiver_receipt_revision IS NULL
            AND receiver_receipt_digest IS NULL
            AND receiver_reason_code IS NULL
            AND outcome_at IS NULL)
        OR (status = 'ACKNOWLEDGED'
            AND receiver_receipt_public_id IS NOT NULL
            AND receiver_receipt_revision > 0
            AND receiver_receipt_digest ~ '^[0-9a-f]{64}$'
            AND receiver_reason_code IS NULL
            AND outcome_at IS NOT NULL)
        OR (status IN ('REJECTED', 'RESULT_UNKNOWN')
            AND receiver_receipt_public_id IS NOT NULL
            AND receiver_receipt_revision > 0
            AND receiver_receipt_digest ~ '^[0-9a-f]{64}$'
            AND receiver_reason_code ~ '^[A-Z][A-Z0-9._-]{0,79}$'
            AND outcome_at IS NOT NULL))
);
CREATE INDEX ix_tme_handoff_reconcile
    ON tme_payroll_handoffs (tenant_id, status, created_at);

CREATE TABLE tme_payroll_handoff_outcomes (
    payroll_handoff_outcome_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    handoff_public_id UUID NOT NULL,
    receiver_receipt_public_id UUID NOT NULL,
    receiver_receipt_revision BIGINT NOT NULL,
    outcome_command_digest CHAR(64) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    receiver_receipt_digest CHAR(64) NOT NULL,
    reason_code VARCHAR(80),
    outcome_at TIMESTAMPTZ NOT NULL,
    response_updated_at TIMESTAMPTZ NOT NULL,
    response_version BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_handoff_outcome_revision UNIQUE (
        tenant_id, handoff_public_id, receiver_receipt_public_id,
        receiver_receipt_revision),
    CONSTRAINT uk_tme_handoff_outcome_sequence UNIQUE (
        tenant_id, handoff_public_id, receiver_receipt_revision),
    CONSTRAINT fk_tme_handoff_outcome FOREIGN KEY (
        tenant_id, handoff_public_id)
        REFERENCES tme_payroll_handoffs (tenant_id, public_id),
    CONSTRAINT ck_tme_handoff_outcome_values CHECK (
        receiver_receipt_revision > 0
        AND response_version = receiver_receipt_revision + 1
        AND outcome_command_digest ~ '^[0-9a-f]{64}$'
        AND receiver_receipt_digest ~ '^[0-9a-f]{64}$'
        AND response_updated_at = received_at
        AND ((outcome = 'ACKNOWLEDGED' AND reason_code IS NULL)
            OR (outcome IN ('REJECTED', 'RESULT_UNKNOWN')
                AND reason_code ~ '^[A-Z][A-Z0-9._-]{0,79}$')))
);

CREATE TABLE tme_close_workload_replay (
    replay_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    binding_id VARCHAR(32) NOT NULL,
    proof_jti UUID NOT NULL,
    request_nonce UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_close_replay_jti UNIQUE (tenant_id, binding_id, proof_jti),
    CONSTRAINT uk_tme_close_replay_nonce UNIQUE (
        tenant_id, binding_id, request_nonce),
    CONSTRAINT ck_tme_close_replay_binding CHECK (
        binding_id IN ('SVC-PEP-TIM-001', 'SVC-PEP-TIM-003')),
    CONSTRAINT ck_tme_close_replay_nonce_profile CHECK (
        (binding_id = 'SVC-PEP-TIM-001' AND request_nonce IS NULL)
        OR (binding_id = 'SVC-PEP-TIM-003'
            AND request_nonce IS NOT NULL AND request_nonce <> proof_jti)),
    CONSTRAINT ck_tme_close_replay_window CHECK (expires_at > created_at)
);

CREATE TABLE tme_close_quarantine (
    close_quarantine_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    handoff_public_id UUID,
    reason_code VARCHAR(80) NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_close_quarantine_public UNIQUE (tenant_id, public_id),
    CONSTRAINT fk_tme_close_quarantine_handoff FOREIGN KEY (
        tenant_id, handoff_public_id)
        REFERENCES tme_payroll_handoffs (tenant_id, public_id),
    CONSTRAINT ck_tme_close_quarantine_reason CHECK (
        reason_code ~ '^[A-Z][A-Z0-9._-]{0,79}$'),
    CONSTRAINT ck_tme_close_quarantine_digest CHECK (
        evidence_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE tme_close_outbox_events (
    outbox_event_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_public_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    schema_name VARCHAR(80) NOT NULL,
    schema_version INTEGER NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_tme_close_outbox_event UNIQUE (tenant_id, event_id),
    CONSTRAINT uk_tme_close_outbox_sequence UNIQUE (
        tenant_id, schema_name, aggregate_public_id, aggregate_sequence),
    CONSTRAINT fk_tme_close_outbox_receipt FOREIGN KEY (
        tenant_id, causation_id)
        REFERENCES tme_close_command_receipts (tenant_id, public_id),
    CONSTRAINT ck_tme_close_outbox_identity CHECK (
        (schema_name = 'TimePeriodClosed.v1'
            AND aggregate_type = 'ClosePeriod')
        OR (schema_name = 'TimePeriodReopened.v1'
            AND aggregate_type = 'ClosePeriod')
        OR (schema_name = 'PayrollTimeHandoffReady.v1'
            AND aggregate_type = 'PayrollHandoff')),
    CONSTRAINT ck_tme_close_outbox_values CHECK (
        aggregate_sequence > 0 AND schema_version = 1
        AND payload_digest ~ '^[0-9a-f]{64}$'
        AND publish_attempts >= 0
        AND jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_tme_close_outbox_partition CHECK (
        partition_key = tenant_id::TEXT || '|dwp-time-server|'
            || aggregate_type || '|' || aggregate_public_id::TEXT)
);
CREATE INDEX ix_tme_close_outbox_unpublished
    ON tme_close_outbox_events (tenant_id, outbox_event_id)
    WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION tme_reject_close_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION 'Time close evidence is append-only' USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION tme_guard_close_receipt_progression()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Time close receipts cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACCEPTED'
           OR NEW.aggregate_version IS NOT NULL
           OR NEW.result_ref IS NOT NULL
           OR NEW.error_code IS NOT NULL
           OR NEW.completed_at IS NOT NULL THEN
            RAISE EXCEPTION 'Time close receipt must begin as an empty ACCEPTED reservation'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.tenant_id, NEW.public_id, NEW.idempotency_key,
           NEW.route_contract_key, NEW.command_type, NEW.originating_action,
           NEW.originating_population_kind, NEW.actor_id,
           NEW.subject_principal_public_id, NEW.population_public_id,
           NEW.population_revision, NEW.grant_revision,
           NEW.population_scope_digest, NEW.field_policy_revision,
           NEW.purpose_code, NEW.authorization_revision,
           NEW.required_permission, NEW.request_digest,
           NEW.aggregate_public_id, NEW.correlation_id, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.public_id, OLD.idempotency_key,
           OLD.route_contract_key, OLD.command_type, OLD.originating_action,
           OLD.originating_population_kind, OLD.actor_id,
           OLD.subject_principal_public_id, OLD.population_public_id,
           OLD.population_revision, OLD.grant_revision,
           OLD.population_scope_digest, OLD.field_policy_revision,
           OLD.purpose_code, OLD.authorization_revision,
           OLD.required_permission, OLD.request_digest,
           OLD.aggregate_public_id, OLD.correlation_id, OLD.created_at) THEN
        RAISE EXCEPTION 'Time close receipt authority is immutable'
            USING ERRCODE = '55000';
    END IF;
    IF NOT ((OLD.status = 'ACCEPTED' AND NEW.status IN (
                'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN'))
            OR (OLD.status = 'RUNNING' AND NEW.status IN (
                'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN'))) THEN
        RAISE EXCEPTION 'Time close receipt transition is invalid'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.aggregate_version IS NOT NULL
       AND NEW.aggregate_version IS DISTINCT FROM OLD.aggregate_version THEN
        RAISE EXCEPTION 'Time close receipt aggregate version is immutable once set'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.result_ref IS NOT NULL
       AND NEW.result_ref IS DISTINCT FROM OLD.result_ref THEN
        RAISE EXCEPTION 'Time close receipt result reference is immutable once set'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.error_code IS NOT NULL
       AND NEW.error_code IS DISTINCT FROM OLD.error_code THEN
        RAISE EXCEPTION 'Time close receipt error evidence is immutable once set'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status IN ('ACCEPTED', 'RUNNING', 'SUCCEEDED')
       AND NEW.error_code IS NOT NULL THEN
        RAISE EXCEPTION 'Successful receipt progression cannot carry an error code'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status IN ('REJECTED', 'FAILED', 'RESULT_UNKNOWN')
       AND (NEW.error_code IS NULL
            OR NEW.error_code !~ '^[A-Z][A-Z0-9._-]{0,79}$') THEN
        RAISE EXCEPTION 'Failure and unknown receipt progression requires error evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION tme_validate_closed_result_lineage()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE prior_revision BIGINT;
BEGIN
    IF NEW.close_revision = 1 THEN
        IF NEW.supersedes_result_public_id IS NOT NULL THEN
            RAISE EXCEPTION 'First close result cannot supersede a prior result'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.supersedes_result_public_id IS NULL THEN
        RAISE EXCEPTION 'Successor close result requires prior result lineage'
            USING ERRCODE = '23514';
    END IF;
    SELECT close_revision INTO prior_revision
      FROM public.tme_closed_time_results
     WHERE tenant_id = NEW.tenant_id
       AND close_period_id = NEW.close_period_id
       AND public_id = NEW.supersedes_result_public_id
     FOR KEY SHARE;
    IF NOT FOUND OR prior_revision <> NEW.close_revision - 1 THEN
        RAISE EXCEPTION 'Close result must supersede the preceding revision'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION tme_guard_closed_result_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_TABLE_NAME = 'tme_closed_time_results' THEN
        PERFORM 1
          FROM public.tme_close_periods period
          JOIN public.tme_close_command_receipts receipt
            ON receipt.tenant_id = period.tenant_id
           AND receipt.aggregate_public_id = period.public_id
           AND receipt.command_type = 'PERIOD_CLOSE'
           AND receipt.originating_action = 'TIME_PERIOD_CLOSE'
           AND receipt.status = 'ACCEPTED'
           AND receipt.subject_principal_public_id = NEW.created_by_subject_public_id
           AND receipt.correlation_id = NEW.correlation_id
           AND receipt.actor_id = period.updated_by
           AND receipt.created_at = NEW.created_at
         WHERE period.tenant_id = NEW.tenant_id
           AND period.close_period_id = NEW.close_period_id
           AND period.status = 'CLOSING'
           AND period.updated_at = NEW.created_at
           AND NEW.close_revision = period.close_revision + 1
           AND NEW.payroll_entity_public_id = period.payroll_entity_public_id
           AND NEW.pay_period_public_id = period.pay_period_public_id
           AND NEW.supersedes_result_public_id
                IS NOT DISTINCT FROM period.latest_result_public_id
         FOR KEY SHARE OF period, receipt;
        IF NOT FOUND THEN
            RAISE EXCEPTION
                'Closed-time result header requires the authorized CLOSING graph'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'tme_closed_time_result_lines' THEN
        PERFORM 1
          FROM public.tme_closed_time_results result
          JOIN public.tme_close_periods period
            ON period.tenant_id = result.tenant_id
           AND period.close_period_id = result.close_period_id
          JOIN public.tme_close_command_receipts receipt
            ON receipt.tenant_id = period.tenant_id
           AND receipt.aggregate_public_id = period.public_id
           AND receipt.command_type = 'PERIOD_CLOSE'
           AND receipt.originating_action = 'TIME_PERIOD_CLOSE'
           AND receipt.status = 'ACCEPTED'
           AND receipt.subject_principal_public_id = result.created_by_subject_public_id
           AND receipt.correlation_id = result.correlation_id
           AND receipt.actor_id = period.updated_by
           AND receipt.created_at = result.created_at
         WHERE result.tenant_id = NEW.tenant_id
           AND result.closed_time_result_id = NEW.closed_time_result_id
           AND period.status = 'CLOSING'
           AND period.updated_at = result.created_at
           AND result.close_revision = period.close_revision + 1
           AND result.payroll_entity_public_id = period.payroll_entity_public_id
           AND result.pay_period_public_id = period.pay_period_public_id
           AND result.supersedes_result_public_id
                IS NOT DISTINCT FROM period.latest_result_public_id
         FOR KEY SHARE OF result, period, receipt;
        IF NOT FOUND THEN
            RAISE EXCEPTION
                'Closed-time result line requires the authorized CLOSING graph'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'tme_closed_time_result_sources' THEN
        PERFORM 1
          FROM public.tme_closed_time_result_lines line
          JOIN public.tme_closed_time_results result
            ON result.tenant_id = line.tenant_id
           AND result.closed_time_result_id = line.closed_time_result_id
          JOIN public.tme_close_periods period
            ON period.tenant_id = result.tenant_id
           AND period.close_period_id = result.close_period_id
          JOIN public.tme_close_command_receipts receipt
            ON receipt.tenant_id = period.tenant_id
           AND receipt.aggregate_public_id = period.public_id
           AND receipt.command_type = 'PERIOD_CLOSE'
           AND receipt.originating_action = 'TIME_PERIOD_CLOSE'
           AND receipt.status = 'ACCEPTED'
           AND receipt.subject_principal_public_id = result.created_by_subject_public_id
           AND receipt.correlation_id = result.correlation_id
           AND receipt.actor_id = period.updated_by
           AND receipt.created_at = result.created_at
         WHERE line.tenant_id = NEW.tenant_id
           AND line.closed_time_result_line_id = NEW.closed_time_result_line_id
           AND period.status = 'CLOSING'
           AND period.updated_at = result.created_at
           AND result.close_revision = period.close_revision + 1
           AND result.payroll_entity_public_id = period.payroll_entity_public_id
           AND result.pay_period_public_id = period.pay_period_public_id
           AND result.supersedes_result_public_id
                IS NOT DISTINCT FROM period.latest_result_public_id
         FOR KEY SHARE OF line, result, period, receipt;
        IF NOT FOUND THEN
            RAISE EXCEPTION
                'Closed-time result source requires the authorized CLOSING graph'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Unsupported closed-time result insert target'
        USING ERRCODE = '23514';
END;
$function$;

CREATE OR REPLACE FUNCTION tme_guard_close_period()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Time close periods cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN'
           OR NEW.close_revision <> 0
           OR NEW.ledger_revision IS NOT NULL
           OR NEW.latest_result_public_id IS NOT NULL
           OR NEW.closed_by_subject_public_id IS NOT NULL
           OR NEW.close_approval_actor_public_id IS NOT NULL
           OR NEW.latest_correction_maker_subject_public_id IS NOT NULL
           OR NEW.closed_at IS NOT NULL
           OR NEW.row_version <> 1 THEN
            RAISE EXCEPTION 'Time close period must begin in canonical OPEN state'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.tenant_id, NEW.public_id, NEW.close_scope,
               NEW.legal_entity_public_id, NEW.time_group_public_id,
               NEW.payroll_entity_public_id, NEW.pay_period_public_id,
               NEW.period_start, NEW.period_end, NEW.created_at, NEW.created_by)
           IS DISTINCT FROM
           ROW(OLD.tenant_id, OLD.public_id, OLD.close_scope,
               OLD.legal_entity_public_id, OLD.time_group_public_id,
               OLD.payroll_entity_public_id, OLD.pay_period_public_id,
               OLD.period_start, OLD.period_end, OLD.created_at, OLD.created_by)
           OR NEW.row_version <> OLD.row_version + 1
           OR NEW.close_revision < OLD.close_revision THEN
            RAISE EXCEPTION 'Time close identity, CAS or revision is invalid'
                USING ERRCODE = '23514';
        END IF;
        IF NOT ((OLD.status IN ('OPEN', 'REOPENED')
                    AND NEW.status IN ('CLOSING', 'FAILED'))
                OR (OLD.status = 'CLOSING'
                    AND NEW.status IN ('CLOSED', 'FAILED'))
                OR (OLD.status = 'CLOSED'
                    AND NEW.status = 'REOPEN_REQUESTED')
                OR (OLD.status = 'REOPEN_REQUESTED'
                    AND NEW.status IN ('REOPENED', 'FAILED'))
                OR (OLD.status = 'FAILED'
                    AND NEW.status IN ('OPEN', 'REOPENED'))) THEN
            RAISE EXCEPTION 'Time close state transition is invalid'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.status = 'CLOSED' THEN
            IF OLD.status <> 'CLOSING'
               OR NEW.close_revision <> OLD.close_revision + 1
               OR NEW.latest_result_public_id IS NOT DISTINCT FROM OLD.latest_result_public_id
               OR NEW.ledger_revision IS NULL
               OR NEW.closed_by_subject_public_id IS NULL
               OR NEW.close_approval_actor_public_id IS NULL
               OR NEW.closed_at IS NULL THEN
                RAISE EXCEPTION 'Close transition requires new immutable evidence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF ROW(NEW.close_revision, NEW.ledger_revision,
                  NEW.latest_result_public_id, NEW.closed_by_subject_public_id,
                  NEW.close_approval_actor_public_id, NEW.closed_at)
              IS DISTINCT FROM
              ROW(OLD.close_revision, OLD.ledger_revision,
                  OLD.latest_result_public_id, OLD.closed_by_subject_public_id,
                  OLD.close_approval_actor_public_id, OLD.closed_at) THEN
            RAISE EXCEPTION 'Non-close transition cannot mutate close evidence'
                USING ERRCODE = '23514';
        END IF;
        IF NOT (OLD.status = 'CLOSED' AND NEW.status = 'REOPEN_REQUESTED')
           AND NEW.latest_correction_maker_subject_public_id
                IS DISTINCT FROM OLD.latest_correction_maker_subject_public_id THEN
            RAISE EXCEPTION 'Correction-maker evidence may be frozen only when reopening begins'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.status = 'CLOSED' AND NEW.status = 'REOPEN_REQUESTED'
           AND NEW.latest_correction_maker_subject_public_id IS NULL THEN
            RAISE EXCEPTION 'Reopen transition requires a correction-maker identity'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION tme_guard_handoff()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payroll handoff cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING'
           OR NEW.row_version <> 1
           OR NEW.receiver_receipt_public_id IS NOT NULL
           OR NEW.receiver_receipt_revision IS NOT NULL
           OR NEW.receiver_receipt_digest IS NOT NULL
           OR NEW.receiver_reason_code IS NOT NULL
           OR NEW.outcome_at IS NOT NULL THEN
            RAISE EXCEPTION 'Payroll handoff must begin in canonical PENDING state'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.tenant_id, NEW.public_id, NEW.closed_time_result_id,
               NEW.close_period_id, NEW.close_period_public_id, NEW.close_revision,
               NEW.payroll_entity_public_id, NEW.pay_period_public_id,
               NEW.worker_count, NEW.line_count, NEW.source_digest,
               NEW.rule_digest, NEW.payload_digest, NEW.correlation_id,
               NEW.created_at, NEW.created_by_subject_public_id)
           IS DISTINCT FROM
           ROW(OLD.tenant_id, OLD.public_id, OLD.closed_time_result_id,
               OLD.close_period_id, OLD.close_period_public_id, OLD.close_revision,
               OLD.payroll_entity_public_id, OLD.pay_period_public_id,
               OLD.worker_count, OLD.line_count, OLD.source_digest,
               OLD.rule_digest, OLD.payload_digest, OLD.correlation_id,
               OLD.created_at, OLD.created_by_subject_public_id)
           OR NEW.row_version <> OLD.row_version + 1 THEN
            RAISE EXCEPTION 'Payroll handoff identity or version is immutable'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.status IN ('ACKNOWLEDGED', 'REJECTED')
           OR (OLD.status = 'PENDING' AND NEW.status NOT IN (
                'ACKNOWLEDGED', 'REJECTED', 'RESULT_UNKNOWN'))
           OR (OLD.status = 'RESULT_UNKNOWN' AND NEW.status NOT IN (
                'ACKNOWLEDGED', 'REJECTED')) THEN
            RAISE EXCEPTION 'Payroll handoff transition is invalid'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.status = 'RESULT_UNKNOWN' AND (
            NEW.receiver_receipt_public_id <> OLD.receiver_receipt_public_id
            OR NEW.receiver_receipt_revision <> OLD.receiver_receipt_revision + 1) THEN
            RAISE EXCEPTION 'Payroll handoff reconciliation lineage is invalid'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.status = 'PENDING' AND NEW.receiver_receipt_revision <> 1 THEN
            RAISE EXCEPTION 'Initial Payroll receiver revision must be one'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION tme_guard_close_outbox_delivery()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Time close outbox cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF ROW(NEW.tenant_id, NEW.event_id, NEW.aggregate_type,
           NEW.aggregate_public_id, NEW.aggregate_sequence, NEW.schema_name,
           NEW.schema_version, NEW.partition_key, NEW.payload, NEW.payload_digest,
           NEW.correlation_id, NEW.causation_id, NEW.occurred_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.event_id, OLD.aggregate_type,
           OLD.aggregate_public_id, OLD.aggregate_sequence, OLD.schema_name,
           OLD.schema_version, OLD.partition_key, OLD.payload, OLD.payload_digest,
           OLD.correlation_id, OLD.causation_id, OLD.occurred_at)
       OR (OLD.published_at IS NOT NULL
           AND NEW.published_at IS DISTINCT FROM OLD.published_at)
       OR NEW.publish_attempts < OLD.publish_attempts THEN
        RAISE EXCEPTION 'Time close outbox envelope is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION tme_validate_close_outbox_event()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE data JSONB;
DECLARE required_common TEXT[] := ARRAY[
    'specVersion','id','source','type','schemaVersion','time','tenantId',
    'aggregateType','aggregateId','aggregateSequence','correlationId',
    'causationId','data'];
DECLARE allowed_common TEXT[] := ARRAY[
    'specVersion','id','source','type','schemaVersion','time','subject','tenantId',
    'aggregateType','aggregateId','aggregateSequence','correlationId',
    'causationId','traceParent','data','extensions'];
BEGIN
    IF NEW.published_at IS NOT NULL OR NEW.publish_attempts <> 0 THEN
        RAISE EXCEPTION 'Time close outbox must begin unpublished'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (NEW.payload ?& required_common)
       OR NEW.payload - allowed_common <> '{}'::JSONB
       OR jsonb_typeof(NEW.payload->'specVersion') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload->'id') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload->'source') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload->'type') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload->'schemaVersion') IS DISTINCT FROM 'number'
       OR jsonb_typeof(NEW.payload->'time') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload->'tenantId') IS DISTINCT FROM 'number'
       OR jsonb_typeof(NEW.payload->'aggregateType') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload->'aggregateId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload->'aggregateSequence') IS DISTINCT FROM 'number'
       OR jsonb_typeof(NEW.payload->'correlationId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload->'causationId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload->'data') IS DISTINCT FROM 'object'
       OR NEW.payload->>'specVersion' <> '1.0'
       OR NEW.payload->>'id' <> NEW.event_id::TEXT
       OR NEW.payload->>'id' !~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR NEW.payload->>'source' <> 'dwp-time-server'
       OR NEW.payload->>'type' <> NEW.schema_name
       OR NEW.payload->>'schemaVersion' !~ '^[0-9]+$'
       OR NEW.payload->>'schemaVersion' <> '1'
       OR NEW.payload->>'tenantId' !~ '^[0-9]+$'
       OR (NEW.payload->>'tenantId')::BIGINT < 1
       OR NEW.payload->>'tenantId' <> NEW.tenant_id::TEXT
       OR NEW.payload->>'aggregateType' <> NEW.aggregate_type
       OR NEW.payload->>'aggregateId' <> NEW.aggregate_public_id::TEXT
       OR NEW.payload->>'aggregateId' !~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR NEW.payload->>'aggregateSequence' !~ '^[0-9]+$'
       OR (NEW.payload->>'aggregateSequence')::BIGINT < 1
       OR NEW.payload->>'aggregateSequence' <> NEW.aggregate_sequence::TEXT
       OR NEW.payload->>'correlationId' <> NEW.correlation_id::TEXT
       OR NEW.payload->>'correlationId' !~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR NEW.payload->>'causationId' <> NEW.causation_id::TEXT
       OR NEW.payload->>'causationId' !~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR NEW.payload->>'time' !~
            '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z$'
       OR (NEW.payload->>'time')::TIMESTAMPTZ IS DISTINCT FROM NEW.occurred_at
       OR NEW.payload_digest <> encode(
            sha256(convert_to(NEW.payload::TEXT, 'UTF8')), 'hex') THEN
        RAISE EXCEPTION 'Time close outbox envelope violates AsyncAPI'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.payload ? 'subject'
       AND NOT ((NEW.payload->'subject') = 'null'::JSONB
            OR (jsonb_typeof(NEW.payload->'subject') = 'string'
                AND length(NEW.payload->>'subject') <= 320)) THEN
        RAISE EXCEPTION 'Time close outbox subject violates AsyncAPI'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.payload ? 'traceParent'
       AND NOT ((NEW.payload->'traceParent') = 'null'::JSONB
            OR (jsonb_typeof(NEW.payload->'traceParent') = 'string'
                AND NEW.payload->>'traceParent' ~
                    '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$')) THEN
        RAISE EXCEPTION 'Time close outbox traceParent violates AsyncAPI'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.payload ? 'extensions'
       AND (jsonb_typeof(NEW.payload->'extensions') IS DISTINCT FROM 'object'
            OR EXISTS (
                SELECT 1 FROM jsonb_each(NEW.payload->'extensions') extension
                 WHERE jsonb_typeof(extension.value) IS DISTINCT FROM 'string')) THEN
        RAISE EXCEPTION 'Time close outbox extensions violate AsyncAPI'
            USING ERRCODE = '23514';
    END IF;
    data := NEW.payload->'data';
    IF NEW.schema_name = 'TimePeriodClosed.v1' THEN
        IF data - ARRAY['closePeriodId','closeRevision','ledgerRevision',
                    'periodEnd','periodStart','ruleDigest','scopeId','sourceDigest']
                <> '{}'::JSONB
           OR NOT (data ?& ARRAY['closePeriodId','closeRevision','ledgerRevision',
                    'periodEnd','periodStart','ruleDigest','scopeId','sourceDigest'])
           OR jsonb_typeof(data->'closePeriodId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(data->'closeRevision') IS DISTINCT FROM 'number'
           OR jsonb_typeof(data->'ledgerRevision') IS DISTINCT FROM 'number'
           OR jsonb_typeof(data->'periodEnd') IS DISTINCT FROM 'string'
           OR jsonb_typeof(data->'periodStart') IS DISTINCT FROM 'string'
           OR jsonb_typeof(data->'ruleDigest') IS DISTINCT FROM 'string'
           OR jsonb_typeof(data->'scopeId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(data->'sourceDigest') IS DISTINCT FROM 'string'
           OR data->>'closeRevision' !~ '^[0-9]+$'
           OR data->>'ledgerRevision' !~ '^[0-9]+$'
           OR NEW.aggregate_sequence <> (data->>'closeRevision')::BIGINT
           OR data->>'closePeriodId' <> NEW.aggregate_public_id::TEXT
           OR data->>'closePeriodId' !~
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR data->>'ruleDigest' !~ '^[0-9a-f]{64}$'
           OR data->>'sourceDigest' !~ '^[0-9a-f]{64}$'
           OR data->>'scopeId' !~
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR data->>'periodStart' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
           OR data->>'periodEnd' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
           OR (data->>'periodStart')::DATE > (data->>'periodEnd')::DATE
           OR (data->>'closeRevision')::BIGINT < 1
           OR (data->>'ledgerRevision')::BIGINT < 1 THEN
            RAISE EXCEPTION 'TimePeriodClosed data violates XCON-009'
                USING ERRCODE = '23514';
        END IF;
        PERFORM 1
          FROM public.tme_close_periods period
          JOIN public.tme_closed_time_results result
            ON result.tenant_id = period.tenant_id
           AND result.close_period_id = period.close_period_id
           AND result.public_id = period.latest_result_public_id
          JOIN public.tme_close_command_receipts receipt
            ON receipt.tenant_id = period.tenant_id
           AND receipt.public_id = NEW.causation_id
         WHERE period.tenant_id = NEW.tenant_id
           AND period.public_id = NEW.aggregate_public_id
           AND period.status = 'CLOSED'
           AND period.close_revision = (data->>'closeRevision')::BIGINT
           AND period.ledger_revision = (data->>'ledgerRevision')::BIGINT
           AND period.period_start = (data->>'periodStart')::DATE
           AND period.period_end = (data->>'periodEnd')::DATE
           AND period.time_group_public_id::TEXT = data->>'scopeId'
           AND result.rule_digest = data->>'ruleDigest'
           AND result.source_digest = data->>'sourceDigest'
           AND receipt.command_type = 'PERIOD_CLOSE'
           AND receipt.originating_action = 'TIME_PERIOD_CLOSE'
           AND receipt.aggregate_public_id = period.public_id
           AND receipt.correlation_id = NEW.correlation_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'TimePeriodClosed event facts are not owner-bound'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.schema_name = 'TimePeriodReopened.v1' THEN
        IF data - ARRAY['closePeriodId','newRevision','priorCloseRevision','reasonCode']
                <> '{}'::JSONB
           OR NOT (data ?& ARRAY[
                'closePeriodId','newRevision','priorCloseRevision','reasonCode'])
           OR jsonb_typeof(data->'closePeriodId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(data->'newRevision') IS DISTINCT FROM 'number'
           OR jsonb_typeof(data->'priorCloseRevision') IS DISTINCT FROM 'number'
           OR jsonb_typeof(data->'reasonCode') IS DISTINCT FROM 'string'
           OR data->>'newRevision' !~ '^[0-9]+$'
           OR data->>'priorCloseRevision' !~ '^[0-9]+$'
           OR NEW.aggregate_sequence <> (data->>'newRevision')::BIGINT
           OR data->>'closePeriodId' <> NEW.aggregate_public_id::TEXT
           OR data->>'closePeriodId' !~
                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR data->>'reasonCode' !~ '^[A-Z0-9][A-Z0-9._-]{0,79}$'
           OR (data->>'newRevision')::BIGINT < 1
           OR (data->>'priorCloseRevision')::BIGINT < 1
           OR (data->>'newRevision')::BIGINT
                <> (data->>'priorCloseRevision')::BIGINT + 1 THEN
            RAISE EXCEPTION 'TimePeriodReopened data violates XCON-010'
                USING ERRCODE = '23514';
        END IF;
        PERFORM 1
          FROM public.tme_close_periods period
          JOIN public.tme_close_command_receipts receipt
            ON receipt.tenant_id = period.tenant_id
           AND receipt.public_id = NEW.causation_id
         WHERE period.tenant_id = NEW.tenant_id
           AND period.public_id = NEW.aggregate_public_id
           AND period.status = 'REOPENED'
           AND period.close_revision = (data->>'priorCloseRevision')::BIGINT
           AND (data->>'newRevision')::BIGINT = period.close_revision + 1
           AND receipt.command_type = 'PERIOD_REOPEN'
           AND receipt.originating_action = 'TIME_PERIOD_REOPEN'
           AND receipt.aggregate_public_id = period.public_id
           AND receipt.correlation_id = NEW.correlation_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'TimePeriodReopened event facts are not owner-bound'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.schema_name = 'PayrollTimeHandoffReady.v1' THEN
        IF NEW.aggregate_sequence <> 1
           OR data - ARRAY['closePeriodId','closeRevision','closedTimeResultId',
                    'handoffId','lineCount','payPeriodId','payloadDigest',
                    'payrollEntityId','ruleDigest','sourceDigest','workerCount']
                <> '{}'::JSONB
           OR NOT (data ?& ARRAY['closePeriodId','closeRevision','closedTimeResultId',
                    'handoffId','lineCount','payPeriodId','payloadDigest',
                    'payrollEntityId','ruleDigest','sourceDigest','workerCount'])
	           OR jsonb_typeof(data->'closePeriodId') IS DISTINCT FROM 'string'
	           OR jsonb_typeof(data->'closeRevision') IS DISTINCT FROM 'number'
	           OR jsonb_typeof(data->'closedTimeResultId') IS DISTINCT FROM 'string'
	           OR jsonb_typeof(data->'handoffId') IS DISTINCT FROM 'string'
	           OR jsonb_typeof(data->'lineCount') IS DISTINCT FROM 'number'
	           OR jsonb_typeof(data->'payPeriodId') IS DISTINCT FROM 'string'
	           OR jsonb_typeof(data->'payloadDigest') IS DISTINCT FROM 'string'
	           OR jsonb_typeof(data->'payrollEntityId') IS DISTINCT FROM 'string'
	           OR jsonb_typeof(data->'ruleDigest') IS DISTINCT FROM 'string'
	           OR jsonb_typeof(data->'sourceDigest') IS DISTINCT FROM 'string'
	           OR jsonb_typeof(data->'workerCount') IS DISTINCT FROM 'number'
	           OR data->>'closeRevision' !~ '^[0-9]+$'
	           OR data->>'workerCount' !~ '^[0-9]+$'
	           OR data->>'lineCount' !~ '^[0-9]+$'
	           OR data->>'handoffId' <> NEW.aggregate_public_id::TEXT
	           OR data->>'closePeriodId' !~
	                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
	           OR data->>'closedTimeResultId' !~
	                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
	           OR data->>'handoffId' !~
	                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
	           OR data->>'payPeriodId' !~
	                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
	           OR data->>'payrollEntityId' !~
	                '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR data->>'payloadDigest' !~ '^[0-9a-f]{64}$'
           OR data->>'ruleDigest' !~ '^[0-9a-f]{64}$'
           OR data->>'sourceDigest' !~ '^[0-9a-f]{64}$'
           OR (data->>'closeRevision')::BIGINT < 1
	           OR (data->>'workerCount')::INTEGER NOT BETWEEN 0 AND 1000000
	           OR (data->>'lineCount')::INTEGER NOT BETWEEN 0 AND 1000000 THEN
            RAISE EXCEPTION 'PayrollTimeHandoffReady data violates XCON-011'
                USING ERRCODE = '23514';
        END IF;
        PERFORM 1
          FROM public.tme_payroll_handoffs handoff
          JOIN public.tme_closed_time_results result
            ON result.tenant_id = handoff.tenant_id
           AND result.closed_time_result_id = handoff.closed_time_result_id
          JOIN public.tme_close_command_receipts receipt
            ON receipt.tenant_id = handoff.tenant_id
           AND receipt.public_id = NEW.causation_id
         WHERE handoff.tenant_id = NEW.tenant_id
           AND handoff.public_id = NEW.aggregate_public_id
           AND handoff.close_period_public_id::TEXT = data->>'closePeriodId'
           AND handoff.close_revision = (data->>'closeRevision')::BIGINT
           AND result.public_id::TEXT = data->>'closedTimeResultId'
           AND handoff.public_id::TEXT = data->>'handoffId'
           AND handoff.line_count = (data->>'lineCount')::INTEGER
           AND handoff.pay_period_public_id::TEXT = data->>'payPeriodId'
           AND handoff.payload_digest = data->>'payloadDigest'
           AND handoff.payroll_entity_public_id::TEXT = data->>'payrollEntityId'
           AND handoff.rule_digest = data->>'ruleDigest'
           AND handoff.source_digest = data->>'sourceDigest'
           AND handoff.worker_count = (data->>'workerCount')::INTEGER
           AND receipt.command_type = 'PAYROLL_HANDOFF'
           AND receipt.originating_action = 'TIME_PAYROLL_HANDOFF'
           AND receipt.aggregate_public_id = handoff.close_period_public_id
           AND receipt.correlation_id = NEW.correlation_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'PayrollTimeHandoffReady event facts are not owner-bound'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unknown Time close event type' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range
        OR datetime_field_overflow THEN
    RAISE EXCEPTION 'Time close event numeric value is invalid' USING ERRCODE = '23514';
END;
$function$;

CREATE TRIGGER trg_tme_close_period_guard
    BEFORE INSERT OR UPDATE OR DELETE ON tme_close_periods
    FOR EACH ROW EXECUTE FUNCTION tme_guard_close_period();
CREATE TRIGGER trg_tme_close_attempt_immutable
    BEFORE UPDATE OR DELETE ON tme_close_attempts
    FOR EACH ROW EXECUTE FUNCTION tme_reject_close_evidence_mutation();
CREATE TRIGGER trg_tme_closed_result_immutable
    BEFORE UPDATE OR DELETE ON tme_closed_time_results
    FOR EACH ROW EXECUTE FUNCTION tme_reject_close_evidence_mutation();
CREATE TRIGGER trg_tme_closed_result_insert_guard
    BEFORE INSERT ON tme_closed_time_results
    FOR EACH ROW EXECUTE FUNCTION tme_guard_closed_result_insert();
CREATE TRIGGER trg_tme_closed_result_lineage
    BEFORE INSERT ON tme_closed_time_results
    FOR EACH ROW EXECUTE FUNCTION tme_validate_closed_result_lineage();
CREATE TRIGGER trg_tme_closed_result_line_immutable
    BEFORE UPDATE OR DELETE ON tme_closed_time_result_lines
    FOR EACH ROW EXECUTE FUNCTION tme_reject_close_evidence_mutation();
CREATE TRIGGER trg_tme_closed_result_line_insert_guard
    BEFORE INSERT ON tme_closed_time_result_lines
    FOR EACH ROW EXECUTE FUNCTION tme_guard_closed_result_insert();
CREATE TRIGGER trg_tme_closed_result_source_immutable
    BEFORE UPDATE OR DELETE ON tme_closed_time_result_sources
    FOR EACH ROW EXECUTE FUNCTION tme_reject_close_evidence_mutation();
CREATE TRIGGER trg_tme_closed_result_source_insert_guard
    BEFORE INSERT ON tme_closed_time_result_sources
    FOR EACH ROW EXECUTE FUNCTION tme_guard_closed_result_insert();
CREATE TRIGGER trg_tme_close_receipt_progression
    BEFORE INSERT OR UPDATE OR DELETE ON tme_close_command_receipts
    FOR EACH ROW EXECUTE FUNCTION tme_guard_close_receipt_progression();
CREATE TRIGGER trg_tme_handoff_guard
    BEFORE INSERT OR UPDATE OR DELETE ON tme_payroll_handoffs
    FOR EACH ROW EXECUTE FUNCTION tme_guard_handoff();
CREATE TRIGGER trg_tme_handoff_outcome_immutable
    BEFORE UPDATE OR DELETE ON tme_payroll_handoff_outcomes
    FOR EACH ROW EXECUTE FUNCTION tme_reject_close_evidence_mutation();
CREATE TRIGGER trg_tme_close_replay_immutable
    BEFORE UPDATE OR DELETE ON tme_close_workload_replay
    FOR EACH ROW EXECUTE FUNCTION tme_reject_close_evidence_mutation();
CREATE TRIGGER trg_tme_close_quarantine_immutable
    BEFORE UPDATE OR DELETE ON tme_close_quarantine
    FOR EACH ROW EXECUTE FUNCTION tme_reject_close_evidence_mutation();
CREATE TRIGGER trg_tme_close_outbox_delivery
    BEFORE UPDATE OR DELETE ON tme_close_outbox_events
    FOR EACH ROW EXECUTE FUNCTION tme_guard_close_outbox_delivery();
CREATE TRIGGER trg_tme_close_outbox_validate
    BEFORE INSERT ON tme_close_outbox_events
    FOR EACH ROW EXECUTE FUNCTION tme_validate_close_outbox_event();

DO $block$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'tme_close_periods',
        'tme_close_command_receipts',
        'tme_close_attempts',
        'tme_closed_time_results',
        'tme_closed_time_result_lines',
        'tme_closed_time_result_sources',
        'tme_payroll_handoffs',
        'tme_payroll_handoff_outcomes',
        'tme_close_workload_replay',
        'tme_close_quarantine',
        'tme_close_outbox_events'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING '
            || '(tenant_id = NULLIF(current_setting(''dwp.tenant_id'', true), '''')::BIGINT) '
            || 'WITH CHECK '
            || '(tenant_id = NULLIF(current_setting(''dwp.tenant_id'', true), '''')::BIGINT)',
            table_name || '_tenant_isolation', table_name);
    END LOOP;
END;
$block$;

REVOKE ALL ON TABLE
    tme_close_periods,
    tme_close_command_receipts,
    tme_close_attempts,
    tme_closed_time_results,
    tme_closed_time_result_lines,
    tme_closed_time_result_sources,
    tme_payroll_handoffs,
    tme_payroll_handoff_outcomes,
    tme_close_workload_replay,
    tme_close_quarantine,
    tme_close_outbox_events
FROM PUBLIC;

-- The current module is a consumer of externally provisioned periods. A future
-- producer requires a fresh role allocation and forward-only migration lease.
REVOKE ALL ON TABLE tme_close_periods FROM "${timeRuntimeRole}";
REVOKE ALL ON SEQUENCE tme_close_periods_close_period_id_seq FROM PUBLIC;
REVOKE USAGE, SELECT ON SEQUENCE tme_close_periods_close_period_id_seq
FROM "${timeRuntimeRole}";

DO $block$
DECLARE sequence_name TEXT;
BEGIN
    FOREACH sequence_name IN ARRAY ARRAY[
        pg_get_serial_sequence('tme_close_command_receipts', 'command_receipt_id'),
        pg_get_serial_sequence('tme_close_attempts', 'close_attempt_id'),
        pg_get_serial_sequence('tme_closed_time_results', 'closed_time_result_id'),
        pg_get_serial_sequence('tme_closed_time_result_lines', 'closed_time_result_line_id'),
        pg_get_serial_sequence('tme_closed_time_result_sources', 'closed_time_result_source_id'),
        pg_get_serial_sequence('tme_payroll_handoffs', 'payroll_handoff_id'),
        pg_get_serial_sequence('tme_payroll_handoff_outcomes', 'payroll_handoff_outcome_id'),
        pg_get_serial_sequence('tme_close_workload_replay', 'replay_id'),
        pg_get_serial_sequence('tme_close_quarantine', 'close_quarantine_id'),
        pg_get_serial_sequence('tme_close_outbox_events', 'outbox_event_id')
    ] LOOP
        EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC', sequence_name);
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
            sequence_name, '${timeRuntimeRole}');
    END LOOP;
END;
$block$;

GRANT SELECT ON TABLE tme_close_periods
TO "${timeRuntimeRole}";

GRANT SELECT, INSERT ON TABLE
    tme_close_command_receipts,
    tme_close_attempts,
    tme_closed_time_results,
    tme_closed_time_result_lines,
    tme_closed_time_result_sources,
    tme_payroll_handoffs,
    tme_payroll_handoff_outcomes,
    tme_close_workload_replay,
    tme_close_quarantine,
    tme_close_outbox_events
TO "${timeRuntimeRole}";

GRANT UPDATE (
    status, close_revision, ledger_revision, latest_result_public_id,
    closed_by_subject_public_id, close_approval_actor_public_id,
    latest_correction_maker_subject_public_id, closed_at,
    row_version, updated_at, updated_by)
ON tme_close_periods TO "${timeRuntimeRole}";
GRANT UPDATE (
    status, aggregate_version, result_ref, error_code, completed_at)
ON tme_close_command_receipts TO "${timeRuntimeRole}";
GRANT UPDATE (
    status, receiver_receipt_public_id, receiver_receipt_revision,
    receiver_receipt_digest, receiver_reason_code, outcome_at,
    row_version, updated_at)
ON tme_payroll_handoffs TO "${timeRuntimeRole}";

GRANT SELECT ON TABLE tme_close_outbox_events
TO "${timeProjectionPublisherRole}";
GRANT UPDATE (published_at, publish_attempts) ON TABLE tme_close_outbox_events
TO "${timeProjectionPublisherRole}";

REVOKE ALL ON FUNCTION
    tme_reject_close_evidence_mutation(),
    tme_guard_close_receipt_progression(),
    tme_validate_closed_result_lineage(),
    tme_guard_closed_result_insert(),
    tme_guard_close_period(),
    tme_guard_handoff(),
    tme_guard_close_outbox_delivery(),
    tme_validate_close_outbox_event()
FROM PUBLIC;
REVOKE ALL ON FUNCTION
    tme_reject_close_evidence_mutation(),
    tme_guard_close_receipt_progression(),
    tme_validate_closed_result_lineage(),
    tme_guard_closed_result_insert(),
    tme_guard_close_period(),
    tme_guard_handoff(),
    tme_guard_close_outbox_delivery(),
    tme_validate_close_outbox_event()
FROM "${timeRuntimeRole}";
REVOKE ALL ON FUNCTION
    tme_reject_close_evidence_mutation(),
    tme_guard_close_receipt_progression(),
    tme_validate_closed_result_lineage(),
    tme_guard_closed_result_insert(),
    tme_guard_close_period(),
    tme_guard_handoff(),
    tme_guard_close_outbox_delivery(),
    tme_validate_close_outbox_event()
FROM "${timeProjectionPublisherRole}";
