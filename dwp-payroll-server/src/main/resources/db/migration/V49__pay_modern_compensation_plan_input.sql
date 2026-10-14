-- Slice BASE-TFR-PAY-016.
-- Immutable PAY consumption of the approved compensation-plan snapshot. This
-- migration does not release payroll calculation or bind a payroll run.

CREATE TABLE pay_compensation_plan_event_inbox (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    schema_version INTEGER NOT NULL,
    snapshot_id UUID,
    snapshot_revision BIGINT,
    snapshot_payload_digest CHAR(64),
    correlation_id UUID,
    envelope_digest CHAR(64) NOT NULL,
    event_envelope JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT pay_compensation_event_positive
        CHECK (tenant_id > 0 AND aggregate_sequence > 0
            AND (snapshot_revision IS NULL OR snapshot_revision > 0)),
    CONSTRAINT pay_compensation_event_contract
        CHECK (event_type =
            'dwp.hris.modern.approved-compensation-plan-snapshot-published.v2'
            AND schema_version = 2),
    CONSTRAINT pay_compensation_event_digest_shape
        CHECK (envelope_digest ~ '^[0-9a-f]{64}$'
            AND (snapshot_payload_digest IS NULL
                OR snapshot_payload_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT pay_compensation_event_envelope_object
        CHECK (jsonb_typeof(event_envelope) = 'object')
);

CREATE INDEX pay_compensation_event_subject_order_idx
    ON pay_compensation_plan_event_inbox
        (tenant_id, aggregate_id, aggregate_sequence DESC);

CREATE TABLE pay_compensation_plan_event_attempts (
    tenant_id BIGINT NOT NULL,
    attempt_id UUID NOT NULL,
    event_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    attempt_kind VARCHAR(16) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    failure_code VARCHAR(80),
    failure_detail VARCHAR(800),
    actor_type VARCHAR(24) NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, attempt_id),
    CONSTRAINT pay_compensation_attempt_event_fk
        FOREIGN KEY (tenant_id, event_id)
        REFERENCES pay_compensation_plan_event_inbox (tenant_id, event_id),
    CONSTRAINT pay_compensation_attempt_number_unique
        UNIQUE (tenant_id, event_id, attempt_number),
    CONSTRAINT pay_compensation_attempt_positive
        CHECK (tenant_id > 0 AND attempt_number > 0),
    CONSTRAINT pay_compensation_attempt_kind_valid
        CHECK (attempt_kind IN ('CONSUME', 'REPLAY')),
    CONSTRAINT pay_compensation_attempt_outcome_valid
        CHECK (outcome IN ('APPLIED', 'QUARANTINED')),
    CONSTRAINT pay_compensation_attempt_failure_consistent
        CHECK ((outcome = 'APPLIED'
                AND failure_code IS NULL AND failure_detail IS NULL)
            OR (outcome = 'QUARANTINED'
                AND failure_code IS NOT NULL AND btrim(failure_code) <> '')),
    CONSTRAINT pay_compensation_attempt_actor_present
        CHECK (btrim(actor_type) <> '' AND btrim(actor_id) <> '')
);

CREATE INDEX pay_compensation_attempt_latest_idx
    ON pay_compensation_plan_event_attempts
        (tenant_id, event_id, attempt_number DESC);

CREATE TABLE pay_compensation_plan_inputs (
    compensation_plan_input_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    public_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    snapshot_revision BIGINT NOT NULL,
    snapshot_payload_digest CHAR(64) NOT NULL,
    plan_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    approval_receipt_id UUID NOT NULL,
    approval_revision BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    line_sequence INTEGER NOT NULL,
    worker_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    component_code VARCHAR(80) NOT NULL,
    currency CHAR(3) NOT NULL,
    approved_amount NUMERIC(19,4) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    payload_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_compensation_input_event_fk
        FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES pay_compensation_plan_event_inbox (tenant_id, event_id),
    CONSTRAINT pay_compensation_input_internal_tenant_unique
        UNIQUE (tenant_id, compensation_plan_input_id),
    CONSTRAINT pay_compensation_input_public_unique
        UNIQUE (tenant_id, public_id),
    CONSTRAINT pay_compensation_input_line_unique
        UNIQUE (tenant_id, snapshot_id, snapshot_revision,
            worker_id, assignment_id, component_code),
    CONSTRAINT pay_compensation_input_sequence_unique
        UNIQUE (tenant_id, snapshot_id, snapshot_revision, line_sequence),
    CONSTRAINT pay_compensation_input_digest_unique
        UNIQUE (tenant_id, payload_digest),
    CONSTRAINT pay_compensation_input_positive
        CHECK (tenant_id > 0 AND snapshot_revision > 0
            AND approval_revision > 0 AND source_version > 0
            AND line_sequence > 0),
    CONSTRAINT pay_compensation_input_amount_nonnegative
        CHECK (approved_amount >= 0),
    CONSTRAINT pay_compensation_input_component_shape
        CHECK (component_code ~ '^[A-Z0-9][A-Z0-9._-]{0,79}$'),
    CONSTRAINT pay_compensation_input_currency_shape
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT pay_compensation_input_digest_shape
        CHECK (snapshot_payload_digest ~ '^[0-9a-f]{64}$'
            AND payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_compensation_input_period_valid
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX pay_compensation_input_cycle_worker_idx
    ON pay_compensation_plan_inputs
        (tenant_id, cycle_id, worker_id, effective_from);

CREATE INDEX pay_compensation_input_snapshot_revision_idx
    ON pay_compensation_plan_inputs
        (tenant_id, snapshot_id, snapshot_revision DESC);

CREATE TABLE pay_compensation_plan_input_receipts (
    tenant_id BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    snapshot_id UUID NOT NULL,
    snapshot_revision BIGINT NOT NULL,
    snapshot_payload_digest CHAR(64) NOT NULL,
    line_count INTEGER NOT NULL,
    outbox_event_id UUID NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, receipt_id),
    CONSTRAINT pay_compensation_receipt_event_fk
        FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES pay_compensation_plan_event_inbox (tenant_id, event_id),
    CONSTRAINT pay_compensation_receipt_event_unique
        UNIQUE (tenant_id, source_event_id),
    CONSTRAINT pay_compensation_receipt_outbox_unique
        UNIQUE (tenant_id, outbox_event_id),
    CONSTRAINT pay_compensation_receipt_positive
        CHECK (tenant_id > 0 AND aggregate_sequence > 0
            AND snapshot_revision > 0 AND line_count > 0),
    CONSTRAINT pay_compensation_receipt_digest_shape
        CHECK (snapshot_payload_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX pay_compensation_receipt_subject_order_idx
    ON pay_compensation_plan_input_receipts
        (tenant_id, aggregate_id, aggregate_sequence DESC);

CREATE TABLE pay_compensation_plan_input_outbox_events (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    receipt_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL,
    correlation_id UUID NOT NULL,
    event_payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT pay_compensation_outbox_receipt_fk
        FOREIGN KEY (tenant_id, receipt_id)
        REFERENCES pay_compensation_plan_input_receipts (tenant_id, receipt_id),
    CONSTRAINT pay_compensation_outbox_receipt_unique
        UNIQUE (tenant_id, receipt_id),
    CONSTRAINT pay_compensation_outbox_positive
        CHECK (tenant_id > 0 AND aggregate_sequence > 0),
    CONSTRAINT pay_compensation_outbox_contract
        CHECK (event_type = 'PayrollCompensationPlanInputMaterialized'
            AND schema_version = 1),
    CONSTRAINT pay_compensation_outbox_payload_object
        CHECK (jsonb_typeof(event_payload) = 'object'),
    CONSTRAINT pay_compensation_outbox_minimized
        CHECK (event_payload ?& ARRAY[
                'receiptId', 'sourceEventId', 'snapshotId',
                'snapshotRevision', 'payloadDigest', 'lineCount']
            AND NOT (event_payload ?| ARRAY[
                'approvedAmount', 'workerId', 'assignmentId', 'lines']))
);

CREATE INDEX pay_compensation_outbox_order_idx
    ON pay_compensation_plan_input_outbox_events
        (tenant_id, occurred_at, event_id);

CREATE TABLE pay_compensation_plan_input_replay_audit (
    tenant_id BIGINT NOT NULL,
    audit_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    actor_type VARCHAR(24) NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    authority VARCHAR(120) NOT NULL,
    reason VARCHAR(800) NOT NULL,
    prior_outcome VARCHAR(24) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, audit_id),
    CONSTRAINT pay_compensation_replay_event_fk
        FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES pay_compensation_plan_event_inbox (tenant_id, event_id),
    CONSTRAINT pay_compensation_replay_authority_exact
        CHECK (actor_type IN ('TENANT_ADMIN', 'PLATFORM_ADMIN')
            AND authority = 'DATA.HRIS_PAYROLL_INPUT:UPDATE'),
    CONSTRAINT pay_compensation_replay_prior_outcome
        CHECK (prior_outcome = 'QUARANTINED'),
    CONSTRAINT pay_compensation_replay_reason_present
        CHECK (btrim(actor_id) <> '' AND btrim(reason) <> '')
);

CREATE INDEX pay_compensation_replay_event_idx
    ON pay_compensation_plan_input_replay_audit
        (tenant_id, source_event_id, occurred_at DESC);

CREATE FUNCTION pay_compensation_plan_input_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $pay_compensation_input_immutable$
BEGIN
    RAISE EXCEPTION 'PAY compensation plan input ledgers are immutable';
END
$pay_compensation_input_immutable$;

CREATE TRIGGER pay_compensation_event_inbox_immutable
    BEFORE UPDATE OR DELETE ON pay_compensation_plan_event_inbox
    FOR EACH ROW EXECUTE FUNCTION pay_compensation_plan_input_reject_mutation();
CREATE TRIGGER pay_compensation_event_attempt_immutable
    BEFORE UPDATE OR DELETE ON pay_compensation_plan_event_attempts
    FOR EACH ROW EXECUTE FUNCTION pay_compensation_plan_input_reject_mutation();
CREATE TRIGGER pay_compensation_input_immutable
    BEFORE UPDATE OR DELETE ON pay_compensation_plan_inputs
    FOR EACH ROW EXECUTE FUNCTION pay_compensation_plan_input_reject_mutation();
CREATE TRIGGER pay_compensation_receipt_immutable
    BEFORE UPDATE OR DELETE ON pay_compensation_plan_input_receipts
    FOR EACH ROW EXECUTE FUNCTION pay_compensation_plan_input_reject_mutation();
CREATE TRIGGER pay_compensation_outbox_immutable
    BEFORE UPDATE OR DELETE ON pay_compensation_plan_input_outbox_events
    FOR EACH ROW EXECUTE FUNCTION pay_compensation_plan_input_reject_mutation();
CREATE TRIGGER pay_compensation_replay_audit_immutable
    BEFORE UPDATE OR DELETE ON pay_compensation_plan_input_replay_audit
    FOR EACH ROW EXECUTE FUNCTION pay_compensation_plan_input_reject_mutation();

REVOKE ALL ON FUNCTION pay_compensation_plan_input_reject_mutation() FROM PUBLIC;

REVOKE ALL ON TABLE pay_compensation_plan_event_inbox FROM PUBLIC;
REVOKE ALL ON TABLE pay_compensation_plan_event_attempts FROM PUBLIC;
REVOKE ALL ON TABLE pay_compensation_plan_inputs FROM PUBLIC;
REVOKE ALL ON TABLE pay_compensation_plan_input_receipts FROM PUBLIC;
REVOKE ALL ON TABLE pay_compensation_plan_input_outbox_events FROM PUBLIC;
REVOKE ALL ON TABLE pay_compensation_plan_input_replay_audit FROM PUBLIC;
REVOKE ALL ON SEQUENCE pay_compensation_plan_inputs_compensation_plan_input_id_seq
    FROM PUBLIC;

ALTER TABLE pay_compensation_plan_event_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_event_inbox FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_event_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_event_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_inputs ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_inputs FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_input_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_input_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_input_outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_input_outbox_events FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_input_replay_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_compensation_plan_input_replay_audit FORCE ROW LEVEL SECURITY;

CREATE POLICY pay_compensation_event_inbox_tenant_policy
    ON pay_compensation_plan_event_inbox
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);
CREATE POLICY pay_compensation_event_attempt_tenant_policy
    ON pay_compensation_plan_event_attempts
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);
CREATE POLICY pay_compensation_input_tenant_policy
    ON pay_compensation_plan_inputs
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);
CREATE POLICY pay_compensation_receipt_tenant_policy
    ON pay_compensation_plan_input_receipts
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);
CREATE POLICY pay_compensation_outbox_tenant_policy
    ON pay_compensation_plan_input_outbox_events
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);
CREATE POLICY pay_compensation_replay_audit_tenant_policy
    ON pay_compensation_plan_input_replay_audit
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

DO $pay_compensation_runtime_access$
DECLARE
    runtime_role_name TEXT := '${payrollRuntimeRole}';
    runtime_role RECORD;
    table_name TEXT;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid payroll runtime role name';
    END IF;
    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
      INTO runtime_role
      FROM pg_roles
     WHERE rolname = runtime_role_name;
    IF NOT FOUND OR runtime_role.rolsuper OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION 'Payroll runtime role is missing or unsafe';
    END IF;

    FOREACH table_name IN ARRAY ARRAY[
        'pay_compensation_plan_event_inbox',
        'pay_compensation_plan_event_attempts',
        'pay_compensation_plan_inputs',
        'pay_compensation_plan_input_receipts',
        'pay_compensation_plan_input_outbox_events',
        'pay_compensation_plan_input_replay_audit']
    LOOP
        EXECUTE format(
            'GRANT SELECT, INSERT ON TABLE %I TO %I',
            table_name, runtime_role_name);
    END LOOP;
    EXECUTE format(
        'GRANT USAGE, SELECT ON SEQUENCE '
        || 'pay_compensation_plan_inputs_compensation_plan_input_id_seq TO %I',
        runtime_role_name);
END
$pay_compensation_runtime_access$;

COMMENT ON TABLE pay_compensation_plan_event_inbox IS
    'Immutable idempotent XCON-021 inbox for BASE-TFR-PAY-016.';
COMMENT ON TABLE pay_compensation_plan_event_attempts IS
    'Append-only consume and replay outcomes, including quarantine evidence.';
COMMENT ON TABLE pay_compensation_plan_inputs IS
    'Immutable XCON-020 compensation lines; not a released payroll calculation input snapshot.';
COMMENT ON TABLE pay_compensation_plan_input_receipts IS
    'Exactly-once effective consumption receipts for approved compensation snapshots.';
COMMENT ON TABLE pay_compensation_plan_input_outbox_events IS
    'Minimized local outbox proving successful PAY-016 materialization.';
COMMENT ON TABLE pay_compensation_plan_input_replay_audit IS
    'Append-only privileged quarantine replay audit evidence.';
