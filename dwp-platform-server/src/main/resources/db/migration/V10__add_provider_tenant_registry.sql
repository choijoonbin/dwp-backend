CREATE TABLE sys_service_tenants (
    provider_tenant_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL UNIQUE,
    tenant_key VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(240) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PROVISIONING',
    data_region VARCHAR(40) NOT NULL,
    isolation_model VARCHAR(20) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_sys_service_tenants_state
        CHECK (lifecycle_state IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_sys_service_tenants_isolation
        CHECK (isolation_model IN ('POOL', 'BRIDGE', 'SILO')),
    CONSTRAINT ck_sys_service_tenants_schema CHECK (schema_version > 0),
    CONSTRAINT ck_sys_service_tenants_configuration
        CHECK (jsonb_typeof(configuration) = 'object')
);

INSERT INTO sys_service_tenants (
    provider_tenant_id, tenant_id, tenant_key, display_name, lifecycle_state,
    data_region, isolation_model, created_by, updated_by)
VALUES (
    '00000000-0000-0000-0000-000000000001', 1, 'default', 'Default Tenant',
    'ACTIVE', 'local', 'POOL', 1, 1)
ON CONFLICT (provider_tenant_id) DO NOTHING;

CREATE INDEX idx_sys_service_tenants_state
    ON sys_service_tenants(lifecycle_state, tenant_key);
