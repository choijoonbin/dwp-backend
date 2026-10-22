-- BASE-TFR-PAY-004. Provider-neutral retirement cases and immutable result
-- lineage. These five tables are the exhaustive V55 lease. V50 calculation,
-- V53 signed country-pack and V54 audit stores are reused and not recreated.

CREATE TABLE pay_statutory_cases (
    statutory_case_internal_id BIGSERIAL NOT NULL,
    tenant_id BIGINT NOT NULL,
    case_id UUID NOT NULL,
    case_revision BIGINT NOT NULL,
    worker_id UUID NOT NULL,
    employment_owner VARCHAR(80) NOT NULL,
    employment_schema_id VARCHAR(128) NOT NULL,
    employment_public_id UUID NOT NULL,
    employment_revision BIGINT NOT NULL,
    employment_digest CHAR(64) NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    effective_on DATE NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL,
    country_pack_state VARCHAR(24) NOT NULL,
    maker_subject_public_id UUID,
    transition_subject_public_id UUID NOT NULL,
    predecessor_digest CHAR(64),
    case_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_statutory_case_pk PRIMARY KEY (
        tenant_id, statutory_case_internal_id),
    CONSTRAINT pay_statutory_case_revision_unique UNIQUE (
        tenant_id, case_id, case_revision),
    CONSTRAINT pay_statutory_case_positive CHECK (
        tenant_id > 0 AND case_revision > 0 AND employment_revision > 0),
    CONSTRAINT pay_statutory_case_identity_separation CHECK (
        case_id <> worker_id AND case_id <> employment_public_id),
    CONSTRAINT pay_statutory_case_state_closed CHECK (
        lifecycle_state IN (
            'DRAFT', 'VALIDATED', 'BLOCKED_PACK_REQUIRED', 'CALCULATED',
            'APPROVED', 'FINALIZED', 'CORRECTED', 'CANCELLED')),
    CONSTRAINT pay_statutory_case_pack_state_closed CHECK (
        country_pack_state IN (
            'NOT_REQUIRED', 'NOT_INSTALLED', 'DISABLED', 'ACTIVE')),
    CONSTRAINT pay_statutory_case_pack_state_consistent CHECK (
        (lifecycle_state = 'BLOCKED_PACK_REQUIRED'
            AND country_pack_state IN ('NOT_INSTALLED', 'DISABLED'))
        OR (lifecycle_state IN (
                'VALIDATED', 'CALCULATED', 'APPROVED',
                'FINALIZED', 'CORRECTED')
            AND country_pack_state IN ('NOT_REQUIRED', 'ACTIVE'))
        OR lifecycle_state IN ('DRAFT', 'CANCELLED')),
    CONSTRAINT pay_statutory_case_digest_shape CHECK (
        employment_digest ~ '^[0-9a-f]{64}$'
        AND case_digest ~ '^[0-9a-f]{64}$'
        AND (predecessor_digest IS NULL
            OR predecessor_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT pay_statutory_case_revision_shape CHECK (
        (case_revision = 1 AND lifecycle_state = 'DRAFT'
            AND predecessor_digest IS NULL)
        OR (case_revision > 1 AND predecessor_digest IS NOT NULL)),
    CONSTRAINT pay_statutory_case_owner_shape CHECK (
        employment_owner ~ '^[A-Za-z][A-Za-z0-9._:-]{1,79}$'
        AND employment_schema_id ~ '^[A-Za-z][A-Za-z0-9._:-]{1,127}$')
);

CREATE INDEX pay_statutory_case_state_idx
    ON pay_statutory_cases (
        tenant_id, lifecycle_state, effective_on, case_id, case_revision DESC);
CREATE INDEX pay_statutory_case_identity_idx
    ON pay_statutory_cases (tenant_id, case_id, case_revision DESC);
CREATE INDEX pay_statutory_case_scope_idx
    ON pay_statutory_cases (
        tenant_id, legal_entity_id, payroll_group_id,
        effective_on, case_id, case_revision DESC);

CREATE TABLE pay_case_basis_refs (
    case_basis_ref_internal_id BIGSERIAL NOT NULL,
    tenant_id BIGINT NOT NULL,
    basis_ref_id UUID NOT NULL,
    case_id UUID NOT NULL,
    case_revision BIGINT NOT NULL,
    basis_role VARCHAR(48) NOT NULL,
    owner_service_key VARCHAR(80) NOT NULL,
    schema_id VARCHAR(128) NOT NULL,
    resource_public_id UUID NOT NULL,
    resource_revision BIGINT NOT NULL,
    resource_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_case_basis_ref_pk PRIMARY KEY (
        tenant_id, case_basis_ref_internal_id),
    CONSTRAINT pay_case_basis_ref_public_unique UNIQUE (
        tenant_id, basis_ref_id),
    CONSTRAINT pay_case_basis_ref_binding_unique UNIQUE (
        tenant_id, case_id, case_revision, basis_role,
        owner_service_key, schema_id, resource_public_id, resource_revision),
    CONSTRAINT pay_case_basis_ref_case_fk FOREIGN KEY (
        tenant_id, case_id, case_revision)
        REFERENCES pay_statutory_cases (
            tenant_id, case_id, case_revision),
    CONSTRAINT pay_case_basis_ref_positive CHECK (
        tenant_id > 0 AND case_revision > 0 AND resource_revision > 0),
    CONSTRAINT pay_case_basis_ref_shape CHECK (
        basis_role ~ '^[A-Z][A-Z0-9_]{1,47}$'
        AND owner_service_key ~ '^[A-Za-z][A-Za-z0-9._:-]{1,79}$'
        AND schema_id ~ '^[A-Za-z][A-Za-z0-9._:-]{1,127}$'
        AND resource_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX pay_case_basis_ref_case_idx
    ON pay_case_basis_refs (
        tenant_id, case_id, case_revision, basis_role, basis_ref_id);

CREATE TABLE pay_case_evidence (
    case_evidence_internal_id BIGSERIAL NOT NULL,
    tenant_id BIGINT NOT NULL,
    evidence_id UUID NOT NULL,
    case_id UUID NOT NULL,
    case_revision BIGINT NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    evidence_owner VARCHAR(80) NOT NULL,
    evidence_schema_id VARCHAR(128) NOT NULL,
    evidence_public_id UUID NOT NULL,
    evidence_revision BIGINT NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_case_evidence_pk PRIMARY KEY (
        tenant_id, case_evidence_internal_id),
    CONSTRAINT pay_case_evidence_public_unique UNIQUE (
        tenant_id, evidence_id),
    CONSTRAINT pay_case_evidence_binding_unique UNIQUE (
        tenant_id, case_id, case_revision, evidence_type,
        evidence_owner, evidence_public_id, evidence_revision),
    CONSTRAINT pay_case_evidence_case_fk FOREIGN KEY (
        tenant_id, case_id, case_revision)
        REFERENCES pay_statutory_cases (
            tenant_id, case_id, case_revision),
    CONSTRAINT pay_case_evidence_positive CHECK (
        tenant_id > 0 AND case_revision > 0 AND evidence_revision > 0),
    CONSTRAINT pay_case_evidence_shape CHECK (
        evidence_type ~ '^[A-Z][A-Z0-9_]{1,63}$'
        AND evidence_owner ~ '^[A-Za-z][A-Za-z0-9._:-]{1,79}$'
        AND evidence_schema_id ~ '^[A-Za-z][A-Za-z0-9._:-]{1,127}$'
        AND evidence_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX pay_case_evidence_case_idx
    ON pay_case_evidence (
        tenant_id, case_id, case_revision, evidence_type, evidence_id);

CREATE TABLE pay_case_results (
    case_result_internal_id BIGSERIAL NOT NULL,
    tenant_id BIGINT NOT NULL,
    result_id UUID NOT NULL,
    case_id UUID NOT NULL,
    case_revision BIGINT NOT NULL,
    worker_id UUID NOT NULL,
    run_id UUID NOT NULL,
    run_revision BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL,
    worker_result_id UUID NOT NULL,
    country_pack_state VARCHAR(24) NOT NULL,
    pack_id UUID,
    pack_snapshot_revision BIGINT,
    activation_revision BIGINT,
    non_pack_policy_public_id UUID,
    non_pack_policy_revision BIGINT,
    non_pack_policy_digest CHAR(64),
    policy_digest CHAR(64) NOT NULL,
    policy_key VARCHAR(128) NOT NULL,
    policy_revision BIGINT NOT NULL,
    policy_effective_from DATE NOT NULL,
    policy_effective_to DATE,
    retirement_amount_text VARCHAR(24) NOT NULL,
    retirement_amount NUMERIC(19,4) GENERATED ALWAYS AS
        (retirement_amount_text::NUMERIC(19,4)) STORED,
    currency CHAR(3) NOT NULL,
    rounding_mode VARCHAR(16) NOT NULL,
    rounding_stage VARCHAR(64) NOT NULL,
    worker_result_digest CHAR(64) NOT NULL,
    calculation_result_digest CHAR(64) NOT NULL,
    case_inputs_digest CHAR(64) NOT NULL,
    result_digest CHAR(64) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_case_result_pk PRIMARY KEY (
        tenant_id, case_result_internal_id),
    CONSTRAINT pay_case_result_public_unique UNIQUE (
        tenant_id, result_id),
    CONSTRAINT pay_case_result_case_unique UNIQUE (
        tenant_id, case_id, case_revision),
    CONSTRAINT pay_case_result_case_fk FOREIGN KEY (
        tenant_id, case_id, case_revision)
        REFERENCES pay_statutory_cases (
            tenant_id, case_id, case_revision),
    CONSTRAINT pay_case_result_worker_fk FOREIGN KEY (
        tenant_id, run_id, attempt_no, worker_result_id)
        REFERENCES pay_worker_results (
            tenant_id, run_id, attempt_no, worker_result_id),
    CONSTRAINT pay_case_result_run_fk FOREIGN KEY (
        tenant_id, run_id, run_revision)
        REFERENCES pay_payroll_runs (
            tenant_id, run_id, run_revision),
    CONSTRAINT pay_case_result_pack_snapshot_fk FOREIGN KEY (
        tenant_id, pack_id, pack_snapshot_revision)
        REFERENCES pay_country_pack_version_snapshots (
            tenant_id, pack_id, snapshot_revision),
    CONSTRAINT pay_case_result_pack_activation_fk FOREIGN KEY (
        tenant_id, pack_id, activation_revision)
        REFERENCES pay_country_pack_activation_versions (
            tenant_id, pack_id, activation_revision),
    CONSTRAINT pay_case_result_non_pack_policy_fk FOREIGN KEY (
        tenant_id, non_pack_policy_public_id, non_pack_policy_revision)
        REFERENCES pay_foundation_versions (
            tenant_id, configuration_id, version),
    CONSTRAINT pay_case_result_positive CHECK (
        tenant_id > 0 AND case_revision > 0 AND run_revision > 0
        AND attempt_no > 0
        AND policy_revision > 0 AND retirement_amount >= 0),
    CONSTRAINT pay_case_result_pack_state_closed CHECK (
        country_pack_state IN (
            'NOT_REQUIRED', 'NOT_INSTALLED', 'DISABLED', 'ACTIVE')),
    CONSTRAINT pay_case_result_policy_xor CHECK (
        (country_pack_state = 'ACTIVE'
            AND pack_id IS NOT NULL
            AND pack_snapshot_revision IS NOT NULL
            AND pack_snapshot_revision >= 0
            AND activation_revision IS NOT NULL
            AND activation_revision > 0
            AND non_pack_policy_public_id IS NULL
            AND non_pack_policy_revision IS NULL
            AND non_pack_policy_digest IS NULL)
        OR (country_pack_state = 'NOT_REQUIRED'
            AND pack_id IS NULL
            AND pack_snapshot_revision IS NULL
            AND activation_revision IS NULL
            AND non_pack_policy_public_id IS NOT NULL
            AND non_pack_policy_revision IS NOT NULL
            AND non_pack_policy_revision > 0
            AND non_pack_policy_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT pay_case_result_money_shape CHECK (
        retirement_amount_text ~ '^(0|[1-9][0-9]{0,14})\.[0-9]{4}$'
        AND currency ~ '^[A-Z]{3}$'
        AND rounding_mode IN ('HALF_UP', 'HALF_EVEN', 'DOWN', 'UP')
        AND rounding_stage = 'RETIREMENT_FINAL'),
    CONSTRAINT pay_case_result_policy_shape CHECK (
        policy_key ~ '^[a-z][a-z0-9._-]{1,127}$'
        AND (policy_effective_to IS NULL
            OR policy_effective_to >= policy_effective_from)),
    CONSTRAINT pay_case_result_digest_shape CHECK (
        worker_result_digest ~ '^[0-9a-f]{64}$'
        AND calculation_result_digest ~ '^[0-9a-f]{64}$'
        AND policy_digest ~ '^[0-9a-f]{64}$'
        AND case_inputs_digest ~ '^[0-9a-f]{64}$'
        AND result_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_case_result_time_shape CHECK (
        recorded_at >= calculated_at)
);

CREATE INDEX pay_case_result_case_idx
    ON pay_case_results (
        tenant_id, case_id, case_revision DESC, result_id);
CREATE INDEX pay_case_result_amount_idx
    ON pay_case_results (
        tenant_id, retirement_amount, case_id, case_revision DESC);
CREATE INDEX pay_case_result_policy_idx
    ON pay_case_results (
        tenant_id, policy_key, policy_revision, case_id, case_revision DESC);

CREATE TABLE pay_case_corrections (
    case_correction_internal_id BIGSERIAL NOT NULL,
    tenant_id BIGINT NOT NULL,
    correction_id UUID NOT NULL,
    case_id UUID NOT NULL,
    finalized_case_revision BIGINT NOT NULL,
    corrected_case_revision BIGINT NOT NULL,
    predecessor_result_id UUID NOT NULL,
    correction_reason_code VARCHAR(64) NOT NULL,
    correction_evidence_digest CHAR(64) NOT NULL,
    correction_maker_subject_public_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pay_case_correction_pk PRIMARY KEY (
        tenant_id, case_correction_internal_id),
    CONSTRAINT pay_case_correction_public_unique UNIQUE (
        tenant_id, correction_id),
    CONSTRAINT pay_case_correction_revision_unique UNIQUE (
        tenant_id, case_id, corrected_case_revision),
    CONSTRAINT pay_case_correction_finalized_fk FOREIGN KEY (
        tenant_id, case_id, finalized_case_revision)
        REFERENCES pay_statutory_cases (
            tenant_id, case_id, case_revision),
    CONSTRAINT pay_case_correction_corrected_fk FOREIGN KEY (
        tenant_id, case_id, corrected_case_revision)
        REFERENCES pay_statutory_cases (
            tenant_id, case_id, case_revision),
    CONSTRAINT pay_case_correction_result_fk FOREIGN KEY (
        tenant_id, predecessor_result_id)
        REFERENCES pay_case_results (tenant_id, result_id),
    CONSTRAINT pay_case_correction_positive CHECK (
        tenant_id > 0 AND finalized_case_revision > 0
        AND corrected_case_revision = finalized_case_revision + 1),
    CONSTRAINT pay_case_correction_shape CHECK (
        correction_reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'
        AND correction_evidence_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX pay_case_correction_case_idx
    ON pay_case_corrections (
        tenant_id, case_id, corrected_case_revision DESC, correction_id);

CREATE FUNCTION pay_v55_reject_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v55_immutable$
BEGIN
    RAISE EXCEPTION 'PAY-004 V55 facts are append-only';
END
$pay_v55_immutable$;

CREATE FUNCTION pay_v55_canonical_digest(digest_values TEXT[])
RETURNS CHAR(64) LANGUAGE plpgsql IMMUTABLE AS $pay_v55_digest$
DECLARE
    value TEXT;
    framed TEXT := '';
BEGIN
    FOREACH value IN ARRAY digest_values LOOP
        IF value IS NULL THEN
            framed := framed || '-1:';
        ELSE
            framed := framed
                || octet_length(convert_to(value, 'UTF8'))::TEXT
                || ':' || value;
        END IF;
    END LOOP;
    RETURN encode(sha256(convert_to(framed, 'UTF8')), 'hex');
END
$pay_v55_digest$;

CREATE FUNCTION pay_v55_pack_policy_key(raw_pack_key TEXT)
RETURNS VARCHAR(128) LANGUAGE plpgsql IMMUTABLE STRICT
AS $pay_v55_pack_policy_key$
DECLARE
    normalized TEXT := regexp_replace(translate(
        raw_pack_key, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
        'abcdefghijklmnopqrstuvwxyz'), '[^a-z0-9._-]', '-', 'g');
BEGIN
    IF normalized ~ '^[a-z][a-z0-9._-]{1,127}$' THEN
        RETURN normalized;
    END IF;
    RETURN 'statutory.' || substring(encode(sha256(convert_to(
        to_json(raw_pack_key)::TEXT, 'UTF8')), 'hex') FROM 1 FOR 16);
END
$pay_v55_pack_policy_key$;

CREATE FUNCTION pay_v55_case_inputs_digest(
    target_tenant BIGINT,
    target_case UUID,
    target_revision BIGINT)
RETURNS CHAR(64) LANGUAGE plpgsql STABLE AS $pay_v55_inputs$
DECLARE
    facts TEXT[] := ARRAY['Pay004CaseInputs.v1'];
    basis_count BIGINT;
    evidence_count BIGINT;
    fact RECORD;
BEGIN
    SELECT COUNT(*) INTO basis_count
      FROM pay_case_basis_refs basis
     WHERE basis.tenant_id = target_tenant
       AND basis.case_id = target_case
       AND basis.case_revision = target_revision;
    facts := facts || basis_count::TEXT;
    FOR fact IN
        SELECT * FROM pay_case_basis_refs basis
         WHERE basis.tenant_id = target_tenant
           AND basis.case_id = target_case
           AND basis.case_revision = target_revision
         ORDER BY basis.basis_role, basis.owner_service_key, basis.schema_id,
                  basis.resource_public_id::TEXT, basis.resource_revision,
                  basis.basis_ref_id::TEXT
    LOOP
        facts := facts || pay_v55_canonical_digest(ARRAY[
            'Pay004BasisFact.v1', fact.basis_ref_id::TEXT, fact.basis_role,
            fact.owner_service_key, fact.schema_id,
            fact.resource_public_id::TEXT, fact.resource_revision::TEXT,
            fact.resource_digest, to_char(
                fact.recorded_at AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')]);
    END LOOP;

    SELECT COUNT(*) INTO evidence_count
      FROM pay_case_evidence evidence
     WHERE evidence.tenant_id = target_tenant
       AND evidence.case_id = target_case
       AND evidence.case_revision = target_revision;
    facts := facts || evidence_count::TEXT;
    FOR fact IN
        SELECT * FROM pay_case_evidence evidence
         WHERE evidence.tenant_id = target_tenant
           AND evidence.case_id = target_case
           AND evidence.case_revision = target_revision
         ORDER BY evidence.evidence_type, evidence.evidence_owner,
                  evidence.evidence_schema_id,
                  evidence.evidence_public_id::TEXT,
                  evidence.evidence_revision, evidence.evidence_id::TEXT
    LOOP
        facts := facts || pay_v55_canonical_digest(ARRAY[
            'Pay004EvidenceFact.v1', fact.evidence_id::TEXT,
            fact.evidence_type, fact.evidence_owner,
            fact.evidence_schema_id, fact.evidence_public_id::TEXT,
            fact.evidence_revision::TEXT, fact.evidence_digest, to_char(
                fact.recorded_at AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')]);
    END LOOP;
    RETURN pay_v55_canonical_digest(facts);
END
$pay_v55_inputs$;

CREATE FUNCTION pay_v55_guard_case_revision()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v55_case$
DECLARE
    prior pay_statutory_cases%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.tenant_id::TEXT || ':' || NEW.case_id::TEXT, 0));
    IF NEW.case_revision = 1 THEN
        IF NEW.lifecycle_state <> 'DRAFT'
           OR NEW.predecessor_digest IS NOT NULL THEN
            RAISE EXCEPTION 'First retirement case revision must be DRAFT';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO prior
      FROM pay_statutory_cases history
     WHERE history.tenant_id = NEW.tenant_id
       AND history.case_id = NEW.case_id
       AND history.case_revision = NEW.case_revision - 1
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Retirement case revisions must be contiguous';
    END IF;
    IF NEW.worker_id IS DISTINCT FROM prior.worker_id
       OR NEW.employment_owner IS DISTINCT FROM prior.employment_owner
       OR NEW.employment_schema_id IS DISTINCT FROM prior.employment_schema_id
       OR NEW.employment_public_id IS DISTINCT FROM prior.employment_public_id
       OR NEW.employment_revision IS DISTINCT FROM prior.employment_revision
       OR NEW.employment_digest IS DISTINCT FROM prior.employment_digest
       OR NEW.legal_entity_id IS DISTINCT FROM prior.legal_entity_id
       OR NEW.payroll_group_id IS DISTINCT FROM prior.payroll_group_id
       OR NEW.effective_on IS DISTINCT FROM prior.effective_on THEN
        RAISE EXCEPTION 'Retirement case identity and scope are immutable';
    END IF;
    IF NEW.predecessor_digest IS DISTINCT FROM prior.case_digest THEN
        RAISE EXCEPTION 'Retirement case predecessor digest differs';
    END IF;
    IF NEW.recorded_at <= prior.recorded_at THEN
        RAISE EXCEPTION 'Retirement case revision time must increase';
    END IF;
    IF (prior.lifecycle_state <> 'DRAFT'
            OR NEW.lifecycle_state = 'CANCELLED')
       AND NEW.country_pack_state IS DISTINCT FROM prior.country_pack_state THEN
        RAISE EXCEPTION 'Resolved retirement pack state is immutable';
    END IF;
    IF NOT (
        (prior.lifecycle_state = 'DRAFT'
            AND NEW.lifecycle_state IN (
                'VALIDATED', 'BLOCKED_PACK_REQUIRED', 'CANCELLED'))
        OR (prior.lifecycle_state = 'VALIDATED'
            AND NEW.lifecycle_state IN ('CALCULATED', 'CANCELLED'))
        OR (prior.lifecycle_state = 'BLOCKED_PACK_REQUIRED'
            AND NEW.lifecycle_state = 'CANCELLED')
        OR (prior.lifecycle_state = 'CALCULATED'
            AND NEW.lifecycle_state = 'APPROVED')
        OR (prior.lifecycle_state = 'APPROVED'
            AND NEW.lifecycle_state = 'FINALIZED')
        OR (prior.lifecycle_state = 'FINALIZED'
            AND NEW.lifecycle_state = 'CORRECTED')) THEN
        RAISE EXCEPTION 'Retirement case transition is not allowed';
    END IF;
    IF NEW.lifecycle_state IN ('APPROVED', 'FINALIZED', 'CORRECTED')
       AND (prior.maker_subject_public_id IS NULL
            OR NEW.maker_subject_public_id IS DISTINCT FROM
                prior.maker_subject_public_id) THEN
        RAISE EXCEPTION 'Retirement maker evidence is immutable';
    END IF;
    IF NEW.lifecycle_state = 'CALCULATED'
       AND NEW.maker_subject_public_id IS DISTINCT FROM
            NEW.transition_subject_public_id THEN
        RAISE EXCEPTION 'Calculation maker evidence differs';
    END IF;
    IF NEW.lifecycle_state IN ('APPROVED', 'FINALIZED', 'CORRECTED')
       AND NEW.transition_subject_public_id IS NOT DISTINCT FROM
            prior.maker_subject_public_id THEN
        RAISE EXCEPTION 'Retirement maker-checker separation failed';
    END IF;
    RETURN NEW;
END
$pay_v55_case$;

CREATE FUNCTION pay_v55_guard_case_attachment()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v55_attachment$
DECLARE
    case_row pay_statutory_cases%ROWTYPE;
    head_row pay_statutory_cases%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.tenant_id::TEXT || ':' || NEW.case_id::TEXT, 0));
    SELECT * INTO case_row
      FROM pay_statutory_cases cases
     WHERE cases.tenant_id = NEW.tenant_id
       AND cases.case_id = NEW.case_id
       AND cases.case_revision = NEW.case_revision;
    SELECT * INTO head_row
      FROM pay_statutory_cases cases
     WHERE cases.tenant_id = NEW.tenant_id
       AND cases.case_id = NEW.case_id
     ORDER BY cases.case_revision DESC
     LIMIT 1;
    IF case_row.case_id IS NULL OR head_row.case_id IS NULL THEN
        RAISE EXCEPTION 'Retirement case revision is missing';
    END IF;
    IF head_row.case_revision = NEW.case_revision
       AND head_row.lifecycle_state = 'CALCULATED'
       AND NEW.recorded_at IS DISTINCT FROM case_row.recorded_at THEN
        RAISE EXCEPTION 'Calculated retirement inputs must share the calculation instant';
    END IF;
    IF head_row.case_revision <> NEW.case_revision
       OR head_row.lifecycle_state NOT IN (
            'DRAFT', 'VALIDATED', 'BLOCKED_PACK_REQUIRED', 'CALCULATED')
       OR EXISTS (
            SELECT 1 FROM pay_case_results result
             WHERE result.tenant_id = NEW.tenant_id
               AND result.case_id = NEW.case_id
               AND result.case_revision = NEW.case_revision)
       OR NEW.recorded_at < case_row.recorded_at THEN
        RAISE EXCEPTION 'Retirement evidence requires the current pre-result case head';
    END IF;
    RETURN NEW;
END
$pay_v55_attachment$;

CREATE FUNCTION pay_v55_guard_case_result()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v55_result$
DECLARE
    case_row pay_statutory_cases%ROWTYPE;
    worker_row pay_worker_results%ROWTYPE;
    run_row pay_payroll_runs%ROWTYPE;
    head_row pay_statutory_cases%ROWTYPE;
    pack_row pay_country_pack_version_snapshots%ROWTYPE;
    activation_row pay_country_pack_activation_versions%ROWTYPE;
    foundation_version pay_foundation_versions%ROWTYPE;
    expected_policy_digest CHAR(64);
    expected_inputs_digest CHAR(64);
    expected_result_digest CHAR(64);
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.tenant_id::TEXT || ':' || NEW.case_id::TEXT, 0));
    SELECT * INTO case_row
      FROM pay_statutory_cases cases
     WHERE cases.tenant_id = NEW.tenant_id
       AND cases.case_id = NEW.case_id
       AND cases.case_revision = NEW.case_revision;
    SELECT * INTO head_row
      FROM pay_statutory_cases cases
     WHERE cases.tenant_id = NEW.tenant_id
       AND cases.case_id = NEW.case_id
     ORDER BY cases.case_revision DESC
     LIMIT 1;
    SELECT * INTO worker_row
      FROM pay_worker_results worker
     WHERE worker.tenant_id = NEW.tenant_id
       AND worker.run_id = NEW.run_id
       AND worker.attempt_no = NEW.attempt_no
       AND worker.worker_result_id = NEW.worker_result_id;
    SELECT * INTO run_row
      FROM pay_payroll_runs run
     WHERE run.tenant_id = NEW.tenant_id
       AND run.run_id = NEW.run_id
       AND run.run_revision = NEW.run_revision;
    IF case_row.lifecycle_state IS DISTINCT FROM 'CALCULATED'
       OR head_row.case_revision IS DISTINCT FROM NEW.case_revision
       OR head_row.lifecycle_state IS DISTINCT FROM 'CALCULATED'
       OR case_row.worker_id IS DISTINCT FROM NEW.worker_id
       OR case_row.country_pack_state IS DISTINCT FROM NEW.country_pack_state
       OR NEW.case_id = NEW.run_id
       OR NEW.case_id = NEW.worker_result_id
       OR NEW.case_id = NEW.result_id
       OR NEW.case_id IS NOT DISTINCT FROM NEW.pack_id
       OR NEW.case_id IS NOT DISTINCT FROM NEW.non_pack_policy_public_id
       OR worker_row.worker_id IS DISTINCT FROM NEW.worker_id
       OR worker_row.currency IS DISTINCT FROM NEW.currency
       OR run_row.legal_entity_id IS DISTINCT FROM case_row.legal_entity_id
       OR run_row.payroll_group_id IS DISTINCT FROM case_row.payroll_group_id
       OR run_row.currency IS DISTINCT FROM NEW.currency
       OR run_row.latest_attempt_no IS DISTINCT FROM NEW.attempt_no
       OR run_row.state NOT IN ('VALIDATED', 'APPROVED', 'FINALIZED')
       OR run_row.maker_subject_public_id IS DISTINCT FROM
            case_row.maker_subject_public_id
       OR case_row.recorded_at IS DISTINCT FROM NEW.calculated_at
       OR worker_row.worker_result_digest IS DISTINCT FROM
            NEW.worker_result_digest
       OR worker_row.calculation_result_digest IS DISTINCT FROM
            NEW.calculation_result_digest
       OR run_row.result_digest IS DISTINCT FROM
            NEW.calculation_result_digest
       OR EXISTS (
            SELECT 1
              FROM pay_payroll_runs later_run
             WHERE later_run.tenant_id = NEW.tenant_id
               AND later_run.run_id = NEW.run_id
               AND later_run.run_revision > NEW.run_revision
               AND later_run.recorded_at <= NEW.calculated_at)
       OR run_row.recorded_at > NEW.calculated_at
       OR worker_row.recorded_at > NEW.calculated_at THEN
        RAISE EXCEPTION 'Exact case and V50 worker-result lineage differs';
    END IF;
    IF EXISTS (
            SELECT 1 FROM pay_case_basis_refs basis
             WHERE basis.tenant_id = NEW.tenant_id
               AND basis.case_id = NEW.case_id
               AND basis.case_revision = NEW.case_revision
               AND basis.recorded_at > NEW.calculated_at)
       OR EXISTS (
            SELECT 1 FROM pay_case_evidence evidence
             WHERE evidence.tenant_id = NEW.tenant_id
               AND evidence.case_id = NEW.case_id
               AND evidence.case_revision = NEW.case_revision
               AND evidence.recorded_at > NEW.calculated_at) THEN
        RAISE EXCEPTION 'Retirement result cannot predate its inputs';
    END IF;
    IF NEW.policy_effective_from > case_row.effective_on
       OR (NEW.policy_effective_to IS NOT NULL
            AND NEW.policy_effective_to < case_row.effective_on) THEN
        RAISE EXCEPTION 'Policy is not effective for the retirement case date';
    END IF;

    IF NEW.country_pack_state = 'ACTIVE' THEN
        SELECT * INTO pack_row
          FROM pay_country_pack_version_snapshots snapshot
         WHERE snapshot.tenant_id = NEW.tenant_id
           AND snapshot.pack_id = NEW.pack_id
           AND snapshot.snapshot_revision = NEW.pack_snapshot_revision;
        SELECT * INTO activation_row
          FROM pay_country_pack_activation_versions activation
         WHERE activation.tenant_id = NEW.tenant_id
           AND activation.pack_id = NEW.pack_id
           AND activation.activation_revision = NEW.activation_revision;
        IF pack_row.pack_id IS NULL OR activation_row.pack_id IS NULL
           OR activation_row.lifecycle_state <> 'ACTIVE'
           OR pack_row.legal_entity_id IS DISTINCT FROM case_row.legal_entity_id
           OR pack_row.payroll_group_id IS DISTINCT FROM case_row.payroll_group_id
           OR pay_v55_pack_policy_key(pack_row.pack_key)
                IS DISTINCT FROM NEW.policy_key
           OR NEW.policy_revision IS DISTINCT FROM NEW.activation_revision
           OR NEW.policy_effective_from IS DISTINCT FROM
                activation_row.effective_from
           OR NEW.policy_effective_to IS DISTINCT FROM
                activation_row.effective_to
           OR pack_row.recorded_at > NEW.calculated_at
           OR activation_row.occurred_at > NEW.calculated_at
           OR EXISTS (
                SELECT 1
                  FROM pay_country_pack_activation_versions later_activation
                 WHERE later_activation.tenant_id = NEW.tenant_id
                   AND later_activation.pack_id = NEW.pack_id
                   AND later_activation.activation_revision >
                        NEW.activation_revision
                   AND later_activation.occurred_at <= NEW.calculated_at)
           OR activation_row.effective_from > case_row.effective_on
           OR (activation_row.effective_to IS NOT NULL
                AND activation_row.effective_to <= case_row.effective_on)
           OR pack_row.effective_from > case_row.effective_on
           OR (pack_row.effective_to IS NOT NULL
                AND pack_row.effective_to <= case_row.effective_on) THEN
            RAISE EXCEPTION 'Exact ACTIVE signed-pack lineage differs';
        END IF;
        expected_policy_digest := pack_row.payload_digest;
    ELSIF NEW.country_pack_state = 'NOT_REQUIRED' THEN
        SELECT * INTO foundation_version
          FROM pay_foundation_versions version
         WHERE version.tenant_id = NEW.tenant_id
           AND version.configuration_id = NEW.non_pack_policy_public_id
           AND version.version = NEW.non_pack_policy_revision;
        IF NEW.non_pack_policy_public_id IS NULL
           OR NEW.non_pack_policy_revision IS NULL
           OR NEW.non_pack_policy_digest IS NULL
           OR NEW.policy_revision <> NEW.non_pack_policy_revision
           OR foundation_version.configuration_id IS NULL
           OR foundation_version.lifecycle_state <> 'PUBLISHED'
           OR foundation_version.definition_digest IS DISTINCT FROM
                NEW.non_pack_policy_digest
           OR foundation_version.definition #>> '{legalEntity,id}'
                IS DISTINCT FROM case_row.legal_entity_id::TEXT
           OR foundation_version.definition #>> '{payrollGroup,id}'
                IS DISTINCT FROM case_row.payroll_group_id::TEXT
           OR foundation_version.definition #>>
                '{effectivePeriod,startsOn}'
                IS DISTINCT FROM NEW.policy_effective_from::TEXT
           OR foundation_version.definition #>>
                '{effectivePeriod,endsOn}'
                IS DISTINCT FROM NEW.policy_effective_to::TEXT
           OR foundation_version.definition #>>
                ARRAY['roundingPolicies', NEW.currency, 'mode']
                IS DISTINCT FROM NEW.rounding_mode
           OR foundation_version.recorded_at > NEW.calculated_at THEN
            RAISE EXCEPTION 'Explicit non-pack policy lineage is required';
        END IF;
        expected_policy_digest := foundation_version.definition_digest;
    ELSE
        RAISE EXCEPTION 'Blocked country-pack state cannot produce a result';
    END IF;

    IF NEW.policy_digest IS NOT NULL
       AND NEW.policy_digest IS DISTINCT FROM expected_policy_digest THEN
        RAISE EXCEPTION 'Retirement result policy digest differs';
    END IF;
    NEW.policy_digest := expected_policy_digest;
    expected_inputs_digest := pay_v55_case_inputs_digest(
        NEW.tenant_id, NEW.case_id, NEW.case_revision);
    IF NEW.case_inputs_digest IS NOT NULL
       AND NEW.case_inputs_digest IS DISTINCT FROM expected_inputs_digest THEN
        RAISE EXCEPTION 'Retirement result input digest differs';
    END IF;
    NEW.case_inputs_digest := expected_inputs_digest;
    expected_result_digest := pay_v55_canonical_digest(ARRAY[
        'Pay004RetirementResult.v1', NEW.tenant_id::TEXT,
        NEW.case_id::TEXT, NEW.case_revision::TEXT, NEW.worker_id::TEXT,
        NEW.run_id::TEXT, NEW.run_revision::TEXT, NEW.attempt_no::TEXT,
        NEW.worker_result_id::TEXT, worker_row.worker_id::TEXT,
        NEW.country_pack_state, NEW.pack_id::TEXT,
        NEW.pack_snapshot_revision::TEXT, NEW.activation_revision::TEXT,
        NEW.non_pack_policy_public_id::TEXT,
        NEW.non_pack_policy_revision::TEXT, NEW.non_pack_policy_digest,
        NEW.policy_key, NEW.policy_revision::TEXT,
        NEW.policy_effective_from::TEXT, NEW.policy_effective_to::TEXT,
        NEW.policy_digest, NEW.retirement_amount_text, NEW.currency,
        NEW.rounding_mode, NEW.rounding_stage, NEW.case_inputs_digest,
        NEW.worker_result_digest, NEW.calculation_result_digest,
        to_char(NEW.calculated_at AT TIME ZONE 'UTC',
            'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')]);
    IF NEW.result_digest IS NOT NULL
       AND NEW.result_digest IS DISTINCT FROM expected_result_digest THEN
        RAISE EXCEPTION 'Retirement result canonical digest differs';
    END IF;
    NEW.result_digest := expected_result_digest;
    RETURN NEW;
END
$pay_v55_result$;

CREATE FUNCTION pay_v55_guard_correction_closure()
RETURNS TRIGGER LANGUAGE plpgsql AS $pay_v55_correction$
DECLARE
    finalized pay_statutory_cases%ROWTYPE;
    corrected pay_statutory_cases%ROWTYPE;
    prior_result pay_case_results%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'pay_statutory_cases' THEN
        IF NEW.lifecycle_state IN (
                'CALCULATED', 'APPROVED', 'FINALIZED', 'CORRECTED')
           AND NOT EXISTS (
                SELECT 1 FROM pay_case_results result
                 WHERE result.tenant_id = NEW.tenant_id
                   AND result.case_id = NEW.case_id
                   AND result.case_revision <= NEW.case_revision) THEN
            RAISE EXCEPTION 'Calculated retirement state requires exact result lineage';
        END IF;
        IF NEW.lifecycle_state IN ('APPROVED', 'FINALIZED', 'CORRECTED') THEN
            SELECT * INTO prior_result
              FROM pay_case_results result
             WHERE result.tenant_id = NEW.tenant_id
               AND result.case_id = NEW.case_id
               AND result.case_revision <= NEW.case_revision
             ORDER BY result.case_revision DESC
             LIMIT 1;
            IF NEW.recorded_at < prior_result.recorded_at THEN
                RAISE EXCEPTION 'Retirement case state predates its result';
            END IF;
        END IF;
        IF NEW.lifecycle_state <> 'CORRECTED' THEN
            RETURN NEW;
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM pay_case_corrections correction
             WHERE correction.tenant_id = NEW.tenant_id
               AND correction.case_id = NEW.case_id
               AND correction.corrected_case_revision = NEW.case_revision) THEN
            RAISE EXCEPTION 'CORRECTED revision requires a correction fact';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO finalized
      FROM pay_statutory_cases cases
     WHERE cases.tenant_id = NEW.tenant_id
       AND cases.case_id = NEW.case_id
       AND cases.case_revision = NEW.finalized_case_revision;
    SELECT * INTO corrected
      FROM pay_statutory_cases cases
     WHERE cases.tenant_id = NEW.tenant_id
       AND cases.case_id = NEW.case_id
       AND cases.case_revision = NEW.corrected_case_revision;
    SELECT * INTO prior_result
      FROM pay_case_results result
     WHERE result.tenant_id = NEW.tenant_id
       AND result.result_id = NEW.predecessor_result_id;
    IF finalized.lifecycle_state IS DISTINCT FROM 'FINALIZED'
       OR corrected.lifecycle_state IS DISTINCT FROM 'CORRECTED'
       OR corrected.predecessor_digest IS DISTINCT FROM finalized.case_digest
       OR prior_result.case_id IS DISTINCT FROM NEW.case_id
       OR prior_result.case_revision > NEW.finalized_case_revision
       OR NEW.recorded_at < corrected.recorded_at
       OR NEW.recorded_at < prior_result.recorded_at
       OR NEW.correction_maker_subject_public_id IS DISTINCT FROM
            corrected.transition_subject_public_id
       OR NEW.correction_maker_subject_public_id IS NOT DISTINCT FROM
            finalized.maker_subject_public_id THEN
        RAISE EXCEPTION 'Correction lineage or maker-checker evidence differs';
    END IF;
    RETURN NEW;
END
$pay_v55_correction$;

CREATE TRIGGER pay_statutory_case_insert_guard
    BEFORE INSERT ON pay_statutory_cases
    FOR EACH ROW EXECUTE FUNCTION pay_v55_guard_case_revision();
CREATE TRIGGER pay_case_basis_ref_insert_guard
    BEFORE INSERT ON pay_case_basis_refs
    FOR EACH ROW EXECUTE FUNCTION pay_v55_guard_case_attachment();
CREATE TRIGGER pay_case_evidence_insert_guard
    BEFORE INSERT ON pay_case_evidence
    FOR EACH ROW EXECUTE FUNCTION pay_v55_guard_case_attachment();
CREATE TRIGGER pay_case_result_insert_guard
    BEFORE INSERT ON pay_case_results
    FOR EACH ROW EXECUTE FUNCTION pay_v55_guard_case_result();
CREATE CONSTRAINT TRIGGER pay_statutory_case_correction_closure
    AFTER INSERT ON pay_statutory_cases
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION pay_v55_guard_correction_closure();
CREATE CONSTRAINT TRIGGER pay_case_correction_closure
    AFTER INSERT ON pay_case_corrections
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION pay_v55_guard_correction_closure();

DO $pay_v55_security$
DECLARE pay_table_name TEXT;
BEGIN
    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_statutory_cases',
        'pay_case_basis_refs',
        'pay_case_evidence',
        'pay_case_results',
        'pay_case_corrections']
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I '
            || 'FOR EACH ROW EXECUTE FUNCTION pay_v55_reject_mutation()',
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
$pay_v55_security$;

ALTER FUNCTION pay_v55_reject_mutation() SECURITY DEFINER;
ALTER FUNCTION pay_v55_reject_mutation()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v55_canonical_digest(TEXT[]) SECURITY DEFINER;
ALTER FUNCTION pay_v55_canonical_digest(TEXT[])
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v55_pack_policy_key(TEXT) SECURITY DEFINER;
ALTER FUNCTION pay_v55_pack_policy_key(TEXT)
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v55_case_inputs_digest(BIGINT, UUID, BIGINT)
    SECURITY DEFINER;
ALTER FUNCTION pay_v55_case_inputs_digest(BIGINT, UUID, BIGINT)
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v55_guard_case_revision() SECURITY DEFINER;
ALTER FUNCTION pay_v55_guard_case_revision()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v55_guard_case_attachment() SECURITY DEFINER;
ALTER FUNCTION pay_v55_guard_case_attachment()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v55_guard_case_result() SECURITY DEFINER;
ALTER FUNCTION pay_v55_guard_case_result()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION pay_v55_guard_correction_closure() SECURITY DEFINER;
ALTER FUNCTION pay_v55_guard_correction_closure()
    SET search_path TO pg_catalog, public, pg_temp;

REVOKE ALL ON FUNCTION pay_v55_reject_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v55_canonical_digest(TEXT[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v55_pack_policy_key(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v55_case_inputs_digest(BIGINT, UUID, BIGINT)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v55_guard_case_revision() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v55_guard_case_attachment() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v55_guard_case_result() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_v55_guard_correction_closure() FROM PUBLIC;

DO $pay_v55_runtime_access$
DECLARE
    runtime_role_name TEXT := '${payrollRuntimeRole}';
    runtime_role RECORD;
    pay_table_name TEXT;
    sequence_binding RECORD;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid PAY-004 database role name';
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

    FOREACH pay_table_name IN ARRAY ARRAY[
        'pay_statutory_cases',
        'pay_case_basis_refs',
        'pay_case_evidence',
        'pay_case_results',
        'pay_case_corrections']
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
                EXECUTE format(
                    'GRANT USAGE, SELECT ON SEQUENCE %s TO %I',
                    sequence_binding.sequence_name, runtime_role_name);
            END IF;
        END LOOP;
    END LOOP;
    EXECUTE format(
        'REVOKE ALL ON FUNCTION pay_v55_reject_mutation(), '
        || 'pay_v55_canonical_digest(TEXT[]), '
        || 'pay_v55_pack_policy_key(TEXT), '
        || 'pay_v55_case_inputs_digest(BIGINT, UUID, BIGINT), '
        || 'pay_v55_guard_case_revision(), '
        || 'pay_v55_guard_case_attachment(), '
        || 'pay_v55_guard_case_result(), '
        || 'pay_v55_guard_correction_closure() FROM %I',
        runtime_role_name);
END
$pay_v55_runtime_access$;

COMMENT ON TABLE pay_statutory_cases IS
    'Append-only RetirementYearEndCase revisions with immutable HRM employment reference and owner-resolved Payroll scope.';
COMMENT ON TABLE pay_case_basis_refs IS
    'Immutable owner-qualified retirement basis references; external owner values are not copied.';
COMMENT ON TABLE pay_case_evidence IS
    'Immutable redacted evidence metadata and lowercase canonical digest lineage.';
COMMENT ON TABLE pay_case_results IS
    'Immutable PostedMoney result bound to exact V50 worker result and exact ACTIVE V53 pack or explicit NOT_REQUIRED policy lineage.';
COMMENT ON TABLE pay_case_corrections IS
    'Append-only correction link preserving the finalized predecessor and result byte-for-byte.';
