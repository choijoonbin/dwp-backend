-- BASE-TFR-SYS-002 / SYS-EVT-009 / PEP-SYS-037 / PEP-SYS-038 / IAQ-059.
-- This is a read-only central projection. It contains no owner recovery command,
-- no duplicate event ledger and no tenant enrollment or feature activation row.

CREATE TABLE hris_operational_exception_projection (
    tenant_id BIGINT NOT NULL,
    exception_public_id UUID NOT NULL,
    event_source VARCHAR(240) NOT NULL,
    owner_service VARCHAR(120) NOT NULL,
    owner_module VARCHAR(3) NOT NULL,
    source_ref VARCHAR(240) NOT NULL,
    source_revision BIGINT NOT NULL,
    exception_type VARCHAR(80) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    internal_state VARCHAR(16) NOT NULL,
    code VARCHAR(80) NOT NULL,
    summary_key VARCHAR(180) NOT NULL,
    owner_route_key VARCHAR(200) NOT NULL,
    evidence_refs TEXT[] NOT NULL,
    correlation_id UUID NOT NULL,
    event_digest CHAR(64) NOT NULL,
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    occurrence_count BIGINT NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT pk_hris_operational_exception_projection PRIMARY KEY
        (tenant_id, event_source, exception_public_id),
    CONSTRAINT uq_hris_operational_exception_owner_revision UNIQUE
        (tenant_id, event_source, exception_public_id, source_revision),
    CONSTRAINT ck_hris_operational_exception_tenant
        CHECK (tenant_id > 0),
    CONSTRAINT ck_hris_operational_exception_positive_versions
        CHECK (source_revision > 0 AND occurrence_count > 0 AND version > 0),
    CONSTRAINT ck_hris_operational_exception_owner
        CHECK ((event_source = 'urn:dwp:people:hrm:operational-exception'
                AND owner_service = 'dwp-people-server' AND owner_module = 'HRM')
            OR (event_source = 'urn:dwp:people:performance:operational-exception'
                AND owner_service = 'dwp-people-server' AND owner_module = 'PER')
            OR (event_source = 'urn:dwp:payroll:operational-exception'
                AND owner_service = 'dwp-payroll-server' AND owner_module = 'PAY')
            OR (event_source = 'urn:dwp:platform:hris:operational-exception'
                AND owner_service = 'dwp-platform-server' AND owner_module = 'SYS')
            OR (event_source = 'urn:dwp:time:operational-exception'
                AND owner_service = 'dwp-time-server' AND owner_module = 'TIM')),
    CONSTRAINT ck_hris_operational_exception_source_ref
        CHECK ((owner_module = 'HRM' AND source_ref ~
                '^urn:dwp:hris:hrm:[a-z][a-z0-9-]{0,63}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            OR (owner_module = 'PER' AND source_ref ~
                '^urn:dwp:hris:per:[a-z][a-z0-9-]{0,63}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            OR (owner_module = 'PAY' AND source_ref ~
                '^urn:dwp:hris:pay:[a-z][a-z0-9-]{0,63}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            OR (owner_module = 'SYS' AND source_ref ~
                '^urn:dwp:hris:sys:[a-z][a-z0-9-]{0,63}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            OR (owner_module = 'TIM' AND source_ref ~
                '^urn:dwp:hris:tim:[a-z][a-z0-9-]{0,63}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),
    CONSTRAINT ck_hris_operational_exception_type_code
        CHECK (exception_type ~ '^[A-Z][A-Z0-9._-]{0,79}$'
            AND code ~ '^[A-Z][A-Z0-9._-]{0,79}$'),
    CONSTRAINT ck_hris_operational_exception_severity
        CHECK (severity IN ('INFO', 'WARNING', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_hris_operational_exception_state
        CHECK (internal_state IN
            ('OPEN', 'ACKNOWLEDGED', 'RECOVERING', 'RESOLVED', 'SUPPRESSED')),
    CONSTRAINT ck_hris_operational_exception_summary_key
        CHECK ((owner_module = 'HRM' AND summary_key ~
                    '^hris\.operations\.exception\.hrm\.[a-z][a-z0-9._-]{0,119}$')
            OR (owner_module = 'PER' AND summary_key ~
                    '^hris\.operations\.exception\.per\.[a-z][a-z0-9._-]{0,119}$')
            OR (owner_module = 'PAY' AND summary_key ~
                    '^hris\.operations\.exception\.pay\.[a-z][a-z0-9._-]{0,119}$')
            OR (owner_module = 'SYS' AND summary_key ~
                    '^hris\.operations\.exception\.sys\.[a-z][a-z0-9._-]{0,119}$')
            OR (owner_module = 'TIM' AND summary_key ~
                    '^hris\.operations\.exception\.tim\.[a-z][a-z0-9._-]{0,119}$')),
    CONSTRAINT ck_hris_operational_exception_owner_route
        CHECK ((owner_module = 'HRM' AND owner_route_key IN (
                    'route.hcm.operations.employee-change-requests.page',
                    'route.hcm.operations.employment-assignments.page',
                    'route.hcm.operations.organization-positions.page'))
            OR (owner_module = 'PER' AND owner_route_key IN (
                    'route.hcm.operations.compensation-planning.page',
                    'route.hcm.operations.performance-goal-cycles.page',
                    'route.hcm.operations.performance-multirater-surveys.page',
                    'route.hcm.operations.performance-review-operations.page'))
            OR (owner_module = 'PAY' AND owner_route_key IN (
                    'route.hcm.configuration.payroll.page',
                    'route.hcm.operations.pay.page',
                    'route.hcm.operations.payroll-population.page'))
            OR (owner_module = 'SYS' AND owner_route_key IN (
                    'route.hcm.configuration.navigation-policy-workspace.page',
                    'route.hcm.configuration.settings-operations.page',
                    'route.hcm.configuration.settings-reference.page',
                    'route.hcm.management.controlled-export.page'))
            OR (owner_module = 'TIM' AND owner_route_key IN (
                    'route.hcm.operations.time-command-center.page',
                    'route.hcm.operations.time-daily-close.page',
                    'route.hcm.operations.time-exceptions.page',
                    'route.hcm.operations.time-interpretation.page',
                    'route.hcm.operations.time-monthly-close.page'))),
    CONSTRAINT ck_hris_operational_exception_evidence_count
        CHECK (array_ndims(evidence_refs) = 1
            AND cardinality(evidence_refs) BETWEEN 1 AND 20),
    CONSTRAINT ck_hris_operational_exception_digest
        CHECK (event_digest ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_hris_operational_exception_stable_list
    ON hris_operational_exception_projection
        (tenant_id, owner_module, last_observed_at DESC,
         exception_public_id DESC);
CREATE INDEX ix_hris_operational_exception_state
    ON hris_operational_exception_projection
        (tenant_id, internal_state, severity, last_observed_at DESC);

CREATE OR REPLACE FUNCTION sys_guard_hris_operational_exception_projection()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    evidence_ref TEXT;
    previous_ref TEXT := NULL;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SYS-002 projection evidence cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    FOREACH evidence_ref IN ARRAY NEW.evidence_refs LOOP
        IF evidence_ref IS NULL OR evidence_ref !~
            '^urn:dwp:hris:evidence:(audit|command-receipt|domain-event|owner-projection):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR (previous_ref IS NOT NULL AND evidence_ref <= previous_ref) THEN
            RAISE EXCEPTION 'SYS-002 evidence references must be unique canonical sorted URNs'
                USING ERRCODE = '23514';
        END IF;
        previous_ref := evidence_ref;
    END LOOP;
    IF TG_OP = 'INSERT' THEN
        IF NEW.source_revision <> 1 OR NEW.occurrence_count <> 1
           OR NEW.version <> 1
           OR NEW.first_observed_at IS DISTINCT FROM NEW.last_observed_at THEN
            RAISE EXCEPTION 'SYS-002 projection must start at owner revision one'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.tenant_id, NEW.event_source, NEW.exception_public_id,
           NEW.owner_service, NEW.owner_module, NEW.first_observed_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.event_source, OLD.exception_public_id,
           OLD.owner_service, OLD.owner_module, OLD.first_observed_at) THEN
        RAISE EXCEPTION 'SYS-002 owner identity and first observation are immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.source_revision <> OLD.source_revision + 1
       OR NEW.occurrence_count <> OLD.occurrence_count + 1
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'SYS-002 projection requires an exact contiguous CAS advance'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_hris_operational_exception_projection_guard
BEFORE INSERT OR UPDATE OR DELETE ON hris_operational_exception_projection
FOR EACH ROW EXECUTE FUNCTION sys_guard_hris_operational_exception_projection();

ALTER TABLE hris_operational_exception_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_operational_exception_projection FORCE ROW LEVEL SECURITY;

CREATE POLICY hris_operational_exception_projection_tenant_policy
    ON hris_operational_exception_projection
    USING (tenant_id = NULLIF(
        current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.tenant_id', TRUE), '')::BIGINT);

COMMENT ON TABLE hris_operational_exception_projection IS
    'Tenant-scoped redacted SYS-EVT-009 read projection; owner recovery remains external.';
