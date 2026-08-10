-- Product behavior codes remain global and immutable at runtime. Tenant vocabulary
-- is isolated by tenant_id so customer extensions cannot alter another tenant.
CREATE TABLE ppl_position_type_catalog (
    position_type VARCHAR(24) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    description VARCHAR(1000),
    sort_order INTEGER NOT NULL DEFAULT 0,
    allows_multiple_incumbents BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ppl_position_type_catalog_key
        CHECK (position_type = UPPER(BTRIM(position_type))
            AND position_type ~ '^[A-Z][A-Z0-9_]{0,23}$'),
    CONSTRAINT ck_ppl_position_type_catalog_labels
        CHECK (jsonb_typeof(label_i18n) = 'object'),
    CONSTRAINT ck_ppl_position_type_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE ppl_position_criticality_catalog (
    criticality VARCHAR(16) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    description VARCHAR(1000),
    sort_order INTEGER NOT NULL DEFAULT 0,
    decision_weight INTEGER NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ppl_position_criticality_catalog_key
        CHECK (criticality = UPPER(BTRIM(criticality))
            AND criticality ~ '^[A-Z][A-Z0-9_]{0,15}$'),
    CONSTRAINT ck_ppl_position_criticality_catalog_labels
        CHECK (jsonb_typeof(label_i18n) = 'object'),
    CONSTRAINT ck_ppl_position_criticality_catalog_weight
        CHECK (decision_weight > 0),
    CONSTRAINT ck_ppl_position_criticality_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO ppl_position_type_catalog (
    position_type, display_name, label_i18n, description, sort_order,
    allows_multiple_incumbents)
VALUES
    ('REGULAR', 'Regular', '{"ko":"일반","en":"Regular"}', 'Standard single-incumbent position.', 10, FALSE),
    ('SHARED', 'Shared', '{"ko":"공유","en":"Shared"}', 'Position that may be shared by multiple incumbents.', 20, TRUE),
    ('ASSISTANT', 'Assistant', '{"ko":"보좌","en":"Assistant"}', 'Assistant or staff position attached to a leader.', 30, FALSE),
    ('TEMPORARY', 'Temporary', '{"ko":"임시","en":"Temporary"}', 'Time-bounded temporary position.', 40, FALSE);

INSERT INTO ppl_position_criticality_catalog (
    criticality, display_name, label_i18n, description, sort_order, decision_weight)
VALUES
    ('LOW', 'Low', '{"ko":"낮음","en":"Low"}', 'Limited operational impact when vacant.', 10, 1),
    ('MEDIUM', 'Medium', '{"ko":"보통","en":"Medium"}', 'Normal operational impact when vacant.', 20, 2),
    ('HIGH', 'High', '{"ko":"높음","en":"High"}', 'Material business impact requiring active succession coverage.', 30, 3),
    ('CRITICAL', 'Critical', '{"ko":"핵심","en":"Critical"}', 'Enterprise-critical impact requiring governed decision evidence.', 40, 4);

ALTER TABLE ppl_positions
    DROP CONSTRAINT ck_ppl_positions_type,
    DROP CONSTRAINT ck_ppl_positions_criticality,
    ADD CONSTRAINT fk_ppl_positions_type_catalog
        FOREIGN KEY (position_type)
        REFERENCES ppl_position_type_catalog(position_type),
    ADD CONSTRAINT fk_ppl_positions_criticality_catalog
        FOREIGN KEY (criticality)
        REFERENCES ppl_position_criticality_catalog(criticality);

CREATE TABLE ppl_assignment_change_reason_catalog (
    assignment_change_reason_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort_order INTEGER NOT NULL DEFAULT 0,
    predefined BOOLEAN NOT NULL DEFAULT FALSE,
    effective_start_date DATE NOT NULL DEFAULT DATE '1900-01-01',
    effective_end_date DATE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_assignment_change_reason_public_id UNIQUE (public_id),
    CONSTRAINT uk_ppl_assignment_change_reason_key UNIQUE (tenant_id, reason_code),
    CONSTRAINT ck_ppl_assignment_change_reason_key
        CHECK (reason_code = UPPER(BTRIM(reason_code))
            AND reason_code ~ '^[A-Z][A-Z0-9._-]{0,79}$'),
    CONSTRAINT ck_ppl_assignment_change_reason_labels
        CHECK (jsonb_typeof(label_i18n) = 'object'),
    CONSTRAINT ck_ppl_assignment_change_reason_metadata
        CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_ppl_assignment_change_reason_dates
        CHECK (effective_end_date IS NULL OR effective_end_date >= effective_start_date),
    CONSTRAINT ck_ppl_assignment_change_reason_state
        CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE ppl_organization_role_catalog (
    organization_role_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    role_code VARCHAR(40) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    icon_key VARCHAR(80),
    sort_order INTEGER NOT NULL DEFAULT 0,
    predefined BOOLEAN NOT NULL DEFAULT FALSE,
    allows_person_holder BOOLEAN NOT NULL DEFAULT TRUE,
    allows_position_holder BOOLEAN NOT NULL DEFAULT TRUE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_organization_role_catalog_public_id UNIQUE (public_id),
    CONSTRAINT uk_ppl_organization_role_catalog_key UNIQUE (tenant_id, role_code),
    CONSTRAINT ck_ppl_organization_role_catalog_key
        CHECK (role_code = UPPER(BTRIM(role_code))
            AND role_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT ck_ppl_organization_role_catalog_labels
        CHECK (jsonb_typeof(label_i18n) = 'object'),
    CONSTRAINT ck_ppl_organization_role_catalog_holder
        CHECK (allows_person_holder OR allows_position_holder),
    CONSTRAINT ck_ppl_organization_role_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

WITH tenants AS (
    SELECT tenant_id FROM sys_service_tenants
    UNION
    SELECT DISTINCT tenant_id FROM ppl_assignments
    UNION
    SELECT DISTINCT tenant_id FROM ppl_organizations
), reasons(reason_code, display_name, description, label_i18n, sort_order) AS (
    VALUES
        ('SEED_IMPORT', 'Initial import', 'Initial workforce projection import.', '{"ko":"최초 가져오기","en":"Initial import"}'::jsonb, 10),
        ('REFERENCE_PROFILE', 'Reference profile', 'Synthetic enterprise reference profile seed.', '{"ko":"참조 프로필","en":"Reference profile"}'::jsonb, 20),
        ('INTERNAL_TRANSFER', 'Internal transfer', 'Assignment moved within the tenant.', '{"ko":"사내 이동","en":"Internal transfer"}'::jsonb, 30),
        ('PROMOTION', 'Promotion', 'Assignment changed due to promotion.', '{"ko":"승진","en":"Promotion"}'::jsonb, 40)
)
INSERT INTO ppl_assignment_change_reason_catalog (
    tenant_id, reason_code, display_name, description, label_i18n,
    sort_order, predefined, created_by, updated_by)
SELECT tenant.tenant_id, reason.reason_code, reason.display_name,
       reason.description, reason.label_i18n, reason.sort_order, TRUE, 1, 1
  FROM tenants tenant
 CROSS JOIN reasons reason;

INSERT INTO ppl_assignment_change_reason_catalog (
    tenant_id, reason_code, display_name, description,
    sort_order, predefined, created_by, updated_by)
SELECT DISTINCT assignment.tenant_id,
       UPPER(BTRIM(assignment.change_reason_code)),
       INITCAP(REPLACE(REPLACE(LOWER(BTRIM(assignment.change_reason_code)), '_', ' '), '-', ' ')),
       'Imported assignment change reason.',
       1000,
       FALSE,
       1,
       1
  FROM ppl_assignments assignment
 WHERE assignment.change_reason_code IS NOT NULL
   AND NOT EXISTS (
       SELECT 1
         FROM ppl_assignment_change_reason_catalog catalog
        WHERE catalog.tenant_id = assignment.tenant_id
          AND catalog.reason_code = UPPER(BTRIM(assignment.change_reason_code)))
ON CONFLICT (tenant_id, reason_code) DO NOTHING;

WITH tenants AS (
    SELECT tenant_id FROM sys_service_tenants
    UNION
    SELECT DISTINCT tenant_id FROM ppl_organizations
), roles(
    role_code, display_name, description, label_i18n, icon_key,
    sort_order, allows_person_holder, allows_position_holder
) AS (
    VALUES
        ('LEADER', 'Leader', 'Primary accountable leader for an organization.', '{"ko":"조직장","en":"Leader"}'::jsonb, 'user-round-check', 10, TRUE, TRUE),
        ('HR_BUSINESS_PARTNER', 'HR business partner', 'People partner accountable for the organization.', '{"ko":"HR 비즈니스 파트너","en":"HR business partner"}'::jsonb, 'users-round', 20, TRUE, FALSE),
        ('FINANCE_PARTNER', 'Finance partner', 'Finance partner accountable for the organization.', '{"ko":"재무 파트너","en":"Finance partner"}'::jsonb, 'badge-dollar-sign', 30, TRUE, FALSE),
        ('MATRIX_MANAGER', 'Matrix manager', 'Additional matrix reporting leader.', '{"ko":"매트릭스 관리자","en":"Matrix manager"}'::jsonb, 'network', 40, TRUE, TRUE),
        ('SECURITY_ADMIN', 'Security administrator', 'Delegated security administrator for the organization.', '{"ko":"보안 관리자","en":"Security administrator"}'::jsonb, 'shield-check', 50, TRUE, FALSE)
)
INSERT INTO ppl_organization_role_catalog (
    tenant_id, role_code, display_name, description, label_i18n,
    icon_key, sort_order, predefined, allows_person_holder,
    allows_position_holder, created_by, updated_by)
SELECT tenant.tenant_id, role.role_code, role.display_name, role.description,
       role.label_i18n, role.icon_key, role.sort_order, TRUE,
       role.allows_person_holder, role.allows_position_holder, 1, 1
  FROM tenants tenant
 CROSS JOIN roles role;

ALTER TABLE ppl_assignments
    ADD CONSTRAINT fk_ppl_assignments_change_reason_catalog
        FOREIGN KEY (tenant_id, change_reason_code)
        REFERENCES ppl_assignment_change_reason_catalog(tenant_id, reason_code);

ALTER TABLE ppl_organization_role_assignments
    DROP CONSTRAINT ck_ppl_organization_role_code,
    ADD CONSTRAINT fk_ppl_organization_role_assignments_catalog
        FOREIGN KEY (tenant_id, role_code)
        REFERENCES ppl_organization_role_catalog(tenant_id, role_code);

ALTER TABLE ppl_organization_scenario_approvals
    ADD CONSTRAINT fk_ppl_organization_scenario_approval_role
        FOREIGN KEY (tenant_id, required_role_code)
        REFERENCES ppl_organization_role_catalog(tenant_id, role_code);

CREATE INDEX idx_ppl_assignment_change_reason_active
    ON ppl_assignment_change_reason_catalog(
        tenant_id, lifecycle_state, effective_start_date, effective_end_date,
        sort_order, display_name);
CREATE INDEX idx_ppl_organization_role_catalog_active
    ON ppl_organization_role_catalog(
        tenant_id, lifecycle_state, sort_order, display_name);

COMMENT ON TABLE ppl_position_type_catalog IS
    'People-owned system position types. Runtime behavior is fixed by product version and exposed through the system code contract read model.';
COMMENT ON TABLE ppl_position_criticality_catalog IS
    'People-owned system criticality values and their governed decision weight.';
COMMENT ON TABLE ppl_assignment_change_reason_catalog IS
    'Tenant-extensible, effective-dated assignment change reasons used by workforce projection and HRIS ingestion.';
COMMENT ON TABLE ppl_organization_role_catalog IS
    'Tenant-extensible organization accountability roles used by assignments and scenario approval gates.';
