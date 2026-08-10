CREATE TABLE prv_operators (
    provider_operator_id BIGSERIAL PRIMARY KEY,
    auth_tenant_id BIGINT NOT NULL,
    auth_user_id BIGINT NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    role_code VARCHAR(50) NOT NULL DEFAULT 'PROVIDER_ADMIN',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_operators_identity UNIQUE (auth_tenant_id, auth_user_id),
    CONSTRAINT ck_prv_operators_state CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE TABLE prv_tenants (
    provider_tenant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_key VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(240) NOT NULL,
    service_tier VARCHAR(30) NOT NULL,
    data_region VARCHAR(40) NOT NULL,
    isolation_model VARCHAR(20) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PROVISIONING',
    onboarding_state VARCHAR(30) NOT NULL DEFAULT 'PREVIEWED',
    auth_tenant_id BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_prv_tenants_tier CHECK (service_tier IN ('STANDARD', 'ENTERPRISE', 'REGULATED')),
    CONSTRAINT ck_prv_tenants_isolation CHECK (isolation_model IN ('POOL', 'BRIDGE', 'SILO')),
    CONSTRAINT ck_prv_tenants_state CHECK (lifecycle_state IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_prv_tenants_onboarding CHECK (
        onboarding_state IN (
            'PREVIEWED', 'CONTROL_PLANE_READY', 'PENDING_EXTERNAL',
            'READY', 'FAILED', 'CANCELLED'
        )
    )
);

CREATE TABLE prv_entitlement_catalog (
    entitlement_id BIGSERIAL PRIMARY KEY,
    entitlement_key VARCHAR(120) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    entitlement_type VARCHAR(20) NOT NULL,
    description VARCHAR(1000),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_entitlement_type CHECK (entitlement_type IN ('APP', 'CAPABILITY', 'LIMIT')),
    CONSTRAINT ck_prv_entitlement_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE prv_tenant_entitlements (
    tenant_entitlement_id BIGSERIAL PRIMARY KEY,
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    entitlement_id BIGINT NOT NULL REFERENCES prv_entitlement_catalog(entitlement_id),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_prv_tenant_entitlements UNIQUE (provider_tenant_id, entitlement_id),
    CONSTRAINT ck_prv_tenant_entitlements_state CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE TABLE prv_operations (
    operation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_tenant_id UUID REFERENCES prv_tenants(provider_tenant_id),
    operation_type VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PREVIEWED',
    risk_tier VARCHAR(20) NOT NULL,
    requested_by BIGINT NOT NULL,
    justification VARCHAR(1000) NOT NULL,
    plan_hash CHAR(64) NOT NULL,
    plan JSONB NOT NULL,
    failure_code VARCHAR(80),
    failure_message VARCHAR(1000),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_operations_type CHECK (operation_type IN ('TENANT_ONBOARD', 'TENANT_SUSPEND', 'TENANT_ACTIVATE', 'ENTITLEMENT_CHANGE')),
    CONSTRAINT ck_prv_operations_state CHECK (lifecycle_state IN ('PREVIEWED', 'EXECUTING', 'PARTIAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_prv_operations_risk CHECK (risk_tier IN ('L1', 'L2', 'L3'))
);

CREATE TABLE prv_operation_steps (
    operation_step_id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES prv_operations(operation_id),
    step_order INTEGER NOT NULL,
    step_key VARCHAR(80) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    target_service VARCHAR(80) NOT NULL,
    external_reference VARCHAR(255),
    redacted_result JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_prv_operation_steps UNIQUE (operation_id, step_order),
    CONSTRAINT ck_prv_operation_steps_state CHECK (lifecycle_state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'PENDING_EXTERNAL', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_prv_operation_steps_order CHECK (step_order > 0)
);

CREATE TABLE prv_audit_events (
    audit_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id BIGINT NOT NULL,
    action VARCHAR(120) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(160) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    correlation_id VARCHAR(128),
    redacted_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'))
);

CREATE INDEX idx_prv_tenants_state ON prv_tenants(lifecycle_state, onboarding_state, tenant_key);
CREATE INDEX idx_prv_operations_time ON prv_operations(created_at DESC);
CREATE INDEX idx_prv_audit_time ON prv_audit_events(occurred_at DESC);

INSERT INTO prv_operators (auth_tenant_id, auth_user_id, display_name)
VALUES (1, 1, 'Local provider administrator');

INSERT INTO prv_entitlement_catalog (entitlement_key, name, entitlement_type, description)
VALUES
    ('core.workspace', 'Core workspace', 'APP', 'Personal home and governed application shell'),
    ('core.people', 'People directory', 'APP', 'Workforce projection and people directory'),
    ('core.control-center', 'Tenant control center', 'APP', 'Tenant administration capabilities'),
    ('ai.agent-runtime', 'AI agent runtime', 'CAPABILITY', 'Governed AI agent plan and execution capability');

INSERT INTO prv_tenants (
    provider_tenant_id, tenant_key, display_name, service_tier, data_region,
    isolation_model, lifecycle_state, onboarding_state, auth_tenant_id, created_by, updated_by)
VALUES (
    '00000000-0000-0000-0000-000000000001', 'default', 'Default Tenant',
    'ENTERPRISE', 'local', 'POOL', 'ACTIVE', 'READY', 1, 1, 1);

INSERT INTO prv_tenant_entitlements (provider_tenant_id, entitlement_id, created_by, updated_by)
SELECT '00000000-0000-0000-0000-000000000001', entitlement_id, 1, 1
  FROM prv_entitlement_catalog;
