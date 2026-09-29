-- Lease: MIGLEASE-HRIS-W1-PAY-001 / slice BASE-TFR-PAY-007.
-- This schema stores configuration, immutable versions, command receipts, and audit facts.
-- Payroll calculation, banking, tax, and country rules are intentionally out of scope.

CREATE TABLE pay_foundation_configurations (
    tenant_id BIGINT NOT NULL,
    configuration_id UUID NOT NULL,
    current_version BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    author_id BIGINT NOT NULL,
    publisher_id BIGINT,
    simulation_report JSONB,
    last_command_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, configuration_id),
    CONSTRAINT pay_foundation_configuration_version_positive
        CHECK (current_version > 0),
    CONSTRAINT pay_foundation_configuration_tenant_actor_positive
        CHECK (tenant_id > 0 AND author_id > 0 AND (publisher_id IS NULL OR publisher_id > 0)),
    CONSTRAINT pay_foundation_configuration_period_valid
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT pay_foundation_configuration_state_valid
        CHECK (lifecycle_state IN ('DRAFT', 'SIMULATED', 'PUBLISHED', 'REVERSED')),
    CONSTRAINT pay_foundation_configuration_publish_sod
        CHECK (publisher_id IS NULL OR publisher_id <> author_id),
    CONSTRAINT pay_foundation_configuration_simulation_shape
        CHECK (simulation_report IS NULL OR jsonb_typeof(simulation_report) = 'object'),
    CONSTRAINT pay_foundation_configuration_tenant_command_unique
        UNIQUE (tenant_id, last_command_id)
);

CREATE INDEX pay_foundation_configuration_scope_idx
    ON pay_foundation_configurations
        (tenant_id, legal_entity_id, payroll_group_id, effective_from, effective_to);

CREATE TABLE pay_foundation_versions (
    tenant_id BIGINT NOT NULL,
    configuration_id UUID NOT NULL,
    version BIGINT NOT NULL,
    definition JSONB NOT NULL,
    definition_digest CHAR(64) NOT NULL,
    dependency_digest CHAR(64) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    simulation_report JSONB,
    authored_by BIGINT NOT NULL,
    published_by BIGINT,
    command_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, configuration_id, version),
    CONSTRAINT pay_foundation_version_configuration_fk
        FOREIGN KEY (tenant_id, configuration_id)
        REFERENCES pay_foundation_configurations (tenant_id, configuration_id),
    CONSTRAINT pay_foundation_version_positive
        CHECK (version > 0 AND tenant_id > 0 AND authored_by > 0),
    CONSTRAINT pay_foundation_version_definition_shape
        CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT pay_foundation_version_digest_shape
        CHECK (definition_digest ~ '^[0-9a-f]{64}$'
            AND dependency_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_foundation_version_state_valid
        CHECK (lifecycle_state IN ('DRAFT', 'SIMULATED', 'PUBLISHED', 'REVERSED')),
    CONSTRAINT pay_foundation_version_simulation_shape
        CHECK (simulation_report IS NULL OR jsonb_typeof(simulation_report) = 'object'),
    CONSTRAINT pay_foundation_version_publish_sod
        CHECK (published_by IS NULL OR published_by <> authored_by),
    CONSTRAINT pay_foundation_version_tenant_command_unique
        UNIQUE (tenant_id, command_id)
);

CREATE TABLE pay_foundation_command_receipts (
    tenant_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    command_id UUID NOT NULL,
    command_type VARCHAR(24) NOT NULL,
    receipt_status VARCHAR(24) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    configuration_id UUID,
    result_version BIGINT,
    reversal_of_command_id UUID,
    failure_code VARCHAR(80),
    correlation_id VARCHAR(160),
    authority_purpose VARCHAR(80) NOT NULL,
    legal_entity_scope_digest CHAR(64) NOT NULL,
    policy_revision VARCHAR(240) NOT NULL,
    authorization_revision VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT pay_foundation_receipt_actor_positive
        CHECK (tenant_id > 0 AND actor_id > 0),
    CONSTRAINT pay_foundation_receipt_digest_shape
        CHECK (request_digest ~ '^[0-9a-f]{64}$'
            AND legal_entity_scope_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_foundation_receipt_authority_evidence_present
        CHECK (btrim(authority_purpose) <> ''
            AND btrim(policy_revision) <> ''
            AND btrim(authorization_revision) <> ''),
    CONSTRAINT pay_foundation_receipt_command_valid
        CHECK (command_type IN ('CREATE', 'UPDATE', 'SIMULATE', 'PUBLISH', 'REVERSE')),
    CONSTRAINT pay_foundation_receipt_status_valid
        CHECK (receipt_status IN ('PENDING', 'SUCCEEDED', 'RESULT_UNKNOWN', 'REVERSAL_FAILED')),
    CONSTRAINT pay_foundation_receipt_result_version_positive
        CHECK (result_version IS NULL OR result_version > 0),
    CONSTRAINT pay_foundation_receipt_completion_consistent
        CHECK ((receipt_status = 'PENDING' AND completed_at IS NULL)
            OR (receipt_status <> 'PENDING' AND completed_at IS NOT NULL)),
    CONSTRAINT pay_foundation_receipt_reversal_consistent
        CHECK ((command_type = 'REVERSE' AND reversal_of_command_id IS NOT NULL)
            OR (command_type <> 'REVERSE' AND reversal_of_command_id IS NULL))
);

CREATE INDEX pay_foundation_receipt_configuration_idx
    ON pay_foundation_command_receipts (tenant_id, configuration_id, created_at DESC);

CREATE FUNCTION pay_foundation_guard_receipt_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $payroll_receipt_transition$
BEGIN
    IF OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.actor_id IS DISTINCT FROM NEW.actor_id
       OR OLD.command_id IS DISTINCT FROM NEW.command_id
       OR OLD.command_type IS DISTINCT FROM NEW.command_type
       OR OLD.request_digest IS DISTINCT FROM NEW.request_digest
       OR OLD.reversal_of_command_id IS DISTINCT FROM NEW.reversal_of_command_id
       OR OLD.correlation_id IS DISTINCT FROM NEW.correlation_id
       OR OLD.authority_purpose IS DISTINCT FROM NEW.authority_purpose
       OR OLD.legal_entity_scope_digest IS DISTINCT FROM NEW.legal_entity_scope_digest
       OR OLD.policy_revision IS DISTINCT FROM NEW.policy_revision
       OR OLD.authorization_revision IS DISTINCT FROM NEW.authorization_revision
       OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Payroll command receipt identity and authority evidence are immutable';
    END IF;

    IF OLD.receipt_status = 'PENDING'
       AND NEW.receipt_status NOT IN ('SUCCEEDED', 'RESULT_UNKNOWN', 'REVERSAL_FAILED') THEN
        RAISE EXCEPTION 'Invalid payroll command receipt transition';
    ELSIF OLD.receipt_status = 'RESULT_UNKNOWN'
       AND NEW.receipt_status NOT IN ('RESULT_UNKNOWN', 'SUCCEEDED', 'REVERSAL_FAILED') THEN
        RAISE EXCEPTION 'Invalid payroll command receipt transition';
    ELSIF OLD.receipt_status IN ('SUCCEEDED', 'REVERSAL_FAILED') THEN
        RAISE EXCEPTION 'Terminal payroll command receipts are immutable';
    END IF;
    RETURN NEW;
END
$payroll_receipt_transition$;

CREATE TRIGGER pay_foundation_receipt_transition_guard
    BEFORE UPDATE ON pay_foundation_command_receipts
    FOR EACH ROW
    EXECUTE FUNCTION pay_foundation_guard_receipt_transition();

REVOKE ALL ON FUNCTION pay_foundation_guard_receipt_transition() FROM PUBLIC;

CREATE TABLE pay_foundation_audit_events (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    configuration_version BIGINT NOT NULL,
    command_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_id BIGINT NOT NULL,
    subject_id BIGINT NOT NULL,
    correlation_id VARCHAR(160),
    authority_purpose VARCHAR(80) NOT NULL,
    legal_entity_scope_digest CHAR(64) NOT NULL,
    policy_revision VARCHAR(240) NOT NULL,
    authorization_revision VARCHAR(240) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_payload JSONB NOT NULL,
    PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT pay_foundation_audit_configuration_fk
        FOREIGN KEY (tenant_id, configuration_id)
        REFERENCES pay_foundation_configurations (tenant_id, configuration_id),
    CONSTRAINT pay_foundation_audit_positive
        CHECK (tenant_id > 0 AND actor_id > 0 AND subject_id > 0
            AND configuration_version > 0),
    CONSTRAINT pay_foundation_audit_payload_shape
        CHECK (jsonb_typeof(event_payload) = 'object'),
    CONSTRAINT pay_foundation_audit_authority_evidence_valid
        CHECK (legal_entity_scope_digest ~ '^[0-9a-f]{64}$'
            AND btrim(authority_purpose) <> ''
            AND btrim(policy_revision) <> ''
            AND btrim(authorization_revision) <> ''),
    CONSTRAINT pay_foundation_audit_tenant_command_event_unique
        UNIQUE (tenant_id, command_id, event_type)
);

CREATE INDEX pay_foundation_audit_configuration_idx
    ON pay_foundation_audit_events
        (tenant_id, configuration_id, configuration_version, occurred_at);

REVOKE ALL ON TABLE pay_foundation_configurations FROM PUBLIC;
REVOKE ALL ON TABLE pay_foundation_versions FROM PUBLIC;
REVOKE ALL ON TABLE pay_foundation_command_receipts FROM PUBLIC;
REVOKE ALL ON TABLE pay_foundation_audit_events FROM PUBLIC;

ALTER TABLE pay_foundation_configurations ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_foundation_configurations FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_foundation_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_foundation_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_foundation_command_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_foundation_command_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_foundation_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_foundation_audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY pay_foundation_configuration_tenant_policy
    ON pay_foundation_configurations
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

CREATE POLICY pay_foundation_version_tenant_policy
    ON pay_foundation_versions
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

CREATE POLICY pay_foundation_receipt_tenant_policy
    ON pay_foundation_command_receipts
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

CREATE POLICY pay_foundation_audit_tenant_policy
    ON pay_foundation_audit_events
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

DO $payroll_runtime_access$
DECLARE
    runtime_role_name TEXT := '${payrollRuntimeRole}';
    runtime_role RECORD;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid payroll runtime role name';
    END IF;

    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
      INTO runtime_role
      FROM pg_roles
     WHERE rolname = runtime_role_name;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Payroll runtime role % must exist', runtime_role_name;
    END IF;
    IF runtime_role.rolsuper
       OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb
       OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION 'Payroll runtime role % has unsafe PostgreSQL attributes', runtime_role_name;
    END IF;

    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE ON TABLE pay_foundation_configurations TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE pay_foundation_versions TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE pay_foundation_command_receipts TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT UPDATE (receipt_status, configuration_id, result_version, failure_code, completed_at) '
        'ON TABLE pay_foundation_command_receipts TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE pay_foundation_audit_events TO %I',
        runtime_role_name);
END
$payroll_runtime_access$;

COMMENT ON TABLE pay_foundation_configurations IS
    'Tenant-scoped current projection for configurable payroll foundation definitions.';
COMMENT ON TABLE pay_foundation_versions IS
    'Immutable payroll foundation definitions; country logic is referenced only by version and digest.';
COMMENT ON TABLE pay_foundation_command_receipts IS
    'Durable idempotency and RESULT_UNKNOWN/reversal reconciliation receipts.';
COMMENT ON TABLE pay_foundation_audit_events IS
    'Append-only command audit facts without payroll amounts or personal data.';
