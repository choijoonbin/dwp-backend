-- BASE-TFR-TIM-010 / W2-20261013-TIM-010-START-R1
-- Frozen typed rules, interpretation attempts, segments, ledger entries, receipts and events are
-- append-only. A correction appends one reversal for each prior effective entry and a new attempt.

CREATE TABLE tme_interpretation_rule_versions (
    interpretation_rule_version_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    lifecycle_state VARCHAR(20) NOT NULL,
    registry_id VARCHAR(80) NOT NULL,
    registry_version INTEGER NOT NULL,
    rule_payload JSONB NOT NULL,
    rule_digest CHAR(64) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_interpretation_rule_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_interpretation_rule_revision UNIQUE (tenant_id, revision),
    CONSTRAINT ck_tme_interpretation_rule_revision CHECK (revision > 0),
    CONSTRAINT ck_tme_interpretation_rule_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_tme_interpretation_rule_state CHECK (lifecycle_state = 'PUBLISHED'),
    CONSTRAINT ck_tme_interpretation_rule_registry
        CHECK (registry_id = 'TIM_RULE_AST_V1' AND registry_version = 1),
    CONSTRAINT ck_tme_interpretation_rule_payload CHECK (
        jsonb_typeof(rule_payload) = 'array' AND jsonb_array_length(rule_payload) > 0),
    CONSTRAINT ck_tme_interpretation_rule_digest CHECK (rule_digest ~ '^[0-9a-f]{64}$')
);
ALTER TABLE tme_interpretation_rule_versions
    ADD CONSTRAINT ex_tme_interpretation_rule_effective EXCLUDE USING gist (
        tenant_id WITH =, daterange(effective_from, effective_to, '[)') WITH &&);

CREATE TABLE tme_interpretation_runs (
    interpretation_run_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    run_scope VARCHAR(20) NOT NULL,
    worker_scope_digest CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    rule_version_public_id UUID NOT NULL,
    rule_version_revision BIGINT NOT NULL,
    input_snapshot_digest CHAR(64) NOT NULL,
    rule_snapshot_digest CHAR(64) NOT NULL,
    output_digest CHAR(64) NOT NULL,
    requested_idempotency_key UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    ledger_delta_applied BOOLEAN NOT NULL,
    supersedes_run_public_id UUID,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT NOT NULL,
    CONSTRAINT uk_tme_interpretation_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_interpretation_internal UNIQUE (tenant_id, interpretation_run_id),
    CONSTRAINT uk_tme_interpretation_idem UNIQUE (
        tenant_id, subject_principal_public_id, requested_idempotency_key),
    CONSTRAINT fk_tme_interpretation_rule FOREIGN KEY (
        tenant_id, rule_version_public_id)
        REFERENCES tme_interpretation_rule_versions (tenant_id, public_id),
    CONSTRAINT fk_tme_interpretation_supersedes FOREIGN KEY (
        tenant_id, supersedes_run_public_id)
        REFERENCES tme_interpretation_runs (tenant_id, public_id),
    CONSTRAINT ck_tme_interpretation_period CHECK (period_end >= period_start),
    CONSTRAINT ck_tme_interpretation_scope CHECK (
        run_scope IN ('LEGAL_ENTITY','ORG_TREE','ORG_UNIT','SELF','TIME_GROUP')),
    CONSTRAINT ck_tme_interpretation_status CHECK (
        status IN ('SUCCEEDED','FAILED','RESULT_UNKNOWN','SUPERSEDED')),
    CONSTRAINT ck_tme_interpretation_attempt CHECK (attempt_no > 0),
    CONSTRAINT ck_tme_interpretation_actor CHECK (created_by > 0),
    CONSTRAINT ck_tme_interpretation_digests CHECK (
        worker_scope_digest ~ '^[0-9a-f]{64}$'
        AND input_snapshot_digest ~ '^[0-9a-f]{64}$'
        AND rule_snapshot_digest ~ '^[0-9a-f]{64}$'
        AND output_digest ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_tme_interpretation_period
    ON tme_interpretation_runs (tenant_id, period_start, period_end, created_at DESC);
CREATE UNIQUE INDEX uk_tme_interpretation_semantic_ledger
    ON tme_interpretation_runs (
        tenant_id, worker_scope_digest, period_start, period_end,
        input_snapshot_digest, rule_snapshot_digest, output_digest,
        supersedes_run_public_id) NULLS NOT DISTINCT
    WHERE ledger_delta_applied;

CREATE TABLE tme_interpretation_attempts (
    interpretation_attempt_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    interpretation_run_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    output_digest CHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_interpretation_attempt_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_interpretation_attempt_internal UNIQUE (
        tenant_id, interpretation_attempt_id),
    CONSTRAINT uk_tme_interpretation_attempt_no UNIQUE (
        tenant_id, interpretation_run_id, attempt_no),
    CONSTRAINT fk_tme_interpretation_attempt_run FOREIGN KEY (
        tenant_id, interpretation_run_id)
        REFERENCES tme_interpretation_runs (tenant_id, interpretation_run_id),
    CONSTRAINT ck_tme_interpretation_attempt_no CHECK (attempt_no > 0),
    CONSTRAINT ck_tme_interpretation_attempt_status CHECK (status = 'SUCCEEDED'),
    CONSTRAINT ck_tme_interpretation_attempt_time CHECK (finished_at >= started_at),
    CONSTRAINT ck_tme_interpretation_attempt_digest
        CHECK (output_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE tme_interpreted_segments (
    interpreted_segment_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    interpretation_attempt_id BIGINT NOT NULL,
    worker_public_id UUID NOT NULL,
    segment_sequence INTEGER NOT NULL,
    segment_type VARCHAR(30) NOT NULL,
    minutes INTEGER NOT NULL,
    input_digest CHAR(64) NOT NULL,
    rule_trace_digest CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_interpreted_segment_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_interpreted_segment_sequence UNIQUE (
        tenant_id, interpretation_attempt_id, worker_public_id, segment_sequence),
    CONSTRAINT fk_tme_interpreted_attempt FOREIGN KEY (
        tenant_id, interpretation_attempt_id)
        REFERENCES tme_interpretation_attempts (tenant_id, interpretation_attempt_id),
    CONSTRAINT ck_tme_interpreted_segment_sequence CHECK (segment_sequence > 0),
    CONSTRAINT ck_tme_interpreted_segment_minutes CHECK (minutes BETWEEN 0 AND 525600),
    CONSTRAINT ck_tme_interpreted_segment_type CHECK (
        segment_type IN ('REGULAR_MINUTES','OVERTIME_MINUTES','UNPAID_BREAK_MINUTES')),
    CONSTRAINT ck_tme_interpreted_segment_digests CHECK (
        input_digest ~ '^[0-9a-f]{64}$' AND rule_trace_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE tme_time_ledger_entries (
    time_ledger_entry_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    worker_public_id UUID NOT NULL,
    local_work_date DATE NOT NULL,
    pay_code VARCHAR(80) NOT NULL,
    unit VARCHAR(12) NOT NULL,
    quantity NUMERIC(19,6) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_public_id UUID NOT NULL,
    interpretation_attempt_public_id UUID NOT NULL,
    reverses_entry_public_id UUID,
    reason_code VARCHAR(80),
    ledger_revision BIGINT NOT NULL,
    input_digest CHAR(64) NOT NULL,
    rule_digest CHAR(64) NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL,
    posted_by BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT uk_tme_time_ledger_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_time_ledger_public_posted UNIQUE (tenant_id, public_id, posted_at),
    CONSTRAINT uk_tme_time_ledger_revision UNIQUE (
        tenant_id, worker_public_id, ledger_revision),
    CONSTRAINT fk_tme_time_ledger_attempt FOREIGN KEY (
        tenant_id, interpretation_attempt_public_id)
        REFERENCES tme_interpretation_attempts (tenant_id, public_id),
    CONSTRAINT fk_tme_time_ledger_reversal FOREIGN KEY (
        tenant_id, reverses_entry_public_id)
        REFERENCES tme_time_ledger_entries (tenant_id, public_id),
    CONSTRAINT ck_tme_time_ledger_revision CHECK (ledger_revision > 0),
    CONSTRAINT ck_tme_time_ledger_unit CHECK (unit = 'MINUTE'),
    CONSTRAINT ck_tme_time_ledger_source CHECK (
        source_type IN ('INTERPRETATION','REVERSAL')),
    CONSTRAINT ck_tme_time_ledger_reversal CHECK (
        (source_type = 'REVERSAL') = (reverses_entry_public_id IS NOT NULL)),
    CONSTRAINT ck_tme_time_ledger_reversal_quantity CHECK (
        (source_type = 'REVERSAL' AND quantity <= 0 AND reason_code = 'CLOCK_INPUT_REINTERPRETED')
        OR (source_type = 'INTERPRETATION' AND quantity >= 0 AND reason_code IS NULL)),
    CONSTRAINT ck_tme_time_ledger_not_self_reversal CHECK (
        reverses_entry_public_id IS NULL OR reverses_entry_public_id <> public_id),
    CONSTRAINT ck_tme_time_ledger_actor CHECK (posted_by > 0),
    CONSTRAINT ck_tme_time_ledger_digests CHECK (
        input_digest ~ '^[0-9a-f]{64}$' AND rule_digest ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_tme_time_ledger_worker_date
    ON tme_time_ledger_entries (
        tenant_id, worker_public_id, local_work_date, pay_code, ledger_revision);
CREATE UNIQUE INDEX uk_tme_time_ledger_single_reversal
    ON tme_time_ledger_entries (tenant_id, reverses_entry_public_id)
    WHERE source_type = 'REVERSAL';

CREATE TABLE tme_interpretation_trace_steps (
    interpretation_trace_step_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    interpretation_attempt_id BIGINT NOT NULL,
    sequence INTEGER NOT NULL,
    rule_code VARCHAR(80) NOT NULL,
    input_minutes INTEGER NOT NULL,
    output_minutes INTEGER NOT NULL,
    CONSTRAINT uk_tme_interpretation_trace_sequence UNIQUE (
        tenant_id, interpretation_attempt_id, sequence),
    CONSTRAINT fk_tme_interpretation_trace_attempt FOREIGN KEY (
        tenant_id, interpretation_attempt_id)
        REFERENCES tme_interpretation_attempts (tenant_id, interpretation_attempt_id),
    CONSTRAINT ck_tme_interpretation_trace_sequence CHECK (sequence > 0),
    CONSTRAINT ck_tme_interpretation_trace_rule
        CHECK (rule_code ~ '^[A-Z][A-Z0-9_]{1,79}$')
);

CREATE TABLE tme_interpretation_command_receipts (
    command_receipt_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    command_type VARCHAR(40) NOT NULL,
    originating_action VARCHAR(40) NOT NULL,
    actor_id BIGINT NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    population_public_id UUID NOT NULL,
    population_revision BIGINT NOT NULL,
    grant_revision BIGINT NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    field_policy_revision BIGINT NOT NULL,
    purpose_code VARCHAR(120) NOT NULL,
    authorization_revision BIGINT NOT NULL,
    request_digest CHAR(64) NOT NULL,
    result_run_public_id UUID NOT NULL,
    result_digest CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_interpretation_receipt_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_interpretation_receipt_idempotency UNIQUE (
        tenant_id, subject_principal_public_id, command_type, idempotency_key),
    CONSTRAINT fk_tme_interpretation_receipt_run FOREIGN KEY (
        tenant_id, result_run_public_id)
        REFERENCES tme_interpretation_runs (tenant_id, public_id),
    CONSTRAINT ck_tme_interpretation_receipt_command
        CHECK (command_type = 'INTERPRETATION_RUN'),
    CONSTRAINT ck_tme_interpretation_receipt_action
        CHECK (originating_action = 'TIME_INTERPRET_RUN'),
    CONSTRAINT ck_tme_interpretation_receipt_actor CHECK (actor_id > 0),
    CONSTRAINT ck_tme_interpretation_receipt_revisions CHECK (
        population_revision > 0 AND grant_revision > 0
        AND field_policy_revision > 0 AND authorization_revision > 0),
    CONSTRAINT ck_tme_interpretation_receipt_status CHECK (status = 'SUCCEEDED'),
    CONSTRAINT ck_tme_interpretation_receipt_digests CHECK (
        population_scope_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^[0-9a-f]{64}$'
        AND result_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE tme_interpretation_outbox_events (
    outbox_event_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_public_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    schema_name VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_tme_interpretation_outbox_event UNIQUE (tenant_id, event_id),
    CONSTRAINT fk_tme_interpretation_outbox_run FOREIGN KEY (
        tenant_id, aggregate_sequence)
        REFERENCES tme_interpretation_runs (tenant_id, interpretation_run_id),
    CONSTRAINT ck_tme_interpretation_outbox_aggregate
        CHECK (aggregate_type = 'InterpretationRun'),
    CONSTRAINT ck_tme_interpretation_outbox_schema
        CHECK (schema_name = 'TimeInterpreted' AND schema_version = 1),
    CONSTRAINT ck_tme_interpretation_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND payload ?& ARRAY[
            'runId','periodStart','periodEnd','inputDigest',
            'ruleDigest','outputDigest','attemptNo']),
    CONSTRAINT ck_tme_interpretation_outbox_digest
        CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_interpretation_outbox_attempts CHECK (publish_attempts >= 0)
);
CREATE INDEX ix_tme_interpretation_outbox_unpublished
    ON tme_interpretation_outbox_events (occurred_at, outbox_event_id)
    WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION tme_reject_interpretation_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION 'interpretation evidence is append-only' USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION tme_guard_interpretation_outbox_delivery()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'interpretation outbox events cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF ROW(NEW.tenant_id, NEW.event_id, NEW.aggregate_type, NEW.aggregate_public_id,
           NEW.aggregate_sequence, NEW.schema_name, NEW.schema_version, NEW.payload,
           NEW.payload_digest, NEW.correlation_id, NEW.causation_id, NEW.occurred_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.event_id, OLD.aggregate_type, OLD.aggregate_public_id,
           OLD.aggregate_sequence, OLD.schema_name, OLD.schema_version, OLD.payload,
           OLD.payload_digest, OLD.correlation_id, OLD.causation_id, OLD.occurred_at) THEN
        RAISE EXCEPTION 'interpretation outbox envelope is immutable' USING ERRCODE = '55000';
    END IF;
    IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION 'interpretation outbox publication is immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.publish_attempts < OLD.publish_attempts THEN
        RAISE EXCEPTION 'interpretation outbox attempts must be monotonic' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;

DO $block$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'tme_interpretation_rule_versions',
        'tme_interpretation_runs',
        'tme_interpretation_attempts',
        'tme_interpreted_segments',
        'tme_time_ledger_entries',
        'tme_interpretation_trace_steps',
        'tme_interpretation_command_receipts'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I '
            || 'FOR EACH ROW EXECUTE FUNCTION tme_reject_interpretation_evidence_mutation()',
            'trg_' || table_name || '_immutable', table_name);
    END LOOP;
END;
$block$;
CREATE TRIGGER trg_tme_interpretation_outbox_delivery
    BEFORE UPDATE OR DELETE ON tme_interpretation_outbox_events
    FOR EACH ROW EXECUTE FUNCTION tme_guard_interpretation_outbox_delivery();

DO $block$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'tme_interpretation_rule_versions',
        'tme_interpretation_runs',
        'tme_interpretation_attempts',
        'tme_interpreted_segments',
        'tme_time_ledger_entries',
        'tme_interpretation_trace_steps',
        'tme_interpretation_command_receipts',
        'tme_interpretation_outbox_events'
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
    tme_interpretation_rule_versions,
    tme_interpretation_runs,
    tme_interpretation_attempts,
    tme_interpreted_segments,
    tme_time_ledger_entries,
    tme_interpretation_trace_steps,
    tme_interpretation_command_receipts,
    tme_interpretation_outbox_events
FROM PUBLIC;
DO $block$
DECLARE evidence RECORD;
DECLARE sequence_name TEXT;
BEGIN
    FOR evidence IN SELECT * FROM (VALUES
        ('tme_interpretation_rule_versions', 'interpretation_rule_version_id', FALSE),
        ('tme_interpretation_runs', 'interpretation_run_id', TRUE),
        ('tme_interpretation_attempts', 'interpretation_attempt_id', TRUE),
        ('tme_interpreted_segments', 'interpreted_segment_id', TRUE),
        ('tme_time_ledger_entries', 'time_ledger_entry_id', TRUE),
        ('tme_interpretation_trace_steps', 'interpretation_trace_step_id', TRUE),
        ('tme_interpretation_command_receipts', 'command_receipt_id', TRUE),
        ('tme_interpretation_outbox_events', 'outbox_event_id', TRUE)
    ) AS sequences(table_name, column_name, runtime_writes) LOOP
        sequence_name := pg_get_serial_sequence(evidence.table_name, evidence.column_name);
        EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC', sequence_name);
        IF evidence.runtime_writes THEN
            EXECUTE format(
                'GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
                sequence_name, '${timeRuntimeRole}');
        END IF;
    END LOOP;
END;
$block$;

GRANT SELECT ON TABLE tme_interpretation_rule_versions TO "${timeRuntimeRole}";
GRANT SELECT, INSERT ON TABLE
    tme_interpretation_runs,
    tme_interpretation_attempts,
    tme_interpreted_segments,
    tme_time_ledger_entries,
    tme_interpretation_trace_steps,
    tme_interpretation_command_receipts,
    tme_interpretation_outbox_events
TO "${timeRuntimeRole}";
GRANT SELECT ON TABLE tme_interpretation_outbox_events
TO "${timeProjectionPublisherRole}";
GRANT UPDATE (published_at, publish_attempts) ON TABLE tme_interpretation_outbox_events
TO "${timeProjectionPublisherRole}";

REVOKE ALL ON FUNCTION
    tme_reject_interpretation_evidence_mutation(),
    tme_guard_interpretation_outbox_delivery()
FROM PUBLIC;
REVOKE ALL ON FUNCTION
    tme_reject_interpretation_evidence_mutation(),
    tme_guard_interpretation_outbox_delivery()
FROM "${timeRuntimeRole}";
REVOKE ALL ON FUNCTION
    tme_reject_interpretation_evidence_mutation(),
    tme_guard_interpretation_outbox_delivery()
FROM "${timeProjectionPublisherRole}";
