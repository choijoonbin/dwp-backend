ALTER TABLE com_users
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN external_id VARCHAR(255),
    ADD COLUMN scim_user_name VARCHAR(255),
    ADD COLUMN given_name VARCHAR(120),
    ADD COLUMN family_name VARCHAR(120),
    ADD CONSTRAINT uk_com_users_public_id UNIQUE (public_id),
    ADD CONSTRAINT ck_com_users_source_type
        CHECK (source_type IN ('LOCAL', 'SCIM', 'HRIS'));

CREATE UNIQUE INDEX uk_com_users_scim_external
    ON com_users(tenant_id, source_type, external_id)
    WHERE external_id IS NOT NULL;
CREATE UNIQUE INDEX uk_com_users_scim_username
    ON com_users(tenant_id, scim_user_name)
    WHERE scim_user_name IS NOT NULL;

ALTER TABLE com_groups
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uk_com_groups_public_id UNIQUE (public_id);

CREATE TABLE sys_scim_connectors (
    scim_connector_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    connector_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    token_prefix VARCHAR(24) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    allowed_operations JSONB NOT NULL DEFAULT '["USERS","GROUPS"]'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_used_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_scim_connectors_key UNIQUE (tenant_id, connector_key),
    CONSTRAINT uk_sys_scim_connectors_prefix UNIQUE (token_prefix),
    CONSTRAINT ck_sys_scim_connectors_state
        CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE TABLE sys_scim_provisioning_events (
    scim_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    scim_connector_id UUID NOT NULL REFERENCES sys_scim_connectors(scim_connector_id),
    operation VARCHAR(20) NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    resource_id VARCHAR(160),
    external_id VARCHAR(255),
    outcome VARCHAR(20) NOT NULL,
    correlation_id VARCHAR(128),
    redacted_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_scim_events_operation
        CHECK (operation IN ('CREATE', 'REPLACE', 'PATCH', 'DELETE', 'READ', 'SEARCH')),
    CONSTRAINT ck_sys_scim_events_resource
        CHECK (resource_type IN ('USER', 'GROUP', 'CONFIG')),
    CONSTRAINT ck_sys_scim_events_outcome
        CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'))
);

CREATE INDEX idx_sys_scim_connectors_tenant_state
    ON sys_scim_connectors(tenant_id, lifecycle_state, connector_key);
CREATE INDEX idx_sys_scim_events_tenant_time
    ON sys_scim_provisioning_events(tenant_id, occurred_at DESC);
