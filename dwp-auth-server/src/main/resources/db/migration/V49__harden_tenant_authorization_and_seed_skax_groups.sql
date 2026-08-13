-- Tenant product entitlements, workforce baseline access, delegated operations,
-- and application-scoped duties are separate authorization layers. These
-- templates are the single source used when a new tenant is provisioned.
CREATE TABLE sys_tenant_resource_templates (
    resource_key VARCHAR(255) PRIMARY KEY,
    resource_type VARCHAR(30) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    required_entitlement VARCHAR(100),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tenant_resource_template_type
        CHECK (resource_type IN ('APP', 'ADMIN', 'DATA', 'ACTION')),
    CONSTRAINT ck_tenant_resource_template_key
        CHECK (resource_key LIKE resource_type || '.%'),
    CONSTRAINT ck_tenant_resource_template_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE sys_tenant_role_permission_templates (
    role_code VARCHAR(50) NOT NULL
        REFERENCES sys_builtin_role_catalog(role_code),
    resource_key VARCHAR(255) NOT NULL
        REFERENCES sys_tenant_resource_templates(resource_key),
    permission_code VARCHAR(50) NOT NULL
        REFERENCES com_permissions(code),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_tenant_role_permission_template
        PRIMARY KEY (role_code, resource_key, permission_code),
    CONSTRAINT ck_tenant_role_permission_template_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('APP.ADMINISTRATION', 'APP', 'Administration', NULL),
    ('APP.WORK', 'APP', 'Work', 'core.workspace'),
    ('APP.ACTIVITY', 'APP', 'Activity', 'core.workspace'),
    ('APP.APPS', 'APP', 'Apps', 'core.workspace'),
    ('APP.MAIL_CALENDAR', 'APP', 'Mail and calendar', 'core.workspace'),
    ('APP.COLLABORATION', 'APP', 'Collaboration', 'core.workspace'),
    ('APP.COMMUNICATIONS', 'APP', 'Employee communications', 'core.workspace'),
    ('APP.EMPLOYEE_SERVICES', 'APP', 'Employee services', 'core.workspace'),
    ('APP.KNOWLEDGE', 'APP', 'Knowledge', 'core.workspace'),
    ('APP.BUSINESS_ERP', 'APP', 'Business ERP', 'core.workspace'),
    ('APP.LEGACY_OPERATIONS', 'APP', 'Legacy operations', 'core.workspace'),
    ('APP.ASK', 'APP', 'Ask DWP', 'ai.agent-runtime'),
    ('APP.HRIS', 'APP', 'HRIS', 'core.people'),
    ('APP.PEOPLE_DIRECTORY', 'APP', 'People directory', 'core.people'),
    ('APP.WORKFORCE_MANAGEMENT', 'APP', 'Workforce management', 'core.people'),
    ('ADMIN.API_MONITORING', 'ADMIN', 'API monitoring', NULL),
    ('ADMIN.IDENTITY_DIRECTORY', 'ADMIN', 'Identity directory administration', NULL),
    ('ADMIN.IDENTITY_PROVISIONING', 'ADMIN', 'Identity provisioning administration', NULL),
    ('ADMIN.APP_GOVERNANCE', 'ADMIN', 'Application governance', NULL),
    ('ADMIN.APP_ACCESS_REQUESTS', 'ADMIN', 'Application access requests', NULL),
    ('ADMIN.AUDIT_VIEW', 'ADMIN', 'Audit and compliance overview', NULL),
    ('ADMIN.AUDIT_INVESTIGATE', 'ADMIN', 'Audit findings and investigations', NULL),
    ('ADMIN.AUDIT_EXPORT', 'ADMIN', 'Audit evidence export', NULL),
    ('ADMIN.AUDIT_CONFIGURE', 'ADMIN', 'Audit retention and integrity configuration', NULL),
    ('ADMIN.COMMUNICATIONS', 'ADMIN', 'Employee communications administration', 'core.workspace'),
    ('ADMIN.SERVICE_CATALOG', 'ADMIN', 'Employee service catalog administration', 'core.workspace'),
    ('ADMIN.SERVICE_OPERATIONS', 'ADMIN', 'Employee service request operations', 'core.workspace'),
    ('ADMIN.PRODUCTIVITY_CONNECTOR', 'ADMIN', 'Productivity connector control plane', 'core.workspace'),
    ('ADMIN.SAVED_VIEW_CUSTODY', 'ADMIN', 'Saved view ownership and retention governance', 'core.workspace'),
    ('ADMIN.WORKFORCE_ACCESS', 'ADMIN', 'Workforce access governance', 'core.people'),
    ('DATA.WORKFORCE', 'DATA', 'Workforce projection', 'core.people'),
    ('ACTION.WORKFORCE_REFERENCE', 'ACTION', 'Workforce reference data', 'core.people'),
    ('ACTION.WORKFORCE_DATA_OPERATIONS', 'ACTION', 'Workforce data operations', 'core.people');

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    -- The workforce baseline contains only first-party employee experiences.
    -- Connector-backed and sensitive business apps are granted by access package.
    ('WORKSPACE_MEMBER', 'APP.WORK', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.WORK', 'UPDATE'),
    ('WORKSPACE_MEMBER', 'APP.ACTIVITY', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.APPS', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.APPS', 'UPDATE'),
    ('WORKSPACE_MEMBER', 'APP.ASK', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.COMMUNICATIONS', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.EMPLOYEE_SERVICES', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.HRIS', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.PEOPLE_DIRECTORY', 'VIEW'),

    -- The tenant administrator bootstraps governance but does not publish
    -- communications, operate services, or decide application access.
    ('TENANT_ADMIN', 'APP.ADMINISTRATION', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.API_MONITORING', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.IDENTITY_DIRECTORY', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.IDENTITY_DIRECTORY', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.IDENTITY_PROVISIONING', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.IDENTITY_PROVISIONING', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.APP_GOVERNANCE', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.APP_GOVERNANCE', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.COMMUNICATIONS', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.SERVICE_CATALOG', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.SERVICE_OPERATIONS', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.PRODUCTIVITY_CONNECTOR', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.PRODUCTIVITY_CONNECTOR', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.SAVED_VIEW_CUSTODY', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.SAVED_VIEW_CUSTODY', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.WORKFORCE_ACCESS', 'VIEW'),

    ('IDENTITY_ADMIN', 'APP.ADMINISTRATION', 'VIEW'),
    ('IDENTITY_ADMIN', 'ADMIN.IDENTITY_DIRECTORY', 'VIEW'),
    ('IDENTITY_ADMIN', 'ADMIN.IDENTITY_DIRECTORY', 'MANAGE'),
    ('IDENTITY_ADMIN', 'ADMIN.IDENTITY_PROVISIONING', 'VIEW'),
    ('IDENTITY_ADMIN', 'ADMIN.IDENTITY_PROVISIONING', 'MANAGE'),

    ('APP_CATALOG_ADMIN', 'APP.ADMINISTRATION', 'VIEW'),
    ('APP_CATALOG_ADMIN', 'ADMIN.APP_GOVERNANCE', 'VIEW'),
    ('APP_CATALOG_ADMIN', 'ADMIN.APP_GOVERNANCE', 'MANAGE'),
    ('APP_CATALOG_ADMIN', 'ADMIN.APP_ACCESS_REQUESTS', 'VIEW'),

    ('COMMUNICATIONS_EDITOR', 'APP.ADMINISTRATION', 'VIEW'),
    ('COMMUNICATIONS_EDITOR', 'APP.COMMUNICATIONS', 'VIEW'),
    ('COMMUNICATIONS_EDITOR', 'ADMIN.COMMUNICATIONS', 'VIEW'),
    ('COMMUNICATIONS_EDITOR', 'ADMIN.COMMUNICATIONS', 'CREATE'),
    ('COMMUNICATIONS_EDITOR', 'ADMIN.COMMUNICATIONS', 'UPDATE'),
    ('COMMUNICATIONS_PUBLISHER', 'APP.ADMINISTRATION', 'VIEW'),
    ('COMMUNICATIONS_PUBLISHER', 'APP.COMMUNICATIONS', 'VIEW'),
    ('COMMUNICATIONS_PUBLISHER', 'ADMIN.COMMUNICATIONS', 'VIEW'),
    ('COMMUNICATIONS_PUBLISHER', 'ADMIN.COMMUNICATIONS', 'APPROVE'),
    ('COMMUNICATIONS_PUBLISHER', 'ADMIN.COMMUNICATIONS', 'MANAGE'),

    ('SERVICE_CATALOG_MANAGER', 'APP.ADMINISTRATION', 'VIEW'),
    ('SERVICE_CATALOG_MANAGER', 'APP.EMPLOYEE_SERVICES', 'VIEW'),
    ('SERVICE_CATALOG_MANAGER', 'ADMIN.SERVICE_CATALOG', 'VIEW'),
    ('SERVICE_CATALOG_MANAGER', 'ADMIN.SERVICE_CATALOG', 'CREATE'),
    ('SERVICE_CATALOG_MANAGER', 'ADMIN.SERVICE_CATALOG', 'UPDATE'),
    ('SERVICE_CATALOG_MANAGER', 'ADMIN.SERVICE_CATALOG', 'MANAGE'),
    ('SERVICE_AGENT', 'APP.ADMINISTRATION', 'VIEW'),
    ('SERVICE_AGENT', 'APP.EMPLOYEE_SERVICES', 'VIEW'),
    ('SERVICE_AGENT', 'ADMIN.SERVICE_OPERATIONS', 'VIEW'),
    ('SERVICE_AGENT', 'ADMIN.SERVICE_OPERATIONS', 'UPDATE'),
    ('SERVICE_AGENT', 'ADMIN.SERVICE_OPERATIONS', 'MANAGE'),

    ('HR_ADMIN', 'APP.ADMINISTRATION', 'VIEW'),
    ('HR_ADMIN', 'APP.HRIS', 'VIEW'),
    ('HR_ADMIN', 'APP.PEOPLE_DIRECTORY', 'VIEW'),
    ('HR_ADMIN', 'APP.WORKFORCE_MANAGEMENT', 'VIEW'),
    ('HR_ADMIN', 'DATA.WORKFORCE', 'MANAGE'),
    ('HR_ADMIN', 'ACTION.WORKFORCE_REFERENCE', 'MANAGE'),
    ('HR_ADMIN', 'ACTION.WORKFORCE_DATA_OPERATIONS', 'MANAGE'),
    ('HR_ADMIN', 'ADMIN.WORKFORCE_ACCESS', 'VIEW'),
    ('HR_ADMIN', 'ADMIN.WORKFORCE_ACCESS', 'MANAGE'),
    ('PEOPLE_ADMIN', 'APP.ADMINISTRATION', 'VIEW'),
    ('PEOPLE_ADMIN', 'APP.HRIS', 'VIEW'),
    ('PEOPLE_ADMIN', 'APP.PEOPLE_DIRECTORY', 'VIEW'),
    ('PEOPLE_ADMIN', 'APP.WORKFORCE_MANAGEMENT', 'VIEW'),
    ('PEOPLE_ADMIN', 'DATA.WORKFORCE', 'VIEW'),
    ('PEOPLE_ADMIN', 'ACTION.WORKFORCE_REFERENCE', 'VIEW'),
    ('PEOPLE_ADMIN', 'ACTION.WORKFORCE_DATA_OPERATIONS', 'VIEW'),
    ('PEOPLE_ADMIN', 'ADMIN.WORKFORCE_ACCESS', 'VIEW'),

    ('AUDITOR', 'APP.ADMINISTRATION', 'VIEW'),
    ('AUDITOR', 'ADMIN.AUDIT_VIEW', 'VIEW'),
    ('AUDITOR', 'ADMIN.AUDIT_INVESTIGATE', 'UPDATE'),
    ('AUDITOR', 'ADMIN.AUDIT_EXPORT', 'EXPORT'),
    ('AUDIT_ADMIN', 'APP.ADMINISTRATION', 'VIEW'),
    ('AUDIT_ADMIN', 'ADMIN.AUDIT_VIEW', 'VIEW'),
    ('AUDIT_ADMIN', 'ADMIN.AUDIT_INVESTIGATE', 'UPDATE'),
    ('AUDIT_ADMIN', 'ADMIN.AUDIT_EXPORT', 'EXPORT'),
    ('AUDIT_ADMIN', 'ADMIN.AUDIT_CONFIGURE', 'MANAGE');

-- Every customer tenant receives the complete role catalog. Product
-- entitlements control resources, not whether a governance role exists.
INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name,
       catalog.description, 'ACTIVE', 'SYSTEM', catalog.privileged,
       catalog.assignable_to_groups, catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE catalog.lifecycle_state = 'ACTIVE'
   AND catalog.assignment_class <> 'CONTROL_PLANE'
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

-- Built-in permissions on standard resources are centrally managed. Remove
-- legacy wildcard grants before materializing the explicit matrix.
DELETE FROM com_role_permissions role_permission
USING com_roles role, com_resources resource
 WHERE role_permission.tenant_id = role.tenant_id
   AND role_permission.role_id = role.role_id
   AND role_permission.resource_id = resource.resource_id
   AND resource.tenant_id = role.tenant_id
   AND role.builtin_role_code IS NOT NULL
   AND EXISTS (
       SELECT 1
         FROM sys_tenant_role_permission_templates template
        WHERE template.role_code = role.code)
   AND EXISTS (
       SELECT 1
         FROM sys_tenant_resource_templates template
        WHERE template.resource_key = resource.key);

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id,
    effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id,
       permission.permission_id, 'ALLOW', 1, 1
  FROM sys_tenant_role_permission_templates template
  JOIN com_roles role
    ON role.code = template.role_code
   AND role.status = 'ACTIVE'
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = template.resource_key
   AND resource.enabled = TRUE
  JOIN com_permissions permission
    ON permission.code = template.permission_code
 WHERE template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

-- A managed workforce identity always retains the workspace baseline. Provider
-- and platform identities remain isolated from customer application access.
INSERT INTO com_role_members (tenant_id, role_id, user_id, created_by, updated_by)
SELECT user_record.tenant_id, baseline.role_id, user_record.user_id, 1, 1
  FROM com_users user_record
  JOIN com_roles baseline
    ON baseline.tenant_id = user_record.tenant_id
   AND baseline.code = 'WORKSPACE_MEMBER'
 WHERE user_record.status IN ('ACTIVE', 'INVITED')
   AND NOT EXISTS (
       SELECT 1
         FROM com_role_members control_member
         JOIN com_roles control_role
           ON control_role.tenant_id = control_member.tenant_id
          AND control_role.role_id = control_member.role_id
         JOIN sys_builtin_role_catalog control_catalog
           ON control_catalog.role_code = control_role.code
        WHERE control_member.tenant_id = user_record.tenant_id
          AND control_member.user_id = user_record.user_id
          AND control_catalog.assignment_class = 'CONTROL_PLANE')
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

-- Ensure every enabled application has a materialized governance boundary.
INSERT INTO com_admin_resource_sets (
    resource_set_id, tenant_id, resource_set_key, name, description,
    resource_type, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-set:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id,
       REGEXP_REPLACE(resource.key, '[^A-Z0-9]+', '_', 'g'),
       resource.name, 'Administrative boundary for ' || resource.name,
       'APP', 'ACTIVE', 1, 1
  FROM com_resources resource
 WHERE resource.type = 'APP'
   AND resource.enabled = TRUE
   AND resource.key <> 'APP.ADMINISTRATION'
ON CONFLICT (tenant_id, resource_set_key) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_admin_resource_set_members (
    resource_set_member_id, tenant_id, resource_set_id,
    resource_type, resource_key, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-member:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id, resource_set.resource_set_id,
       resource.type, resource.key, 'ACTIVE', 1, 1
  FROM com_resources resource
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = resource.tenant_id
   AND resource_set.resource_set_key =
       REGEXP_REPLACE(resource.key, '[^A-Z0-9]+', '_', 'g')
 WHERE resource.type = 'APP'
   AND resource.enabled = TRUE
   AND resource.key <> 'APP.ADMINISTRATION'
ON CONFLICT (resource_set_id, resource_type, resource_key) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

-- SKAX development groups model the future HRIS/IdP projection. Production
-- delivery replaces membership with governed SCIM or IAM synchronization.
INSERT INTO com_groups (
    tenant_id, group_key, display_name, description,
    source_type, external_id, status, created_by, updated_by)
SELECT tenant.tenant_id, seed.group_key, seed.display_name, seed.description,
       'LOCAL', 'seed:' || lower(seed.group_key), 'ACTIVE', 1, 1
  FROM com_tenants tenant
 CROSS JOIN (VALUES
    ('SKAX_ALL_EMPLOYEES', 'SKAX 전체 구성원', '전사 표준 앱 접근 패키지 대상 그룹'),
    ('SKAX_COMMUNICATIONS_EDITORS', '사내 소식 편집자', '사내 소식 작성 및 편집 담당 그룹'),
    ('SKAX_SERVICE_CATALOG_MANAGERS', '서비스 카탈로그 관리자', '임직원 서비스 카탈로그 운영 그룹'),
    ('SKAX_SERVICE_AGENTS', '서비스 처리 담당자', '임직원 서비스 요청 처리 그룹'),
    ('SKAX_APP_OWNERS', '애플리케이션 소유자', '애플리케이션 수명주기 책임 그룹'),
    ('SKAX_APP_CONFIGURATION_ADMINS', '애플리케이션 설정 관리자', '애플리케이션 설정 운영 그룹'),
    ('SKAX_APP_ACCESS_MANAGERS', '앱 접근 이행 담당자', '승인된 앱 접근 권한을 이행하는 그룹'),
    ('SKAX_APP_ACCESS_APPROVERS', '앱 접근 승인자', '앱 접근 요청을 독립적으로 승인하는 그룹'),
    ('SKAX_APP_ACCESS_REVIEWERS', '앱 접근 검토자', '앱 접근 권한을 정기 검토하는 그룹'),
    ('SKAX_ERP_USERS', 'ERP 사용자', '재무 및 ERP 업무 애플리케이션 접근 그룹'),
    ('SKAX_LEGACY_OPERATIONS_USERS', '레거시 운영 사용자', '레거시 운영 애플리케이션 접근 그룹')
 ) seed(group_key, display_name, description)
 WHERE tenant.code = 'default' AND tenant.name = 'SKAX'
ON CONFLICT (tenant_id, group_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    source_type = 'LOCAL',
    external_id = EXCLUDED.external_id,
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_group_members (
    tenant_id, group_id, user_id, source_type, created_by, updated_by)
SELECT access_group.tenant_id, access_group.group_id,
       user_record.user_id, 'LOCAL', 1, 1
  FROM com_groups access_group
  JOIN com_tenants tenant
    ON tenant.tenant_id = access_group.tenant_id
   AND tenant.code = 'default'
   AND tenant.name = 'SKAX'
  JOIN com_users user_record
    ON user_record.tenant_id = access_group.tenant_id
   AND user_record.status IN ('ACTIVE', 'INVITED')
   AND user_record.email_normalized LIKE '%@sk.com'
 WHERE access_group.group_key = 'SKAX_ALL_EMPLOYEES'
ON CONFLICT (tenant_id, group_id, user_id) DO NOTHING;

CREATE TEMP TABLE tmp_skax_seed_group_members (
    group_key VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    PRIMARY KEY (group_key, email)
) ON COMMIT DROP;

INSERT INTO tmp_skax_seed_group_members VALUES
    ('SKAX_COMMUNICATIONS_EDITORS', 'gunwoo.choi@sk.com'),
    ('SKAX_SERVICE_CATALOG_MANAGERS', 'seojin.yoon@sk.com'),
    ('SKAX_SERVICE_AGENTS', 'jiwoo.bae@sk.com'),
    ('SKAX_APP_OWNERS', 'yujin.choi@sk.com'),
    ('SKAX_APP_CONFIGURATION_ADMINS', 'minseok.jang@sk.com'),
    ('SKAX_APP_ACCESS_MANAGERS', 'subin.oh@sk.com'),
    ('SKAX_APP_ACCESS_APPROVERS', 'taehoon.kang@sk.com'),
    ('SKAX_APP_ACCESS_REVIEWERS', 'yerin.moon@sk.com'),
    ('SKAX_ERP_USERS', 'seungmin.yoo@sk.com'),
    ('SKAX_ERP_USERS', 'taeyeon.kim@sk.com'),
    ('SKAX_ERP_USERS', 'yejun.shin@sk.com'),
    ('SKAX_LEGACY_OPERATIONS_USERS', 'minseo.kim@sk.com'),
    ('SKAX_LEGACY_OPERATIONS_USERS', 'jiho.park@sk.com'),
    ('SKAX_LEGACY_OPERATIONS_USERS', 'jiwoo.bae@sk.com'),
    ('SKAX_LEGACY_OPERATIONS_USERS', 'junho.song@sk.com'),
    ('SKAX_LEGACY_OPERATIONS_USERS', 'minsung.kwon@sk.com');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_skax_seed_group_members seed
          LEFT JOIN com_tenants tenant
            ON tenant.code = 'default' AND tenant.name = 'SKAX'
          LEFT JOIN com_users user_record
            ON user_record.tenant_id = tenant.tenant_id
           AND user_record.email_normalized = seed.email
         WHERE user_record.user_id IS NULL) THEN
        RAISE EXCEPTION 'A SKAX authorization-group seed identity is missing';
    END IF;
END
$$;

INSERT INTO com_group_members (
    tenant_id, group_id, user_id, source_type, created_by, updated_by)
SELECT access_group.tenant_id, access_group.group_id,
       user_record.user_id, 'LOCAL', 1, 1
  FROM tmp_skax_seed_group_members seed
  JOIN com_tenants tenant
    ON tenant.code = 'default' AND tenant.name = 'SKAX'
  JOIN com_groups access_group
    ON access_group.tenant_id = tenant.tenant_id
   AND access_group.group_key = seed.group_key
  JOIN com_users user_record
    ON user_record.tenant_id = tenant.tenant_id
   AND user_record.email_normalized = seed.email
ON CONFLICT (tenant_id, group_id, user_id) DO NOTHING;

CREATE TEMP TABLE tmp_skax_group_roles (
    group_key VARCHAR(100) PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_skax_group_roles VALUES
    ('SKAX_COMMUNICATIONS_EDITORS', 'COMMUNICATIONS_EDITOR'),
    ('SKAX_SERVICE_CATALOG_MANAGERS', 'SERVICE_CATALOG_MANAGER'),
    ('SKAX_SERVICE_AGENTS', 'SERVICE_AGENT');

INSERT INTO com_group_role_assignments (
    tenant_id, group_id, role_id, assignment_type, scope_type,
    lifecycle_state, justification, created_by, updated_by)
SELECT access_group.tenant_id, access_group.group_id, role.role_id,
       'ACTIVE', 'TENANT', 'ACTIVE',
       'SKAX 업무 그룹에 승인된 기능 운영 역할을 부여합니다.', 1, 1
  FROM tmp_skax_group_roles seed
  JOIN com_tenants tenant
    ON tenant.code = 'default' AND tenant.name = 'SKAX'
  JOIN com_groups access_group
    ON access_group.tenant_id = tenant.tenant_id
   AND access_group.group_key = seed.group_key
  JOIN com_roles role
    ON role.tenant_id = tenant.tenant_id
   AND role.code = seed.role_code
ON CONFLICT (tenant_id, group_id, role_id, scope_type, COALESCE(scope_ref, ''))
    WHERE lifecycle_state = 'ACTIVE'
DO NOTHING;

-- Remove duplicate direct grants now represented by governed groups.
DELETE FROM com_role_members membership
USING com_tenants tenant, com_users user_record, com_roles role,
      tmp_skax_seed_group_members member_seed, tmp_skax_group_roles role_seed
 WHERE tenant.code = 'default' AND tenant.name = 'SKAX'
   AND membership.tenant_id = tenant.tenant_id
   AND user_record.tenant_id = membership.tenant_id
   AND user_record.user_id = membership.user_id
   AND role.tenant_id = membership.tenant_id
   AND role.role_id = membership.role_id
   AND member_seed.email = user_record.email_normalized
   AND role_seed.group_key = member_seed.group_key
   AND role_seed.role_code = role.code;

CREATE TEMP TABLE tmp_skax_access_packages (
    group_key VARCHAR(100) NOT NULL,
    resource_key VARCHAR(255) NOT NULL,
    PRIMARY KEY (group_key, resource_key)
) ON COMMIT DROP;

INSERT INTO tmp_skax_access_packages VALUES
    ('SKAX_ALL_EMPLOYEES', 'APP.MAIL_CALENDAR'),
    ('SKAX_ALL_EMPLOYEES', 'APP.COLLABORATION'),
    ('SKAX_ALL_EMPLOYEES', 'APP.KNOWLEDGE'),
    ('SKAX_ERP_USERS', 'APP.BUSINESS_ERP'),
    ('SKAX_LEGACY_OPERATIONS_USERS', 'APP.LEGACY_OPERATIONS');

INSERT INTO com_principal_resource_grants (
    principal_resource_grant_id, tenant_id, principal_type, principal_ref,
    resource_id, permission_id, source_type, source_ref, lifecycle_state,
    justification, granted_by, created_by, updated_by)
SELECT gen_random_uuid(), access_group.tenant_id, 'GROUP', access_group.group_id::text,
       resource.resource_id, permission.permission_id, 'ACCESS_PACKAGE',
       'skax-package:' || lower(seed.group_key) || ':' || lower(seed.resource_key),
       'ACTIVE', 'SKAX 업무 그룹에 승인된 애플리케이션 접근 패키지입니다.',
       tenant_admin.user_id, tenant_admin.user_id, tenant_admin.user_id
  FROM tmp_skax_access_packages seed
  JOIN com_tenants tenant
    ON tenant.code = 'default' AND tenant.name = 'SKAX'
  JOIN com_groups access_group
    ON access_group.tenant_id = tenant.tenant_id
   AND access_group.group_key = seed.group_key
  JOIN com_resources resource
    ON resource.tenant_id = tenant.tenant_id
   AND resource.key = seed.resource_key
   AND resource.enabled = TRUE
  JOIN com_permissions permission ON permission.code = 'VIEW'
  JOIN com_users tenant_admin
    ON tenant_admin.tenant_id = tenant.tenant_id
   AND tenant_admin.email_normalized = 'hyunwoo.park@sk.com'
ON CONFLICT (tenant_id, source_type, source_ref) DO UPDATE SET
    principal_type = EXCLUDED.principal_type,
    principal_ref = EXCLUDED.principal_ref,
    resource_id = EXCLUDED.resource_id,
    permission_id = EXCLUDED.permission_id,
    lifecycle_state = 'ACTIVE',
    valid_from = CURRENT_TIMESTAMP,
    valid_to = NULL,
    revoked_at = NULL,
    revoked_by = NULL,
    revocation_reason = NULL,
    justification = EXCLUDED.justification,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

-- Move application responsibility from named users to replaceable groups.
UPDATE com_admin_role_assignments assignment
   SET lifecycle_state = 'REVOKED',
       revoked_by = tenant_admin.user_id,
       revoked_at = CURRENT_TIMESTAMP,
       revocation_reason = 'Migrated to governed SKAX application responsibility groups.',
       version = assignment.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = tenant_admin.user_id
  FROM com_tenants tenant, com_users tenant_admin
 WHERE tenant.code = 'default' AND tenant.name = 'SKAX'
   AND assignment.tenant_id = tenant.tenant_id
   AND tenant_admin.tenant_id = tenant.tenant_id
   AND tenant_admin.email_normalized = 'hyunwoo.park@sk.com'
   AND assignment.principal_type = 'USER'
   AND assignment.lifecycle_state = 'ACTIVE'
   AND assignment.responsibility_code IN (
       'APP_OWNER', 'APP_CONFIG_ADMIN', 'APP_ACCESS_MANAGER',
       'APP_ACCESS_APPROVER', 'APP_ACCESS_REVIEWER');

CREATE TEMP TABLE tmp_skax_app_responsibilities (
    group_key VARCHAR(100) NOT NULL,
    responsibility_code VARCHAR(50) NOT NULL,
    access_apps_only BOOLEAN NOT NULL,
    PRIMARY KEY (group_key, responsibility_code)
) ON COMMIT DROP;

INSERT INTO tmp_skax_app_responsibilities VALUES
    ('SKAX_APP_OWNERS', 'APP_OWNER', FALSE),
    ('SKAX_APP_CONFIGURATION_ADMINS', 'APP_CONFIG_ADMIN', FALSE),
    ('SKAX_APP_ACCESS_MANAGERS', 'APP_ACCESS_MANAGER', TRUE),
    ('SKAX_APP_ACCESS_APPROVERS', 'APP_ACCESS_APPROVER', TRUE),
    ('SKAX_APP_ACCESS_REVIEWERS', 'APP_ACCESS_REVIEWER', TRUE);

INSERT INTO com_admin_role_assignments (
    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
    responsibility_code, resource_set_id, assignment_source,
    lifecycle_state, valid_from, review_due_at, justification,
    approved_by, approved_at, decision_reason, created_by, updated_by)
SELECT gen_random_uuid(), tenant.tenant_id, 'GROUP', access_group.group_id::text,
       seed.responsibility_code, resource_set.resource_set_id, 'GROUP',
       'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '180 days',
       'SKAX 애플리케이션 책임을 업무 그룹 단위로 운영합니다.',
       tenant_admin.user_id, CURRENT_TIMESTAMP,
       'Approved as part of the governed SKAX authorization baseline.',
       tenant_admin.user_id, tenant_admin.user_id
  FROM tmp_skax_app_responsibilities seed
  JOIN com_tenants tenant
    ON tenant.code = 'default' AND tenant.name = 'SKAX'
  JOIN com_groups access_group
    ON access_group.tenant_id = tenant.tenant_id
   AND access_group.group_key = seed.group_key
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = tenant.tenant_id
   AND resource_set.lifecycle_state = 'ACTIVE'
  JOIN com_admin_resource_set_members member
    ON member.tenant_id = resource_set.tenant_id
   AND member.resource_set_id = resource_set.resource_set_id
   AND member.lifecycle_state = 'ACTIVE'
  JOIN com_users tenant_admin
    ON tenant_admin.tenant_id = tenant.tenant_id
   AND tenant_admin.email_normalized = 'hyunwoo.park@sk.com'
 WHERE member.resource_type = 'APP'
   AND member.resource_key <> 'APP.ADMINISTRATION'
   AND (seed.access_apps_only = FALSE OR member.resource_key IN (
       'APP.MAIL_CALENDAR', 'APP.COLLABORATION', 'APP.KNOWLEDGE',
       'APP.BUSINESS_ERP', 'APP.LEGACY_OPERATIONS'))
   AND NOT EXISTS (
       SELECT 1
         FROM com_admin_role_assignments existing
        WHERE existing.tenant_id = tenant.tenant_id
          AND existing.principal_type = 'GROUP'
          AND existing.principal_ref = access_group.group_id::text
          AND existing.responsibility_code = seed.responsibility_code
          AND existing.resource_set_id = resource_set.resource_set_id
          AND existing.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE'));

-- Non-SKAX bootstrap tenants keep a named accountable owner until their IAM
-- groups and delegated responsibilities are configured.
INSERT INTO com_admin_role_assignments (
    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
    responsibility_code, resource_set_id, assignment_source,
    lifecycle_state, valid_from, review_due_at, justification,
    approved_by, approved_at, decision_reason, created_by, updated_by)
SELECT gen_random_uuid(), resource_set.tenant_id, 'USER', administrator.user_id::text,
       'APP_OWNER', resource_set.resource_set_id, 'PROVISIONING', 'ACTIVE',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '90 days',
       'Bootstrap owner pending customer IAM responsibility assignment.',
       administrator.user_id, CURRENT_TIMESTAMP,
       'Created by tenant provisioning to avoid an ownerless application.',
       administrator.user_id, administrator.user_id
  FROM com_admin_resource_sets resource_set
  JOIN LATERAL (
      SELECT user_record.user_id
        FROM com_role_members membership
        JOIN com_roles role
          ON role.tenant_id = membership.tenant_id
         AND role.role_id = membership.role_id
         AND role.code = 'TENANT_ADMIN'
        JOIN com_users user_record
          ON user_record.tenant_id = membership.tenant_id
         AND user_record.user_id = membership.user_id
       WHERE membership.tenant_id = resource_set.tenant_id
         AND user_record.status IN ('ACTIVE', 'INVITED')
       ORDER BY user_record.user_id
       LIMIT 1
  ) administrator ON TRUE
  JOIN com_tenants tenant ON tenant.tenant_id = resource_set.tenant_id
 WHERE resource_set.lifecycle_state = 'ACTIVE'
   AND NOT (tenant.code = 'default' AND tenant.name = 'SKAX')
   AND NOT EXISTS (
       SELECT 1
         FROM com_admin_role_assignments owner_assignment
        WHERE owner_assignment.tenant_id = resource_set.tenant_id
          AND owner_assignment.resource_set_id = resource_set.resource_set_id
          AND owner_assignment.responsibility_code = 'APP_OWNER'
          AND owner_assignment.lifecycle_state = 'ACTIVE');

UPDATE com_admin_role_assignments assignment
   SET lifecycle_state = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
       revocation_reason = 'Application entitlement was disabled.',
       version = assignment.version + 1, updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_admin_resource_sets resource_set
 WHERE resource_set.tenant_id = assignment.tenant_id
   AND resource_set.resource_set_id = assignment.resource_set_id
   AND resource_set.lifecycle_state = 'RETIRED'
   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE');

-- Operational diagnostics remain queryable by deployment checks without
-- requiring access to application data in other service databases.
CREATE VIEW sys_authorization_integrity_findings AS
WITH effective_baseline AS (
    SELECT member.tenant_id, member.user_id
      FROM com_role_members member
      JOIN com_roles role
        ON role.tenant_id = member.tenant_id
       AND role.role_id = member.role_id
       AND role.code = 'WORKSPACE_MEMBER'
    UNION
    SELECT membership.tenant_id, membership.user_id
      FROM com_group_members membership
      JOIN com_groups access_group
        ON access_group.tenant_id = membership.tenant_id
       AND access_group.group_id = membership.group_id
       AND access_group.status = 'ACTIVE'
      JOIN com_group_role_assignments assignment
        ON assignment.tenant_id = membership.tenant_id
       AND assignment.group_id = membership.group_id
       AND assignment.lifecycle_state = 'ACTIVE'
       AND assignment.assignment_type = 'ACTIVE'
       AND assignment.scope_type = 'TENANT'
       AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
       AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
      JOIN com_roles role
        ON role.tenant_id = assignment.tenant_id
       AND role.role_id = assignment.role_id
       AND role.code = 'WORKSPACE_MEMBER'
), control_plane_users AS (
    SELECT DISTINCT membership.tenant_id, membership.user_id
      FROM com_role_members membership
      JOIN com_roles role
        ON role.tenant_id = membership.tenant_id
       AND role.role_id = membership.role_id
      JOIN sys_builtin_role_catalog catalog ON catalog.role_code = role.code
     WHERE catalog.assignment_class = 'CONTROL_PLANE'
)
SELECT 'BASELINE_ROLE_MISSING'::VARCHAR(64) AS finding_code,
       user_record.tenant_id,
       'USER'::VARCHAR(32) AS subject_type,
       user_record.user_id::TEXT AS subject_ref,
       user_record.email_normalized AS detail
  FROM com_users user_record
  LEFT JOIN effective_baseline baseline
    ON baseline.tenant_id = user_record.tenant_id
   AND baseline.user_id = user_record.user_id
  LEFT JOIN control_plane_users control_user
    ON control_user.tenant_id = user_record.tenant_id
   AND control_user.user_id = user_record.user_id
 WHERE user_record.status IN ('ACTIVE', 'INVITED')
   AND baseline.user_id IS NULL
   AND control_user.user_id IS NULL
UNION ALL
SELECT 'PRIVILEGED_GROUP_ROLE', assignment.tenant_id, 'GROUP',
       assignment.group_id::TEXT, role.code
  FROM com_group_role_assignments assignment
  JOIN com_roles role
    ON role.tenant_id = assignment.tenant_id
   AND role.role_id = assignment.role_id
 WHERE assignment.lifecycle_state = 'ACTIVE'
   AND (role.privileged = TRUE OR role.assignable_to_groups = FALSE)
UNION ALL
SELECT 'DISABLED_RESOURCE_ACTIVE_GRANT', grant_record.tenant_id,
       grant_record.principal_type, grant_record.principal_ref, resource.key
  FROM com_principal_resource_grants grant_record
  JOIN com_resources resource ON resource.resource_id = grant_record.resource_id
 WHERE grant_record.lifecycle_state = 'ACTIVE' AND resource.enabled = FALSE
UNION ALL
SELECT 'ORPHANED_GROUP_RESOURCE_GRANT', grant_record.tenant_id, 'GROUP',
       grant_record.principal_ref, resource.key
  FROM com_principal_resource_grants grant_record
  JOIN com_resources resource ON resource.resource_id = grant_record.resource_id
 WHERE grant_record.lifecycle_state = 'ACTIVE'
   AND grant_record.principal_type = 'GROUP'
   AND NOT EXISTS (
       SELECT 1 FROM com_groups access_group
        WHERE access_group.tenant_id = grant_record.tenant_id
          AND access_group.group_id::TEXT = grant_record.principal_ref
          AND access_group.status = 'ACTIVE')
UNION ALL
SELECT 'RETIRED_APP_LIVE_RESPONSIBILITY', assignment.tenant_id,
       assignment.principal_type, assignment.principal_ref,
       assignment.responsibility_code || ':' || resource_set.resource_set_key
  FROM com_admin_role_assignments assignment
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = assignment.tenant_id
   AND resource_set.resource_set_id = assignment.resource_set_id
 WHERE resource_set.lifecycle_state = 'RETIRED'
   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE')
UNION ALL
SELECT 'APP_RESOURCE_WITHOUT_OWNER', resource_set.tenant_id, 'APP_RESOURCE_SET',
       resource_set.resource_set_id::TEXT, resource_set.resource_set_key
  FROM com_admin_resource_sets resource_set
 WHERE resource_set.lifecycle_state = 'ACTIVE'
   AND NOT EXISTS (
       SELECT 1
         FROM com_admin_role_assignments assignment
        WHERE assignment.tenant_id = resource_set.tenant_id
          AND assignment.resource_set_id = resource_set.resource_set_id
          AND assignment.responsibility_code = 'APP_OWNER'
          AND assignment.lifecycle_state = 'ACTIVE'
          AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
          AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP))
UNION ALL
SELECT 'APP_RESPONSIBILITY_SOD_CONFLICT', left_assignment.tenant_id,
       left_assignment.principal_type, left_assignment.principal_ref,
       left_assignment.admin_role_assignment_id::TEXT || ':' ||
           right_assignment.admin_role_assignment_id::TEXT
  FROM com_admin_role_assignments left_assignment
  JOIN com_admin_role_assignments right_assignment
    ON right_assignment.tenant_id = left_assignment.tenant_id
   AND right_assignment.principal_type = left_assignment.principal_type
   AND right_assignment.principal_ref = left_assignment.principal_ref
   AND right_assignment.admin_role_assignment_id > left_assignment.admin_role_assignment_id
  JOIN com_admin_resource_set_members left_member
    ON left_member.tenant_id = left_assignment.tenant_id
   AND left_member.resource_set_id = left_assignment.resource_set_id
   AND left_member.lifecycle_state = 'ACTIVE'
  JOIN com_admin_resource_set_members right_member
    ON right_member.tenant_id = right_assignment.tenant_id
   AND right_member.resource_set_id = right_assignment.resource_set_id
   AND right_member.resource_type = left_member.resource_type
   AND right_member.resource_key = left_member.resource_key
   AND right_member.lifecycle_state = 'ACTIVE'
 WHERE left_assignment.lifecycle_state = 'ACTIVE'
   AND right_assignment.lifecycle_state = 'ACTIVE'
   AND ((left_assignment.responsibility_code = 'APP_ACCESS_MANAGER'
         AND right_assignment.responsibility_code IN (
             'APP_ACCESS_APPROVER', 'APP_ACCESS_REVIEWER'))
     OR (right_assignment.responsibility_code = 'APP_ACCESS_MANAGER'
         AND left_assignment.responsibility_code IN (
             'APP_ACCESS_APPROVER', 'APP_ACCESS_REVIEWER')));

COMMENT ON TABLE sys_tenant_resource_templates IS
    'Product-entitlement-aware standard resource catalog for tenant provisioning.';
COMMENT ON TABLE sys_tenant_role_permission_templates IS
    'Deny-by-default permission matrix for immutable built-in tenant roles.';
COMMENT ON VIEW sys_authorization_integrity_findings IS
    'Actionable authorization drift and separation-of-duties findings.';

-- Permission snapshots must be re-issued after role, group, and entitlement
-- materialization changes.
UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE user_record.status IN ('ACTIVE', 'INVITED');

UPDATE sys_auth_sessions
   SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE revoked_at IS NULL;
