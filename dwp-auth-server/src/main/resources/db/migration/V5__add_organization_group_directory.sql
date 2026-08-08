CREATE TABLE com_organization_units (
    org_unit_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    org_key VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    parent_org_unit_id BIGINT,
    source_type VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    external_id VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    revision BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_org_units_tenant_key UNIQUE (tenant_id, org_key),
    CONSTRAINT uk_com_org_units_tenant_id UNIQUE (tenant_id, org_unit_id),
    CONSTRAINT ck_com_org_units_source CHECK (source_type IN ('LOCAL', 'SCIM')),
    CONSTRAINT ck_com_org_units_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fk_com_org_units_parent
        FOREIGN KEY (tenant_id, parent_org_unit_id)
        REFERENCES com_organization_units(tenant_id, org_unit_id)
);

CREATE UNIQUE INDEX uk_com_org_units_external
    ON com_organization_units(tenant_id, source_type, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX idx_com_org_units_tenant_parent
    ON com_organization_units(tenant_id, parent_org_unit_id);

ALTER TABLE com_users
    ADD CONSTRAINT uk_com_users_tenant_id UNIQUE (tenant_id, user_id),
    ADD COLUMN primary_org_unit_id BIGINT,
    ADD CONSTRAINT fk_com_users_primary_org
        FOREIGN KEY (tenant_id, primary_org_unit_id)
        REFERENCES com_organization_units(tenant_id, org_unit_id);

CREATE INDEX idx_com_users_tenant_primary_org
    ON com_users(tenant_id, primary_org_unit_id);

CREATE TABLE com_groups (
    group_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    group_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    description TEXT,
    source_type VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    external_id VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    revision BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_groups_tenant_key UNIQUE (tenant_id, group_key),
    CONSTRAINT uk_com_groups_tenant_id UNIQUE (tenant_id, group_id),
    CONSTRAINT ck_com_groups_source CHECK (source_type IN ('LOCAL', 'SCIM')),
    CONSTRAINT ck_com_groups_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX uk_com_groups_external
    ON com_groups(tenant_id, source_type, external_id)
    WHERE external_id IS NOT NULL;

CREATE TABLE com_group_members (
    group_member_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_group_members_tenant_group_user
        UNIQUE (tenant_id, group_id, user_id),
    CONSTRAINT ck_com_group_members_source CHECK (source_type IN ('LOCAL', 'SCIM')),
    CONSTRAINT fk_com_group_members_group
        FOREIGN KEY (tenant_id, group_id)
        REFERENCES com_groups(tenant_id, group_id),
    CONSTRAINT fk_com_group_members_user
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES com_users(tenant_id, user_id)
);

CREATE INDEX idx_com_group_members_tenant_user
    ON com_group_members(tenant_id, user_id);
