CREATE TABLE adm_registry_entries (
    registry_entry_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    registry_type VARCHAR(24) NOT NULL,
    entry_key VARCHAR(100) NOT NULL,
    revision INTEGER NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    owner_ref VARCHAR(160) NOT NULL,
    risk_tier VARCHAR(20) NOT NULL,
    artifact_version VARCHAR(64) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_registry_entries_revision
        UNIQUE (tenant_id, registry_type, entry_key, revision),
    CONSTRAINT ck_adm_registry_entries_type
        CHECK (registry_type IN ('APP', 'CONNECTOR', 'AGENT', 'TOOL', 'POLICY')),
    CONSTRAINT ck_adm_registry_entries_risk
        CHECK (risk_tier IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_adm_registry_entries_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_adm_registry_entries_revision CHECK (revision > 0)
);

CREATE UNIQUE INDEX uk_adm_registry_entries_active
    ON adm_registry_entries (tenant_id, registry_type, entry_key)
    WHERE lifecycle_state = 'ACTIVE';

CREATE UNIQUE INDEX uk_adm_registry_entries_draft
    ON adm_registry_entries (tenant_id, registry_type, entry_key)
    WHERE lifecycle_state = 'DRAFT';

CREATE INDEX idx_adm_registry_entries_tenant_type_state
    ON adm_registry_entries (tenant_id, registry_type, lifecycle_state, entry_key);

