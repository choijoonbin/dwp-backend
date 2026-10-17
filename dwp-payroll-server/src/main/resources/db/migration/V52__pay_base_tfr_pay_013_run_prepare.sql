-- BASE-TFR-PAY-013. Preparation facts are append-only and are kept separate
-- from the attempt-owned V50 calculation snapshot while linking to it exactly.

-- These two projections are populated only by their owning projection publisher.
-- The Payroll runtime can read but cannot self-attest target or semantic source
-- identity/revision facts.
CREATE TABLE pay_population_source_revisions (
    population_source_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    source_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    population_kind VARCHAR(32) NOT NULL,
    named_population_id UUID,
    item_count INTEGER NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_population_source_identity_unique UNIQUE (
        tenant_id, source_id, source_revision),
    CONSTRAINT pay_population_source_scope_revision_unique
        UNIQUE NULLS NOT DISTINCT (
        tenant_id, legal_entity_id, payroll_group_id, population_kind,
        named_population_id, source_revision),
    CONSTRAINT pay_population_source_positive CHECK (
        tenant_id > 0 AND source_revision > 0 AND item_count >= 0),
    CONSTRAINT pay_population_source_digest_shape CHECK (
        payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_population_source_kind_shape CHECK (
        population_kind = 'LEGAL_ENTITY' AND named_population_id IS NULL),
    CONSTRAINT pay_population_source_time_shape CHECK (
        effective_at <= recorded_at)
);

CREATE TABLE pay_population_source_members (
    population_member_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    source_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    member_ordinal INTEGER NOT NULL,
    compensation_plan_input_id BIGINT NOT NULL,
    worker_id UUID NOT NULL,
    source_payload_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_population_source_member_parent_fk FOREIGN KEY (
        tenant_id, source_id, source_revision)
        REFERENCES pay_population_source_revisions (
            tenant_id, source_id, source_revision),
    CONSTRAINT pay_population_source_member_ordinal_unique UNIQUE (
        tenant_id, source_id, source_revision, member_ordinal),
    CONSTRAINT pay_population_source_member_input_unique UNIQUE (
        tenant_id, source_id, source_revision, compensation_plan_input_id),
    CONSTRAINT pay_population_source_member_positive CHECK (
        tenant_id > 0 AND source_revision > 0 AND member_ordinal > 0
        AND compensation_plan_input_id > 0),
    CONSTRAINT pay_population_source_member_digest_shape CHECK (
        source_payload_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE pay_semantic_source_revisions (
    semantic_source_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    source_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    pay_period_id UUID NOT NULL,
    target_population_digest CHAR(64) NOT NULL,
    source_snapshot_id UUID NOT NULL,
    source_snapshot_revision BIGINT NOT NULL,
    source_payload_digest CHAR(64) NOT NULL,
    formula_set_digest CHAR(64) NOT NULL,
    function_set_version VARCHAR(64) NOT NULL,
    line_count INTEGER NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_semantic_source_identity_unique UNIQUE (
        tenant_id, source_id, source_revision),
    CONSTRAINT pay_semantic_source_binding_unique UNIQUE (
        tenant_id, legal_entity_id, payroll_group_id, pay_period_id,
        target_population_digest, source_snapshot_id,
        source_snapshot_revision, formula_set_digest, source_revision),
    CONSTRAINT pay_semantic_source_positive CHECK (
        tenant_id > 0 AND source_revision > 0
        AND source_snapshot_revision > 0 AND line_count > 0),
    CONSTRAINT pay_semantic_source_digest_shape CHECK (
        payload_digest ~ '^[0-9a-f]{64}$'
        AND target_population_digest ~ '^[0-9a-f]{64}$'
        AND source_payload_digest ~ '^[0-9a-f]{64}$'
        AND formula_set_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_semantic_source_function_shape CHECK (
        function_set_version ~ '^[A-Za-z0-9._:-]{1,64}$'),
    CONSTRAINT pay_semantic_source_time_shape CHECK (
        effective_at <= recorded_at)
);

CREATE TABLE pay_run_preparation_step_up_uses (
    step_up_use_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    challenge_id VARCHAR(160) NOT NULL,
    nonce VARCHAR(160) NOT NULL,
    issuer VARCHAR(300) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    decision_revision VARCHAR(200) NOT NULL,
    preparation_id UUID NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    use_kind VARCHAR(24) NOT NULL,
    used_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_prepare_step_up_challenge_unique UNIQUE (
        tenant_id, issuer, challenge_id),
    CONSTRAINT pay_prepare_step_up_nonce_unique UNIQUE (
        tenant_id, issuer, nonce),
    CONSTRAINT pay_prepare_step_up_positive CHECK (tenant_id > 0),
    CONSTRAINT pay_prepare_step_up_idempotency_shape CHECK (
        char_length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT pay_prepare_step_up_digest_shape CHECK (
        request_digest ~ '^[0-9a-f]{64}$'
        AND payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_prepare_step_up_evidence_shape CHECK (
        char_length(challenge_id) BETWEEN 1 AND 160
        AND char_length(nonce) BETWEEN 1 AND 160
        AND char_length(issuer) BETWEEN 1 AND 300
        AND char_length(decision_revision) BETWEEN 1 AND 200
        AND used_at < expires_at),
    CONSTRAINT pay_prepare_step_up_kind_shape CHECK (
        use_kind IN ('PREPARE_COMMIT', 'IDEMPOTENT_REPLAY'))
);

CREATE UNIQUE INDEX pay_prepare_step_up_commit_unique
    ON pay_run_preparation_step_up_uses (tenant_id, preparation_id)
    WHERE use_kind = 'PREPARE_COMMIT';

CREATE TABLE pay_run_preparations (
    preparation_record_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    preparation_id UUID NOT NULL,
    state_sequence INTEGER NOT NULL,
    preparation_state VARCHAR(24) NOT NULL,
    run_id UUID NOT NULL,
    run_revision BIGINT NOT NULL DEFAULT 2,
    request_digest CHAR(64) NOT NULL,
    snapshot_id UUID NOT NULL,
    snapshot_digest CHAR(64) NOT NULL,
    population_revision VARCHAR(200) NOT NULL,
    target_population_digest CHAR(64) NOT NULL,
    authorization_scope_digest CHAR(64) NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    pay_period_id UUID NOT NULL,
    population_kind VARCHAR(32) NOT NULL,
    named_population_id UUID,
    maker_actor_id BIGINT NOT NULL,
    maker_subject_public_id UUID NOT NULL,
    context_scope_key VARCHAR(160) NOT NULL,
    authorization_revision BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    event_id UUID,
    source_as_of TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_run_preparation_state_unique UNIQUE (
        tenant_id, preparation_id, state_sequence),
    CONSTRAINT pay_run_preparation_run_state_unique UNIQUE (
        tenant_id, run_id, state_sequence),
    CONSTRAINT pay_run_preparation_snapshot_state_unique UNIQUE (
        tenant_id, snapshot_id, state_sequence),
    CONSTRAINT pay_run_preparation_event_unique UNIQUE (
        tenant_id, event_id),
    CONSTRAINT pay_run_preparation_run_fk FOREIGN KEY (
        tenant_id, run_id, run_revision)
        REFERENCES pay_payroll_runs (tenant_id, run_id, run_revision)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT pay_run_preparation_snapshot_fk FOREIGN KEY (
        tenant_id, snapshot_id)
        REFERENCES pay_input_snapshots (tenant_id, input_snapshot_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT pay_run_preparation_event_fk FOREIGN KEY (
        tenant_id, event_id)
        REFERENCES pay_outbox_events (tenant_id, event_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT pay_run_preparation_positive CHECK (
        tenant_id > 0 AND state_sequence IN (1, 2) AND run_revision = 2
        AND maker_actor_id > 0 AND authorization_revision > 0),
    CONSTRAINT pay_run_preparation_state_valid CHECK (
        (state_sequence = 1 AND preparation_state = 'PREPARING'
            AND event_id IS NULL)
        OR (state_sequence = 2 AND preparation_state = 'READY'
            AND event_id IS NOT NULL)),
    CONSTRAINT pay_run_preparation_population_valid CHECK (
        population_kind IN ('LEGAL_ENTITY', 'PAY_GROUP', 'NAMED_POPULATION')
        AND ((population_kind = 'NAMED_POPULATION'
                AND named_population_id IS NOT NULL)
            OR (population_kind <> 'NAMED_POPULATION'
                AND named_population_id IS NULL))),
    CONSTRAINT pay_run_preparation_digest_shape CHECK (
        request_digest ~ '^[0-9a-f]{64}$'
        AND snapshot_digest ~ '^[0-9a-f]{64}$'
        AND target_population_digest ~ '^[0-9a-f]{64}$'
        AND authorization_scope_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_run_preparation_text_shape CHECK (
        btrim(population_revision) <> ''
        AND btrim(context_scope_key) <> ''
        AND source_as_of <= recorded_at)
);

CREATE TABLE pay_run_preparation_sources (
    preparation_source_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    preparation_id UUID NOT NULL,
    preparation_state_sequence INTEGER NOT NULL DEFAULT 1,
    source_ordinal INTEGER NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id UUID NOT NULL,
    source_revision BIGINT NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    approval_receipt_id UUID,
    approval_revision BIGINT,
    source_line_count INTEGER,
    source_status VARCHAR(16) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_run_preparation_source_ordinal_unique UNIQUE (
        tenant_id, preparation_id, source_ordinal),
    CONSTRAINT pay_run_preparation_source_type_unique UNIQUE (
        tenant_id, preparation_id, source_type),
    CONSTRAINT pay_run_preparation_source_identity_unique UNIQUE (
        tenant_id, preparation_id, source_type, source_id,
        source_revision, payload_digest),
    CONSTRAINT pay_run_preparation_source_parent_fk FOREIGN KEY (
        tenant_id, preparation_id, preparation_state_sequence)
        REFERENCES pay_run_preparations (
            tenant_id, preparation_id, state_sequence),
    CONSTRAINT pay_run_preparation_source_positive CHECK (
        tenant_id > 0 AND preparation_state_sequence = 1
        AND source_ordinal BETWEEN 1 AND 5
        AND source_revision > 0
        AND (source_line_count IS NULL OR source_line_count > 0)
        AND (approval_revision IS NULL OR approval_revision > 0)),
    CONSTRAINT pay_run_preparation_source_type_valid CHECK (source_type IN (
        'TARGET_POPULATION_PROJECTION', 'COMPENSATION_PLAN_SNAPSHOT',
        'PAYROLL_FOUNDATION_POLICY', 'PAYROLL_FORMULA_SET',
        'SEMANTIC_MAPPING_SET')),
    CONSTRAINT pay_run_preparation_source_status_valid CHECK (
        source_status IN ('CURRENT', 'STALE', 'INCOMPLETE')),
    CONSTRAINT pay_run_preparation_source_count_shape CHECK (
        (source_type = 'PAYROLL_FOUNDATION_POLICY'
            AND source_line_count IS NULL)
        OR (source_type <> 'PAYROLL_FOUNDATION_POLICY'
            AND source_line_count IS NOT NULL)),
    CONSTRAINT pay_run_preparation_source_digest_shape CHECK (
        payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_run_preparation_source_approval_shape CHECK (
        (source_type = 'COMPENSATION_PLAN_SNAPSHOT'
            AND approval_receipt_id IS NOT NULL AND approval_revision IS NOT NULL)
        OR (source_type <> 'COMPENSATION_PLAN_SNAPSHOT'
            AND approval_receipt_id IS NULL AND approval_revision IS NULL))
);

CREATE TABLE pay_run_preparation_decisions (
    preparation_decision_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    preparation_id UUID NOT NULL,
    preparation_state_sequence INTEGER NOT NULL DEFAULT 1,
    decision_ordinal INTEGER NOT NULL,
    decision_code VARCHAR(100) NOT NULL,
    decision_status VARCHAR(12) NOT NULL,
    source_type VARCHAR(40),
    source_id UUID,
    source_revision BIGINT,
    evidence_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_run_preparation_decision_ordinal_unique UNIQUE (
        tenant_id, preparation_id, decision_ordinal),
    CONSTRAINT pay_run_preparation_decision_code_unique UNIQUE (
        tenant_id, preparation_id, decision_code),
    CONSTRAINT pay_run_preparation_decision_parent_fk FOREIGN KEY (
        tenant_id, preparation_id, preparation_state_sequence)
        REFERENCES pay_run_preparations (
            tenant_id, preparation_id, state_sequence),
    CONSTRAINT pay_run_preparation_decision_positive CHECK (
        tenant_id > 0 AND preparation_state_sequence = 1
        AND decision_ordinal > 0
        AND (source_revision IS NULL OR source_revision > 0)),
    CONSTRAINT pay_run_preparation_decision_status_valid CHECK (
        decision_status IN ('PASS', 'FAIL')),
    CONSTRAINT pay_run_preparation_decision_source_shape CHECK (
        (source_type IS NULL AND source_id IS NULL AND source_revision IS NULL)
        OR (source_type IN ('TARGET_POPULATION_PROJECTION',
                'COMPENSATION_PLAN_SNAPSHOT', 'PAYROLL_FOUNDATION_POLICY',
                'PAYROLL_FORMULA_SET', 'SEMANTIC_MAPPING_SET')
            AND source_id IS NOT NULL AND source_revision IS NOT NULL)),
    CONSTRAINT pay_run_preparation_decision_digest_shape CHECK (
        evidence_digest ~ '^[0-9a-f]{64}$'
        AND decision_code ~ '^[A-Z0-9][A-Z0-9._-]{0,99}$')
);

CREATE TABLE pay_run_preparation_lines (
    preparation_line_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    preparation_id UUID NOT NULL,
    preparation_state_sequence INTEGER NOT NULL DEFAULT 1,
    line_ordinal INTEGER NOT NULL,
    compensation_plan_input_id BIGINT NOT NULL,
    source_public_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    snapshot_revision BIGINT NOT NULL,
    snapshot_payload_digest CHAR(64) NOT NULL,
    plan_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    component_code VARCHAR(80) NOT NULL,
    currency CHAR(3) NOT NULL,
    approved_amount NUMERIC(19,4) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    source_payload_digest CHAR(64) NOT NULL,
    semantic_binding JSONB NOT NULL,
    semantic_binding_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_run_preparation_line_ordinal_unique UNIQUE (
        tenant_id, preparation_id, line_ordinal),
    CONSTRAINT pay_run_preparation_line_source_unique UNIQUE (
        tenant_id, preparation_id, compensation_plan_input_id),
    CONSTRAINT pay_run_preparation_line_public_unique UNIQUE (
        tenant_id, preparation_id, source_public_id),
    CONSTRAINT pay_run_preparation_line_source_fk FOREIGN KEY (
        tenant_id, compensation_plan_input_id)
        REFERENCES pay_compensation_plan_inputs (
            tenant_id, compensation_plan_input_id),
    CONSTRAINT pay_run_preparation_line_parent_fk FOREIGN KEY (
        tenant_id, preparation_id, preparation_state_sequence)
        REFERENCES pay_run_preparations (
            tenant_id, preparation_id, state_sequence),
    CONSTRAINT pay_run_preparation_line_positive CHECK (
        tenant_id > 0 AND preparation_state_sequence = 1
        AND line_ordinal > 0 AND snapshot_revision > 0
        AND approved_amount >= 0
        AND (effective_to IS NULL OR effective_to >= effective_from)),
    CONSTRAINT pay_run_preparation_line_shape CHECK (
        snapshot_payload_digest ~ '^[0-9a-f]{64}$'
        AND source_payload_digest ~ '^[0-9a-f]{64}$'
        AND semantic_binding_digest ~ '^[0-9a-f]{64}$'
        AND jsonb_typeof(semantic_binding) = 'object'
        AND semantic_binding ->> 'bindingDigest' = semantic_binding_digest
        AND component_code ~ '^[A-Z0-9][A-Z0-9._-]{0,79}$'
        AND currency ~ '^[A-Z]{3}$')
);

CREATE TABLE pay_run_preparation_formula_refs (
    preparation_formula_internal_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    preparation_id UUID NOT NULL,
    preparation_state_sequence INTEGER NOT NULL DEFAULT 1,
    formula_id UUID NOT NULL,
    formula_version BIGINT NOT NULL,
    ast_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_run_preparation_formula_unique UNIQUE (
        tenant_id, preparation_id, formula_id),
    CONSTRAINT pay_run_preparation_formula_identity_unique UNIQUE (
        tenant_id, preparation_id, formula_id, formula_version, ast_digest),
    CONSTRAINT pay_run_preparation_formula_parent_fk FOREIGN KEY (
        tenant_id, preparation_id, preparation_state_sequence)
        REFERENCES pay_run_preparations (
            tenant_id, preparation_id, state_sequence),
    CONSTRAINT pay_run_preparation_formula_source_fk FOREIGN KEY (
        tenant_id, formula_id, formula_version)
        REFERENCES pay_formula_versions (tenant_id, formula_id, version),
    CONSTRAINT pay_run_preparation_formula_positive CHECK (
        tenant_id > 0 AND preparation_state_sequence = 1
        AND formula_version > 0),
    CONSTRAINT pay_run_preparation_formula_digest_shape CHECK (
        ast_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX pay_run_preparation_run_idx
    ON pay_run_preparations (tenant_id, run_id, state_sequence DESC);
CREATE INDEX pay_run_preparation_receipt_idx
    ON pay_run_preparations (tenant_id, receipt_id, state_sequence DESC);

ALTER TABLE pay_command_receipts
    DROP CONSTRAINT pay_command_receipt_command_valid;
ALTER TABLE pay_command_receipts
    ADD CONSTRAINT pay_command_receipt_command_valid CHECK (
        (command_type = 'RUN_PREPARE' AND originating_action = 'PAY_RUN_PREPARE')
        OR (command_type = 'RUN_CALCULATE'
            AND originating_action = 'PAY_RUN_CALCULATE')
        OR (command_type = 'RUN_APPROVE'
            AND originating_action = 'PAY_RUN_APPROVE')
        OR (command_type = 'RUN_FINALIZE'
            AND originating_action = 'PAY_RUN_FINALIZE'));

ALTER TABLE pay_outbox_events
    DROP CONSTRAINT pay_outbox_event_contract;
ALTER TABLE pay_outbox_events
    ADD CONSTRAINT pay_outbox_event_contract CHECK (
        schema_version = 1 AND event_type IN (
            'PayrollRunPrepared', 'PayrollCalculated',
            'PayrollRunApproved', 'PayrollFinalized'));

CREATE FUNCTION pay_v52_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $pay_v52_reject_mutation$
BEGIN
    RAISE EXCEPTION 'PAY-013 V52 preparation facts are append-only';
END
$pay_v52_reject_mutation$;

CREATE FUNCTION pay_v52_guard_prepare_receipt()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $pay_v52_prepare_receipt$
BEGIN
    IF NEW.status_sequence <> 1
       OR NEW.receipt_status <> 'SUCCEEDED'
       OR NEW.command_type <> 'RUN_PREPARE'
       OR NEW.originating_action <> 'PAY_RUN_PREPARE'
       OR NEW.purpose_code <> 'PAYROLL_PROCESSING'
       OR NEW.aggregate_version <> 2
       OR NEW.result_ref IS DISTINCT FROM
            'payroll-run:' || NEW.run_id::TEXT
       OR length(NEW.idempotency_key) NOT BETWEEN 16 AND 128
       OR NEW.step_up_challenge_id IS NULL
       OR NEW.step_up_nonce IS NULL
       OR NEW.step_up_issuer IS NULL
       OR NEW.step_up_expires_at IS NULL
       OR NEW.step_up_expires_at <= NEW.created_at
       OR NEW.step_up_payload_sha256 IS NULL
       OR NEW.step_up_decision_revision IS NULL
       OR NEW.audit_type <> 'PAYROLL_RUN_PREPARED'
       OR jsonb_typeof(NEW.audit_payload) <> 'object'
       OR NOT (NEW.audit_payload ?& ARRAY[
            'preparationId', 'requestDigest', 'snapshotDigest'])
       OR NEW.audit_payload - ARRAY[
            'preparationId', 'requestDigest',
            'snapshotDigest']::TEXT[] <> '{}'::JSONB
       OR NEW.audit_payload ->> 'requestDigest' <> NEW.request_digest
       OR (NEW.audit_payload ->> 'preparationId')::UUID IS NULL
       OR COALESCE(NEW.audit_payload ->> 'snapshotDigest', '')
            !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Payroll prepare receipt is not exact';
    END IF;
    RETURN NEW;
END
$pay_v52_prepare_receipt$;

CREATE FUNCTION pay_v52_require_step_up_use_binding()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $pay_v52_step_up_use$
DECLARE
    prepared public.pay_run_preparations%ROWTYPE;
    receipt_head public.pay_command_receipts%ROWTYPE;
BEGIN
    SELECT * INTO prepared
      FROM public.pay_run_preparations
     WHERE tenant_id = NEW.tenant_id
       AND preparation_id = NEW.preparation_id
       AND state_sequence = 1
       AND preparation_state = 'PREPARING';
    SELECT * INTO receipt_head
      FROM public.pay_command_receipts
     WHERE tenant_id = NEW.tenant_id
       AND subject_principal_public_id = NEW.subject_principal_public_id
       AND idempotency_key = NEW.idempotency_key
       AND command_type = 'RUN_PREPARE'
       AND originating_action = 'PAY_RUN_PREPARE'
       AND status_sequence = 1
     ORDER BY created_at DESC LIMIT 1;
    IF prepared.preparation_id IS NULL
       OR receipt_head.receipt_id IS NULL
       OR prepared.request_digest IS DISTINCT FROM NEW.request_digest
       OR prepared.maker_subject_public_id IS DISTINCT FROM
            NEW.subject_principal_public_id
       OR receipt_head.request_digest IS DISTINCT FROM NEW.request_digest
       OR receipt_head.audit_payload ->> 'preparationId' IS DISTINCT FROM
            NEW.preparation_id::TEXT
       OR receipt_head.step_up_issuer IS DISTINCT FROM NEW.issuer
       OR receipt_head.step_up_decision_revision IS DISTINCT FROM
            NEW.decision_revision
       OR (NEW.use_kind = 'PREPARE_COMMIT' AND (
            receipt_head.step_up_challenge_id IS DISTINCT FROM NEW.challenge_id
            OR receipt_head.step_up_nonce IS DISTINCT FROM NEW.nonce
            OR receipt_head.step_up_expires_at IS DISTINCT FROM NEW.expires_at
            OR receipt_head.step_up_payload_sha256 IS DISTINCT FROM
                NEW.payload_sha256
            OR receipt_head.created_at IS DISTINCT FROM NEW.used_at)) THEN
        RAISE EXCEPTION
            'Payroll preparation step-up use is not receipt bound';
    END IF;
    RETURN NEW;
END
$pay_v52_step_up_use$;

CREATE FUNCTION pay_v52_guard_preparation_child_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $pay_v52_preparation_child$
DECLARE
    preparation_run_id UUID;
    run_state TEXT;
    current_run_revision BIGINT;
BEGIN
    SELECT prepared.run_id INTO preparation_run_id
      FROM public.pay_run_preparations prepared
     WHERE prepared.tenant_id = NEW.tenant_id
       AND prepared.preparation_id = NEW.preparation_id
       AND prepared.state_sequence = 1
       AND prepared.preparation_state = 'PREPARING'
       AND NOT EXISTS (
           SELECT 1 FROM public.pay_run_preparations sealed
            WHERE sealed.tenant_id = prepared.tenant_id
              AND sealed.preparation_id = prepared.preparation_id
              AND sealed.state_sequence > 1);
    SELECT runs.state, runs.run_revision
      INTO run_state, current_run_revision
      FROM public.pay_payroll_runs
           runs
     WHERE runs.tenant_id = NEW.tenant_id
       AND runs.run_id = preparation_run_id
     ORDER BY runs.run_revision DESC LIMIT 1;
    IF preparation_run_id IS NULL OR run_state <> 'PREPARING'
       OR current_run_revision <> 1 THEN
        RAISE EXCEPTION 'Payroll preparation child facts are sealed';
    END IF;
    RETURN NEW;
END
$pay_v52_preparation_child$;

CREATE FUNCTION pay_v52_guard_ready_run_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $pay_v52_ready_run$
DECLARE
    prior_run public.pay_payroll_runs%ROWTYPE;
    snapshot_fact public.pay_input_snapshots%ROWTYPE;
    receipt_head public.pay_command_receipts%ROWTYPE;
    prepared public.pay_run_preparations%ROWTYPE;
    current_attempt_state TEXT;
    attempt_receipt UUID;
    attempt_engine TEXT;
    ref_count INTEGER;
    source_count INTEGER;
    decision_count INTEGER;
    line_count INTEGER;
    formula_count INTEGER;
    compensation_line_count INTEGER;
BEGIN
    SELECT * INTO prior_run
      FROM public.pay_payroll_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
     ORDER BY run_revision DESC LIMIT 1;
    IF NOT FOUND
       OR NEW.run_revision <> prior_run.run_revision + 1
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
       OR NEW.worker_count <> 0 OR NEW.gross_amount <> 0 OR NEW.net_amount <> 0
       OR NEW.result_digest IS NOT NULL OR NEW.approval_case_id IS NOT NULL
       OR NEW.decision_digest IS NOT NULL OR NEW.approved_by_actor_id IS NOT NULL
       OR NEW.approved_at IS NOT NULL OR NEW.audit_bundle_digest IS NOT NULL
       OR NEW.command_receipt_id IS NULL THEN
        RAISE EXCEPTION 'Payroll READY run identity or empty result facts differ';
    END IF;

    SELECT * INTO receipt_head
      FROM public.pay_command_receipts
     WHERE tenant_id = NEW.tenant_id
       AND receipt_id = NEW.command_receipt_id
     ORDER BY status_sequence DESC LIMIT 1;

    -- PAY-013 preparation READY has no calculation attempt. Its complete,
    -- already-bound attempt-one snapshot is sealed before this revision.
    IF prior_run.state = 'PREPARING'
       AND prior_run.latest_attempt_no = 0
       AND NEW.latest_attempt_no = 0
       AND receipt_head.command_type = 'RUN_PREPARE' THEN
        SELECT * INTO prepared
          FROM public.pay_run_preparations
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND state_sequence = 1 AND preparation_state = 'PREPARING';
        SELECT * INTO snapshot_fact
          FROM public.pay_input_snapshots
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND attempt_no = 1;
        SELECT COUNT(*) INTO ref_count
          FROM public.pay_input_snapshot_compensation_refs ref
         WHERE ref.tenant_id = NEW.tenant_id
           AND ref.input_snapshot_internal_id =
               snapshot_fact.input_snapshot_internal_id;
        SELECT COUNT(*),
               MAX(source_line_count) FILTER (
                   WHERE source_type = 'COMPENSATION_PLAN_SNAPSHOT')
          INTO source_count, compensation_line_count
          FROM public.pay_run_preparation_sources
         WHERE tenant_id = NEW.tenant_id
           AND preparation_id = prepared.preparation_id
           AND source_status = 'CURRENT';
        SELECT COUNT(*) INTO decision_count
          FROM public.pay_run_preparation_decisions
         WHERE tenant_id = NEW.tenant_id
           AND preparation_id = prepared.preparation_id
           AND decision_status = 'PASS';
        SELECT COUNT(*) INTO line_count
          FROM public.pay_run_preparation_lines
         WHERE tenant_id = NEW.tenant_id
           AND preparation_id = prepared.preparation_id;
        SELECT COUNT(*) INTO formula_count
          FROM public.pay_run_preparation_formula_refs
         WHERE tenant_id = NEW.tenant_id
           AND preparation_id = prepared.preparation_id;
        IF prepared.preparation_id IS NULL
           OR receipt_head.receipt_status <> 'SUCCEEDED'
           OR receipt_head.originating_action <> 'PAY_RUN_PREPARE'
           OR receipt_head.run_id IS DISTINCT FROM NEW.run_id
           OR receipt_head.aggregate_version IS DISTINCT FROM NEW.run_revision
           OR receipt_head.audit_payload ->> 'preparationId' <>
                prepared.preparation_id::TEXT
           OR receipt_head.audit_payload ->> 'snapshotDigest' <>
                prepared.snapshot_digest
           OR prepared.receipt_id IS DISTINCT FROM NEW.command_receipt_id
           OR prepared.run_revision IS DISTINCT FROM NEW.run_revision
           OR prepared.authorization_scope_digest IS DISTINCT FROM
                NEW.population_scope_digest
           OR snapshot_fact.input_snapshot_id IS DISTINCT FROM prepared.snapshot_id
           OR snapshot_fact.source_snapshot_id IS DISTINCT FROM NEW.source_snapshot_id
           OR snapshot_fact.source_snapshot_revision IS DISTINCT FROM
                NEW.source_snapshot_revision
           OR snapshot_fact.source_payload_digest IS DISTINCT FROM
                NEW.source_payload_digest
           OR snapshot_fact.target_population_digest IS DISTINCT FROM
                prepared.authorization_scope_digest
           OR snapshot_fact.engine_version <> 'PAY_CALCULATION_V1'
           OR snapshot_fact.semantic_binding_digest IS NULL
           OR ref_count IS DISTINCT FROM snapshot_fact.source_line_count
           OR source_count <> 5
           OR decision_count < 5
           OR EXISTS (
                SELECT 1 FROM public.pay_run_preparation_decisions decision
                 WHERE decision.tenant_id = NEW.tenant_id
                   AND decision.preparation_id = prepared.preparation_id
                   AND decision.decision_status <> 'PASS')
           OR line_count IS DISTINCT FROM snapshot_fact.source_line_count
           OR compensation_line_count IS DISTINCT FROM line_count
           OR formula_count < 1
           OR formula_count IS DISTINCT FROM
                jsonb_array_length(snapshot_fact.formula_bindings)
           OR EXISTS (
                SELECT 1
                  FROM public.pay_run_preparation_lines line
                  LEFT JOIN public.pay_input_snapshot_compensation_refs ref
                    ON ref.tenant_id = line.tenant_id
                   AND ref.input_snapshot_internal_id =
                       snapshot_fact.input_snapshot_internal_id
                   AND ref.input_ordinal = line.line_ordinal
                   AND ref.compensation_plan_input_id =
                       line.compensation_plan_input_id
                 WHERE line.tenant_id = NEW.tenant_id
                   AND line.preparation_id = prepared.preparation_id
                   AND (ref.compensation_plan_input_id IS NULL
                        OR ref.worker_id IS DISTINCT FROM line.worker_id
                        OR ref.assignment_id IS DISTINCT FROM line.assignment_id
                        OR ref.component_code IS DISTINCT FROM line.component_code
                        OR ref.currency IS DISTINCT FROM line.currency
                        OR ref.approved_amount IS DISTINCT FROM line.approved_amount
                        OR ref.source_payload_digest IS DISTINCT FROM
                            line.source_payload_digest
                        OR ref.semantic_binding IS DISTINCT FROM
                            line.semantic_binding
                        OR ref.semantic_binding_digest IS DISTINCT FROM
                            line.semantic_binding_digest)) THEN
            RAISE EXCEPTION 'Payroll preparation READY proof is incomplete';
        END IF;
        RETURN NEW;
    END IF;

    -- V50 calculation READY, including the first attempt consuming the
    -- PAY-013 pre-frozen snapshot, remains attempt and receipt bound.
    IF NOT ((prior_run.state IN ('PREPARING', 'FAILED', 'RESULT_UNKNOWN')
                AND NEW.latest_attempt_no = prior_run.latest_attempt_no + 1)
            OR (prior_run.state = 'READY' AND prior_run.latest_attempt_no = 0
                AND NEW.latest_attempt_no = 1)) THEN
        RAISE EXCEPTION 'Illegal Payroll calculation READY transition';
    END IF;
    SELECT * INTO snapshot_fact
      FROM public.pay_input_snapshots
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
       AND attempt_no = NEW.latest_attempt_no;
    SELECT COUNT(*) INTO ref_count
      FROM public.pay_input_snapshot_compensation_refs ref
     WHERE ref.tenant_id = NEW.tenant_id
       AND ref.input_snapshot_internal_id = snapshot_fact.input_snapshot_internal_id;
    SELECT attempt.attempt_state, attempt.command_receipt_id,
           attempt.engine_version
      INTO current_attempt_state, attempt_receipt, attempt_engine
      FROM public.pay_payroll_run_attempts attempt
     WHERE attempt.tenant_id = NEW.tenant_id
       AND attempt.run_id = NEW.run_id
       AND attempt.attempt_no = NEW.latest_attempt_no
     ORDER BY attempt.status_sequence DESC LIMIT 1;
    IF snapshot_fact.input_snapshot_internal_id IS NULL
       OR receipt_head.receipt_id IS NULL
       OR snapshot_fact.source_snapshot_id IS DISTINCT FROM NEW.source_snapshot_id
       OR snapshot_fact.source_snapshot_revision IS DISTINCT FROM
            NEW.source_snapshot_revision
       OR snapshot_fact.source_payload_digest IS DISTINCT FROM
            NEW.source_payload_digest
       OR snapshot_fact.target_population_digest IS DISTINCT FROM
            NEW.population_scope_digest
       OR ref_count IS DISTINCT FROM snapshot_fact.source_line_count
       OR current_attempt_state IS DISTINCT FROM 'READY'
       OR attempt_receipt IS DISTINCT FROM NEW.command_receipt_id
       OR attempt_engine IS DISTINCT FROM snapshot_fact.engine_version
       OR receipt_head.receipt_status <> 'ACCEPTED'
       OR receipt_head.command_type <> 'RUN_CALCULATE'
       OR receipt_head.originating_action <> 'PAY_RUN_CALCULATE'
       OR receipt_head.run_id IS DISTINCT FROM NEW.run_id THEN
        RAISE EXCEPTION 'Payroll calculation READY proof is unavailable';
    END IF;
    RETURN NEW;
END
$pay_v52_ready_run$;

CREATE FUNCTION pay_v52_guard_preparation_state()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $pay_v52_preparation_state$
DECLARE
    prior public.pay_run_preparations%ROWTYPE;
    run_fact public.pay_payroll_runs%ROWTYPE;
    snapshot_fact public.pay_input_snapshots%ROWTYPE;
    receipt_head public.pay_command_receipts%ROWTYPE;
    source_count INTEGER;
    decision_count INTEGER;
    prepared_line_count INTEGER;
    formula_count INTEGER;
    ref_count INTEGER;
BEGIN
    SELECT * INTO prior
      FROM public.pay_run_preparations
     WHERE tenant_id = NEW.tenant_id
       AND preparation_id = NEW.preparation_id
     ORDER BY state_sequence DESC LIMIT 1;
    IF NOT FOUND THEN
        SELECT * INTO run_fact
          FROM public.pay_payroll_runs
         WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
           AND run_revision = 1;
        IF run_fact.run_id IS NULL
           OR NEW.state_sequence <> 1 OR NEW.preparation_state <> 'PREPARING'
           OR NEW.event_id IS NOT NULL OR run_fact.state <> 'PREPARING'
           OR run_fact.latest_attempt_no <> 0
           OR run_fact.maker_actor_id IS DISTINCT FROM NEW.maker_actor_id
           OR run_fact.maker_subject_public_id IS DISTINCT FROM
                NEW.maker_subject_public_id
           OR run_fact.legal_entity_id IS DISTINCT FROM NEW.legal_entity_id
           OR run_fact.payroll_group_id IS DISTINCT FROM NEW.payroll_group_id
           OR run_fact.pay_period_id IS DISTINCT FROM NEW.pay_period_id
           OR run_fact.population_kind IS DISTINCT FROM NEW.population_kind
           OR run_fact.population_scope_digest IS DISTINCT FROM
                NEW.authorization_scope_digest THEN
            RAISE EXCEPTION 'Payroll preparation must start at PREPARING';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.state_sequence <> 2 OR NEW.preparation_state <> 'READY'
       OR prior.state_sequence <> 1 OR prior.preparation_state <> 'PREPARING'
       OR ROW(NEW.run_id, NEW.run_revision, NEW.request_digest,
              NEW.snapshot_id, NEW.snapshot_digest, NEW.population_revision,
              NEW.target_population_digest, NEW.authorization_scope_digest,
              NEW.legal_entity_id, NEW.payroll_group_id, NEW.pay_period_id,
              NEW.population_kind, NEW.named_population_id, NEW.maker_actor_id,
              NEW.maker_subject_public_id, NEW.context_scope_key,
              NEW.authorization_revision, NEW.receipt_id,
              NEW.source_as_of, NEW.recorded_at)
          IS DISTINCT FROM
          ROW(prior.run_id, prior.run_revision, prior.request_digest,
              prior.snapshot_id, prior.snapshot_digest, prior.population_revision,
              prior.target_population_digest, prior.authorization_scope_digest,
              prior.legal_entity_id, prior.payroll_group_id, prior.pay_period_id,
              prior.population_kind, prior.named_population_id,
              prior.maker_actor_id, prior.maker_subject_public_id,
              prior.context_scope_key, prior.authorization_revision,
              prior.receipt_id, prior.source_as_of, prior.recorded_at) THEN
        RAISE EXCEPTION 'Payroll preparation identity or sequence changed';
    END IF;
    SELECT * INTO run_fact
      FROM public.pay_payroll_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
       AND run_revision = NEW.run_revision;
    SELECT * INTO snapshot_fact
      FROM public.pay_input_snapshots
     WHERE tenant_id = NEW.tenant_id
       AND input_snapshot_id = NEW.snapshot_id;
    SELECT * INTO receipt_head
      FROM public.pay_command_receipts
     WHERE tenant_id = NEW.tenant_id AND receipt_id = NEW.receipt_id
     ORDER BY status_sequence DESC LIMIT 1;
    SELECT COUNT(*) INTO source_count
      FROM public.pay_run_preparation_sources
     WHERE tenant_id = NEW.tenant_id
       AND preparation_id = NEW.preparation_id;
    SELECT COUNT(*) INTO decision_count
      FROM public.pay_run_preparation_decisions
     WHERE tenant_id = NEW.tenant_id
       AND preparation_id = NEW.preparation_id;
    SELECT COUNT(*) INTO prepared_line_count
      FROM public.pay_run_preparation_lines
     WHERE tenant_id = NEW.tenant_id
       AND preparation_id = NEW.preparation_id;
    SELECT COUNT(*) INTO formula_count
      FROM public.pay_run_preparation_formula_refs
     WHERE tenant_id = NEW.tenant_id
       AND preparation_id = NEW.preparation_id;
    SELECT COUNT(*) INTO ref_count
      FROM public.pay_input_snapshot_compensation_refs ref
     WHERE ref.tenant_id = NEW.tenant_id
       AND ref.input_snapshot_internal_id =
           snapshot_fact.input_snapshot_internal_id;
    IF run_fact.run_id IS NULL
       OR snapshot_fact.input_snapshot_internal_id IS NULL
       OR receipt_head.receipt_id IS NULL
       OR run_fact.state <> 'READY' OR run_fact.latest_attempt_no <> 0
       OR run_fact.command_receipt_id IS DISTINCT FROM NEW.receipt_id
       OR run_fact.population_scope_digest IS DISTINCT FROM
            NEW.authorization_scope_digest
       OR receipt_head.command_type <> 'RUN_PREPARE'
       OR receipt_head.originating_action <> 'PAY_RUN_PREPARE'
       OR receipt_head.receipt_status <> 'SUCCEEDED'
       OR receipt_head.run_id IS DISTINCT FROM NEW.run_id
       OR receipt_head.request_digest IS DISTINCT FROM NEW.request_digest
       OR receipt_head.aggregate_version IS DISTINCT FROM NEW.run_revision
       OR receipt_head.actor_id IS DISTINCT FROM NEW.maker_actor_id
       OR receipt_head.subject_principal_public_id IS DISTINCT FROM
            NEW.maker_subject_public_id
       OR receipt_head.population_scope_digest IS DISTINCT FROM
            NEW.authorization_scope_digest
       OR receipt_head.authorization_revision IS DISTINCT FROM
            NEW.authorization_revision
       OR receipt_head.audit_payload ->> 'preparationId' <>
            NEW.preparation_id::TEXT
       OR receipt_head.audit_payload ->> 'snapshotDigest' <>
            NEW.snapshot_digest
       OR NOT EXISTS (
            SELECT 1
              FROM public.pay_run_preparation_step_up_uses step_up_use
             WHERE step_up_use.tenant_id = NEW.tenant_id
               AND step_up_use.preparation_id = NEW.preparation_id
               AND step_up_use.subject_principal_public_id =
                    NEW.maker_subject_public_id
               AND step_up_use.idempotency_key = receipt_head.idempotency_key
               AND step_up_use.request_digest = NEW.request_digest
               AND step_up_use.challenge_id =
                    receipt_head.step_up_challenge_id
               AND step_up_use.nonce = receipt_head.step_up_nonce
               AND step_up_use.use_kind = 'PREPARE_COMMIT')
       OR snapshot_fact.run_id IS DISTINCT FROM NEW.run_id
       OR snapshot_fact.attempt_no <> 1
       OR snapshot_fact.target_population_digest IS DISTINCT FROM
            NEW.authorization_scope_digest
       OR source_count <> 5 OR decision_count <> 7
       OR EXISTS (
            SELECT 1 FROM public.pay_run_preparation_sources source
             WHERE source.tenant_id = NEW.tenant_id
               AND source.preparation_id = NEW.preparation_id
               AND source.source_status <> 'CURRENT')
       OR EXISTS (
            SELECT 1 FROM public.pay_run_preparation_decisions decision
             WHERE decision.tenant_id = NEW.tenant_id
               AND decision.preparation_id = NEW.preparation_id
               AND decision.decision_status <> 'PASS')
       OR EXISTS (
            SELECT 1
              FROM (VALUES
                    ('PURPOSE_AUTHORITY_VERIFIED'),
                    ('TARGET_POPULATION_CURRENT'),
                    ('COMPENSATION_SNAPSHOT_COMPLETE'),
                    ('SOURCE_APPROVAL_VERIFIED'),
                    ('FOUNDATION_POLICY_PUBLISHED'),
                    ('FORMULA_SET_PUBLISHED'),
                    ('SEMANTIC_MAPPING_EXACT')) expected(decision_code)
             WHERE NOT EXISTS (
                SELECT 1 FROM public.pay_run_preparation_decisions actual
                 WHERE actual.tenant_id = NEW.tenant_id
                   AND actual.preparation_id = NEW.preparation_id
                   AND actual.decision_code = expected.decision_code))
       OR NOT EXISTS (
            SELECT 1
              FROM public.pay_run_preparation_sources source
              JOIN public.pay_population_source_revisions authority
                ON authority.tenant_id = source.tenant_id
               AND authority.source_id = source.source_id
               AND authority.source_revision = source.source_revision
               AND authority.payload_digest = source.payload_digest
               AND authority.legal_entity_id = NEW.legal_entity_id
               AND authority.payroll_group_id = NEW.payroll_group_id
               AND authority.population_kind = NEW.population_kind
               AND authority.named_population_id IS NOT DISTINCT FROM
                    NEW.named_population_id
               AND authority.item_count = source.source_line_count
               AND authority.effective_at <= NEW.source_as_of
               AND authority.recorded_at <= NEW.source_as_of
               AND authority.source_revision = (
                   SELECT MAX(current.source_revision)
                     FROM public.pay_population_source_revisions current
                    WHERE current.tenant_id = authority.tenant_id
                      AND current.legal_entity_id = authority.legal_entity_id
                      AND current.payroll_group_id = authority.payroll_group_id
                      AND current.population_kind = authority.population_kind
                      AND current.named_population_id IS NOT DISTINCT FROM
                          authority.named_population_id
                      AND current.effective_at <= NEW.source_as_of
                      AND current.recorded_at <= NEW.source_as_of)
               AND authority.item_count = (
                   SELECT COUNT(DISTINCT member.worker_id)
                     FROM public.pay_population_source_members member
                    WHERE member.tenant_id = authority.tenant_id
                      AND member.source_id = authority.source_id
                      AND member.source_revision = authority.source_revision
                      AND member.recorded_at <= NEW.source_as_of)
               AND authority.payload_digest = (
                   SELECT encode(sha256(convert_to(
                       '{"members":[' || COALESCE(string_agg(
                           '{"compensationPlanInputId":'
                           || member.compensation_plan_input_id::TEXT
                           || ',"payloadDigest":"'
                           || member.source_payload_digest
                           || '","workerId":"' || member.worker_id::TEXT || '"}',
                           ',' ORDER BY member.member_ordinal), '')
                       || '],"schema":"PayrollPopulationSourceRevision.v1"'
                       || ',"scope":{"legalEntityId":"'
                       || authority.legal_entity_id::TEXT
                       || '","payrollGroupId":"'
                       || authority.payroll_group_id::TEXT
                       || '","populationKind":"' || authority.population_kind
                       || '","tenantId":' || authority.tenant_id::TEXT || '}'
                       || ',"sourceId":"' || authority.source_id::TEXT
                       || '","sourceRevision":'
                       || authority.source_revision::TEXT || '}', 'UTF8')), 'hex')
                     FROM public.pay_population_source_members member
                    WHERE member.tenant_id = authority.tenant_id
                      AND member.source_id = authority.source_id
                      AND member.source_revision = authority.source_revision
                      AND member.recorded_at <= NEW.source_as_of)
             WHERE source.tenant_id = NEW.tenant_id
               AND source.preparation_id = NEW.preparation_id
               AND source.source_type = 'TARGET_POPULATION_PROJECTION'
               AND source.payload_digest = NEW.target_population_digest
               AND source.source_line_count = (
                   SELECT COUNT(DISTINCT line.worker_id)
                     FROM public.pay_run_preparation_lines line
                    WHERE line.tenant_id = NEW.tenant_id
                      AND line.preparation_id = NEW.preparation_id)
               AND NOT EXISTS (
                   SELECT 1
                     FROM public.pay_population_source_members member
                    WHERE member.tenant_id = authority.tenant_id
                      AND member.source_id = authority.source_id
                      AND member.source_revision = authority.source_revision
                      AND NOT EXISTS (
                          SELECT 1
                            FROM public.pay_run_preparation_lines line
                           WHERE line.tenant_id = NEW.tenant_id
                             AND line.preparation_id = NEW.preparation_id
                             AND line.compensation_plan_input_id =
                                 member.compensation_plan_input_id
                             AND line.worker_id = member.worker_id
                             AND line.source_payload_digest =
                                 member.source_payload_digest))
               AND NOT EXISTS (
                   SELECT 1
                     FROM public.pay_run_preparation_lines line
                    WHERE line.tenant_id = NEW.tenant_id
                      AND line.preparation_id = NEW.preparation_id
                      AND NOT EXISTS (
                          SELECT 1
                            FROM public.pay_population_source_members member
                           WHERE member.tenant_id = authority.tenant_id
                             AND member.source_id = authority.source_id
                             AND member.source_revision = authority.source_revision
                             AND member.compensation_plan_input_id =
                                 line.compensation_plan_input_id
                             AND member.worker_id = line.worker_id
                             AND member.source_payload_digest =
                                 line.source_payload_digest)))
       OR NOT EXISTS (
            SELECT 1 FROM public.pay_run_preparation_sources source
             WHERE source.tenant_id = NEW.tenant_id
               AND source.preparation_id = NEW.preparation_id
               AND source.source_type = 'COMPENSATION_PLAN_SNAPSHOT'
               AND source.source_id = run_fact.source_snapshot_id
               AND source.source_revision = run_fact.source_snapshot_revision
               AND source.payload_digest = run_fact.source_payload_digest
               AND source.source_line_count = snapshot_fact.source_line_count
               AND source.approval_receipt_id IS NOT NULL
               AND source.approval_revision IS NOT NULL)
       OR NOT EXISTS (
            SELECT 1
              FROM public.pay_run_preparation_sources source
              JOIN public.pay_compensation_plan_input_receipts receipt
                ON receipt.tenant_id = source.tenant_id
               AND receipt.snapshot_id = source.source_id
               AND receipt.snapshot_revision = source.source_revision
               AND receipt.snapshot_payload_digest = source.payload_digest
               AND receipt.line_count = source.source_line_count
               AND receipt.applied_at <= NEW.source_as_of
             WHERE source.tenant_id = NEW.tenant_id
               AND source.preparation_id = NEW.preparation_id
               AND source.source_type = 'COMPENSATION_PLAN_SNAPSHOT')
       OR NOT EXISTS (
            SELECT 1
              FROM public.pay_run_preparation_sources source
              JOIN public.pay_foundation_configurations configuration
                ON configuration.tenant_id = source.tenant_id
               AND configuration.configuration_id = source.source_id
               AND configuration.current_version = source.source_revision
               AND configuration.lifecycle_state = 'PUBLISHED'
               AND configuration.legal_entity_id = NEW.legal_entity_id
               AND configuration.payroll_group_id = NEW.payroll_group_id
              JOIN public.pay_foundation_versions version
                ON version.tenant_id = configuration.tenant_id
               AND version.configuration_id = configuration.configuration_id
               AND version.version = configuration.current_version
               AND version.lifecycle_state = 'PUBLISHED'
               AND version.definition_digest = source.payload_digest
             WHERE source.tenant_id = NEW.tenant_id
               AND source.preparation_id = NEW.preparation_id
               AND source.source_type = 'PAYROLL_FOUNDATION_POLICY'
               AND configuration.updated_at <= NEW.source_as_of
               AND version.recorded_at <= NEW.source_as_of
               AND configuration.effective_from <= (
                   SELECT MIN(line.effective_from)
                     FROM public.pay_run_preparation_lines line
                    WHERE line.tenant_id = NEW.tenant_id
                      AND line.preparation_id = NEW.preparation_id)
               AND (configuration.effective_to IS NULL
                    OR configuration.effective_to >= (
                        SELECT MIN(line.effective_from)
                          FROM public.pay_run_preparation_lines line
                         WHERE line.tenant_id = NEW.tenant_id
                           AND line.preparation_id = NEW.preparation_id)))
       OR 1 IS DISTINCT FROM (
            SELECT COUNT(*)
              FROM public.pay_foundation_configurations configuration
              JOIN public.pay_foundation_versions version
                ON version.tenant_id = configuration.tenant_id
               AND version.configuration_id = configuration.configuration_id
               AND version.version = configuration.current_version
               AND version.lifecycle_state = 'PUBLISHED'
               AND version.recorded_at <= NEW.source_as_of
             WHERE configuration.tenant_id = NEW.tenant_id
               AND configuration.legal_entity_id = NEW.legal_entity_id
               AND configuration.payroll_group_id = NEW.payroll_group_id
               AND configuration.lifecycle_state = 'PUBLISHED'
               AND configuration.updated_at <= NEW.source_as_of
               AND configuration.effective_from <= (
                   SELECT MIN(line.effective_from)
                     FROM public.pay_run_preparation_lines line
                    WHERE line.tenant_id = NEW.tenant_id
                      AND line.preparation_id = NEW.preparation_id)
               AND (configuration.effective_to IS NULL
                    OR configuration.effective_to >= (
                        SELECT MIN(line.effective_from)
                          FROM public.pay_run_preparation_lines line
                         WHERE line.tenant_id = NEW.tenant_id
                           AND line.preparation_id = NEW.preparation_id)))
       OR NOT EXISTS (
            WITH canonical_formula_set AS (
                SELECT '[' || string_agg(
                    '{"astDigest":"' || formula.ast_digest
                    || '","formulaId":"' || formula.formula_id::TEXT
                    || '","version":' || formula.formula_version::TEXT || '}',
                    ',' ORDER BY
                        CASE WHEN substring(replace(
                                      formula.formula_id::TEXT, '-', ''), 1, 1)
                                  IN ('8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
                             THEN 0 ELSE 1 END,
                        substring(replace(
                            formula.formula_id::TEXT, '-', ''), 1, 16),
                        CASE WHEN substring(replace(
                                      formula.formula_id::TEXT, '-', ''), 17, 1)
                                  IN ('8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
                             THEN 0 ELSE 1 END,
                        substring(replace(
                            formula.formula_id::TEXT, '-', ''), 17, 16))
                    || ']' AS canonical_json
                  FROM public.pay_run_preparation_formula_refs formula
                 WHERE formula.tenant_id = NEW.tenant_id
                   AND formula.preparation_id = NEW.preparation_id
            ), canonical_identity AS (
                SELECT encode(sha256(convert_to(
                           formula_set.canonical_json, 'UTF8')), 'hex') AS digest,
                       encode(sha256(
                           convert_to('payroll-formula-set-v1', 'UTF8')
                           || decode('00', 'hex')
                           || convert_to(
                               '{"legalEntityId":"'
                               || NEW.legal_entity_id::TEXT
                               || '","payrollGroupId":"'
                               || NEW.payroll_group_id::TEXT
                               || '","tenantId":' || NEW.tenant_id::TEXT || '}',
                               'UTF8')), 'hex') AS identity_hash
                  FROM canonical_formula_set formula_set
            ), expected AS (
                SELECT identity.digest,
                       (substring(identity.identity_hash, 1, 8) || '-'
                        || substring(identity.identity_hash, 9, 4) || '-5'
                        || substring(identity.identity_hash, 14, 3) || '-'
                        || substring('89ab',
                            mod(strpos('0123456789abcdef', substring(
                                identity.identity_hash, 17, 1)) - 1, 4) + 1, 1)
                        || substring(identity.identity_hash, 18, 3) || '-'
                        || substring(identity.identity_hash, 21, 12))::UUID
                           AS source_id
                  FROM canonical_identity identity
            )
            SELECT 1 FROM public.pay_run_preparation_sources source
              CROSS JOIN expected
             WHERE source.tenant_id = NEW.tenant_id
               AND source.preparation_id = NEW.preparation_id
               AND source.source_type = 'PAYROLL_FORMULA_SET'
               AND source.payload_digest = snapshot_fact.formula_set_digest
               AND source.payload_digest = expected.digest
               AND source.source_id = expected.source_id
               AND source.source_line_count = formula_count
               AND source.source_revision = (
                   SELECT MAX(formula_version)
                     FROM public.pay_run_preparation_formula_refs formula
                    WHERE formula.tenant_id = NEW.tenant_id
                      AND formula.preparation_id = NEW.preparation_id))
       OR NOT EXISTS (
            SELECT 1
              FROM public.pay_run_preparation_sources source
              JOIN public.pay_semantic_source_revisions authority
                ON authority.tenant_id = source.tenant_id
               AND authority.source_id = source.source_id
               AND authority.source_revision = source.source_revision
               AND authority.payload_digest = source.payload_digest
               AND authority.legal_entity_id = NEW.legal_entity_id
               AND authority.payroll_group_id = NEW.payroll_group_id
               AND authority.pay_period_id = NEW.pay_period_id
               AND authority.target_population_digest =
                    NEW.target_population_digest
               AND authority.source_snapshot_id = run_fact.source_snapshot_id
               AND authority.source_snapshot_revision =
                    run_fact.source_snapshot_revision
               AND authority.source_payload_digest = run_fact.source_payload_digest
               AND authority.formula_set_digest = snapshot_fact.formula_set_digest
               AND authority.function_set_version =
                    snapshot_fact.function_set_version
               AND authority.line_count = source.source_line_count
               AND authority.effective_at <= NEW.source_as_of
               AND authority.recorded_at <= NEW.source_as_of
               AND authority.source_revision = (
                   SELECT MAX(current.source_revision)
                     FROM public.pay_semantic_source_revisions current
                    WHERE current.tenant_id = authority.tenant_id
                      AND current.legal_entity_id = authority.legal_entity_id
                      AND current.payroll_group_id = authority.payroll_group_id
                      AND current.pay_period_id = authority.pay_period_id
                      AND current.target_population_digest =
                          authority.target_population_digest
                      AND current.source_snapshot_id =
                          authority.source_snapshot_id
                      AND current.source_snapshot_revision =
                          authority.source_snapshot_revision
                      AND current.formula_set_digest =
                          authority.formula_set_digest
                      AND current.effective_at <= NEW.source_as_of
                      AND current.recorded_at <= NEW.source_as_of)
               AND authority.line_count = prepared_line_count
               AND authority.payload_digest = (
                   SELECT encode(sha256(convert_to(
                       '{"bindingDigests":[' || string_agg(
                           '"' || line.semantic_binding_digest || '"',
                           ',' ORDER BY line.line_ordinal)
                       || '],"engineVersion":"PAY_CALCULATION_V1"'
                       || ',"schema":"PayrollSemanticBindingSet.v1"}',
                       'UTF8')), 'hex')
                     FROM public.pay_run_preparation_lines line
                    WHERE line.tenant_id = NEW.tenant_id
                      AND line.preparation_id = NEW.preparation_id)
             WHERE source.tenant_id = NEW.tenant_id
               AND source.preparation_id = NEW.preparation_id
               AND source.source_type = 'SEMANTIC_MAPPING_SET'
               AND source.payload_digest = snapshot_fact.semantic_binding_digest
               AND source.source_line_count = snapshot_fact.source_line_count)
       OR EXISTS (
            SELECT 1
              FROM public.pay_run_preparation_decisions decision
             WHERE decision.tenant_id = NEW.tenant_id
               AND decision.preparation_id = NEW.preparation_id
               AND decision.source_id IS NOT NULL
               AND NOT EXISTS (
                   SELECT 1 FROM public.pay_run_preparation_sources source
                    WHERE source.tenant_id = decision.tenant_id
                      AND source.preparation_id = decision.preparation_id
                      AND source.source_type = decision.source_type
                      AND source.source_id = decision.source_id
                      AND source.source_revision = decision.source_revision))
       OR EXISTS (
            SELECT 1
              FROM public.pay_run_preparation_lines line
              JOIN public.pay_run_preparation_sources compensation
                ON compensation.tenant_id = line.tenant_id
               AND compensation.preparation_id = line.preparation_id
               AND compensation.source_type = 'COMPENSATION_PLAN_SNAPSHOT'
              LEFT JOIN public.pay_compensation_plan_inputs authoritative
                ON authoritative.tenant_id = line.tenant_id
               AND authoritative.compensation_plan_input_id =
                   line.compensation_plan_input_id
             WHERE line.tenant_id = NEW.tenant_id
               AND line.preparation_id = NEW.preparation_id
               AND (authoritative.compensation_plan_input_id IS NULL
                    OR line.source_public_id IS DISTINCT FROM authoritative.public_id
                    OR line.source_event_id IS DISTINCT FROM authoritative.source_event_id
                    OR line.snapshot_id IS DISTINCT FROM authoritative.snapshot_id
                    OR line.snapshot_id IS DISTINCT FROM run_fact.source_snapshot_id
                    OR line.snapshot_revision IS DISTINCT FROM
                        authoritative.snapshot_revision
                    OR line.snapshot_revision IS DISTINCT FROM
                        run_fact.source_snapshot_revision
                    OR line.snapshot_payload_digest IS DISTINCT FROM
                        authoritative.snapshot_payload_digest
                    OR line.snapshot_payload_digest IS DISTINCT FROM
                        run_fact.source_payload_digest
                    OR line.plan_id IS DISTINCT FROM authoritative.plan_id
                    OR line.cycle_id IS DISTINCT FROM authoritative.cycle_id
                    OR line.cycle_id IS DISTINCT FROM NEW.pay_period_id
                    OR line.line_ordinal IS DISTINCT FROM authoritative.line_sequence
                    OR line.worker_id IS DISTINCT FROM authoritative.worker_id
                    OR line.assignment_id IS DISTINCT FROM authoritative.assignment_id
                    OR line.component_code IS DISTINCT FROM
                        authoritative.component_code
                    OR line.currency IS DISTINCT FROM authoritative.currency
                    OR line.approved_amount IS DISTINCT FROM
                        authoritative.approved_amount
                    OR line.effective_from IS DISTINCT FROM authoritative.effective_from
                    OR line.effective_to IS DISTINCT FROM authoritative.effective_to
                    OR line.source_payload_digest IS DISTINCT FROM
                        authoritative.payload_digest
                    OR authoritative.approval_receipt_id IS DISTINCT FROM
                        compensation.approval_receipt_id
                    OR authoritative.approval_revision IS DISTINCT FROM
                        compensation.approval_revision
                    OR authoritative.recorded_at > NEW.source_as_of))
       OR EXISTS (
            SELECT 1
              FROM public.pay_run_preparation_formula_refs formula
              LEFT JOIN public.pay_formula_versions authoritative
                ON authoritative.tenant_id = formula.tenant_id
               AND authoritative.formula_id = formula.formula_id
               AND authoritative.version = formula.formula_version
             WHERE formula.tenant_id = NEW.tenant_id
               AND formula.preparation_id = NEW.preparation_id
               AND (authoritative.formula_id IS NULL
                    OR authoritative.lifecycle_state <> 'PUBLISHED'
                    OR authoritative.recorded_at > NEW.source_as_of
                    OR authoritative.legal_entity_id IS DISTINCT FROM
                        NEW.legal_entity_id
                    OR authoritative.payroll_group_id IS DISTINCT FROM
                        NEW.payroll_group_id
                    OR authoritative.ast_digest IS DISTINCT FROM
                        formula.ast_digest
                    OR EXISTS (
                        SELECT 1
                          FROM public.pay_formula_versions later
                         WHERE later.tenant_id = formula.tenant_id
                           AND later.formula_id = formula.formula_id
                           AND later.legal_entity_id = NEW.legal_entity_id
                           AND later.payroll_group_id = NEW.payroll_group_id
                           AND later.lifecycle_state = 'PUBLISHED'
                           AND later.recorded_at <= NEW.source_as_of
                           AND later.version > formula.formula_version)))
       OR formula_count IS DISTINCT FROM (
            SELECT COUNT(DISTINCT authoritative.formula_id)
              FROM public.pay_formula_versions authoritative
             WHERE authoritative.tenant_id = NEW.tenant_id
               AND authoritative.legal_entity_id = NEW.legal_entity_id
               AND authoritative.payroll_group_id = NEW.payroll_group_id
               AND authoritative.lifecycle_state = 'PUBLISHED'
               AND authoritative.recorded_at <= NEW.source_as_of)
       OR EXISTS (
            SELECT 1
              FROM public.pay_run_preparation_formula_refs formula
             WHERE formula.tenant_id = NEW.tenant_id
               AND formula.preparation_id = NEW.preparation_id
               AND NOT EXISTS (
                   SELECT 1
                     FROM jsonb_array_elements(
                         snapshot_fact.formula_bindings) binding
                    WHERE binding ->> 'formulaId' = formula.formula_id::TEXT
                      AND binding ->> 'version' = formula.formula_version::TEXT
                      AND binding ->> 'astDigest' = formula.ast_digest
                      AND binding - ARRAY[
                          'formulaId', 'version', 'astDigest']::TEXT[] =
                          '{}'::JSONB))
       OR EXISTS (
            SELECT 1
              FROM jsonb_array_elements(
                  snapshot_fact.formula_bindings) binding
             WHERE jsonb_typeof(binding) <> 'object'
                OR binding - ARRAY[
                    'formulaId', 'version', 'astDigest']::TEXT[] <> '{}'::JSONB
                OR NOT EXISTS (
                    SELECT 1
                      FROM public.pay_run_preparation_formula_refs formula
                     WHERE formula.tenant_id = NEW.tenant_id
                       AND formula.preparation_id = NEW.preparation_id
                       AND binding ->> 'formulaId' = formula.formula_id::TEXT
                       AND binding ->> 'version' = formula.formula_version::TEXT
                       AND binding ->> 'astDigest' = formula.ast_digest))
       OR prepared_line_count IS DISTINCT FROM snapshot_fact.source_line_count
       OR ref_count IS DISTINCT FROM snapshot_fact.source_line_count
       OR formula_count < 1
       OR formula_count IS DISTINCT FROM
            jsonb_array_length(snapshot_fact.formula_bindings) THEN
        RAISE EXCEPTION 'Payroll preparation READY facts are incomplete';
    END IF;
    RETURN NEW;
END
$pay_v52_preparation_state$;

CREATE FUNCTION pay_v52_guard_prepared_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $pay_v52_prepared_outbox$
DECLARE
    run_fact public.pay_payroll_runs%ROWTYPE;
    receipt_head public.pay_command_receipts%ROWTYPE;
    prepared public.pay_run_preparations%ROWTYPE;
    data_payload JSONB;
    source_count INTEGER;
    decision_count INTEGER;
BEGIN
    IF NEW.event_type <> 'PayrollRunPrepared'
       OR NEW.schema_version <> 1 OR NEW.attempt_no IS NOT NULL
       OR NEW.run_revision <> 2 OR NEW.aggregate_sequence <> 2
       OR jsonb_typeof(NEW.event_payload) <> 'object'
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
       OR NEW.event_payload ->> 'source' <> 'urn:dwp:payroll:preparation'
       OR NEW.event_payload ->> 'type' <> 'PayrollRunPrepared.v1'
       OR (NEW.event_payload ->> 'schemaVersion')::INTEGER <> 1
       OR (NEW.event_payload ->> 'tenantId')::BIGINT <> NEW.tenant_id
       OR NEW.event_payload ->> 'aggregateType' <> 'PAYROLL_RUN'
       OR NEW.event_payload ->> 'aggregateId' <> NEW.run_id::TEXT
       OR (NEW.event_payload ->> 'aggregateSequence')::BIGINT <> 2
       OR NEW.event_payload ->> 'correlationId' <> NEW.correlation_id::TEXT
       OR (NEW.event_payload ->> 'time')::TIMESTAMPTZ IS DISTINCT FROM
            NEW.occurred_at
       OR jsonb_typeof(NEW.event_payload -> 'data') <> 'object' THEN
        RAISE EXCEPTION 'PayrollRunPrepared envelope is not exact';
    END IF;
    data_payload := NEW.event_payload -> 'data';
    SELECT * INTO run_fact
      FROM public.pay_payroll_runs
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
       AND run_revision = NEW.run_revision;
    SELECT * INTO receipt_head
      FROM public.pay_command_receipts
     WHERE tenant_id = NEW.tenant_id AND receipt_id = NEW.receipt_id
     ORDER BY status_sequence DESC LIMIT 1;
    SELECT * INTO prepared
      FROM public.pay_run_preparations
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id
       AND state_sequence = 2 AND preparation_state = 'READY';
    SELECT COUNT(*) INTO source_count
      FROM public.pay_run_preparation_sources
     WHERE tenant_id = NEW.tenant_id
       AND preparation_id = prepared.preparation_id;
    SELECT COUNT(*) INTO decision_count
      FROM public.pay_run_preparation_decisions
     WHERE tenant_id = NEW.tenant_id
       AND preparation_id = prepared.preparation_id;
    IF run_fact.run_id IS NULL
       OR receipt_head.receipt_id IS NULL
       OR prepared.preparation_id IS NULL
       OR run_fact.state <> 'READY' OR run_fact.latest_attempt_no <> 0
       OR run_fact.command_receipt_id IS DISTINCT FROM NEW.receipt_id
       OR receipt_head.command_type <> 'RUN_PREPARE'
       OR receipt_head.originating_action <> 'PAY_RUN_PREPARE'
       OR receipt_head.receipt_status <> 'SUCCEEDED'
       OR receipt_head.correlation_id IS DISTINCT FROM NEW.correlation_id
       OR receipt_head.aggregate_version IS DISTINCT FROM NEW.run_revision
       OR prepared.receipt_id IS DISTINCT FROM NEW.receipt_id
       OR prepared.event_id IS DISTINCT FROM NEW.event_id
       OR prepared.run_revision IS DISTINCT FROM NEW.run_revision
       OR NOT (data_payload ?& ARRAY[
            'preparationId', 'runId', 'runRevision', 'snapshotId',
            'snapshotDigest', 'populationRevision', 'targetPopulationDigest',
            'sourceCount', 'preflightDecisionCount'])
       OR data_payload - ARRAY[
            'preparationId', 'runId', 'runRevision', 'snapshotId',
            'snapshotDigest', 'populationRevision', 'targetPopulationDigest',
            'sourceCount', 'preflightDecisionCount']::TEXT[] <> '{}'::JSONB
       OR data_payload ->> 'preparationId' <> prepared.preparation_id::TEXT
       OR data_payload ->> 'runId' <> NEW.run_id::TEXT
       OR (data_payload ->> 'runRevision')::BIGINT <> NEW.run_revision
       OR data_payload ->> 'snapshotId' <> prepared.snapshot_id::TEXT
       OR data_payload ->> 'snapshotDigest' <> prepared.snapshot_digest
       OR data_payload ->> 'populationRevision' <> prepared.population_revision
       OR data_payload ->> 'targetPopulationDigest' <>
            prepared.target_population_digest
       OR (data_payload ->> 'sourceCount')::INTEGER <> source_count
       OR (data_payload ->> 'preflightDecisionCount')::INTEGER <>
            decision_count THEN
        RAISE EXCEPTION 'PayrollRunPrepared proof or payload is not exact';
    END IF;
    RETURN NEW;
END
$pay_v52_prepared_outbox$;

-- A preparation READY revision, its READY preparation head and its single
-- prepared event are one commit-time fact. This deferred constraint closes the
-- temporary insertion-order gap needed by the runtime transaction.
CREATE FUNCTION pay_v52_require_prepared_commit()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $pay_v52_prepared_commit$
DECLARE
    receipt_command TEXT;
    prepared public.pay_run_preparations%ROWTYPE;
    prepared_event public.pay_outbox_events%ROWTYPE;
BEGIN
    SELECT receipt.command_type INTO receipt_command
      FROM public.pay_command_receipts receipt
     WHERE receipt.tenant_id = NEW.tenant_id
       AND receipt.receipt_id = NEW.command_receipt_id
     ORDER BY receipt.status_sequence DESC LIMIT 1;
    IF receipt_command IS DISTINCT FROM 'RUN_PREPARE' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO prepared
      FROM public.pay_run_preparations preparation
     WHERE preparation.tenant_id = NEW.tenant_id
       AND preparation.run_id = NEW.run_id
       AND preparation.run_revision = NEW.run_revision
       AND preparation.state_sequence = 2
       AND preparation.preparation_state = 'READY';
    SELECT * INTO prepared_event
      FROM public.pay_outbox_events event
     WHERE event.tenant_id = NEW.tenant_id
       AND event.event_id = prepared.event_id;
    IF prepared.preparation_id IS NULL
       OR prepared.receipt_id IS DISTINCT FROM NEW.command_receipt_id
       OR prepared_event.event_id IS NULL
       OR prepared_event.event_type IS DISTINCT FROM 'PayrollRunPrepared'
       OR prepared_event.schema_version IS DISTINCT FROM 1
       OR prepared_event.run_id IS DISTINCT FROM NEW.run_id
       OR prepared_event.run_revision IS DISTINCT FROM NEW.run_revision
       OR prepared_event.aggregate_sequence IS DISTINCT FROM NEW.run_revision
       OR prepared_event.receipt_id IS DISTINCT FROM NEW.command_receipt_id THEN
        RAISE EXCEPTION
            'Payroll preparation READY revision is missing its sealed event';
    END IF;
    RETURN NEW;
END
$pay_v52_prepared_commit$;

-- Preserve every V50/V51 path except the specifically extended READY,
-- prepare-receipt and prepared-event records.
DROP TRIGGER pay_payroll_run_revision_guard ON pay_payroll_runs;
CREATE TRIGGER pay_payroll_run_revision_guard
    BEFORE INSERT ON pay_payroll_runs
    FOR EACH ROW
    WHEN (NEW.state <> 'READY')
    EXECUTE FUNCTION pay_v50_guard_run_revision();
CREATE TRIGGER pay_v52_payroll_ready_revision_guard
    BEFORE INSERT ON pay_payroll_runs
    FOR EACH ROW
    WHEN (NEW.state = 'READY')
    EXECUTE FUNCTION pay_v52_guard_ready_run_revision();
CREATE CONSTRAINT TRIGGER pay_v52_prepared_commit_guard
    AFTER INSERT ON pay_payroll_runs
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.state = 'READY' AND NEW.latest_attempt_no = 0)
    EXECUTE FUNCTION pay_v52_require_prepared_commit();

DROP TRIGGER pay_command_receipt_record_guard ON pay_command_receipts;
CREATE TRIGGER pay_command_receipt_record_guard
    BEFORE INSERT ON pay_command_receipts
    FOR EACH ROW
    WHEN (NEW.command_type <> 'RUN_PREPARE')
    EXECUTE FUNCTION pay_v50_guard_receipt_record();
CREATE TRIGGER pay_v52_prepare_receipt_guard
    BEFORE INSERT ON pay_command_receipts
    FOR EACH ROW
    WHEN (NEW.command_type = 'RUN_PREPARE')
    EXECUTE FUNCTION pay_v52_guard_prepare_receipt();

CREATE CONSTRAINT TRIGGER pay_v52_step_up_use_binding_guard
    AFTER INSERT ON pay_run_preparation_step_up_uses
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION pay_v52_require_step_up_use_binding();

DROP TRIGGER pay_outbox_event_guard ON pay_outbox_events;
CREATE TRIGGER pay_outbox_event_guard
    BEFORE INSERT ON pay_outbox_events
    FOR EACH ROW
    WHEN (NEW.event_type <> 'PayrollRunPrepared')
    EXECUTE FUNCTION pay_v50_guard_outbox_event();
CREATE TRIGGER pay_v52_prepared_outbox_guard
    BEFORE INSERT ON pay_outbox_events
    FOR EACH ROW
    WHEN (NEW.event_type = 'PayrollRunPrepared')
    EXECUTE FUNCTION pay_v52_guard_prepared_outbox();

CREATE TRIGGER pay_run_preparation_state_guard
    BEFORE INSERT ON pay_run_preparations
    FOR EACH ROW EXECUTE FUNCTION pay_v52_guard_preparation_state();

CREATE TRIGGER pay_run_preparation_source_insert_guard
    BEFORE INSERT ON pay_run_preparation_sources
    FOR EACH ROW EXECUTE FUNCTION pay_v52_guard_preparation_child_insert();
CREATE TRIGGER pay_run_preparation_decision_insert_guard
    BEFORE INSERT ON pay_run_preparation_decisions
    FOR EACH ROW EXECUTE FUNCTION pay_v52_guard_preparation_child_insert();
CREATE TRIGGER pay_run_preparation_line_insert_guard
    BEFORE INSERT ON pay_run_preparation_lines
    FOR EACH ROW EXECUTE FUNCTION pay_v52_guard_preparation_child_insert();
CREATE TRIGGER pay_run_preparation_formula_insert_guard
    BEFORE INSERT ON pay_run_preparation_formula_refs
    FOR EACH ROW EXECUTE FUNCTION pay_v52_guard_preparation_child_insert();

DO $pay_v52_security$
DECLARE pay_table_name TEXT;
BEGIN
    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_population_source_revisions',
        'pay_population_source_members',
        'pay_semantic_source_revisions',
        'pay_run_preparation_step_up_uses',
        'pay_run_preparations', 'pay_run_preparation_sources',
        'pay_run_preparation_decisions', 'pay_run_preparation_lines',
        'pay_run_preparation_formula_refs']
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I '
            || 'FOR EACH ROW EXECUTE FUNCTION pay_v52_reject_mutation()',
            pay_table_name || '_immutable', pay_table_name);
        EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC', pay_table_name);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', pay_table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', pay_table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I '
            || 'USING (tenant_id = NULLIF(current_setting('
            || '''dwp.payroll_tenant_id'', true), '''')::BIGINT) '
            || 'WITH CHECK (tenant_id = NULLIF(current_setting('
            || '''dwp.payroll_tenant_id'', true), '''')::BIGINT)',
            pay_table_name || '_tenant_policy', pay_table_name);
    END LOOP;
END
$pay_v52_security$;

REVOKE ALL ON FUNCTION pay_v52_reject_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v52_guard_prepare_receipt() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v52_require_step_up_use_binding() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v52_guard_preparation_child_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v52_guard_ready_run_revision() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v52_guard_preparation_state() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v52_guard_prepared_outbox() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v52_require_prepared_commit() FROM PUBLIC;

DO $pay_v52_runtime_access$
DECLARE
    runtime_role_name TEXT := '${payrollRuntimeRole}';
    projection_role_name TEXT := '${payrollProjectionPublisherRole}';
    runtime_role RECORD;
    projection_role RECORD;
    pay_table_name TEXT;
    sequence_binding RECORD;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$'
       OR projection_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid PAY-013 database role name';
    END IF;
    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb,
           rolreplication, rolbypassrls
      INTO runtime_role
      FROM pg_roles WHERE rolname = runtime_role_name;
    IF NOT FOUND OR runtime_role.rolsuper OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION 'Payroll runtime role is missing or unsafe';
    END IF;
    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb,
           rolreplication, rolbypassrls
      INTO projection_role
      FROM pg_roles WHERE rolname = projection_role_name;
    IF NOT FOUND OR projection_role.rolsuper OR projection_role.rolcreaterole
       OR projection_role.rolcreatedb OR projection_role.rolreplication
       OR projection_role.rolbypassrls
       OR projection_role_name = runtime_role_name
       OR projection_role_name = current_user
       OR projection_role_name = session_user THEN
        RAISE EXCEPTION 'Payroll projection publisher role is missing or unsafe';
    END IF;

    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_population_source_revisions',
        'pay_population_source_members',
        'pay_semantic_source_revisions']
    LOOP
        EXECUTE format('GRANT SELECT ON TABLE %I TO %I',
            pay_table_name, runtime_role_name);
        EXECUTE format('GRANT SELECT, INSERT ON TABLE %I TO %I',
            pay_table_name, projection_role_name);
        FOR sequence_binding IN
            SELECT pg_get_serial_sequence(
                       'public.' || pay_table_name, columns.column_name)
                       AS sequence_name
              FROM information_schema.columns columns
             WHERE columns.table_schema = 'public'
               AND columns.table_name = pay_table_name
               AND columns.column_default LIKE 'nextval(%'
        LOOP
            IF sequence_binding.sequence_name IS NOT NULL THEN
                EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC',
                    sequence_binding.sequence_name);
                EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
                    sequence_binding.sequence_name, projection_role_name);
            END IF;
        END LOOP;
    END LOOP;
    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_run_preparation_step_up_uses',
        'pay_run_preparations', 'pay_run_preparation_sources',
        'pay_run_preparation_decisions', 'pay_run_preparation_lines',
        'pay_run_preparation_formula_refs']
    LOOP
        EXECUTE format('GRANT SELECT, INSERT ON TABLE %I TO %I',
            pay_table_name, runtime_role_name);
        FOR sequence_binding IN
            SELECT pg_get_serial_sequence(
                       'public.' || pay_table_name, columns.column_name)
                       AS sequence_name
              FROM information_schema.columns columns
             WHERE columns.table_schema = 'public'
               AND columns.table_name = pay_table_name
               AND columns.column_default LIKE 'nextval(%'
        LOOP
            IF sequence_binding.sequence_name IS NOT NULL THEN
                EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC',
                    sequence_binding.sequence_name);
                EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
                    sequence_binding.sequence_name, runtime_role_name);
            END IF;
        END LOOP;
    END LOOP;
    EXECUTE format(
        'REVOKE ALL ON FUNCTION pay_v52_reject_mutation(), '
        || 'pay_v52_guard_prepare_receipt(), '
        || 'pay_v52_require_step_up_use_binding(), '
        || 'pay_v52_guard_preparation_child_insert(), '
        || 'pay_v52_guard_ready_run_revision(), '
        || 'pay_v52_guard_preparation_state(), '
        || 'pay_v52_guard_prepared_outbox(), '
        || 'pay_v52_require_prepared_commit() FROM %I, %I',
        runtime_role_name, projection_role_name);
END
$pay_v52_runtime_access$;
