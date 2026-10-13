-- Slice BASE-TFR-PAY-006 / Control 35931b47.
-- Immutable payroll element/formula versions, durable idempotency receipts and the
-- minimized PayrollConfigurationPublished.v1 outbox. No executable source is stored.

CREATE TABLE pay_element_versions (
    tenant_id BIGINT NOT NULL,
    element_id UUID NOT NULL,
    version BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    element_code VARCHAR(80) NOT NULL,
    definition JSONB NOT NULL,
    authored_by BIGINT NOT NULL,
    command_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, element_id, version),
    CONSTRAINT pay_element_version_positive
        CHECK (tenant_id > 0 AND version > 0 AND authored_by > 0),
    CONSTRAINT pay_element_state_valid
        CHECK (lifecycle_state = 'DRAFT'),
    CONSTRAINT pay_element_code_valid
        CHECK (element_code ~ '^[A-Za-z][A-Za-z0-9_.-]{0,79}$'),
    CONSTRAINT pay_element_definition_object
        CHECK (jsonb_typeof(definition) = 'object')
);

CREATE INDEX pay_element_scope_latest_idx
    ON pay_element_versions
        (tenant_id, legal_entity_id, payroll_group_id, element_id, version DESC);

CREATE TABLE pay_formula_versions (
    tenant_id BIGINT NOT NULL,
    formula_id UUID NOT NULL,
    version BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    legal_entity_id UUID NOT NULL,
    payroll_group_id UUID NOT NULL,
    formula_code VARCHAR(80) NOT NULL,
    definition JSONB NOT NULL,
    ast_digest CHAR(64) NOT NULL,
    validation_digest CHAR(64),
    authored_by BIGINT NOT NULL,
    published_by BIGINT,
    command_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, formula_id, version),
    CONSTRAINT pay_formula_version_positive
        CHECK (tenant_id > 0 AND version > 0 AND authored_by > 0
            AND (published_by IS NULL OR published_by > 0)),
    CONSTRAINT pay_formula_state_valid
        CHECK (lifecycle_state IN ('DRAFT', 'VALIDATED', 'PUBLISHED')),
    CONSTRAINT pay_formula_code_valid
        CHECK (formula_code ~ '^[A-Za-z][A-Za-z0-9_.-]{0,79}$'),
    CONSTRAINT pay_formula_definition_object
        CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT pay_formula_digest_shape
        CHECK (ast_digest ~ '^[0-9a-f]{64}$'
            AND (validation_digest IS NULL OR validation_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT pay_formula_publish_evidence
        CHECK ((lifecycle_state <> 'PUBLISHED' AND published_by IS NULL)
            OR (lifecycle_state = 'PUBLISHED'
                AND published_by IS NOT NULL
                AND validation_digest IS NOT NULL
                AND published_by <> authored_by))
);

CREATE INDEX pay_formula_scope_latest_idx
    ON pay_formula_versions
        (tenant_id, legal_entity_id, payroll_group_id, formula_id, version DESC);

CREATE TABLE pay_configuration_command_receipts (
    tenant_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    command_id UUID NOT NULL,
    command_type VARCHAR(40) NOT NULL,
    originating_action VARCHAR(48) NOT NULL,
    subject_principal_public_id UUID NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    field_policy_revision VARCHAR(160) NOT NULL,
    purpose_code VARCHAR(160) NOT NULL,
    authorization_revision VARCHAR(160) NOT NULL,
    receipt_status VARCHAR(24) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    resource_id UUID NOT NULL,
    result_version BIGINT NOT NULL,
    PRIMARY KEY (
        tenant_id, subject_principal_public_id, originating_action, command_id),
    CONSTRAINT pay_configuration_receipt_id_unique
        UNIQUE (tenant_id, receipt_id),
    CONSTRAINT pay_configuration_receipt_positive
        CHECK (tenant_id > 0 AND actor_id > 0 AND result_version > 0),
    CONSTRAINT pay_configuration_command_type_valid
        CHECK (command_type IN (
            'CREATE_ELEMENT_VERSION',
            'CREATE_FORMULA_VERSION',
            'VALIDATE_FORMULA',
            'SIMULATE_FORMULA',
            'PUBLISH_FORMULA')),
    CONSTRAINT pay_configuration_originating_action_valid
        CHECK (originating_action IN (
            'PAY_ELEMENT_CONFIGURE',
            'PAY_FORMULA_CONFIGURE',
            'PAY_FORMULA_VALIDATE',
            'PAY_FORMULA_SIMULATE',
            'PAY_FORMULA_PUBLISH')),
    CONSTRAINT pay_configuration_receipt_status_valid
        CHECK (receipt_status IN (
            'ACCEPTED', 'RUNNING', 'SUCCEEDED',
            'REJECTED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT pay_configuration_resource_type_valid
        CHECK (resource_type IN ('ELEMENT', 'FORMULA')),
    CONSTRAINT pay_configuration_receipt_digest_shape
        CHECK (request_digest ~ '^[0-9a-f]{64}$'
            AND population_scope_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT pay_configuration_receipt_context_present
        CHECK (btrim(correlation_id) <> ''
            AND btrim(field_policy_revision) <> ''
            AND btrim(purpose_code) <> ''
            AND btrim(authorization_revision) <> '')
);

CREATE INDEX pay_configuration_receipt_resource_idx
    ON pay_configuration_command_receipts
        (tenant_id, resource_type, resource_id, result_version);

CREATE TABLE pay_configuration_outbox_events (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    receipt_id UUID NOT NULL,
    command_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    schema_version INTEGER NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    event_payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT pay_configuration_event_sequence_positive
        CHECK (tenant_id > 0 AND aggregate_sequence > 0),
    CONSTRAINT pay_configuration_event_type_exact
        CHECK (event_type = 'PayrollConfigurationPublished'
            AND schema_version = 1),
    CONSTRAINT pay_configuration_event_payload_object
        CHECK (jsonb_typeof(event_payload) = 'object'),
    CONSTRAINT pay_configuration_event_minimized
        CHECK ((event_payload -> 'data') ?& ARRAY[
            'configurationId', 'version', 'effectiveFrom', 'validationDigest']
            AND NOT ((event_payload -> 'data') ?| ARRAY[
                'ast', 'expression', 'formula', 'inputs', 'result'])),
    CONSTRAINT pay_configuration_event_tenant_receipt_unique
        UNIQUE (tenant_id, receipt_id),
    CONSTRAINT pay_configuration_event_tenant_aggregate_sequence_unique
        UNIQUE (tenant_id, aggregate_id, aggregate_sequence)
);

CREATE INDEX pay_configuration_outbox_order_idx
    ON pay_configuration_outbox_events
        (tenant_id, occurred_at, event_id);

REVOKE ALL ON TABLE pay_element_versions FROM PUBLIC;
REVOKE ALL ON TABLE pay_formula_versions FROM PUBLIC;
REVOKE ALL ON TABLE pay_configuration_command_receipts FROM PUBLIC;
REVOKE ALL ON TABLE pay_configuration_outbox_events FROM PUBLIC;

ALTER TABLE pay_element_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_element_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_formula_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_formula_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_configuration_command_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_configuration_command_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_configuration_outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_configuration_outbox_events FORCE ROW LEVEL SECURITY;

CREATE POLICY pay_element_version_tenant_policy
    ON pay_element_versions
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

CREATE POLICY pay_formula_version_tenant_policy
    ON pay_formula_versions
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

CREATE POLICY pay_configuration_receipt_tenant_policy
    ON pay_configuration_command_receipts
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

CREATE POLICY pay_configuration_outbox_tenant_policy
    ON pay_configuration_outbox_events
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

DO $pay_configuration_runtime_access$
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
        RAISE EXCEPTION 'Payroll runtime role % has unsafe PostgreSQL attributes',
            runtime_role_name;
    END IF;

    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE pay_element_versions TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE pay_formula_versions TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE pay_configuration_command_receipts TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE pay_configuration_outbox_events TO %I',
        runtime_role_name);
END
$pay_configuration_runtime_access$;

COMMENT ON TABLE pay_element_versions IS
    'Immutable tenant-scoped payroll element versions for BASE-TFR-PAY-006.';
COMMENT ON TABLE pay_formula_versions IS
    'Immutable typed PAY_RULE_AST_V1 formula lifecycle versions; source strings are forbidden.';
COMMENT ON TABLE pay_configuration_command_receipts IS
    'Durable tenant and command-type scoped idempotency receipts for PAY-006.';
COMMENT ON TABLE pay_configuration_outbox_events IS
    'Minimized PayrollConfigurationPublished.v1 events emitted only by successful publish.';
