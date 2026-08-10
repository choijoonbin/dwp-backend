-- Organization levels are tenant vocabulary, not a product hierarchy.
-- hierarchy_rank is a presentation hint only; no parent/child type combination is enforced.
CREATE TABLE ppl_organization_type_catalog (
    organization_type_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    type_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    icon_key VARCHAR(80),
    color_token VARCHAR(30),
    hierarchy_rank INTEGER,
    root_candidate BOOLEAN NOT NULL DEFAULT FALSE,
    worker_assignment_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    child_assignment_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_org_type_catalog_public_id UNIQUE (public_id),
    CONSTRAINT uk_ppl_org_type_catalog_key UNIQUE (tenant_id, type_key),
    CONSTRAINT ck_ppl_org_type_catalog_key
        CHECK (type_key = UPPER(BTRIM(type_key))
            AND type_key ~ '^[A-Z][A-Z0-9._-]{0,99}$'),
    CONSTRAINT ck_ppl_org_type_catalog_rank
        CHECK (hierarchy_rank IS NULL OR hierarchy_rank >= 0),
    CONSTRAINT ck_ppl_org_type_catalog_labels
        CHECK (jsonb_typeof(label_i18n) = 'object'),
    CONSTRAINT ck_ppl_org_type_catalog_metadata
        CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_ppl_org_type_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

WITH tenants AS (
    SELECT tenant_id FROM sys_service_tenants
    UNION
    SELECT DISTINCT tenant_id FROM ppl_organizations
), starter_types(
    type_key, display_name, description, hierarchy_rank, root_candidate,
    worker_assignment_allowed, child_assignment_allowed, icon_key
) AS (
    VALUES
        ('COMPANY', 'Company', 'Enterprise or company root.', 10, TRUE, TRUE, TRUE, 'building-2'),
        ('BUSINESS_UNIT', 'Business unit', 'Accountable business portfolio.', 20, FALSE, TRUE, TRUE, 'briefcase-business'),
        ('DIVISION', 'Division', 'Major operating or functional division.', 30, FALSE, TRUE, TRUE, 'network'),
        ('DEPARTMENT', 'Department', 'Department or functional unit.', 40, FALSE, TRUE, TRUE, 'landmark'),
        ('SUPERVISORY', 'Team', 'Supervisory organization with a people leader.', 50, FALSE, TRUE, TRUE, 'users-round'),
        ('COST_CENTER', 'Cost center', 'Financial responsibility unit.', 60, FALSE, TRUE, TRUE, 'badge-dollar-sign'),
        ('CUSTOM', 'Custom unit', 'Tenant-defined organization unit.', 500, FALSE, TRUE, TRUE, 'shapes')
)
INSERT INTO ppl_organization_type_catalog (
    tenant_id, type_key, display_name, description, hierarchy_rank,
    root_candidate, worker_assignment_allowed, child_assignment_allowed,
    icon_key, created_by, updated_by)
SELECT tenant.tenant_id, type.type_key, type.display_name, type.description,
       type.hierarchy_rank, type.root_candidate, type.worker_assignment_allowed,
       type.child_assignment_allowed, type.icon_key, 1, 1
  FROM tenants tenant
 CROSS JOIN starter_types type
ON CONFLICT (tenant_id, type_key) DO NOTHING;

INSERT INTO ppl_organization_type_catalog (
    tenant_id, type_key, display_name, description, hierarchy_rank,
    created_by, updated_by)
SELECT DISTINCT organization.tenant_id,
       UPPER(BTRIM(organization.organization_type)),
       INITCAP(REPLACE(REPLACE(LOWER(BTRIM(organization.organization_type)), '_', ' '), '-', ' ')),
       'Imported organization type.',
       500,
       1,
       1
  FROM ppl_organizations organization
 WHERE NOT EXISTS (
       SELECT 1
         FROM ppl_organization_type_catalog catalog
        WHERE catalog.tenant_id = organization.tenant_id
          AND catalog.type_key = UPPER(BTRIM(organization.organization_type)))
ON CONFLICT (tenant_id, type_key) DO NOTHING;

ALTER TABLE ppl_organizations
    DROP CONSTRAINT ck_ppl_organizations_type;

ALTER TABLE ppl_organizations
    ALTER COLUMN organization_type TYPE VARCHAR(100),
    ALTER COLUMN organization_type SET DEFAULT 'CUSTOM';

UPDATE ppl_organizations
   SET organization_type = UPPER(BTRIM(organization_type));

ALTER TABLE ppl_organizations
    ADD CONSTRAINT fk_ppl_organizations_type_catalog
        FOREIGN KEY (tenant_id, organization_type)
        REFERENCES ppl_organization_type_catalog(tenant_id, type_key);

CREATE INDEX idx_ppl_org_type_catalog_active
    ON ppl_organization_type_catalog(
        tenant_id, lifecycle_state, root_candidate DESC, hierarchy_rank, display_name);

COMMENT ON TABLE ppl_organization_type_catalog IS
    'Tenant-governed organization vocabulary. Types and hierarchy depth are extensible data, not hard-coded product levels.';
COMMENT ON COLUMN ppl_organization_type_catalog.hierarchy_rank IS
    'Optional sorting hint only. It never restricts valid parent-child relationships.';
COMMENT ON COLUMN ppl_organization_type_catalog.root_candidate IS
    'Preference used when selecting among parentless roots; it does not require a fixed root type.';
