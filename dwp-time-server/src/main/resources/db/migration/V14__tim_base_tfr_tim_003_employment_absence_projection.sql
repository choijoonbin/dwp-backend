-- BASE-TFR-TIM-003 / HRIS-WAVE7-R7-BATCH-04-TIM003
-- Time-owned employment relationship projection for absence eligibility recovery.

CREATE FUNCTION public.tim003_assignment_snapshots_structurally_valid(
        snapshots JSONB,
        enclosing_legal_entity_public_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
DECLARE
    assignment JSONB;
    key_count BIGINT;
    assignment_id TEXT;
    previous_assignment_id TEXT;
    assignment_revision_text TEXT;
    effective_from_text TEXT;
    effective_to_text TEXT;
    effective_from_value DATE;
    effective_to_value DATE;
BEGIN
    IF pg_catalog.jsonb_typeof(snapshots) IS DISTINCT FROM 'array'
       OR pg_catalog.jsonb_array_length(snapshots) > 1000 THEN
        RETURN FALSE;
    END IF;

    FOR assignment IN
        SELECT value
          FROM pg_catalog.jsonb_array_elements(snapshots)
    LOOP
        IF pg_catalog.jsonb_typeof(assignment) IS DISTINCT FROM 'object' THEN
            RETURN FALSE;
        END IF;

        SELECT pg_catalog.count(*)
          INTO key_count
          FROM pg_catalog.jsonb_object_keys(assignment);
        IF key_count <> 9
           OR NOT (
               assignment ? 'assignmentPublicId'
               AND assignment ? 'assignmentRevision'
               AND assignment ? 'assignmentStatus'
               AND assignment ? 'legalEntityPublicId'
               AND assignment ? 'organizationPublicId'
               AND assignment ? 'locationPublicId'
               AND assignment ? 'timeZone'
               AND assignment ? 'effectiveFrom'
               AND assignment ? 'effectiveTo') THEN
            RETURN FALSE;
        END IF;

        IF pg_catalog.jsonb_typeof(assignment -> 'assignmentPublicId')
                    IS DISTINCT FROM 'string'
           OR pg_catalog.jsonb_typeof(assignment -> 'assignmentRevision')
                    IS DISTINCT FROM 'number'
           OR pg_catalog.jsonb_typeof(assignment -> 'assignmentStatus')
                    IS DISTINCT FROM 'string'
           OR pg_catalog.jsonb_typeof(assignment -> 'legalEntityPublicId')
                    IS DISTINCT FROM 'string'
           OR pg_catalog.jsonb_typeof(assignment -> 'effectiveFrom')
                    IS DISTINCT FROM 'string' THEN
            RETURN FALSE;
        END IF;

        assignment_id := assignment ->> 'assignmentPublicId';
        IF assignment_id !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR (previous_assignment_id IS NOT NULL
               AND previous_assignment_id >= assignment_id) THEN
            RETURN FALSE;
        END IF;
        previous_assignment_id := assignment_id;

        assignment_revision_text := assignment ->> 'assignmentRevision';
        IF assignment_revision_text !~ '^(0|[1-9][0-9]{0,18})$'
           OR assignment_revision_text::NUMERIC > 9223372036854775807 THEN
            RETURN FALSE;
        END IF;

        IF assignment ->> 'assignmentStatus'
                NOT IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'ENDED') THEN
            RETURN FALSE;
        END IF;

        IF assignment ->> 'legalEntityPublicId'
                !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR assignment ->> 'legalEntityPublicId'
                <> enclosing_legal_entity_public_id::TEXT THEN
            RETURN FALSE;
        END IF;

        IF pg_catalog.jsonb_typeof(assignment -> 'organizationPublicId')
                NOT IN ('string', 'null')
           OR (pg_catalog.jsonb_typeof(assignment -> 'organizationPublicId') = 'string'
               AND assignment ->> 'organizationPublicId'
                   !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') THEN
            RETURN FALSE;
        END IF;

        IF pg_catalog.jsonb_typeof(assignment -> 'locationPublicId')
                NOT IN ('string', 'null')
           OR pg_catalog.jsonb_typeof(assignment -> 'timeZone')
                NOT IN ('string', 'null')
           OR ((pg_catalog.jsonb_typeof(assignment -> 'locationPublicId') = 'null')
               <> (pg_catalog.jsonb_typeof(assignment -> 'timeZone') = 'null'))
           OR (pg_catalog.jsonb_typeof(assignment -> 'locationPublicId') = 'string'
               AND assignment ->> 'locationPublicId'
                   !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
           OR (pg_catalog.jsonb_typeof(assignment -> 'timeZone') = 'string'
               AND pg_catalog.length(assignment ->> 'timeZone') NOT BETWEEN 1 AND 80) THEN
            RETURN FALSE;
        END IF;

        effective_from_text := assignment ->> 'effectiveFrom';
        IF effective_from_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
            RETURN FALSE;
        END IF;
        effective_from_value := effective_from_text::DATE;

        IF pg_catalog.jsonb_typeof(assignment -> 'effectiveTo') = 'null' THEN
            effective_to_value := NULL;
        ELSIF pg_catalog.jsonb_typeof(assignment -> 'effectiveTo') = 'string' THEN
            effective_to_text := assignment ->> 'effectiveTo';
            IF effective_to_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
                RETURN FALSE;
            END IF;
            effective_to_value := effective_to_text::DATE;
            IF effective_to_value <= effective_from_value THEN
                RETURN FALSE;
            END IF;
        ELSE
            RETURN FALSE;
        END IF;
    END LOOP;

    RETURN TRUE;
EXCEPTION
    WHEN OTHERS THEN
        RETURN FALSE;
END;
$function$;

CREATE TABLE public.abs_employment_relationship_projections (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    relationship_public_id UUID NOT NULL,
    worker_public_id UUID,
    freshness_state VARCHAR(24) NOT NULL,
    last_observed_event_sequence BIGINT NOT NULL DEFAULT 0
        CHECK (last_observed_event_sequence >= 0),
    required_relationship_revision BIGINT NOT NULL DEFAULT 0
        CHECK (required_relationship_revision >= 0),
    shared_offset_sequence BIGINT NOT NULL DEFAULT 0
        CHECK (shared_offset_sequence >= 0),
    gap_expected_sequence BIGINT,
    owner_relationship_revision BIGINT,
    worker_status VARCHAR(16),
    relationship_type VARCHAR(80),
    relationship_status VARCHAR(16),
    legal_entity_public_id UUID,
    relationship_effective_from DATE,
    relationship_effective_to DATE,
    owner_effective_on DATE,
    assignment_snapshots JSONB NOT NULL DEFAULT '[]'::JSONB,
    owner_contract_id VARCHAR(80),
    owner_schema_version INTEGER,
    owner_revision BIGINT,
    owner_as_of TIMESTAMPTZ,
    owner_valid_until TIMESTAMPTZ,
    owner_content_sha256 CHAR(64),
    owner_service VARCHAR(80),
    purpose_code VARCHAR(80),
    owner_scope_digest CHAR(64),
    owner_business_time_zone VARCHAR(80),
    owner_minimum_relationship_revision BIGINT,
    tenant_grant_revision BIGINT,
    authorization_revision BIGINT,
    field_policy_revision BIGINT,
    owner_issued_at TIMESTAMPTZ,
    owner_evidence_digest CHAR(64),
    owner_key_id VARCHAR(160),
    last_invalidation_event_id UUID,
    last_invalidation_change_type VARCHAR(80),
    last_invalidation_effective_date DATE,
    last_invalidation_payload_sha256 CHAR(64),
    last_correlation_id VARCHAR(160),
    recovery_outcome VARCHAR(32) NOT NULL DEFAULT 'NOT_ATTEMPTED',
    last_recovery_error_code VARCHAR(120),
    last_recovery_at TIMESTAMPTZ,
    projection_version BIGINT NOT NULL DEFAULT 1
        CHECK (projection_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, relationship_public_id),
    CONSTRAINT ck_abs_emp_freshness_state CHECK (
        freshness_state IN (
            'CURRENT', 'STALE', 'REFETCH_PENDING', 'GAP', 'QUARANTINED')),
    CONSTRAINT ck_abs_emp_recovery_outcome CHECK (
        recovery_outcome IN (
            'NOT_ATTEMPTED', 'PENDING', 'SUCCEEDED',
            'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),
    CONSTRAINT ck_abs_emp_event_revision_order CHECK (
        required_relationship_revision >= last_observed_event_sequence),
    CONSTRAINT ck_abs_emp_gap_evidence CHECK (
        (freshness_state = 'GAP'
            AND gap_expected_sequence = shared_offset_sequence + 1
            AND last_observed_event_sequence > gap_expected_sequence)
        OR (freshness_state <> 'GAP' AND gap_expected_sequence IS NULL)),
    CONSTRAINT ck_abs_emp_state_outcome_pair CHECK (
        (freshness_state IN ('CURRENT', 'STALE')
            AND recovery_outcome = 'SUCCEEDED')
        OR (freshness_state = 'REFETCH_PENDING'
            AND recovery_outcome IN ('PENDING', 'RETRYABLE_FAILURE'))
        OR (freshness_state = 'GAP'
            AND recovery_outcome IN (
                'NOT_ATTEMPTED', 'PENDING', 'RETRYABLE_FAILURE'))
        OR (freshness_state = 'QUARANTINED'
            AND recovery_outcome = 'PERMANENT_FAILURE')),
    CONSTRAINT ck_abs_emp_worker_status CHECK (
        worker_status IS NULL
        OR worker_status IN ('PENDING', 'ACTIVE', 'LEAVE', 'TERMINATED')),
    CONSTRAINT ck_abs_emp_relationship_type CHECK (
        relationship_type IS NULL
        OR relationship_type ~ '^[A-Z0-9][A-Z0-9._-]{0,79}$'),
    CONSTRAINT ck_abs_emp_relationship_status CHECK (
        relationship_status IS NULL
        OR relationship_status IN ('PENDING', 'ACTIVE', 'INACTIVE', 'TERMINATED')),
    CONSTRAINT ck_abs_emp_owner_bundle_all_or_none CHECK (
        pg_catalog.num_nonnulls(
            worker_public_id,
            owner_relationship_revision,
            worker_status,
            relationship_type,
            relationship_status,
            legal_entity_public_id,
            relationship_effective_from,
            owner_effective_on,
            owner_contract_id,
            owner_schema_version,
            owner_revision,
            owner_as_of,
            owner_valid_until,
            owner_content_sha256,
            owner_service,
            purpose_code,
            owner_scope_digest,
            owner_business_time_zone,
            owner_minimum_relationship_revision,
            tenant_grant_revision,
            authorization_revision,
            field_policy_revision,
            owner_issued_at,
            owner_evidence_digest,
            owner_key_id) IN (0, 25)),
    CONSTRAINT ck_abs_emp_owner_absent_companions CHECK (
        worker_public_id IS NOT NULL
        OR (relationship_effective_to IS NULL
            AND assignment_snapshots = '[]'::JSONB)),
    CONSTRAINT ck_abs_emp_owner_revisions CHECK (
        worker_public_id IS NULL
        OR (owner_relationship_revision >= 0
            AND owner_revision = owner_relationship_revision
            AND owner_minimum_relationship_revision >= 0
            AND owner_minimum_relationship_revision <= owner_relationship_revision
            AND tenant_grant_revision > 0
            AND authorization_revision > 0
            AND field_policy_revision > 0)),
    CONSTRAINT ck_abs_emp_owner_contract CHECK (
        worker_public_id IS NULL
        OR (owner_contract_id = 'SVC-PEP-HRM-007/TIME_ABSENCE_V1'
            AND owner_schema_version = 1
            AND owner_service = 'dwp-people-server'
            AND purpose_code = 'TIM_LEAVE_EMPLOYMENT_RECOVERY')),
    CONSTRAINT ck_abs_emp_owner_business_zone CHECK (
        worker_public_id IS NULL
        OR owner_business_time_zone = 'UTC'
        OR (owner_business_time_zone
                ~ '^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)+$'
            AND owner_business_time_zone !~ '^Etc/GMT')),
    CONSTRAINT ck_abs_emp_digests CHECK (
        (owner_content_sha256 IS NULL
            OR owner_content_sha256 ~ '^[0-9a-f]{64}$')
        AND (owner_scope_digest IS NULL
            OR owner_scope_digest ~ '^[0-9a-f]{64}$')
        AND (owner_evidence_digest IS NULL
            OR owner_evidence_digest ~ '^[0-9a-f]{64}$')
        AND (last_invalidation_payload_sha256 IS NULL
            OR last_invalidation_payload_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_abs_emp_relationship_period CHECK (
        relationship_effective_to IS NULL
        OR relationship_effective_to > relationship_effective_from),
    CONSTRAINT ck_abs_emp_owner_validity CHECK (
        owner_valid_until IS NULL OR owner_valid_until > owner_as_of),
    CONSTRAINT ck_abs_emp_assignment_snapshots CHECK (
        pg_catalog.jsonb_typeof(assignment_snapshots) = 'array'
        AND pg_catalog.jsonb_array_length(assignment_snapshots) <= 1000
        AND public.tim003_assignment_snapshots_structurally_valid(
            assignment_snapshots, legal_entity_public_id)),
    CONSTRAINT ck_abs_emp_current_owner_evidence CHECK (
        freshness_state NOT IN ('CURRENT', 'STALE')
        OR (worker_public_id IS NOT NULL
            AND shared_offset_sequence >= required_relationship_revision
            AND owner_relationship_revision >= required_relationship_revision
            AND owner_relationship_revision >= shared_offset_sequence)),
    CONSTRAINT ck_abs_emp_recovery_evidence CHECK (
        (recovery_outcome = 'NOT_ATTEMPTED'
            AND last_recovery_at IS NULL
            AND last_recovery_error_code IS NULL)
        OR (recovery_outcome IN ('PENDING', 'SUCCEEDED')
            AND last_recovery_at IS NOT NULL
            AND last_recovery_error_code IS NULL)
        OR (recovery_outcome IN ('RETRYABLE_FAILURE', 'PERMANENT_FAILURE')
            AND last_recovery_at IS NOT NULL
            AND last_recovery_error_code
                ~ '^[A-Z0-9][A-Z0-9._-]{0,79}$')),
    CONSTRAINT ck_abs_emp_invalidation_bundle CHECK (
        (last_observed_event_sequence = 0
            AND last_invalidation_event_id IS NULL
            AND last_invalidation_change_type IS NULL
            AND last_invalidation_effective_date IS NULL
            AND last_invalidation_payload_sha256 IS NULL
            AND last_correlation_id IS NULL)
        OR (last_observed_event_sequence > 0
            AND last_invalidation_event_id IS NOT NULL
            AND last_invalidation_change_type IS NOT NULL
            AND last_invalidation_effective_date IS NOT NULL
            AND last_invalidation_payload_sha256 IS NOT NULL
            AND last_correlation_id IS NOT NULL))
);

CREATE INDEX ix_abs_emp_projection_tenant_worker
    ON public.abs_employment_relationship_projections (
        tenant_id, worker_public_id)
    WHERE worker_public_id IS NOT NULL;

CREATE INDEX ix_abs_emp_projection_tenant_freshness_valid_until
    ON public.abs_employment_relationship_projections (
        tenant_id, freshness_state, owner_valid_until);

CREATE FUNCTION public.tim003_seal_employment_projection_update()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog
AS $function$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.relationship_public_id IS DISTINCT FROM OLD.relationship_public_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'TIM-003 projection identity and creation time are immutable';
    END IF;
    NEW.projection_version := OLD.projection_version + 1;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_abs_emp_projection_seal_update
BEFORE UPDATE ON public.abs_employment_relationship_projections
FOR EACH ROW
EXECUTE FUNCTION public.tim003_seal_employment_projection_update();

ALTER TABLE public.abs_employment_relationship_projections
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.abs_employment_relationship_projections
    FORCE ROW LEVEL SECURITY;

CREATE POLICY abs_employment_relationship_projections_tenant_isolation
ON public.abs_employment_relationship_projections
USING (
    tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
WITH CHECK (
    tenant_id = NULLIF(
        pg_catalog.current_setting('dwp.tenant_id', TRUE), '')::BIGINT);

REVOKE ALL ON TABLE public.abs_employment_relationship_projections FROM PUBLIC;

GRANT SELECT ON TABLE public.abs_employment_relationship_projections
TO "${timeRuntimeRole}";

GRANT INSERT (
    tenant_id,
    relationship_public_id,
    worker_public_id,
    freshness_state,
    last_observed_event_sequence,
    required_relationship_revision,
    shared_offset_sequence,
    gap_expected_sequence,
    owner_relationship_revision,
    worker_status,
    relationship_type,
    relationship_status,
    legal_entity_public_id,
    relationship_effective_from,
    relationship_effective_to,
    owner_effective_on,
    assignment_snapshots,
    owner_contract_id,
    owner_schema_version,
    owner_revision,
    owner_as_of,
    owner_valid_until,
    owner_content_sha256,
    owner_service,
    purpose_code,
    owner_scope_digest,
    owner_business_time_zone,
    owner_minimum_relationship_revision,
    tenant_grant_revision,
    authorization_revision,
    field_policy_revision,
    owner_issued_at,
    owner_evidence_digest,
    owner_key_id,
    last_invalidation_event_id,
    last_invalidation_change_type,
    last_invalidation_effective_date,
    last_invalidation_payload_sha256,
    last_correlation_id,
    recovery_outcome,
    last_recovery_error_code,
    last_recovery_at)
ON public.abs_employment_relationship_projections
TO "${timeRuntimeRole}";

GRANT UPDATE (
    worker_public_id,
    freshness_state,
    last_observed_event_sequence,
    required_relationship_revision,
    shared_offset_sequence,
    gap_expected_sequence,
    owner_relationship_revision,
    worker_status,
    relationship_type,
    relationship_status,
    legal_entity_public_id,
    relationship_effective_from,
    relationship_effective_to,
    owner_effective_on,
    assignment_snapshots,
    owner_contract_id,
    owner_schema_version,
    owner_revision,
    owner_as_of,
    owner_valid_until,
    owner_content_sha256,
    owner_service,
    purpose_code,
    owner_scope_digest,
    owner_business_time_zone,
    owner_minimum_relationship_revision,
    tenant_grant_revision,
    authorization_revision,
    field_policy_revision,
    owner_issued_at,
    owner_evidence_digest,
    owner_key_id,
    last_invalidation_event_id,
    last_invalidation_change_type,
    last_invalidation_effective_date,
    last_invalidation_payload_sha256,
    last_correlation_id,
    recovery_outcome,
    last_recovery_error_code,
    last_recovery_at)
ON public.abs_employment_relationship_projections
TO "${timeRuntimeRole}";

REVOKE ALL ON FUNCTION
    public.tim003_assignment_snapshots_structurally_valid(JSONB, UUID),
    public.tim003_seal_employment_projection_update()
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION
    public.tim003_assignment_snapshots_structurally_valid(JSONB, UUID)
TO "${timeRuntimeRole}";
