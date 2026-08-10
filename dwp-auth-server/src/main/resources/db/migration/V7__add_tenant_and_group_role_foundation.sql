ALTER TABLE com_tenants
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN default_locale VARCHAR(35) NOT NULL DEFAULT 'en',
    ADD COLUMN time_zone VARCHAR(80) NOT NULL DEFAULT 'UTC',
    ADD COLUMN data_region VARCHAR(40),
    ADD COLUMN isolation_model VARCHAR(20) NOT NULL DEFAULT 'POOL',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT uk_com_tenants_public_id UNIQUE (public_id),
    ADD CONSTRAINT ck_com_tenants_isolation
        CHECK (isolation_model IN ('POOL', 'BRIDGE', 'SILO'));

ALTER TABLE com_users
    ADD COLUMN person_public_id UUID;

CREATE UNIQUE INDEX uk_com_users_person_public_id
    ON com_users(tenant_id, person_public_id)
    WHERE person_public_id IS NOT NULL;

ALTER TABLE com_organization_units
    ADD COLUMN workforce_organization_public_id UUID;

CREATE UNIQUE INDEX uk_com_org_units_workforce_public_id
    ON com_organization_units(tenant_id, workforce_organization_public_id)
    WHERE workforce_organization_public_id IS NOT NULL;

ALTER TABLE com_roles
    ADD COLUMN role_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOM',
    ADD COLUMN privileged BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN assignable_to_groups BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT uk_com_roles_tenant_id UNIQUE (tenant_id, role_id),
    ADD CONSTRAINT ck_com_roles_type CHECK (role_type IN ('SYSTEM', 'CUSTOM'));

UPDATE com_roles
SET role_type = 'SYSTEM', privileged = TRUE, assignable_to_groups = FALSE
WHERE code IN ('ADMIN', 'TENANT_ADMIN', 'PLATFORM_ADMIN');

CREATE TABLE com_group_role_assignments (
    group_role_assignment_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    group_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    assignment_type VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    scope_type VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    scope_ref VARCHAR(160),
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    justification VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_com_group_role_assignments_group
        FOREIGN KEY (tenant_id, group_id) REFERENCES com_groups(tenant_id, group_id),
    CONSTRAINT fk_com_group_role_assignments_role
        FOREIGN KEY (tenant_id, role_id) REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT ck_com_group_role_assignments_type
        CHECK (assignment_type IN ('ACTIVE', 'ELIGIBLE')),
    CONSTRAINT ck_com_group_role_assignments_scope
        CHECK (scope_type IN ('TENANT', 'ORG_UNIT', 'RESOURCE')),
    CONSTRAINT ck_com_group_role_assignments_scope_ref
        CHECK (
            (scope_type = 'TENANT' AND scope_ref IS NULL)
            OR (scope_type <> 'TENANT' AND scope_ref IS NOT NULL)
        ),
    CONSTRAINT ck_com_group_role_assignments_state
        CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_com_group_role_assignments_validity
        CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
);

CREATE UNIQUE INDEX uk_com_group_role_assignments_active
    ON com_group_role_assignments(
        tenant_id,
        group_id,
        role_id,
        scope_type,
        COALESCE(scope_ref, '')
    )
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX idx_com_group_role_assignments_effective
    ON com_group_role_assignments(
        tenant_id,
        group_id,
        lifecycle_state,
        assignment_type,
        valid_from,
        valid_to
    );

CREATE TABLE com_role_hierarchy (
    role_hierarchy_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    senior_role_id BIGINT NOT NULL,
    junior_role_id BIGINT NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_com_role_hierarchy_senior
        FOREIGN KEY (tenant_id, senior_role_id) REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT fk_com_role_hierarchy_junior
        FOREIGN KEY (tenant_id, junior_role_id) REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT uk_com_role_hierarchy_pair
        UNIQUE (tenant_id, senior_role_id, junior_role_id),
    CONSTRAINT ck_com_role_hierarchy_not_self CHECK (senior_role_id <> junior_role_id),
    CONSTRAINT ck_com_role_hierarchy_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE com_separation_of_duty_rules (
    separation_rule_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    rule_key VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    rule_type VARCHAR(20) NOT NULL,
    left_role_id BIGINT NOT NULL,
    right_role_id BIGINT NOT NULL,
    enforcement_mode VARCHAR(20) NOT NULL DEFAULT 'BLOCK',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_sod_rules_key UNIQUE (tenant_id, rule_key),
    CONSTRAINT fk_com_sod_rules_left
        FOREIGN KEY (tenant_id, left_role_id) REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT fk_com_sod_rules_right
        FOREIGN KEY (tenant_id, right_role_id) REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT ck_com_sod_rules_type CHECK (rule_type IN ('STATIC', 'DYNAMIC')),
    CONSTRAINT ck_com_sod_rules_mode CHECK (enforcement_mode IN ('BLOCK', 'REQUIRE_APPROVAL')),
    CONSTRAINT ck_com_sod_rules_state CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_com_sod_rules_not_self CHECK (left_role_id <> right_role_id)
);

INSERT INTO com_permissions (code, name)
VALUES
    ('EXECUTE', 'Execute'),
    ('APPROVE', 'Approve'),
    ('EXPORT', 'Export')
ON CONFLICT (code) DO NOTHING;
