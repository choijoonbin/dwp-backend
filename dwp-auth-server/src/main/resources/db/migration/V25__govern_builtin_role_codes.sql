CREATE TABLE sys_builtin_role_catalog (
    role_code VARCHAR(50) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(800) NOT NULL,
    role_family VARCHAR(24) NOT NULL,
    label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    privileged BOOLEAN NOT NULL DEFAULT FALSE,
    assignable_to_groups BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_builtin_role_catalog_code
        CHECK (role_code = UPPER(BTRIM(role_code))
            AND role_code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_sys_builtin_role_catalog_family
        CHECK (role_family IN (
            'PLATFORM', 'TENANT', 'WORKSPACE', 'PEOPLE', 'AUDIT', 'PROVIDER')),
    CONSTRAINT ck_sys_builtin_role_catalog_labels
        CHECK (jsonb_typeof(label_i18n) = 'object'),
    CONSTRAINT ck_sys_builtin_role_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order)
VALUES
    ('ADMIN', 'Administrator', 'Foundation administrator.', 'PLATFORM',
     '{"ko":"관리자","en":"Administrator"}', TRUE, FALSE, 10),
    ('PLATFORM_ADMIN', 'Platform administrator', 'Tenant platform configuration administrator.', 'PLATFORM',
     '{"ko":"플랫폼 관리자","en":"Platform administrator"}', TRUE, FALSE, 20),
    ('TENANT_ADMIN', 'Tenant administrator', 'Administrator for one customer tenant.', 'TENANT',
     '{"ko":"테넌트 관리자","en":"Tenant administrator"}', TRUE, FALSE, 30),
    ('WORKSPACE_MEMBER', 'Workspace member', 'Default workspace access role.', 'WORKSPACE',
     '{"ko":"워크스페이스 구성원","en":"Workspace member"}', FALSE, TRUE, 40),
    ('HR_ADMIN', 'HR administrator', 'Workforce data and organization administrator.', 'PEOPLE',
     '{"ko":"HR 관리자","en":"HR administrator"}', TRUE, FALSE, 50),
    ('PEOPLE_ADMIN', 'People administrator', 'People service administrator.', 'PEOPLE',
     '{"ko":"인사 서비스 관리자","en":"People administrator"}', TRUE, FALSE, 60),
    ('AUDITOR', 'Auditor', 'Read-only audit and compliance investigator.', 'AUDIT',
     '{"ko":"감사자","en":"Auditor"}', TRUE, FALSE, 70),
    ('AUDIT_ADMIN', 'Audit administrator', 'Audit policy and investigation administrator.', 'AUDIT',
     '{"ko":"감사 관리자","en":"Audit administrator"}', TRUE, FALSE, 80),
    ('PROVIDER_ADMIN', 'Provider administrator', 'Provider control plane administrator.', 'PROVIDER',
     '{"ko":"프로바이더 관리자","en":"Provider administrator"}', TRUE, FALSE, 90),
    ('PROVIDER_OPERATOR', 'Provider operator', 'Provider estate operator.', 'PROVIDER',
     '{"ko":"프로바이더 운영자","en":"Provider operator"}', TRUE, FALSE, 100),
    ('PROVIDER_SUPPORT', 'Provider support', 'Time-bound provider support operator.', 'PROVIDER',
     '{"ko":"프로바이더 지원 담당자","en":"Provider support"}', TRUE, FALSE, 110),
    ('PROVIDER_AUDITOR', 'Provider auditor', 'Read-only provider control plane auditor.', 'PROVIDER',
     '{"ko":"프로바이더 감사자","en":"Provider auditor"}', TRUE, FALSE, 120);

ALTER TABLE com_roles
    ADD COLUMN builtin_role_code VARCHAR(50),
    ADD CONSTRAINT fk_com_roles_builtin_role
        FOREIGN KEY (builtin_role_code)
        REFERENCES sys_builtin_role_catalog(role_code);

UPDATE com_roles role_ref
   SET role_type = 'SYSTEM',
       builtin_role_code = catalog.role_code,
       privileged = catalog.privileged,
       assignable_to_groups = catalog.assignable_to_groups,
       updated_at = CURRENT_TIMESTAMP
  FROM sys_builtin_role_catalog catalog
 WHERE catalog.role_code = role_ref.code;

ALTER TABLE com_roles
    ADD CONSTRAINT ck_com_roles_builtin_contract
        CHECK (
            (role_type = 'SYSTEM' AND builtin_role_code = code)
            OR (role_type = 'CUSTOM' AND builtin_role_code IS NULL));

CREATE OR REPLACE FUNCTION enforce_builtin_role_code_contract()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.role_type = 'CUSTOM'
       AND EXISTS (
           SELECT 1
             FROM sys_builtin_role_catalog catalog
            WHERE catalog.role_code = NEW.code) THEN
        RAISE EXCEPTION 'Built-in role code % is reserved', NEW.code
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_com_roles_builtin_contract
BEFORE INSERT OR UPDATE OF code, role_type, builtin_role_code
ON com_roles
FOR EACH ROW
EXECUTE FUNCTION enforce_builtin_role_code_contract();

CREATE INDEX idx_sys_builtin_role_catalog_active
    ON sys_builtin_role_catalog(lifecycle_state, role_family, sort_order, role_code);
