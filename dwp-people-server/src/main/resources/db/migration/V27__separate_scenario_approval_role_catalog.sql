CREATE TABLE ppl_approval_role_catalog (
    role_code VARCHAR(50) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(800) NOT NULL,
    label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort_order INTEGER NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ppl_approval_role_catalog_code
        CHECK (role_code = UPPER(BTRIM(role_code))
            AND role_code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_ppl_approval_role_catalog_labels
        CHECK (jsonb_typeof(label_i18n) = 'object'),
    CONSTRAINT ck_ppl_approval_role_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO ppl_approval_role_catalog (
    role_code, display_name, description, label_i18n, sort_order)
VALUES
    ('HR_ADMIN', 'HR administrator', 'Primary organization design approver.',
     '{"ko":"HR 관리자","en":"HR administrator"}', 10),
    ('PEOPLE_ADMIN', 'People administrator', 'People service administrator.',
     '{"ko":"인사 서비스 관리자","en":"People administrator"}', 20),
    ('TENANT_ADMIN', 'Tenant administrator', 'Customer tenant administrator.',
     '{"ko":"테넌트 관리자","en":"Tenant administrator"}', 30),
    ('PLATFORM_ADMIN', 'Platform administrator', 'Tenant platform administrator.',
     '{"ko":"플랫폼 관리자","en":"Platform administrator"}', 40),
    ('ADMIN', 'Administrator', 'Foundation administrator.',
     '{"ko":"관리자","en":"Administrator"}', 50);

ALTER TABLE ppl_organization_scenario_approvals
    DROP CONSTRAINT fk_ppl_organization_scenario_approval_role,
    ADD CONSTRAINT fk_ppl_organization_scenario_approval_role
        FOREIGN KEY (required_role_code)
        REFERENCES ppl_approval_role_catalog(role_code);

CREATE INDEX idx_ppl_approval_role_catalog_active
    ON ppl_approval_role_catalog(lifecycle_state, sort_order, role_code);
