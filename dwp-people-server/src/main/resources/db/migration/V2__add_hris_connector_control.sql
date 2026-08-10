CREATE TABLE int_connector_instances (
    connector_instance_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    source_system_id BIGINT NOT NULL,
    connector_key VARCHAR(100) NOT NULL,
    connector_type VARCHAR(40) NOT NULL,
    endpoint_uri VARCHAR(1000),
    auth_mode VARCHAR(30) NOT NULL DEFAULT 'OAUTH2_CLIENT_CREDENTIALS',
    credential_reference VARCHAR(255),
    schedule_expression VARCHAR(120),
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    health_state VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    last_health_checked_at TIMESTAMPTZ,
    last_successful_sync_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_int_connector_instances_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT uk_int_connector_instances_key UNIQUE (tenant_id, connector_key),
    CONSTRAINT ck_int_connector_instances_type CHECK (
        connector_type IN (
            'WORKDAY_REST', 'WORKDAY_SOAP', 'ORACLE_HCM_REST',
            'SAP_SUCCESSFACTORS', 'SCIM_BRIDGE', 'CUSTOM_REST', 'FILE_IMPORT'
        )
    ),
    CONSTRAINT ck_int_connector_instances_auth CHECK (
        auth_mode IN ('NONE', 'BASIC', 'OAUTH2_CLIENT_CREDENTIALS', 'MTLS', 'SIGNED_REQUEST')
    ),
    CONSTRAINT ck_int_connector_instances_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_int_connector_instances_health
        CHECK (health_state IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'FAILED')),
    CONSTRAINT ck_int_connector_instances_secret
        CHECK (auth_mode = 'NONE' OR credential_reference IS NOT NULL)
);

CREATE TABLE int_mapping_profiles (
    mapping_profile_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    source_system_id BIGINT NOT NULL,
    profile_key VARCHAR(120) NOT NULL,
    adapter_type VARCHAR(40) NOT NULL,
    source_schema_version VARCHAR(80) NOT NULL,
    target_schema_version VARCHAR(80) NOT NULL,
    mapping_definition JSONB NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_int_mapping_profiles_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT uk_int_mapping_profiles_key UNIQUE (tenant_id, source_system_id, profile_key),
    CONSTRAINT ck_int_mapping_profiles_adapter CHECK (
        adapter_type IN (
            'WORKDAY_REFERENCE', 'WORKDAY_REST', 'WORKDAY_SOAP',
            'ORACLE_HCM_REST', 'SAP_SUCCESSFACTORS', 'CANONICAL_JSON'
        )
    ),
    CONSTRAINT ck_int_mapping_profiles_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE int_ingestion_receipts (
    ingestion_receipt_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    source_system_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    sync_run_id UUID,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_int_ingestion_receipts_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT fk_int_ingestion_receipts_run
        FOREIGN KEY (tenant_id, sync_run_id)
        REFERENCES int_sync_runs(tenant_id, sync_run_id),
    CONSTRAINT uk_int_ingestion_receipts_key
        UNIQUE (tenant_id, source_system_id, idempotency_key),
    CONSTRAINT ck_int_ingestion_receipts_state
        CHECK (lifecycle_state IN ('PROCESSING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_int_connector_instances_state
    ON int_connector_instances(tenant_id, lifecycle_state, connector_key);
CREATE INDEX idx_int_mapping_profiles_state
    ON int_mapping_profiles(tenant_id, lifecycle_state, profile_key);
CREATE INDEX idx_int_ingestion_receipts_run
    ON int_ingestion_receipts(tenant_id, sync_run_id);
