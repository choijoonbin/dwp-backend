CREATE TABLE prv_organizations (
    organization_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_key VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(240) NOT NULL,
    legal_name VARCHAR(320),
    customer_reference VARCHAR(120),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    schema_version INTEGER NOT NULL DEFAULT 1,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_prv_organizations_customer_reference UNIQUE (customer_reference),
    CONSTRAINT ck_prv_organizations_state
        CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_prv_organizations_schema CHECK (schema_version > 0),
    CONSTRAINT ck_prv_organizations_attributes_object
        CHECK (jsonb_typeof(attributes) = 'object')
);

INSERT INTO prv_organizations (
    organization_id, organization_key, display_name, lifecycle_state,
    created_at, created_by, updated_at, updated_by)
SELECT
    provider_tenant_id, tenant_key, display_name,
    CASE
        WHEN lifecycle_state = 'RETIRED' THEN 'CLOSED'
        WHEN lifecycle_state = 'SUSPENDED' THEN 'SUSPENDED'
        ELSE 'ACTIVE'
    END,
    created_at, created_by, updated_at, updated_by
FROM prv_tenants;

ALTER TABLE prv_tenants
    ADD COLUMN organization_id UUID,
    ADD COLUMN environment_key VARCHAR(32) NOT NULL DEFAULT 'production',
    ADD COLUMN default_locale VARCHAR(35) NOT NULL DEFAULT 'ko',
    ADD COLUMN time_zone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
    ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN configuration JSONB NOT NULL DEFAULT '{}'::jsonb;

UPDATE prv_tenants
SET organization_id = provider_tenant_id;

ALTER TABLE prv_tenants
    ALTER COLUMN organization_id SET NOT NULL,
    ADD CONSTRAINT fk_prv_tenants_organization
        FOREIGN KEY (organization_id) REFERENCES prv_organizations(organization_id),
    ADD CONSTRAINT uk_prv_tenants_organization_environment
        UNIQUE (organization_id, environment_key),
    ADD CONSTRAINT ck_prv_tenants_environment
        CHECK (environment_key ~ '^[a-z][a-z0-9-]{1,31}$'),
    ADD CONSTRAINT ck_prv_tenants_schema CHECK (schema_version > 0),
    ADD CONSTRAINT ck_prv_tenants_configuration_object
        CHECK (jsonb_typeof(configuration) = 'object');

CREATE UNIQUE INDEX uk_prv_tenants_auth_tenant
    ON prv_tenants(auth_tenant_id)
    WHERE auth_tenant_id IS NOT NULL;

CREATE TABLE prv_regions (
    region_key VARCHAR(40) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    jurisdiction_code VARCHAR(16),
    residency_class VARCHAR(30) NOT NULL DEFAULT 'STANDARD',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_regions_residency
        CHECK (residency_class IN ('STANDARD', 'RESTRICTED', 'LOCAL_ONLY')),
    CONSTRAINT ck_prv_regions_state CHECK (lifecycle_state IN ('ACTIVE', 'DRAINING', 'RETIRED')),
    CONSTRAINT ck_prv_regions_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);

INSERT INTO prv_regions (region_key, display_name, jurisdiction_code, residency_class)
VALUES
    ('local', 'Local development', 'LOCAL', 'LOCAL_ONLY'),
    ('ap-northeast-2', 'Asia Pacific (Seoul)', 'KR', 'STANDARD')
ON CONFLICT (region_key) DO NOTHING;

INSERT INTO prv_regions (region_key, display_name)
SELECT DISTINCT data_region, data_region
FROM prv_tenants
ON CONFLICT (region_key) DO NOTHING;

ALTER TABLE prv_tenants
    ADD CONSTRAINT fk_prv_tenants_region
        FOREIGN KEY (data_region) REFERENCES prv_regions(region_key);

CREATE TABLE prv_deployment_cells (
    deployment_cell_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cell_key VARCHAR(80) NOT NULL UNIQUE,
    region_key VARCHAR(40) NOT NULL REFERENCES prv_regions(region_key),
    display_name VARCHAR(160) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    supported_isolation_models JSONB NOT NULL DEFAULT '["POOL", "BRIDGE", "SILO"]'::jsonb,
    routing_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_deployment_cells_state
        CHECK (lifecycle_state IN ('ACTIVE', 'DRAINING', 'RETIRED')),
    CONSTRAINT ck_prv_deployment_cells_isolation_array
        CHECK (jsonb_typeof(supported_isolation_models) = 'array'),
    CONSTRAINT ck_prv_deployment_cells_routing_object
        CHECK (jsonb_typeof(routing_metadata) = 'object')
);

INSERT INTO prv_deployment_cells (cell_key, region_key, display_name)
SELECT region_key || '-primary', region_key, display_name || ' primary cell'
FROM prv_regions
ON CONFLICT (cell_key) DO NOTHING;

CREATE TABLE prv_service_catalog (
    service_key VARCHAR(80) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    service_kind VARCHAR(30) NOT NULL,
    criticality VARCHAR(20) NOT NULL,
    provisioning_order INTEGER NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    capability_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_service_catalog_kind
        CHECK (service_kind IN ('CONTROL_PLANE', 'DATA_PLANE', 'STORAGE')),
    CONSTRAINT ck_prv_service_catalog_criticality
        CHECK (criticality IN ('STANDARD', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_prv_service_catalog_order CHECK (provisioning_order > 0),
    CONSTRAINT ck_prv_service_catalog_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_prv_service_catalog_metadata_object
        CHECK (jsonb_typeof(capability_metadata) = 'object')
);

INSERT INTO prv_service_catalog (
    service_key, display_name, service_kind, criticality, provisioning_order)
VALUES
    ('auth', 'Identity and access', 'CONTROL_PLANE', 'CRITICAL', 10),
    ('platform', 'Tenant experience', 'CONTROL_PLANE', 'HIGH', 20),
    ('people', 'Workforce projection', 'DATA_PLANE', 'HIGH', 30),
    ('asset-storage', 'Tenant asset storage', 'STORAGE', 'STANDARD', 40)
ON CONFLICT (service_key) DO NOTHING;

CREATE TABLE prv_tenant_service_instances (
    tenant_service_instance_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    service_key VARCHAR(80) NOT NULL REFERENCES prv_service_catalog(service_key),
    deployment_cell_id UUID REFERENCES prv_deployment_cells(deployment_cell_id),
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PROVISIONING',
    external_resource_id VARCHAR(255),
    endpoint_reference VARCHAR(500),
    applied_schema_version INTEGER,
    configuration_schema_version INTEGER NOT NULL DEFAULT 1,
    configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    health_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_reconciled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_prv_tenant_service_instances UNIQUE (provider_tenant_id, service_key),
    CONSTRAINT ck_prv_tenant_service_instances_state
        CHECK (lifecycle_state IN ('PROVISIONING', 'READY', 'DEGRADED', 'SUSPENDED', 'FAILED', 'RETIRED')),
    CONSTRAINT ck_prv_tenant_service_instances_schema CHECK (configuration_schema_version > 0),
    CONSTRAINT ck_prv_tenant_service_instances_configuration
        CHECK (jsonb_typeof(configuration) = 'object'),
    CONSTRAINT ck_prv_tenant_service_instances_health
        CHECK (jsonb_typeof(health_snapshot) = 'object')
);

INSERT INTO prv_tenant_service_instances (
    provider_tenant_id, service_key, deployment_cell_id, lifecycle_state,
    external_resource_id, applied_schema_version, last_reconciled_at, created_by, updated_by)
SELECT
    tenant.provider_tenant_id,
    service.service_key,
    cell.deployment_cell_id,
    CASE WHEN tenant.onboarding_state = 'READY' THEN 'READY' ELSE 'PROVISIONING' END,
    CASE WHEN service.service_key = 'auth' THEN tenant.auth_tenant_id::text ELSE tenant.tenant_key END,
    1,
    CASE WHEN tenant.onboarding_state = 'READY' THEN CURRENT_TIMESTAMP ELSE NULL END,
    tenant.created_by,
    tenant.updated_by
FROM prv_tenants tenant
CROSS JOIN prv_service_catalog service
JOIN prv_deployment_cells cell
  ON cell.region_key = tenant.data_region
 AND cell.lifecycle_state = 'ACTIVE'
ON CONFLICT (provider_tenant_id, service_key) DO NOTHING;

CREATE TABLE prv_tenant_domains (
    tenant_domain_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    domain_name VARCHAR(253) NOT NULL,
    domain_type VARCHAR(20) NOT NULL DEFAULT 'LOGIN',
    verification_method VARCHAR(20) NOT NULL DEFAULT 'DNS_TXT',
    verification_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verification_token_hash CHAR(64),
    primary_domain BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at TIMESTAMPTZ,
    last_checked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_prv_tenant_domains_name UNIQUE (domain_name),
    CONSTRAINT uk_prv_tenant_domains_tenant_id UNIQUE (provider_tenant_id, tenant_domain_id),
    CONSTRAINT ck_prv_tenant_domains_lowercase CHECK (domain_name = lower(domain_name)),
    CONSTRAINT ck_prv_tenant_domains_type CHECK (domain_type IN ('LOGIN', 'EMAIL', 'CUSTOM')),
    CONSTRAINT ck_prv_tenant_domains_method CHECK (verification_method IN ('DNS_TXT', 'HTTP', 'INTERNAL')),
    CONSTRAINT ck_prv_tenant_domains_state
        CHECK (verification_state IN ('PENDING', 'VERIFIED', 'FAILED', 'REVOKED')),
    CONSTRAINT ck_prv_tenant_domains_verified
        CHECK ((verification_state = 'VERIFIED' AND verified_at IS NOT NULL) OR verification_state <> 'VERIFIED')
);

CREATE UNIQUE INDEX uk_prv_tenant_domains_primary
    ON prv_tenant_domains(provider_tenant_id)
    WHERE primary_domain = TRUE AND verification_state <> 'REVOKED';

INSERT INTO prv_tenant_domains (
    provider_tenant_id, domain_name, domain_type, verification_method,
    verification_state, primary_domain, verified_at, created_by, updated_by)
SELECT
    provider_tenant_id, tenant_key || '.local', 'LOGIN', 'INTERNAL',
    'VERIFIED', TRUE, CURRENT_TIMESTAMP, created_by, updated_by
FROM prv_tenants
ON CONFLICT (domain_name) DO NOTHING;

CREATE TABLE prv_tenant_administrators (
    tenant_administrator_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    auth_user_id BIGINT,
    principal VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    display_name VARCHAR(200) NOT NULL,
    role_code VARCHAR(50) NOT NULL DEFAULT 'TENANT_ADMIN',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    primary_administrator BOOLEAN NOT NULL DEFAULT FALSE,
    last_invited_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_prv_tenant_administrators_principal UNIQUE (provider_tenant_id, principal),
    CONSTRAINT ck_prv_tenant_administrators_role CHECK (role_code IN ('TENANT_ADMIN')),
    CONSTRAINT ck_prv_tenant_administrators_state
        CHECK (lifecycle_state IN ('PENDING', 'INVITED', 'ACTIVE', 'SUSPENDED', 'REVOKED'))
);

CREATE UNIQUE INDEX uk_prv_tenant_administrators_primary
    ON prv_tenant_administrators(provider_tenant_id)
    WHERE primary_administrator = TRUE AND lifecycle_state <> 'REVOKED';

INSERT INTO prv_tenant_administrators (
    provider_tenant_id, auth_user_id, principal, email, display_name,
    lifecycle_state, primary_administrator, activated_at, created_by, updated_by)
SELECT
    provider_tenant_id, 1, 'admin', 'admin@localhost', 'Administrator',
    'ACTIVE', TRUE, CURRENT_TIMESTAMP, created_by, updated_by
FROM prv_tenants
WHERE auth_tenant_id = 1
ON CONFLICT (provider_tenant_id, principal) DO NOTHING;

CREATE TABLE prv_operator_roles (
    role_code VARCHAR(50) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_prv_operator_roles_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE prv_operator_role_permissions (
    role_code VARCHAR(50) NOT NULL REFERENCES prv_operator_roles(role_code),
    permission_code VARCHAR(80) NOT NULL,
    PRIMARY KEY (role_code, permission_code)
);

CREATE TABLE prv_operator_role_assignments (
    operator_role_assignment_id BIGSERIAL PRIMARY KEY,
    provider_operator_id BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    role_code VARCHAR(50) NOT NULL REFERENCES prv_operator_roles(role_code),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    CONSTRAINT uk_prv_operator_role_assignments UNIQUE (provider_operator_id, role_code),
    CONSTRAINT ck_prv_operator_role_assignments_state CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_prv_operator_role_assignments_validity
        CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
);

INSERT INTO prv_operator_roles (role_code, display_name, description)
VALUES
    ('PROVIDER_ADMIN', 'Provider administrator', 'Full provider control-plane administration'),
    ('PROVIDER_OPERATOR', 'Provider operator', 'Tenant estate and provisioning operations'),
    ('PROVIDER_SUPPORT', 'Provider support', 'Time-bound tenant support access'),
    ('PROVIDER_AUDITOR', 'Provider auditor', 'Read-only estate and audit access')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'ESTATE_READ'),
    ('PROVIDER_ADMIN', 'TENANT_WRITE'),
    ('PROVIDER_ADMIN', 'ENTITLEMENT_WRITE'),
    ('PROVIDER_ADMIN', 'OPERATION_EXECUTE'),
    ('PROVIDER_ADMIN', 'SUPPORT_SESSION_WRITE'),
    ('PROVIDER_ADMIN', 'AUDIT_READ'),
    ('PROVIDER_OPERATOR', 'ESTATE_READ'),
    ('PROVIDER_OPERATOR', 'TENANT_WRITE'),
    ('PROVIDER_OPERATOR', 'ENTITLEMENT_WRITE'),
    ('PROVIDER_OPERATOR', 'OPERATION_EXECUTE'),
    ('PROVIDER_SUPPORT', 'ESTATE_READ'),
    ('PROVIDER_SUPPORT', 'SUPPORT_SESSION_WRITE'),
    ('PROVIDER_AUDITOR', 'ESTATE_READ'),
    ('PROVIDER_AUDITOR', 'AUDIT_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;

INSERT INTO prv_operator_role_assignments (provider_operator_id, role_code, created_by)
SELECT provider_operator_id, role_code, auth_user_id
FROM prv_operators
ON CONFLICT (provider_operator_id, role_code) DO NOTHING;

CREATE TABLE prv_support_sessions (
    support_session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    provider_operator_id BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    justification VARCHAR(1000) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_prv_support_sessions_state CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_prv_support_sessions_expiry CHECK (expires_at > started_at),
    CONSTRAINT ck_prv_support_sessions_revocation
        CHECK ((lifecycle_state = 'REVOKED' AND revoked_at IS NOT NULL) OR lifecycle_state <> 'REVOKED')
);

CREATE TABLE prv_support_session_scopes (
    support_session_id UUID NOT NULL REFERENCES prv_support_sessions(support_session_id) ON DELETE CASCADE,
    scope_code VARCHAR(80) NOT NULL,
    PRIMARY KEY (support_session_id, scope_code),
    CONSTRAINT ck_prv_support_session_scope
        CHECK (scope_code IN ('TENANT_CONFIGURATION_READ', 'TENANT_CONFIGURATION_WRITE', 'WORKFORCE_READ'))
);

ALTER TABLE prv_operation_steps
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(80),
    ADD COLUMN last_error_message VARCHAR(1000),
    ADD CONSTRAINT ck_prv_operation_steps_attempt_count CHECK (attempt_count >= 0);

CREATE TABLE prv_operation_step_attempts (
    operation_step_attempt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_step_id BIGINT NOT NULL REFERENCES prv_operation_steps(operation_step_id),
    attempt_number INTEGER NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    redacted_result JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_prv_operation_step_attempts UNIQUE (operation_step_id, attempt_number),
    CONSTRAINT ck_prv_operation_step_attempts_number CHECK (attempt_number > 0),
    CONSTRAINT ck_prv_operation_step_attempts_state
        CHECK (lifecycle_state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_prv_operation_step_attempts_result
        CHECK (jsonb_typeof(redacted_result) = 'object')
);

ALTER TABLE prv_audit_events
    ADD COLUMN provider_operator_id BIGINT REFERENCES prv_operators(provider_operator_id),
    ADD COLUMN provider_tenant_id UUID REFERENCES prv_tenants(provider_tenant_id),
    ADD COLUMN organization_id UUID REFERENCES prv_organizations(organization_id),
    ADD COLUMN event_category VARCHAR(40) NOT NULL DEFAULT 'ADMINISTRATION';

CREATE INDEX idx_prv_organizations_state
    ON prv_organizations(lifecycle_state, organization_key);
CREATE INDEX idx_prv_tenants_organization
    ON prv_tenants(organization_id, lifecycle_state, environment_key);
CREATE INDEX idx_prv_service_instances_state
    ON prv_tenant_service_instances(provider_tenant_id, lifecycle_state, service_key);
CREATE INDEX idx_prv_support_sessions_active
    ON prv_support_sessions(provider_tenant_id, lifecycle_state, expires_at DESC);
CREATE INDEX idx_prv_operation_step_attempts_time
    ON prv_operation_step_attempts(operation_step_id, started_at DESC);
CREATE INDEX idx_prv_audit_tenant_time
    ON prv_audit_events(provider_tenant_id, occurred_at DESC);
