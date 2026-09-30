-- Tenant capability overrides are restrictive suppressions only. They cannot add a permission,
-- replace a capability code, or bypass the immutable active authorization bundle. High-risk
-- contracts remain owner locked. Lower-risk contracts may be disabled temporarily after an
-- exact app-scope, three-person workflow. Runtime Auth consumes the effective ACTIVE winner.

CREATE TABLE sys_product_app_resource_mapping (
    product_key VARCHAR(80) PRIMARY KEY,
    app_resource_key VARCHAR(255) NOT NULL
        REFERENCES sys_tenant_resource_templates(resource_key),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uk_product_app_resource_mapping UNIQUE (product_key, app_resource_key),
    CONSTRAINT ck_product_app_mapping_product CHECK (
        product_key = LOWER(BTRIM(product_key))
        AND product_key ~ '^[a-z][a-z0-9-]{1,79}$'),
    CONSTRAINT ck_product_app_mapping_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO sys_product_app_resource_mapping (product_key, app_resource_key)
VALUES
    ('approvals', 'APP.APPROVALS'),
    ('calendar', 'APP.CALENDAR'),
    ('communications', 'APP.COMMUNICATIONS'),
    ('dwaion', 'APP.ASK'),
    ('hcm', 'APP.HCM'),
    ('mail', 'APP.MAIL'),
    ('meetings', 'APP.MEETINGS'),
    ('messaging', 'APP.MESSAGING'),
    ('notifications', 'APP.NOTIFICATIONS'),
    ('services', 'APP.EMPLOYEE_SERVICES'),
    ('spaces', 'APP.SPACES'),
    ('workplace', 'APP.WORKPLACE');

CREATE TABLE sys_tenant_capability_override_rules (
    rule_key VARCHAR(100) NOT NULL,
    rule_version BIGINT NOT NULL,
    priority INTEGER NOT NULL,
    risk_tier VARCHAR(20),
    override_mode VARCHAR(24) NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    requires_approval BOOLEAN NOT NULL DEFAULT TRUE,
    max_duration_days INTEGER,
    owner_key VARCHAR(120) NOT NULL,
    reason_code VARCHAR(120) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rule_key, rule_version),
    CONSTRAINT ck_tenant_capability_rule_risk CHECK (
        risk_tier IS NULL OR risk_tier IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_tenant_capability_rule_mode CHECK (
        override_mode IN ('OWNER_LOCKED', 'ALLOW_DISABLE')),
    CONSTRAINT ck_tenant_capability_rule_scope CHECK (scope_type = 'TENANT'),
    CONSTRAINT ck_tenant_capability_rule_duration CHECK (
        (override_mode = 'OWNER_LOCKED' AND max_duration_days IS NULL)
        OR (override_mode = 'ALLOW_DISABLE' AND max_duration_days BETWEEN 1 AND 365)),
    CONSTRAINT ck_tenant_capability_rule_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO sys_tenant_capability_override_rules (
    rule_key, rule_version, priority, risk_tier, override_mode, max_duration_days,
    owner_key, reason_code)
VALUES
    ('high-risk-owner-lock', 1, 100, 'HIGH', 'OWNER_LOCKED', NULL,
     'AUTH_PRODUCT_AUTHORIZATION_CATALOG', 'HIGH_RISK_CAPABILITY_OWNER_LOCKED'),
    ('critical-risk-owner-lock', 1, 110, 'CRITICAL', 'OWNER_LOCKED', NULL,
     'AUTH_PRODUCT_AUTHORIZATION_CATALOG', 'CRITICAL_CAPABILITY_OWNER_LOCKED'),
    ('tenant-restrictive-disable', 1, 0, NULL, 'ALLOW_DISABLE', 90,
     'AUTH_TENANT_CAPABILITY_OVERRIDE', 'TENANT_MAY_ONLY_RESTRICT_CAPABILITY');

CREATE OR REPLACE FUNCTION dwp_reject_tenant_capability_rule_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'tenant capability override rules are immutable; publish a new version';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_capability_rule_immutable
    BEFORE UPDATE OR DELETE ON sys_tenant_capability_override_rules
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_tenant_capability_rule_mutation();

CREATE TABLE com_tenant_capability_override_changes (
    override_change_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    contract_key VARCHAR(180) NOT NULL,
    product_key VARCHAR(80) NOT NULL,
    app_resource_key VARCHAR(255) NOT NULL,
    policy_rule_key VARCHAR(100) NOT NULL,
    policy_rule_version BIGINT NOT NULL,
    base_bundle_id UUID NOT NULL,
    base_active_revision BIGINT NOT NULL,
    desired_state VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    valid_to TIMESTAMPTZ,
    justification VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL,
    submitted_at TIMESTAMPTZ,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    activated_by BIGINT,
    activated_at TIMESTAMPTZ,
    activation_receipt_id UUID,
    revoked_by BIGINT,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tenant_capability_change_mapping
        FOREIGN KEY (product_key, app_resource_key)
        REFERENCES sys_product_app_resource_mapping(product_key, app_resource_key),
    CONSTRAINT fk_tenant_capability_change_rule
        FOREIGN KEY (policy_rule_key, policy_rule_version)
        REFERENCES sys_tenant_capability_override_rules(rule_key, rule_version),
    CONSTRAINT ck_tenant_capability_change_desired CHECK (
        desired_state IN ('DISABLED', 'INHERIT')),
    CONSTRAINT ck_tenant_capability_change_state CHECK (
        lifecycle_state IN (
            'DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED',
            'ACTIVE', 'REVOKED', 'SUPERSEDED')),
    CONSTRAINT ck_tenant_capability_change_window CHECK (
        (desired_state = 'DISABLED' AND valid_to IS NOT NULL)
        OR (desired_state = 'INHERIT' AND valid_to IS NULL)),
    CONSTRAINT ck_tenant_capability_change_justification CHECK (
        length(BTRIM(justification)) BETWEEN 10 AND 1000),
    CONSTRAINT ck_tenant_capability_change_review CHECK (
        (lifecycle_state IN ('APPROVED', 'ACTIVE')
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND approved_by <> requested_by)
        OR lifecycle_state NOT IN ('APPROVED', 'ACTIVE')),
    CONSTRAINT ck_tenant_capability_change_activation CHECK (
        (lifecycle_state = 'ACTIVE'
            AND activated_by IS NOT NULL AND activated_at IS NOT NULL
            AND activation_receipt_id IS NOT NULL
            AND activated_by <> requested_by AND activated_by <> approved_by)
        OR lifecycle_state <> 'ACTIVE'),
    CONSTRAINT ck_tenant_capability_change_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_tenant_capability_override_open
    ON com_tenant_capability_override_changes (tenant_id, contract_key)
    WHERE lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED');
CREATE UNIQUE INDEX uk_tenant_capability_override_active
    ON com_tenant_capability_override_changes (tenant_id, contract_key)
    WHERE lifecycle_state = 'ACTIVE';
CREATE INDEX idx_tenant_capability_override_effective
    ON com_tenant_capability_override_changes (
        tenant_id, contract_key, lifecycle_state, activated_at DESC);

CREATE TABLE com_tenant_capability_override_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    override_change_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_id BIGINT NOT NULL,
    correlation_id VARCHAR(160),
    resulting_version BIGINT NOT NULL,
    receipt_id UUID,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tenant_capability_override_event_evidence CHECK (
        jsonb_typeof(evidence) = 'object'),
    UNIQUE (override_change_id, resulting_version, event_type)
);

CREATE OR REPLACE FUNCTION dwp_reject_tenant_capability_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'tenant capability override events are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_capability_event_immutable
    BEFORE UPDATE OR DELETE ON com_tenant_capability_override_events
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_tenant_capability_event_mutation();

COMMENT ON TABLE sys_tenant_capability_override_rules IS
    'Immutable typed manifest for owner lock versus tenant restrictive disable.';
COMMENT ON TABLE com_tenant_capability_override_changes IS
    'Versioned DISABLED or INHERIT tenant exception workflow; never grants authority.';
