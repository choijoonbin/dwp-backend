-- Application administration is a scoped responsibility, not implicit access
-- to application business data. Tenant-wide bootstrap roles remain in
-- com_role_members while delegated application duties are bound to resource
-- sets in the tables below.

INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES
    ('IDENTITY_ADMIN', 'Identity administrator',
     'Manages tenant identities, groups, and provisioning without managing privileged roles.',
     'TENANT', '{"ko":"ID 관리자","en":"Identity administrator"}',
     TRUE, FALSE, 35, 'ACTIVE', 'DELEGATED'),
    ('APP_CATALOG_ADMIN', 'Application catalog administrator',
     'Governs tenant application ownership and delegated application responsibilities.',
     'TENANT', '{"ko":"앱 카탈로그 관리자","en":"Application catalog administrator"}',
     TRUE, FALSE, 36, 'ACTIVE', 'DELEGATED'),
    ('PROVIDER_TENANT_PROVISIONER', 'Provider tenant provisioner',
     'Creates and activates customer tenant foundations.',
     'PROVIDER', '{"ko":"프로바이더 테넌트 개통 담당자","en":"Provider tenant provisioner"}',
     TRUE, FALSE, 101, 'ACTIVE', 'CONTROL_PLANE'),
    ('PROVIDER_ENTITLEMENT_ADMIN', 'Provider entitlement administrator',
     'Maintains commercial tenant entitlements without tenant data access.',
     'PROVIDER', '{"ko":"프로바이더 구독 권한 관리자","en":"Provider entitlement administrator"}',
     TRUE, FALSE, 102, 'ACTIVE', 'CONTROL_PLANE'),
    ('PROVIDER_CHANGE_APPROVER', 'Provider change approver',
     'Approves gated provider operations independently from the requester.',
     'PROVIDER', '{"ko":"프로바이더 변경 승인자","en":"Provider change approver"}',
     TRUE, FALSE, 103, 'ACTIVE', 'CONTROL_PLANE')
ON CONFLICT (role_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    role_family = EXCLUDED.role_family,
    label_i18n = EXCLUDED.label_i18n,
    privileged = EXCLUDED.privileged,
    assignable_to_groups = EXCLUDED.assignable_to_groups,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    assignment_class = EXCLUDED.assignment_class,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant_id, catalog.role_code, catalog.display_name, catalog.description,
       'ACTIVE', 'SYSTEM', catalog.privileged, catalog.assignable_to_groups,
       catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE catalog.role_code IN ('IDENTITY_ADMIN', 'APP_CATALOG_ADMIN')
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    role_type = 'SYSTEM',
    privileged = EXCLUDED.privileged,
    assignable_to_groups = EXCLUDED.assignable_to_groups,
    builtin_role_code = EXCLUDED.builtin_role_code,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

-- Provider identities live in the provider host tenant. These roles are never
-- part of the customer-tenant delegation matrix.
INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name, catalog.description,
       'ACTIVE', 'SYSTEM', TRUE, FALSE, catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE tenant.code = 'default'
   AND catalog.role_code IN (
       'PROVIDER_TENANT_PROVISIONER',
       'PROVIDER_ENTITLEMENT_ADMIN',
       'PROVIDER_CHANGE_APPROVER')
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    role_type = 'SYSTEM',
    privileged = TRUE,
    assignable_to_groups = FALSE,
    builtin_role_code = EXCLUDED.builtin_role_code,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO sys_role_assignment_policies (
    grantor_role_code, target_role_code, assignment_mode, lifecycle_state)
SELECT grantor.role_code, target.role_code, 'DIRECT', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
 CROSS JOIN (VALUES ('IDENTITY_ADMIN'), ('APP_CATALOG_ADMIN')) target(role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code, lifecycle_state)
VALUES
    ('APP_CATALOG_ADMIN', 'AUDITOR', 'AUDIT_INDEPENDENCE', 'ACTIVE'),
    ('AUDITOR', 'IDENTITY_ADMIN', 'AUDIT_INDEPENDENCE', 'ACTIVE')
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant_id, 'ADMIN', resource.key, resource.name, TRUE, 1, 1
  FROM com_tenants
 CROSS JOIN (VALUES
    ('ADMIN.IDENTITY_DIRECTORY', 'Identity directory administration'),
    ('ADMIN.IDENTITY_PROVISIONING', 'Identity provisioning administration'),
    ('ADMIN.APP_GOVERNANCE', 'Application governance'),
    ('ADMIN.APP_ACCESS_REQUESTS', 'Application access requests')
 ) resource(key, name)
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code IN ('VIEW', 'MANAGE')
 WHERE role.code IN ('ADMIN', 'PLATFORM_ADMIN', 'TENANT_ADMIN')
   AND resource.key IN (
       'ADMIN.IDENTITY_DIRECTORY', 'ADMIN.IDENTITY_PROVISIONING',
       'ADMIN.APP_GOVERNANCE', 'ADMIN.APP_ACCESS_REQUESTS')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code IN ('VIEW', 'MANAGE')
 WHERE role.code = 'IDENTITY_ADMIN'
   AND resource.key IN ('ADMIN.IDENTITY_DIRECTORY', 'ADMIN.IDENTITY_PROVISIONING')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code IN ('VIEW', 'MANAGE')
 WHERE role.code = 'APP_CATALOG_ADMIN'
   AND ((resource.key = 'ADMIN.APP_GOVERNANCE'
         AND permission.code IN ('VIEW', 'MANAGE'))
        OR (resource.key = 'ADMIN.APP_ACCESS_REQUESTS'
            AND permission.code = 'VIEW'))
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = 'APP.ADMINISTRATION'
  JOIN com_permissions permission ON permission.code = 'VIEW'
 WHERE role.code IN ('IDENTITY_ADMIN', 'APP_CATALOG_ADMIN')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by;

CREATE TABLE sys_admin_responsibility_catalog (
    responsibility_code VARCHAR(50) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    risk_tier VARCHAR(10) NOT NULL,
    allowed_principal_types JSONB NOT NULL DEFAULT '["USER","GROUP"]'::jsonb,
    label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort_order INTEGER NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_admin_responsibility_code
        CHECK (responsibility_code = UPPER(BTRIM(responsibility_code))
            AND responsibility_code ~ '^[A-Z][A-Z0-9_]{2,49}$'),
    CONSTRAINT ck_admin_responsibility_risk CHECK (risk_tier IN ('L1', 'L2', 'L3')),
    CONSTRAINT ck_admin_responsibility_principals
        CHECK (jsonb_typeof(allowed_principal_types) = 'array'),
    CONSTRAINT ck_admin_responsibility_labels CHECK (jsonb_typeof(label_i18n) = 'object'),
    CONSTRAINT ck_admin_responsibility_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO sys_admin_responsibility_catalog (
    responsibility_code, display_name, description, resource_type, risk_tier,
    label_i18n, sort_order)
VALUES
    ('APP_OWNER', 'Application owner',
     'Accountable owner for application lifecycle, delegated administrators, and periodic review.',
     'APP', 'L3', '{"ko":"앱 소유자","en":"Application owner"}', 10),
    ('APP_CONFIG_ADMIN', 'Application configuration administrator',
     'Maintains configuration and connector settings for assigned applications.',
     'APP', 'L2', '{"ko":"앱 설정 관리자","en":"Application configuration administrator"}', 20),
    ('APP_ACCESS_MANAGER', 'Application access manager',
     'Fulfils approved access decisions for assigned applications.',
     'APP', 'L3', '{"ko":"앱 접근 관리자","en":"Application access manager"}', 30),
    ('APP_ACCESS_APPROVER', 'Application access approver',
     'Approves or rejects access requests independently from fulfilment.',
     'APP', 'L3', '{"ko":"앱 접근 승인자","en":"Application access approver"}', 40),
    ('APP_ACCESS_REVIEWER', 'Application access reviewer',
     'Performs periodic access certification independently from fulfilment.',
     'APP', 'L2', '{"ko":"앱 접근 검토자","en":"Application access reviewer"}', 50);

CREATE TABLE com_admin_resource_sets (
    resource_set_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    resource_type VARCHAR(30) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_admin_resource_set_key UNIQUE (tenant_id, resource_set_key),
    CONSTRAINT uk_admin_resource_set_tenant_id UNIQUE (tenant_id, resource_set_id),
    CONSTRAINT ck_admin_resource_set_key
        CHECK (resource_set_key = UPPER(BTRIM(resource_set_key))
            AND resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_admin_resource_set_type CHECK (resource_type IN ('APP')),
    CONSTRAINT ck_admin_resource_set_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE com_admin_resource_set_members (
    resource_set_member_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    resource_set_id UUID NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    resource_key VARCHAR(255) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_admin_resource_set_member_set
        FOREIGN KEY (tenant_id, resource_set_id)
        REFERENCES com_admin_resource_sets(tenant_id, resource_set_id),
    CONSTRAINT fk_admin_resource_set_member_resource
        FOREIGN KEY (tenant_id, resource_type, resource_key)
        REFERENCES com_resources(tenant_id, type, key),
    CONSTRAINT uk_admin_resource_set_member
        UNIQUE (resource_set_id, resource_type, resource_key),
    CONSTRAINT ck_admin_resource_set_member_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE com_admin_role_assignments (
    admin_role_assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    principal_type VARCHAR(20) NOT NULL,
    principal_ref VARCHAR(160) NOT NULL,
    responsibility_code VARCHAR(50) NOT NULL
        REFERENCES sys_admin_responsibility_catalog(responsibility_code),
    resource_set_id UUID NOT NULL,
    assignment_source VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    review_due_at TIMESTAMPTZ NOT NULL,
    justification VARCHAR(1000) NOT NULL,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    revoked_by BIGINT,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_admin_role_assignment_set
        FOREIGN KEY (tenant_id, resource_set_id)
        REFERENCES com_admin_resource_sets(tenant_id, resource_set_id),
    CONSTRAINT ck_admin_role_assignment_principal
        CHECK (principal_type IN ('USER', 'GROUP', 'SERVICE', 'AGENT')),
    CONSTRAINT ck_admin_role_assignment_source
        CHECK (assignment_source IN ('MANUAL', 'GROUP', 'IAM', 'PROVISIONING', 'AGENT')),
    CONSTRAINT ck_admin_role_assignment_state
        CHECK (lifecycle_state IN (
            'PENDING_APPROVAL', 'ACTIVE', 'DENIED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_admin_role_assignment_window
        CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_admin_role_assignment_justification
        CHECK (length(btrim(justification)) >= 10),
    CONSTRAINT ck_admin_role_assignment_revocation
        CHECK ((lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE', 'DENIED')
                   AND revoked_at IS NULL AND revoked_by IS NULL)
            OR (lifecycle_state IN ('REVOKED', 'EXPIRED') AND revoked_at IS NOT NULL)),
    CONSTRAINT ck_admin_role_assignment_approval
        CHECK ((lifecycle_state = 'ACTIVE' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
            OR lifecycle_state <> 'ACTIVE')
);

CREATE UNIQUE INDEX uk_admin_role_assignment_active
    ON com_admin_role_assignments (
        tenant_id, principal_type, principal_ref,
        responsibility_code, resource_set_id)
    WHERE lifecycle_state = 'ACTIVE';
CREATE INDEX idx_admin_role_assignment_principal
    ON com_admin_role_assignments (
        tenant_id, principal_type, principal_ref, lifecycle_state, valid_to);
CREATE INDEX idx_admin_role_assignment_resource
    ON com_admin_role_assignments (
        tenant_id, resource_set_id, responsibility_code, lifecycle_state);
CREATE INDEX idx_admin_role_assignment_review
    ON com_admin_role_assignments (tenant_id, review_due_at)
    WHERE lifecycle_state = 'ACTIVE';
CREATE INDEX idx_admin_role_assignment_pending
    ON com_admin_role_assignments (tenant_id, created_at)
    WHERE lifecycle_state = 'PENDING_APPROVAL';
CREATE INDEX idx_admin_resource_member_resolution
    ON com_admin_resource_set_members (
        tenant_id, resource_type, resource_key, lifecycle_state, resource_set_id);

COMMENT ON TABLE com_admin_resource_sets IS
    'Tenant-owned collections used to constrain delegated administration to explicit applications.';
COMMENT ON TABLE com_admin_role_assignments IS
    'Scoped administrative responsibility assignments with validity, provenance, review, and revocation evidence.';
COMMENT ON COLUMN com_admin_role_assignments.principal_ref IS
    'User/group numeric identifier or externally governed service/agent identifier, interpreted by principal_type.';

-- Every active tenant application receives a deterministic single-app resource
-- set. Administrators may later create multi-app sets through the API.
INSERT INTO com_admin_resource_sets (
    resource_set_id, tenant_id, resource_set_key, name, description,
    resource_type, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-set:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id,
       REGEXP_REPLACE(resource.key, '[^A-Z0-9]+', '_', 'g'),
       resource.name,
       'Administrative boundary for ' || resource.name,
       'APP', 'ACTIVE', 1, 1
  FROM com_resources resource
 WHERE resource.tenant_id IS NOT NULL
   AND resource.type = 'APP'
   AND resource.enabled = TRUE
   AND resource.key <> 'APP.ADMINISTRATION'
ON CONFLICT (tenant_id, resource_set_key) DO NOTHING;

INSERT INTO com_admin_resource_set_members (
    resource_set_member_id, tenant_id, resource_set_id,
    resource_type, resource_key, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-member:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id, resource_set.resource_set_id,
       resource.type, resource.key, 'ACTIVE', 1, 1
  FROM com_resources resource
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = resource.tenant_id
   AND resource_set.resource_set_key = REGEXP_REPLACE(resource.key, '[^A-Z0-9]+', '_', 'g')
 WHERE resource.type = 'APP'
   AND resource.enabled = TRUE
   AND resource.key <> 'APP.ADMINISTRATION'
ON CONFLICT (resource_set_id, resource_type, resource_key) DO NOTHING;

-- Local role-isolated tenant accounts. Production environments replace these
-- credentials with governed HRIS/IdP activation.
CREATE TEMP TABLE tmp_scoped_admin_review_accounts (
    email VARCHAR(255) PRIMARY KEY,
    tenant_role VARCHAR(50),
    responsibility_code VARCHAR(50),
    resource_key VARCHAR(255)
) ON COMMIT DROP;

INSERT INTO tmp_scoped_admin_review_accounts VALUES
    ('seowoo.jung@sk.com', 'IDENTITY_ADMIN', NULL, NULL),
    ('chaewon.kim@sk.com', 'APP_CATALOG_ADMIN', NULL, NULL),
    ('yujin.choi@sk.com', 'WORKSPACE_MEMBER', 'APP_OWNER', 'APP.MAIL_CALENDAR'),
    ('minseok.jang@sk.com', 'WORKSPACE_MEMBER', 'APP_CONFIG_ADMIN', 'APP.MAIL_CALENDAR'),
    ('subin.oh@sk.com', 'WORKSPACE_MEMBER', 'APP_ACCESS_MANAGER', 'APP.MAIL_CALENDAR'),
    ('taehoon.kang@sk.com', 'WORKSPACE_MEMBER', 'APP_ACCESS_APPROVER', 'APP.MAIL_CALENDAR'),
    ('yerin.moon@sk.com', 'WORKSPACE_MEMBER', 'APP_ACCESS_REVIEWER', 'APP.MAIL_CALENDAR'),
    ('jisoo.hong@sk.com', 'HR_ADMIN', NULL, NULL),
    ('doyoon.nam@sk.com', 'PEOPLE_ADMIN', NULL, NULL),
    ('doyun.kim@sk.com', 'AUDITOR', NULL, NULL),
    ('seoyeon.lee@sk.com', 'AUDIT_ADMIN', NULL, NULL);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tmp_scoped_admin_review_accounts seed
        LEFT JOIN com_users user_record
          ON user_record.tenant_id = 1
         AND user_record.email_normalized = seed.email
        WHERE user_record.user_id IS NULL) THEN
        RAISE EXCEPTION 'A scoped administration review account is missing from the workforce projection';
    END IF;
END
$$;

UPDATE com_users user_record
   SET status = 'ACTIVE', access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP, updated_by = 1
  FROM tmp_scoped_admin_review_accounts seed
 WHERE user_record.tenant_id = 1
   AND user_record.email_normalized = seed.email;

UPDATE com_user_accounts target
   SET password_hash = source.password_hash,
       status = 'ACTIVE', failed_login_count = 0,
       last_failed_at = NULL, locked_until = NULL,
       updated_at = CURRENT_TIMESTAMP, updated_by = 1
  FROM tmp_scoped_admin_review_accounts seed
  JOIN com_users user_record
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = seed.email
  JOIN com_users bootstrap
    ON bootstrap.tenant_id = 1
   AND bootstrap.email_normalized = 'admin@dwp.local'
  JOIN com_user_accounts source
    ON source.tenant_id = bootstrap.tenant_id
   AND source.user_id = bootstrap.user_id
   AND source.provider_type = 'LOCAL'
   AND source.provider_id = 'local'
 WHERE target.tenant_id = user_record.tenant_id
   AND target.user_id = user_record.user_id
   AND target.provider_type = 'LOCAL'
   AND target.provider_id = 'local';

DELETE FROM com_role_members membership
USING com_users user_record, tmp_scoped_admin_review_accounts seed
WHERE membership.tenant_id = user_record.tenant_id
  AND membership.user_id = user_record.user_id
  AND user_record.tenant_id = 1
  AND user_record.email_normalized = seed.email;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT user_record.tenant_id, role.role_id, user_record.user_id, 1, 1
  FROM tmp_scoped_admin_review_accounts seed
  JOIN com_users user_record
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = seed.email
  JOIN com_roles role
    ON role.tenant_id = user_record.tenant_id
   AND role.code = seed.tenant_role
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

INSERT INTO com_admin_role_assignments (
    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
    responsibility_code, resource_set_id, assignment_source,
    lifecycle_state, valid_from, review_due_at, justification,
    approved_by, approved_at, created_by, updated_by)
SELECT md5('scoped-admin:' || user_record.user_id || ':' || seed.responsibility_code
           || ':' || seed.resource_key)::uuid,
       user_record.tenant_id, 'USER', user_record.user_id::text,
       seed.responsibility_code, resource_set.resource_set_id,
       'PROVISIONING', 'ACTIVE', CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP + INTERVAL '180 days',
       'Local role-isolated application governance verification assignment.',
       tenant_admin.user_id, CURRENT_TIMESTAMP, tenant_admin.user_id, tenant_admin.user_id
  FROM tmp_scoped_admin_review_accounts seed
  JOIN com_users user_record
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = seed.email
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = user_record.tenant_id
   AND resource_set.resource_set_key = REGEXP_REPLACE(seed.resource_key, '[^A-Z0-9]+', '_', 'g')
  JOIN com_users tenant_admin
    ON tenant_admin.tenant_id = user_record.tenant_id
   AND tenant_admin.email_normalized = 'hyunwoo.park@sk.com'
 WHERE seed.responsibility_code IS NOT NULL
ON CONFLICT DO NOTHING;

-- Local provider duty accounts are kept outside the customer workforce source.
CREATE TEMP TABLE tmp_provider_duty_accounts (
    user_id BIGINT PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    job_title VARCHAR(160) NOT NULL,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_provider_duty_accounts VALUES
    (900010, 'provider.provisioner@dwp.local', 'Provider Tenant Provisioner',
     'Tenant provisioning operator', 'PROVIDER_TENANT_PROVISIONER'),
    (900011, 'provider.entitlement@dwp.local', 'Provider Entitlement Administrator',
     'Subscription entitlement administrator', 'PROVIDER_ENTITLEMENT_ADMIN'),
    (900012, 'provider.approver@dwp.local', 'Provider Change Approver',
     'Independent change approver', 'PROVIDER_CHANGE_APPROVER'),
    (900013, 'provider.operator@dwp.local', 'Provider Service Operator',
     'Service operations engineer', 'PROVIDER_OPERATOR'),
    (900014, 'provider.support@dwp.local', 'Provider Support Operator',
     'Customer support engineer', 'PROVIDER_SUPPORT'),
    (900015, 'provider.auditor@dwp.local', 'Provider Auditor',
     'Provider compliance auditor', 'PROVIDER_AUDITOR');

INSERT INTO com_users (
    user_id, tenant_id, display_name, email, status, job_title,
    preferred_locale, source_type, created_by, updated_by)
SELECT seed.user_id, tenant.tenant_id, seed.display_name, seed.email,
       'ACTIVE', seed.job_title, 'ko-KR', 'LOCAL', 1, 1
  FROM tmp_provider_duty_accounts seed
  JOIN com_tenants tenant ON tenant.code = 'default'
ON CONFLICT (user_id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    email = EXCLUDED.email,
    status = 'ACTIVE',
    job_title = EXCLUDED.job_title,
    preferred_locale = EXCLUDED.preferred_locale,
    source_type = 'LOCAL',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO com_user_accounts (
    tenant_id, user_id, provider_type, provider_id, principal,
    password_hash, status, created_by, updated_by)
SELECT tenant.tenant_id, seed.user_id, 'LOCAL', 'local', seed.email,
       source.password_hash, 'ACTIVE', 1, 1
  FROM tmp_provider_duty_accounts seed
  JOIN com_tenants tenant ON tenant.code = 'default'
  JOIN com_users bootstrap
    ON bootstrap.tenant_id = tenant.tenant_id
   AND bootstrap.email_normalized = 'admin@dwp.local'
  JOIN com_user_accounts source
    ON source.tenant_id = bootstrap.tenant_id
   AND source.user_id = bootstrap.user_id
   AND source.provider_type = 'LOCAL'
   AND source.provider_id = 'local'
ON CONFLICT (tenant_id, provider_type, provider_id, principal) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    password_hash = EXCLUDED.password_hash,
    status = 'ACTIVE',
    failed_login_count = 0,
    last_failed_at = NULL,
    locked_until = NULL,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

DELETE FROM com_role_members membership
USING tmp_provider_duty_accounts seed
WHERE membership.tenant_id = 1 AND membership.user_id = seed.user_id;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT role.tenant_id, role.role_id, seed.user_id, 1, 1
  FROM tmp_provider_duty_accounts seed
  JOIN com_roles role ON role.tenant_id = 1 AND role.code = seed.role_code
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('com_users', 'user_id'),
    (SELECT MAX(user_id) FROM com_users), TRUE);

-- Force a fresh session after the authority model changes.
UPDATE sys_auth_sessions
   SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP, updated_by = 1
 WHERE revoked_at IS NULL;
