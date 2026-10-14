-- BASE-TFR-TIM-017 / HRIS-WAVE5-START-BASE-TFR-TIM-017-R1
-- Canonical Timecards extend the immutable V10 ledger forward. V1-V10 remain byte-identical.

ALTER TABLE tme_time_ledger_entries
    ALTER COLUMN interpretation_attempt_public_id DROP NOT NULL,
    DROP CONSTRAINT ck_tme_time_ledger_source,
    DROP CONSTRAINT ck_tme_time_ledger_reversal,
    DROP CONSTRAINT ck_tme_time_ledger_reversal_quantity;

ALTER TABLE tme_time_ledger_entries
    ADD CONSTRAINT ck_tme_time_ledger_source CHECK (
        source_type IN ('INTERPRETATION', 'MANUAL_CORRECTION', 'REVERSAL')),
    ADD CONSTRAINT ck_tme_time_ledger_source_profile CHECK (
        (source_type = 'INTERPRETATION'
            AND interpretation_attempt_public_id IS NOT NULL
            AND source_public_id = interpretation_attempt_public_id
            AND reverses_entry_public_id IS NULL
            AND reason_code IS NULL
            AND quantity >= 0)
        OR
        (source_type = 'MANUAL_CORRECTION'
            AND interpretation_attempt_public_id IS NULL
            AND reverses_entry_public_id IS NULL
            AND reason_code IS NOT NULL
            AND reason_code <> ''
            AND reason_code = btrim(reason_code)
            AND length(reason_code) <= 64
            AND quantity <> 0)
        OR
        (source_type = 'REVERSAL'
            AND reverses_entry_public_id IS NOT NULL
            AND reason_code IS NOT NULL
            AND reason_code IN ('CLOCK_INPUT_REINTERPRETED', 'TIMECARD_CORRECTION')
            AND quantity <> 0)) NOT VALID,
    ADD CONSTRAINT ck_tme_time_ledger_reversal_attempt CHECK (
        source_type <> 'REVERSAL'
        OR (reason_code IS NOT NULL
            AND ((reason_code = 'CLOCK_INPUT_REINTERPRETED'
                    AND interpretation_attempt_public_id IS NOT NULL
                    AND source_public_id = interpretation_attempt_public_id)
                OR (reason_code = 'TIMECARD_CORRECTION'
                    AND interpretation_attempt_public_id IS NULL)))) NOT VALID;

CREATE OR REPLACE FUNCTION tme_validate_time_ledger_reversal()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE referenced public.tme_time_ledger_entries%ROWTYPE;
BEGIN
    IF NEW.source_type <> 'REVERSAL' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO referenced
      FROM public.tme_time_ledger_entries
     WHERE tenant_id = NEW.tenant_id
       AND public_id = NEW.reverses_entry_public_id
     FOR KEY SHARE;
    IF NOT FOUND OR referenced.source_type = 'REVERSAL'
       OR referenced.worker_public_id <> NEW.worker_public_id
       OR referenced.local_work_date <> NEW.local_work_date
       OR referenced.pay_code <> NEW.pay_code
       OR referenced.unit <> NEW.unit
       OR NEW.quantity <> -referenced.quantity THEN
        RAISE EXCEPTION 'ledger reversal must exactly negate one effective entry'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.tme_time_ledger_entries prior_reversal
         WHERE prior_reversal.tenant_id = NEW.tenant_id
           AND prior_reversal.source_type = 'REVERSAL'
           AND prior_reversal.reverses_entry_public_id = NEW.reverses_entry_public_id) THEN
        RAISE EXCEPTION 'ledger entry is already reversed' USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_tme_time_ledger_validate_reversal
    BEFORE INSERT ON tme_time_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION tme_validate_time_ledger_reversal();

CREATE TABLE tme_time_ledger_revision_counters (
    tenant_id BIGINT NOT NULL,
    worker_public_id UUID NOT NULL,
    current_revision BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, worker_public_id),
    CONSTRAINT ck_tme_ledger_counter_revision CHECK (current_revision > 0)
);

-- V10 FORCE RLS intentionally applies even to the table owner. The migration role is
-- NOSUPERUSER/NOBYPASSRLS, so temporarily restore the normal owner exemption while V11
-- performs its cross-tenant historical seed/probe. Flyway runs this migration in one
-- transaction; an error rolls this ALTER back to the pre-migration FORCE state.
ALTER TABLE tme_time_ledger_entries NO FORCE ROW LEVEL SECURITY;

INSERT INTO tme_time_ledger_revision_counters (
    tenant_id, worker_public_id, current_revision)
SELECT tenant_id, worker_public_id, MAX(ledger_revision)
  FROM tme_time_ledger_entries
 GROUP BY tenant_id, worker_public_id;

-- V10 writers use runPublicId as source_public_id. Canonicalize only NEW rows while
-- preserving immutable historical rows, and keep the shared revision allocator in sync.
CREATE OR REPLACE FUNCTION tme_prepare_time_ledger_entry_v11()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'tim-ledger:' || NEW.tenant_id || ':' || NEW.worker_public_id, 0));
    IF NEW.source_type = 'INTERPRETATION'
       OR (NEW.source_type = 'REVERSAL'
           AND NEW.reason_code = 'CLOCK_INPUT_REINTERPRETED') THEN
        IF NEW.interpretation_attempt_public_id IS NULL THEN
            RAISE EXCEPTION 'interpretation ledger source requires an attempt'
                USING ERRCODE = '23514';
        END IF;
        NEW.source_public_id := NEW.interpretation_attempt_public_id;
    END IF;
    INSERT INTO public.tme_time_ledger_revision_counters (
        tenant_id, worker_public_id, current_revision)
    VALUES (NEW.tenant_id, NEW.worker_public_id, NEW.ledger_revision)
    ON CONFLICT (tenant_id, worker_public_id)
    DO UPDATE SET current_revision = GREATEST(
        public.tme_time_ledger_revision_counters.current_revision,
        EXCLUDED.current_revision);
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_tme_time_ledger_prepare_v11
    BEFORE INSERT ON tme_time_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION tme_prepare_time_ledger_entry_v11();

-- NOT VALID preserves V10 append-only history that used runPublicId. PostgreSQL still
-- enforces both constraints for every new row; clean/no-legacy databases are validated.
DO $block$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM tme_time_ledger_entries
         WHERE (
            (source_type = 'INTERPRETATION'
                AND interpretation_attempt_public_id IS NOT NULL
                AND source_public_id = interpretation_attempt_public_id
                AND reverses_entry_public_id IS NULL
                AND reason_code IS NULL
                AND quantity >= 0)
            OR
            (source_type = 'MANUAL_CORRECTION'
                AND interpretation_attempt_public_id IS NULL
                AND reverses_entry_public_id IS NULL
                AND reason_code IS NOT NULL
                AND reason_code <> ''
                AND reason_code = btrim(reason_code)
                AND length(reason_code) <= 64
                AND quantity <> 0)
            OR
            (source_type = 'REVERSAL'
                AND reverses_entry_public_id IS NOT NULL
                AND reason_code IS NOT NULL
                AND reason_code IN ('CLOCK_INPUT_REINTERPRETED', 'TIMECARD_CORRECTION')
                AND quantity <> 0)
         ) IS FALSE
            OR (
                source_type <> 'REVERSAL'
                OR (reason_code IS NOT NULL
                    AND ((reason_code = 'CLOCK_INPUT_REINTERPRETED'
                            AND interpretation_attempt_public_id IS NOT NULL
                            AND source_public_id = interpretation_attempt_public_id)
                        OR (reason_code = 'TIMECARD_CORRECTION'
                            AND interpretation_attempt_public_id IS NULL)))
            ) IS FALSE
    ) THEN
        ALTER TABLE tme_time_ledger_entries
            VALIDATE CONSTRAINT ck_tme_time_ledger_source_profile;
        ALTER TABLE tme_time_ledger_entries
            VALIDATE CONSTRAINT ck_tme_time_ledger_reversal_attempt;
    END IF;
END;
$block$;

-- Restore the sealed V10 tenant boundary immediately after the only historical scans.
ALTER TABLE tme_time_ledger_entries FORCE ROW LEVEL SECURITY;

CREATE TABLE tme_timecards (
    timecard_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    worker_public_id UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    state VARCHAR(24) NOT NULL,
    total_minutes INTEGER NOT NULL,
    ledger_revision BIGINT NOT NULL,
    interpretation_run_public_id UUID,
    period_closed BOOLEAN NOT NULL DEFAULT FALSE,
    submitted_by_subject_public_id UUID,
    latest_correction_maker_subject_public_id UUID,
    decided_by_subject_public_id UUID,
    decided_at TIMESTAMPTZ,
    successor_timecard_public_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_tme_timecard_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_timecard_public_worker UNIQUE (
        tenant_id, public_id, worker_public_id),
    CONSTRAINT uk_tme_timecard_internal UNIQUE (tenant_id, timecard_id),
    CONSTRAINT fk_tme_timecard_interpretation_run FOREIGN KEY (
        tenant_id, interpretation_run_public_id)
        REFERENCES tme_interpretation_runs (tenant_id, public_id),
    CONSTRAINT fk_tme_timecard_successor FOREIGN KEY (tenant_id, successor_timecard_public_id)
        REFERENCES tme_timecards (tenant_id, public_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_tme_timecard_period CHECK (period_end >= period_start),
    CONSTRAINT ck_tme_timecard_state CHECK (state IN (
        'OPEN', 'SUBMITTED', 'APPROVED', 'REJECTED',
        'CORRECTION_REQUIRED', 'LOCKED', 'CLOSED', 'SUPERSEDED')),
    CONSTRAINT ck_tme_timecard_totals CHECK (
        total_minutes >= 0 AND ledger_revision > 0 AND row_version >= 0),
    CONSTRAINT ck_tme_timecard_actor CHECK (updated_by > 0),
    CONSTRAINT ck_tme_timecard_decision_evidence CHECK (
        (decided_by_subject_public_id IS NULL) = (decided_at IS NULL)),
    CONSTRAINT ck_tme_timecard_successor_state CHECK (
        (state = 'SUPERSEDED') = (successor_timecard_public_id IS NOT NULL)
        AND successor_timecard_public_id IS DISTINCT FROM public_id)
);
CREATE INDEX ix_tme_timecard_worker_period
    ON tme_timecards (tenant_id, worker_public_id, period_start, period_end);
CREATE UNIQUE INDEX uk_tme_timecard_active_period
    ON tme_timecards (tenant_id, worker_public_id, period_start, period_end)
    WHERE state <> 'SUPERSEDED';

CREATE TABLE tme_timecard_command_receipts (
    command_receipt_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    command_type VARCHAR(128) NOT NULL,
    originating_action VARCHAR(128) NOT NULL,
    originating_population_kind VARCHAR(24) NOT NULL,
    originating_required_permission VARCHAR(64) NOT NULL,
    actor_id BIGINT NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    worker_public_id UUID NOT NULL,
    population_public_id UUID NOT NULL,
    population_revision BIGINT NOT NULL,
    grant_revision BIGINT NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    field_policy_revision BIGINT NOT NULL,
    purpose_code VARCHAR(64) NOT NULL,
    authorization_revision BIGINT NOT NULL,
    request_digest CHAR(64) NOT NULL,
    timecard_public_id UUID NOT NULL,
    aggregate_revision BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    result_ref VARCHAR(255),
    error_code VARCHAR(80),
    correlation_id UUID NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_tme_timecard_receipt_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_timecard_receipt_idem UNIQUE (
        tenant_id, subject_principal_public_id, originating_action, idempotency_key),
    CONSTRAINT fk_tme_timecard_receipt_card FOREIGN KEY (tenant_id, timecard_public_id)
        REFERENCES tme_timecards (tenant_id, public_id),
    CONSTRAINT fk_tme_timecard_receipt_worker FOREIGN KEY (
        tenant_id, timecard_public_id, worker_public_id)
        REFERENCES tme_timecards (tenant_id, public_id, worker_public_id),
    CONSTRAINT ck_tme_timecard_receipt_idem CHECK (
        length(idempotency_key) BETWEEN 16 AND 128
        AND idempotency_key = btrim(idempotency_key)),
    CONSTRAINT ck_tme_timecard_receipt_pair CHECK (
        (command_type = 'TIMECARD_CORRECT'
            AND originating_action = 'TIME_TIMECARD_CORRECT'
            AND ((originating_population_kind = 'SELF'
                    AND originating_required_permission = 'DATA.HR_TIME:CREATE')
                OR (originating_population_kind = 'TARGET_POPULATION'
                    AND originating_required_permission = 'DATA.HR_TIME:UPDATE')))
        OR (command_type = 'TIMECARD_SUBMIT'
            AND originating_action = 'TIME_TIMECARD_SUBMIT'
            AND originating_population_kind = 'SELF'
            AND originating_required_permission = 'DATA.HR_TIME:CREATE')
        OR (command_type = 'TIMECARD_DECIDE'
            AND originating_action = 'TIME_TIMECARD_DECIDE'
            AND originating_population_kind = 'TARGET_POPULATION'
            AND originating_required_permission = 'DATA.HR_TIME:APPROVE')),
    CONSTRAINT ck_tme_timecard_receipt_actor CHECK (actor_id > 0),
    CONSTRAINT ck_tme_timecard_receipt_revisions CHECK (
        population_revision > 0 AND grant_revision > 0
        AND field_policy_revision > 0 AND authorization_revision > 0
        AND aggregate_revision > 0),
    CONSTRAINT ck_tme_timecard_receipt_digests CHECK (
        population_scope_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_timecard_receipt_purpose CHECK (purpose_code = 'TIME_TIMECARD'),
    CONSTRAINT ck_tme_timecard_receipt_status CHECK (status IN (
        'ACCEPTED', 'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_tme_timecard_receipt_completion CHECK (
        (status IN ('SUCCEEDED', 'REJECTED', 'FAILED') AND completed_at IS NOT NULL)
        OR status IN ('ACCEPTED', 'RUNNING', 'RESULT_UNKNOWN'))
);

CREATE TABLE tme_timecard_versions (
    timecard_version_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    timecard_public_id UUID NOT NULL,
    version BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    total_minutes INTEGER NOT NULL,
    ledger_revision BIGINT NOT NULL,
    transition_type VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64),
    correction_public_id UUID,
    command_receipt_public_id UUID,
    actor_id BIGINT NOT NULL,
    actor_subject_public_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_timecard_version_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_timecard_version_number UNIQUE (
        tenant_id, timecard_public_id, version),
    CONSTRAINT fk_tme_timecard_version_card FOREIGN KEY (tenant_id, timecard_public_id)
        REFERENCES tme_timecards (tenant_id, public_id),
    CONSTRAINT fk_tme_timecard_version_receipt FOREIGN KEY (
        tenant_id, command_receipt_public_id)
        REFERENCES tme_timecard_command_receipts (tenant_id, public_id),
    CONSTRAINT ck_tme_timecard_version_state CHECK (state IN (
        'OPEN', 'SUBMITTED', 'APPROVED', 'REJECTED',
        'CORRECTION_REQUIRED', 'LOCKED', 'CLOSED', 'SUPERSEDED')),
    CONSTRAINT ck_tme_timecard_version_transition CHECK (transition_type IN (
        'MATERIALIZE', 'CORRECT', 'SUBMIT', 'DECIDE', 'LOCK', 'CLOSE', 'REOPEN')),
    CONSTRAINT ck_tme_timecard_version_values CHECK (
        version >= 0 AND total_minutes >= 0 AND ledger_revision > 0 AND actor_id > 0),
    CONSTRAINT ck_tme_timecard_version_receipt CHECK (
        (transition_type = 'MATERIALIZE' AND command_receipt_public_id IS NULL)
        OR (transition_type <> 'MATERIALIZE' AND command_receipt_public_id IS NOT NULL))
);

CREATE TABLE tme_timecard_lines (
    timecard_line_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    timecard_public_id UUID NOT NULL,
    timecard_version BIGINT NOT NULL,
    line_sequence INTEGER NOT NULL,
    ledger_entry_public_id UUID NOT NULL,
    work_date DATE NOT NULL,
    pay_code VARCHAR(64) NOT NULL,
    minutes INTEGER NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    correction_public_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_timecard_line_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_timecard_line_sequence UNIQUE (
        tenant_id, timecard_public_id, timecard_version, line_sequence),
    CONSTRAINT fk_tme_timecard_line_version FOREIGN KEY (
        tenant_id, timecard_public_id, timecard_version)
        REFERENCES tme_timecard_versions (tenant_id, timecard_public_id, version)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_tme_timecard_line_ledger FOREIGN KEY (tenant_id, ledger_entry_public_id)
        REFERENCES tme_time_ledger_entries (tenant_id, public_id),
    CONSTRAINT ck_tme_timecard_line_sequence CHECK (line_sequence > 0),
    CONSTRAINT ck_tme_timecard_line_minutes CHECK (
        minutes BETWEEN -1440 AND 1440 AND minutes <> 0),
    CONSTRAINT ck_tme_timecard_line_source CHECK (
        source_type = 'MANUAL_CORRECTION' AND correction_public_id IS NOT NULL),
    CONSTRAINT ck_tme_timecard_line_pay_code CHECK (
        pay_code <> '' AND pay_code = btrim(pay_code))
);

CREATE TABLE tme_timecard_decisions (
    timecard_decision_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    timecard_public_id UUID NOT NULL,
    timecard_version BIGINT NOT NULL,
    decision VARCHAR(24) NOT NULL,
    resulting_state VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    restricted_comment VARCHAR(1000),
    decided_at TIMESTAMPTZ NOT NULL,
    decided_by BIGINT NOT NULL,
    decided_by_subject_public_id UUID NOT NULL,
    command_receipt_public_id UUID NOT NULL,
    CONSTRAINT uk_tme_timecard_decision_public UNIQUE (tenant_id, public_id),
    CONSTRAINT uk_tme_timecard_decision_version UNIQUE (
        tenant_id, timecard_public_id, timecard_version),
    CONSTRAINT fk_tme_timecard_decision_version FOREIGN KEY (
        tenant_id, timecard_public_id, timecard_version)
        REFERENCES tme_timecard_versions (tenant_id, timecard_public_id, version),
    CONSTRAINT fk_tme_timecard_decision_receipt FOREIGN KEY (
        tenant_id, command_receipt_public_id)
        REFERENCES tme_timecard_command_receipts (tenant_id, public_id),
    CONSTRAINT ck_tme_timecard_decision_pair CHECK (
        (decision = 'APPROVE' AND resulting_state = 'APPROVED')
        OR (decision = 'REJECT' AND resulting_state = 'REJECTED')
        OR (decision = 'REQUIRE_CORRECTION'
            AND resulting_state = 'CORRECTION_REQUIRED')),
    CONSTRAINT ck_tme_timecard_decision_reason CHECK (
        reason_code <> '' AND reason_code = btrim(reason_code)),
    CONSTRAINT ck_tme_timecard_decision_actor CHECK (decided_by > 0)
);

CREATE TABLE tme_timecard_outbox_events (
    outbox_event_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    decision_public_id UUID NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_public_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    schema_name VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL,
    partition_key VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_tme_timecard_outbox_event UNIQUE (tenant_id, event_id),
    CONSTRAINT uk_tme_timecard_outbox_decision UNIQUE (tenant_id, decision_public_id),
    CONSTRAINT uk_tme_timecard_outbox_sequence UNIQUE (
        tenant_id, aggregate_public_id, aggregate_sequence),
    CONSTRAINT fk_tme_timecard_outbox_decision FOREIGN KEY (
        tenant_id, decision_public_id)
        REFERENCES tme_timecard_decisions (tenant_id, public_id),
    CONSTRAINT ck_tme_timecard_outbox_identity CHECK (
        aggregate_type = 'TIMECARD'
        AND schema_name = 'TimecardDecisionRecorded.v1'
        AND schema_version = 1),
    CONSTRAINT ck_tme_timecard_outbox_sequence CHECK (aggregate_sequence > 0),
    CONSTRAINT ck_tme_timecard_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND payload ?& ARRAY[
            'timecardId', 'workerId', 'status', 'ledgerRevision', 'decisionAt']
        AND (payload - ARRAY[
            'timecardId', 'workerId', 'status', 'ledgerRevision', 'decisionAt']) = '{}'::jsonb
        AND CASE WHEN jsonb_typeof(payload->'timecardId') = 'string'
            THEN payload->>'timecardId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            ELSE FALSE END
        AND CASE WHEN jsonb_typeof(payload->'workerId') = 'string'
            THEN payload->>'workerId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            ELSE FALSE END
        AND CASE WHEN jsonb_typeof(payload->'status') = 'string'
            THEN payload->>'status' IN ('APPROVED', 'REJECTED', 'CORRECTION_REQUIRED')
            ELSE FALSE END
        AND CASE WHEN jsonb_typeof(payload->'ledgerRevision') = 'number'
            THEN (payload->>'ledgerRevision')::NUMERIC >= 1
                AND trunc((payload->>'ledgerRevision')::NUMERIC)
                    = (payload->>'ledgerRevision')::NUMERIC
            ELSE FALSE END
        AND CASE WHEN jsonb_typeof(payload->'decisionAt') = 'string'
            THEN payload->>'decisionAt' ~*
                '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$'
                AND (payload->>'decisionAt')::TIMESTAMPTZ IS NOT NULL
            ELSE FALSE END),
    CONSTRAINT ck_tme_timecard_outbox_digest CHECK (
        payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_timecard_outbox_attempts CHECK (publish_attempts >= 0)
);
CREATE INDEX ix_tme_timecard_outbox_unpublished
    ON tme_timecard_outbox_events (occurred_at, outbox_event_id)
    WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION tme_guard_timecard_aggregate()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'timecards cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'OPEN' OR NEW.row_version <> 0 OR NEW.period_closed
           OR NEW.submitted_by_subject_public_id IS NOT NULL
           OR NEW.latest_correction_maker_subject_public_id IS NOT NULL
           OR NEW.decided_by_subject_public_id IS NOT NULL
           OR NEW.decided_at IS NOT NULL
           OR NEW.successor_timecard_public_id IS NOT NULL THEN
            RAISE EXCEPTION 'timecards materialize as OPEN version zero'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.tenant_id, NEW.public_id, NEW.worker_public_id,
           NEW.period_start, NEW.period_end, NEW.interpretation_run_public_id, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.public_id, OLD.worker_public_id,
           OLD.period_start, OLD.period_end, OLD.interpretation_run_public_id, OLD.created_at)
       OR NEW.row_version <> OLD.row_version + 1
       OR NEW.ledger_revision < OLD.ledger_revision
       OR OLD.state = 'SUPERSEDED'
       OR NOT (
           (OLD.state = 'OPEN' AND NEW.state IN ('OPEN', 'SUBMITTED'))
           OR (OLD.state = 'REJECTED' AND NEW.state = 'OPEN')
           OR (OLD.state = 'CORRECTION_REQUIRED' AND NEW.state = 'OPEN')
           OR (OLD.state = 'SUBMITTED'
                AND NEW.state IN ('APPROVED', 'REJECTED', 'CORRECTION_REQUIRED'))
           OR (OLD.state = 'APPROVED' AND NEW.state = 'LOCKED')
           OR (OLD.state = 'LOCKED' AND NEW.state = 'CLOSED')
           OR (OLD.state = 'CLOSED' AND NEW.state = 'SUPERSEDED')) THEN
        RAISE EXCEPTION 'illegal Timecard transition or version mutation'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('OPEN', 'REJECTED', 'CORRECTION_REQUIRED')
       AND NEW.state = 'OPEN' THEN
        IF NEW.ledger_revision <= OLD.ledger_revision
           OR NEW.latest_correction_maker_subject_public_id IS NULL
           OR NEW.submitted_by_subject_public_id IS DISTINCT FROM
                OLD.submitted_by_subject_public_id
           OR NEW.decided_by_subject_public_id IS DISTINCT FROM
                OLD.decided_by_subject_public_id
           OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
           OR NEW.period_closed IS DISTINCT FROM OLD.period_closed THEN
            RAISE EXCEPTION 'Timecard correction evidence requires a new ledger revision'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.state = 'OPEN' AND NEW.state = 'SUBMITTED' THEN
        IF NEW.submitted_by_subject_public_id IS NULL
           OR ROW(NEW.total_minutes, NEW.ledger_revision,
                  NEW.latest_correction_maker_subject_public_id,
                  NEW.decided_by_subject_public_id, NEW.decided_at, NEW.period_closed)
              IS DISTINCT FROM
              ROW(OLD.total_minutes, OLD.ledger_revision,
                  OLD.latest_correction_maker_subject_public_id,
                  OLD.decided_by_subject_public_id, OLD.decided_at, OLD.period_closed) THEN
            RAISE EXCEPTION 'Timecard submit cannot mutate correction or decision evidence'
                USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.state = 'SUBMITTED'
       AND NEW.state IN ('APPROVED', 'REJECTED', 'CORRECTION_REQUIRED') THEN
        IF NEW.decided_by_subject_public_id IS NULL OR NEW.decided_at IS NULL
           OR ROW(NEW.total_minutes, NEW.ledger_revision,
                  NEW.submitted_by_subject_public_id,
                  NEW.latest_correction_maker_subject_public_id, NEW.period_closed)
              IS DISTINCT FROM
              ROW(OLD.total_minutes, OLD.ledger_revision,
                  OLD.submitted_by_subject_public_id,
                  OLD.latest_correction_maker_subject_public_id, OLD.period_closed) THEN
            RAISE EXCEPTION 'Timecard decision cannot mutate submitted or correction evidence'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF ROW(NEW.total_minutes, NEW.ledger_revision,
               NEW.submitted_by_subject_public_id,
               NEW.latest_correction_maker_subject_public_id,
               NEW.decided_by_subject_public_id, NEW.decided_at)
           IS DISTINCT FROM
           ROW(OLD.total_minutes, OLD.ledger_revision,
               OLD.submitted_by_subject_public_id,
               OLD.latest_correction_maker_subject_public_id,
               OLD.decided_by_subject_public_id, OLD.decided_at)
           OR (OLD.state = 'APPROVED'
               AND NEW.period_closed IS DISTINCT FROM OLD.period_closed)
           OR (OLD.state = 'LOCKED' AND NOT NEW.period_closed)
           OR (OLD.state = 'CLOSED'
               AND NEW.period_closed IS DISTINCT FROM OLD.period_closed) THEN
            RAISE EXCEPTION 'Timecard lock, close or supersede cannot mutate evidence'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION tme_reject_timecard_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION 'Timecard evidence is append-only' USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION tme_guard_timecard_counter()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger revision counters cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'UPDATE' AND (
        NEW.tenant_id <> OLD.tenant_id OR NEW.worker_public_id <> OLD.worker_public_id
        OR NEW.current_revision < OLD.current_revision) THEN
        RAISE EXCEPTION 'ledger revision counters are identity-stable and monotonic'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION tme_guard_timecard_outbox_delivery()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Timecard outbox events cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF ROW(NEW.tenant_id, NEW.event_id, NEW.decision_public_id, NEW.aggregate_type,
           NEW.aggregate_public_id, NEW.aggregate_sequence, NEW.schema_name,
           NEW.schema_version, NEW.partition_key, NEW.payload, NEW.payload_digest,
           NEW.correlation_id, NEW.causation_id, NEW.occurred_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.event_id, OLD.decision_public_id, OLD.aggregate_type,
           OLD.aggregate_public_id, OLD.aggregate_sequence, OLD.schema_name,
           OLD.schema_version, OLD.partition_key, OLD.payload, OLD.payload_digest,
           OLD.correlation_id, OLD.causation_id, OLD.occurred_at) THEN
        RAISE EXCEPTION 'Timecard outbox envelope is immutable' USING ERRCODE = '55000';
    END IF;
    IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION 'Timecard outbox publication is immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.publish_attempts < OLD.publish_attempts THEN
        RAISE EXCEPTION 'Timecard outbox attempts must be monotonic' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_tme_timecards_guard
    BEFORE INSERT OR UPDATE OR DELETE ON tme_timecards
    FOR EACH ROW EXECUTE FUNCTION tme_guard_timecard_aggregate();
CREATE TRIGGER trg_tme_ledger_counters_guard
    BEFORE UPDATE OR DELETE ON tme_time_ledger_revision_counters
    FOR EACH ROW EXECUTE FUNCTION tme_guard_timecard_counter();
CREATE TRIGGER trg_tme_timecard_versions_immutable
    BEFORE UPDATE OR DELETE ON tme_timecard_versions
    FOR EACH ROW EXECUTE FUNCTION tme_reject_timecard_evidence_mutation();
CREATE TRIGGER trg_tme_timecard_lines_immutable
    BEFORE UPDATE OR DELETE ON tme_timecard_lines
    FOR EACH ROW EXECUTE FUNCTION tme_reject_timecard_evidence_mutation();
CREATE TRIGGER trg_tme_timecard_decisions_immutable
    BEFORE UPDATE OR DELETE ON tme_timecard_decisions
    FOR EACH ROW EXECUTE FUNCTION tme_reject_timecard_evidence_mutation();
CREATE TRIGGER trg_tme_timecard_receipts_immutable
    BEFORE UPDATE OR DELETE ON tme_timecard_command_receipts
    FOR EACH ROW EXECUTE FUNCTION tme_reject_timecard_evidence_mutation();
CREATE TRIGGER trg_tme_timecard_outbox_delivery
    BEFORE UPDATE OR DELETE ON tme_timecard_outbox_events
    FOR EACH ROW EXECUTE FUNCTION tme_guard_timecard_outbox_delivery();

DO $block$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'tme_timecards',
        'tme_timecard_versions',
        'tme_timecard_lines',
        'tme_timecard_decisions',
        'tme_timecard_command_receipts',
        'tme_timecard_outbox_events',
        'tme_time_ledger_revision_counters'
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
    tme_timecards,
    tme_timecard_versions,
    tme_timecard_lines,
    tme_timecard_decisions,
    tme_timecard_command_receipts,
    tme_timecard_outbox_events,
    tme_time_ledger_revision_counters
FROM PUBLIC;

DO $block$
DECLARE sequence_name TEXT;
BEGIN
    FOREACH sequence_name IN ARRAY ARRAY[
        pg_get_serial_sequence('tme_timecards', 'timecard_id'),
        pg_get_serial_sequence('tme_timecard_versions', 'timecard_version_id'),
        pg_get_serial_sequence('tme_timecard_lines', 'timecard_line_id'),
        pg_get_serial_sequence('tme_timecard_decisions', 'timecard_decision_id'),
        pg_get_serial_sequence('tme_timecard_command_receipts', 'command_receipt_id'),
        pg_get_serial_sequence('tme_timecard_outbox_events', 'outbox_event_id')
    ] LOOP
        EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC', sequence_name);
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
            sequence_name, '${timeRuntimeRole}');
    END LOOP;
END;
$block$;

GRANT SELECT, INSERT ON TABLE tme_timecards TO "${timeRuntimeRole}";
GRANT UPDATE (
    state, total_minutes, ledger_revision, period_closed,
    submitted_by_subject_public_id, latest_correction_maker_subject_public_id,
    decided_by_subject_public_id, decided_at, successor_timecard_public_id,
    row_version, updated_at, updated_by)
ON tme_timecards TO "${timeRuntimeRole}";
GRANT SELECT, INSERT ON TABLE
    tme_timecard_versions,
    tme_timecard_lines,
    tme_timecard_decisions,
    tme_timecard_command_receipts,
    tme_timecard_outbox_events
TO "${timeRuntimeRole}";
GRANT SELECT, INSERT ON TABLE tme_time_ledger_revision_counters
TO "${timeRuntimeRole}";
GRANT UPDATE (current_revision) ON TABLE tme_time_ledger_revision_counters
TO "${timeRuntimeRole}";

GRANT SELECT ON TABLE tme_timecard_outbox_events
TO "${timeProjectionPublisherRole}";
GRANT UPDATE (published_at, publish_attempts) ON TABLE tme_timecard_outbox_events
TO "${timeProjectionPublisherRole}";

REVOKE ALL ON FUNCTION
    tme_validate_time_ledger_reversal(),
    tme_prepare_time_ledger_entry_v11(),
    tme_guard_timecard_aggregate(),
    tme_reject_timecard_evidence_mutation(),
    tme_guard_timecard_counter(),
    tme_guard_timecard_outbox_delivery()
FROM PUBLIC;
REVOKE ALL ON FUNCTION
    tme_validate_time_ledger_reversal(),
    tme_prepare_time_ledger_entry_v11(),
    tme_guard_timecard_aggregate(),
    tme_reject_timecard_evidence_mutation(),
    tme_guard_timecard_counter(),
    tme_guard_timecard_outbox_delivery()
FROM "${timeRuntimeRole}";
REVOKE ALL ON FUNCTION
    tme_validate_time_ledger_reversal(),
    tme_prepare_time_ledger_entry_v11(),
    tme_guard_timecard_aggregate(),
    tme_reject_timecard_evidence_mutation(),
    tme_guard_timecard_counter(),
    tme_guard_timecard_outbox_delivery()
FROM "${timeProjectionPublisherRole}";
