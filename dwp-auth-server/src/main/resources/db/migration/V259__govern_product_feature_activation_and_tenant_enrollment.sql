-- Product feature activation is a positive, default-off control plane. It is deliberately
-- independent from tenant capability overrides, which can only suppress existing authority.
-- This migration publishes an immutable catalog and workflow substrate only: it does not
-- activate a feature, enroll a tenant, grant a permission, or change customer data.

CREATE TABLE sys_product_feature_activation_policies (
    feature_key VARCHAR(120) NOT NULL,
    policy_version BIGINT NOT NULL,
    product_key VARCHAR(80) NOT NULL,
    app_resource_key VARCHAR(255) NOT NULL,
    minimum_bundle_version BIGINT NOT NULL,
    baseline_state VARCHAR(16) NOT NULL DEFAULT 'OFF',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    owner_key VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (feature_key, policy_version),
    CONSTRAINT uk_product_feature_policy_tuple UNIQUE (
        feature_key, policy_version, product_key, app_resource_key,
        minimum_bundle_version),
    CONSTRAINT fk_product_feature_policy_app
        FOREIGN KEY (product_key, app_resource_key)
        REFERENCES sys_product_app_resource_mapping(product_key, app_resource_key),
    CONSTRAINT ck_product_feature_policy_key CHECK (
        feature_key = UPPER(BTRIM(feature_key))
        AND feature_key ~ '^[A-Z][A-Z0-9_]{2,119}$'),
    CONSTRAINT ck_product_feature_policy_product CHECK (
        product_key = LOWER(BTRIM(product_key))
        AND product_key ~ '^[a-z][a-z0-9-]{1,79}$'),
    CONSTRAINT ck_product_feature_policy_app CHECK (
        app_resource_key = UPPER(BTRIM(app_resource_key))
        AND app_resource_key ~ '^APP\.[A-Z][A-Z0-9_.-]{1,250}$'),
    CONSTRAINT ck_product_feature_policy_bundle CHECK (minimum_bundle_version > 0),
    CONSTRAINT ck_product_feature_policy_baseline CHECK (baseline_state = 'OFF'),
    CONSTRAINT ck_product_feature_policy_state CHECK (lifecycle_state = 'PUBLISHED'),
    CONSTRAINT ck_product_feature_policy_version CHECK (policy_version > 0)
);

CREATE TABLE sys_product_feature_route_owners (
    route_contract_key VARCHAR(240) PRIMARY KEY,
    feature_key VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_feature_route_owner_tuple UNIQUE (
        route_contract_key, feature_key),
    CONSTRAINT ck_product_feature_route_owner_key CHECK (
        route_contract_key = LOWER(BTRIM(route_contract_key))
        AND route_contract_key ~ '^route\.[a-z][a-z0-9.-]{8,230}$'),
    CONSTRAINT ck_product_feature_route_owner_feature CHECK (
        feature_key = UPPER(BTRIM(feature_key))
        AND feature_key ~ '^[A-Z][A-Z0-9_]{2,119}$')
);

CREATE TABLE sys_product_feature_route_bindings (
    route_contract_key VARCHAR(240) NOT NULL,
    feature_key VARCHAR(120) NOT NULL,
    policy_version BIGINT NOT NULL,
    product_key VARCHAR(80) NOT NULL,
    app_resource_key VARCHAR(255) NOT NULL,
    minimum_bundle_version BIGINT NOT NULL,
    route_kind VARCHAR(16) NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    surface_key VARCHAR(160) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (route_contract_key, feature_key, policy_version),
    CONSTRAINT fk_product_feature_route_owner
        FOREIGN KEY (route_contract_key, feature_key)
        REFERENCES sys_product_feature_route_owners(route_contract_key, feature_key),
    CONSTRAINT fk_product_feature_route_policy
        FOREIGN KEY (
            feature_key, policy_version, product_key, app_resource_key,
            minimum_bundle_version)
        REFERENCES sys_product_feature_activation_policies(
            feature_key, policy_version, product_key, app_resource_key,
            minimum_bundle_version),
    CONSTRAINT ck_product_feature_route_key CHECK (
        route_contract_key = LOWER(BTRIM(route_contract_key))
        AND route_contract_key ~ '^route\.[a-z][a-z0-9.-]{8,230}$'),
    CONSTRAINT ck_product_feature_route_product CHECK (product_key = 'hcm'),
    CONSTRAINT ck_product_feature_route_app CHECK (app_resource_key = 'APP.HCM'),
    CONSTRAINT ck_product_feature_route_bundle CHECK (minimum_bundle_version = 50),
    CONSTRAINT ck_product_feature_route_kind CHECK (route_kind IN ('PAGE', 'DATA', 'ACTION')),
    CONSTRAINT ck_product_feature_route_kind_suffix CHECK (
        (route_kind = 'PAGE' AND route_contract_key LIKE '%.page')
        OR (route_kind = 'DATA' AND route_contract_key LIKE '%.data')
        OR (route_kind = 'ACTION' AND route_contract_key LIKE '%.action')),
    CONSTRAINT ck_product_feature_route_subject CHECK (subject_type = 'PRODUCT'),
    CONSTRAINT ck_product_feature_route_surface CHECK (
        surface_key IN ('hcm.operations', 'hcm.management')),
    CONSTRAINT ck_product_feature_route_state CHECK (
        lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE OR REPLACE FUNCTION dwp_reject_product_feature_catalog_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'product feature policy and route catalog rows are immutable; publish a versioned successor';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_feature_policy_immutable
    BEFORE UPDATE OR DELETE ON sys_product_feature_activation_policies
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_product_feature_catalog_mutation();

CREATE TRIGGER trg_product_feature_route_owner_immutable
    BEFORE UPDATE OR DELETE ON sys_product_feature_route_owners
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_product_feature_catalog_mutation();

CREATE TRIGGER trg_product_feature_route_immutable
    BEFORE UPDATE OR DELETE ON sys_product_feature_route_bindings
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_product_feature_catalog_mutation();

CREATE TABLE auth_product_feature_activation_changes (
    activation_change_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_key VARCHAR(120) NOT NULL,
    policy_version BIGINT NOT NULL,
    target_bundle_version BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    justification VARCHAR(1000) NOT NULL,
    requested_by VARCHAR(160) NOT NULL,
    submitted_at TIMESTAMPTZ,
    approved_by VARCHAR(160),
    approved_at TIMESTAMPTZ,
    activated_by VARCHAR(160),
    activated_at TIMESTAMPTZ,
    activation_receipt_id UUID,
    revoked_by VARCHAR(160),
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(1000),
    superseded_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_feature_activation_policy
        FOREIGN KEY (feature_key, policy_version)
        REFERENCES sys_product_feature_activation_policies(feature_key, policy_version),
    CONSTRAINT ck_product_feature_activation_bundle CHECK (target_bundle_version >= 50),
    CONSTRAINT ck_product_feature_activation_state CHECK (
        lifecycle_state IN (
            'DRAFT', 'IN_REVIEW', 'APPROVED', 'ACTIVE', 'REVOKED', 'SUPERSEDED')),
    CONSTRAINT ck_product_feature_activation_justification CHECK (
        length(BTRIM(justification)) BETWEEN 10 AND 1000),
    CONSTRAINT ck_product_feature_activation_requester CHECK (
        requested_by = LOWER(BTRIM(requested_by))
        AND length(requested_by) BETWEEN 1 AND 160),
    CONSTRAINT ck_product_feature_activation_actor_identity CHECK (
        (approved_by IS NULL OR approved_by = LOWER(BTRIM(approved_by)))
        AND (activated_by IS NULL OR activated_by = LOWER(BTRIM(activated_by)))
        AND (revoked_by IS NULL OR revoked_by = LOWER(BTRIM(revoked_by)))),
    CONSTRAINT ck_product_feature_activation_review CHECK (
        lifecycle_state NOT IN ('APPROVED', 'ACTIVE', 'REVOKED', 'SUPERSEDED')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND approved_by <> requested_by)),
    CONSTRAINT ck_product_feature_activation_active CHECK (
        lifecycle_state NOT IN ('ACTIVE', 'REVOKED', 'SUPERSEDED')
        OR (activated_by IS NOT NULL AND activated_at IS NOT NULL
            AND activation_receipt_id IS NOT NULL
            AND activated_by <> requested_by AND activated_by <> approved_by)),
    CONSTRAINT ck_product_feature_activation_revoked CHECK (
        lifecycle_state <> 'REVOKED'
        OR (revoked_by IS NOT NULL AND revoked_at IS NOT NULL
            AND length(BTRIM(revocation_reason)) BETWEEN 10 AND 1000)),
    CONSTRAINT ck_product_feature_activation_superseded CHECK (
        lifecycle_state <> 'SUPERSEDED' OR superseded_by IS NOT NULL),
    CONSTRAINT ck_product_feature_activation_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_product_feature_activation_open
    ON auth_product_feature_activation_changes (feature_key)
    WHERE lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED');
CREATE UNIQUE INDEX uk_product_feature_activation_active
    ON auth_product_feature_activation_changes (feature_key)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE com_tenant_feature_enrollment_changes (
    enrollment_change_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    feature_key VARCHAR(120) NOT NULL,
    policy_version BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    justification VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL,
    submitted_at TIMESTAMPTZ,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    activated_by BIGINT,
    activated_at TIMESTAMPTZ,
    activation_receipt_id UUID,
    revoked_by BIGINT,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(1000),
    superseded_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tenant_feature_enrollment_policy
        FOREIGN KEY (feature_key, policy_version)
        REFERENCES sys_product_feature_activation_policies(feature_key, policy_version),
    CONSTRAINT ck_tenant_feature_enrollment_state CHECK (
        lifecycle_state IN (
            'DRAFT', 'IN_REVIEW', 'APPROVED', 'ACTIVE', 'REVOKED', 'SUPERSEDED')),
    CONSTRAINT ck_tenant_feature_enrollment_justification CHECK (
        length(BTRIM(justification)) BETWEEN 10 AND 1000),
    CONSTRAINT ck_tenant_feature_enrollment_review CHECK (
        lifecycle_state NOT IN ('APPROVED', 'ACTIVE', 'REVOKED', 'SUPERSEDED')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND approved_by <> requested_by)),
    CONSTRAINT ck_tenant_feature_enrollment_active CHECK (
        lifecycle_state NOT IN ('ACTIVE', 'REVOKED', 'SUPERSEDED')
        OR (activated_by IS NOT NULL AND activated_at IS NOT NULL
            AND activation_receipt_id IS NOT NULL
            AND activated_by <> requested_by AND activated_by <> approved_by)),
    CONSTRAINT ck_tenant_feature_enrollment_revoked CHECK (
        lifecycle_state <> 'REVOKED'
        OR (revoked_by IS NOT NULL AND revoked_at IS NOT NULL
            AND length(BTRIM(revocation_reason)) BETWEEN 10 AND 1000)),
    CONSTRAINT ck_tenant_feature_enrollment_superseded CHECK (
        lifecycle_state <> 'SUPERSEDED' OR superseded_by IS NOT NULL),
    CONSTRAINT ck_tenant_feature_enrollment_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_tenant_feature_enrollment_open
    ON com_tenant_feature_enrollment_changes (tenant_id, feature_key)
    WHERE lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED');
CREATE UNIQUE INDEX uk_tenant_feature_enrollment_active
    ON com_tenant_feature_enrollment_changes (tenant_id, feature_key)
    WHERE lifecycle_state = 'ACTIVE';

CREATE OR REPLACE FUNCTION dwp_validate_active_tenant_feature_enrollment()
RETURNS TRIGGER AS $$
DECLARE
    expected_product VARCHAR(80);
    expected_app VARCHAR(255);
BEGIN
    IF NEW.lifecycle_state <> 'ACTIVE' THEN
        RETURN NEW;
    END IF;

    SELECT product_key, app_resource_key
      INTO expected_product, expected_app
      FROM sys_product_feature_activation_policies
     WHERE feature_key = NEW.feature_key
       AND policy_version = NEW.policy_version
       AND lifecycle_state = 'PUBLISHED'
     ORDER BY policy_version DESC
     LIMIT 1;

    IF NOT EXISTS (
        SELECT 1 FROM com_tenant_app_installations installation
         WHERE installation.tenant_id = NEW.tenant_id
           AND installation.product_key = expected_product
           AND installation.app_resource_key = expected_app
           AND installation.lifecycle_state = 'ENABLED') THEN
        RAISE EXCEPTION 'tenant application adoption must be enabled before feature enrollment';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM auth_product_feature_activation_changes activation
         WHERE activation.feature_key = NEW.feature_key
           AND activation.policy_version = NEW.policy_version
           AND activation.lifecycle_state = 'ACTIVE') THEN
        RAISE EXCEPTION 'global feature activation must be active before tenant enrollment';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_feature_enrollment_prerequisites
    BEFORE INSERT OR UPDATE ON com_tenant_feature_enrollment_changes
    FOR EACH ROW EXECUTE FUNCTION dwp_validate_active_tenant_feature_enrollment();

CREATE TABLE auth_product_feature_control_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(24) NOT NULL,
    aggregate_id UUID NOT NULL,
    tenant_id BIGINT,
    feature_key VARCHAR(120) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_ref VARCHAR(160) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    resulting_version BIGINT NOT NULL,
    receipt_id UUID,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_product_feature_event_aggregate CHECK (
        aggregate_type IN ('GLOBAL_ACTIVATION', 'TENANT_ENROLLMENT')),
    CONSTRAINT ck_product_feature_event_tenant CHECK (
        (aggregate_type = 'GLOBAL_ACTIVATION' AND tenant_id IS NULL)
        OR (aggregate_type = 'TENANT_ENROLLMENT' AND tenant_id IS NOT NULL)),
    CONSTRAINT ck_product_feature_event_actor CHECK (
        actor_ref = BTRIM(actor_ref) AND length(actor_ref) BETWEEN 1 AND 160),
    CONSTRAINT ck_product_feature_event_correlation CHECK (
        correlation_id = BTRIM(correlation_id)
        AND length(correlation_id) BETWEEN 1 AND 160),
    CONSTRAINT ck_product_feature_event_version CHECK (resulting_version >= 0),
    CONSTRAINT ck_product_feature_event_evidence CHECK (jsonb_typeof(evidence) = 'object'),
    UNIQUE (aggregate_type, aggregate_id, resulting_version, event_type)
);

CREATE OR REPLACE FUNCTION dwp_reject_product_feature_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'product feature control events are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_feature_event_immutable
    BEFORE UPDATE OR DELETE ON auth_product_feature_control_events
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_product_feature_event_mutation();

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_ORGANIZATION_POSITIONS', 1, 'hcm', 'APP.HCM', 50, 'OFF', 'HRM'),
    ('HRIS_OPERATIONS_EXCEPTION_CENTER', 1, 'hcm', 'APP.HCM', 50, 'OFF', 'SYS'),
    ('HRIS_PAYROLL_RUN_PREPARE', 1, 'hcm', 'APP.HCM', 50, 'OFF', 'PAY'),
    ('HRIS_TIME_CLOSE_HANDOFF', 1, 'hcm', 'APP.HCM', 50, 'OFF', 'TIM');

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.operations.organization-positions.page', 'HRIS_ORGANIZATION_POSITIONS'),
    ('route.hcm.operations.organization-graph.data', 'HRIS_ORGANIZATION_POSITIONS'),
    ('route.hcm.configuration.settings-operations.page', 'HRIS_OPERATIONS_EXCEPTION_CENTER'),
    ('route.hcm.operations.exception.data', 'HRIS_OPERATIONS_EXCEPTION_CENTER'),
    ('route.hcm.operations.exception-detail.data', 'HRIS_OPERATIONS_EXCEPTION_CENTER'),
    ('route.hcm.operations.payroll-population.page', 'HRIS_PAYROLL_RUN_PREPARE'),
    ('route.hcm.operations.payroll-support.data', 'HRIS_PAYROLL_RUN_PREPARE'),
    ('route.hcm.operations.payroll-run.action', 'HRIS_PAYROLL_RUN_PREPARE'),
    ('route.hcm.operations.time-command-center.page', 'HRIS_TIME_CLOSE_HANDOFF'),
    ('route.hcm.operations.time-daily-close.page', 'HRIS_TIME_CLOSE_HANDOFF'),
    ('route.hcm.operations.time-monthly-close.page', 'HRIS_TIME_CLOSE_HANDOFF'),
    ('route.hcm.operations.time-close-validate.action', 'HRIS_TIME_CLOSE_HANDOFF'),
    ('route.hcm.operations.time-close.action', 'HRIS_TIME_CLOSE_HANDOFF'),
    ('route.hcm.operations.time-reopen.action', 'HRIS_TIME_CLOSE_HANDOFF'),
    ('route.hcm.operations.time-payroll-handoff-create.action', 'HRIS_TIME_CLOSE_HANDOFF'),
    ('route.hcm.operations.time-payroll-handoff.data', 'HRIS_TIME_CLOSE_HANDOFF');

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.operations.organization-positions.page', 'HRIS_ORGANIZATION_POSITIONS', 1, 'hcm', 'APP.HCM', 50, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.organization-graph.data', 'HRIS_ORGANIZATION_POSITIONS', 1, 'hcm', 'APP.HCM', 50, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.configuration.settings-operations.page', 'HRIS_OPERATIONS_EXCEPTION_CENTER', 1, 'hcm', 'APP.HCM', 50, 'PAGE', 'PRODUCT', 'hcm.management'),
    ('route.hcm.operations.exception.data', 'HRIS_OPERATIONS_EXCEPTION_CENTER', 1, 'hcm', 'APP.HCM', 50, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.exception-detail.data', 'HRIS_OPERATIONS_EXCEPTION_CENTER', 1, 'hcm', 'APP.HCM', 50, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.payroll-population.page', 'HRIS_PAYROLL_RUN_PREPARE', 1, 'hcm', 'APP.HCM', 50, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.payroll-support.data', 'HRIS_PAYROLL_RUN_PREPARE', 1, 'hcm', 'APP.HCM', 50, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.payroll-run.action', 'HRIS_PAYROLL_RUN_PREPARE', 1, 'hcm', 'APP.HCM', 50, 'ACTION', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.time-command-center.page', 'HRIS_TIME_CLOSE_HANDOFF', 1, 'hcm', 'APP.HCM', 50, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.time-daily-close.page', 'HRIS_TIME_CLOSE_HANDOFF', 1, 'hcm', 'APP.HCM', 50, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.time-monthly-close.page', 'HRIS_TIME_CLOSE_HANDOFF', 1, 'hcm', 'APP.HCM', 50, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.time-close-validate.action', 'HRIS_TIME_CLOSE_HANDOFF', 1, 'hcm', 'APP.HCM', 50, 'ACTION', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.time-close.action', 'HRIS_TIME_CLOSE_HANDOFF', 1, 'hcm', 'APP.HCM', 50, 'ACTION', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.time-reopen.action', 'HRIS_TIME_CLOSE_HANDOFF', 1, 'hcm', 'APP.HCM', 50, 'ACTION', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.time-payroll-handoff-create.action', 'HRIS_TIME_CLOSE_HANDOFF', 1, 'hcm', 'APP.HCM', 50, 'ACTION', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.time-payroll-handoff.data', 'HRIS_TIME_CLOSE_HANDOFF', 1, 'hcm', 'APP.HCM', 50, 'DATA', 'PRODUCT', 'hcm.operations');

COMMENT ON TABLE sys_product_feature_activation_policies IS
    'Immutable default-OFF feature catalog. Highest published version is current; rollback appends another version.';
COMMENT ON TABLE sys_product_feature_route_owners IS
    'Immutable global route ownership; one exact route can belong to only one feature across all versions.';
COMMENT ON TABLE sys_product_feature_route_bindings IS
    'Immutable per-policy route snapshots; removals append an explicit RETIRED tombstone.';
COMMENT ON TABLE auth_product_feature_activation_changes IS
    'Three-person, CAS-controlled global feature activation workflow; no rows are seeded.';
COMMENT ON TABLE com_tenant_feature_enrollment_changes IS
    'Three-person, CAS-controlled tenant enrollment workflow; no rows are seeded.';
COMMENT ON TABLE auth_product_feature_control_events IS
    'Append-only immutable receipts for global activation and tenant enrollment commands.';
