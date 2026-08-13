INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES
    ('COMMUNICATIONS_EDITOR', 'Communications editor',
     'Creates and maintains employee communications without publication authority.',
     'TENANT', '{"ko":"사내 소식 편집자","en":"Communications editor"}',
     FALSE, TRUE, 42, 'ACTIVE', 'DELEGATED'),
    ('COMMUNICATIONS_PUBLISHER', 'Communications publisher',
     'Reviews and publishes targeted employee communications.',
     'TENANT', '{"ko":"사내 소식 게시 책임자","en":"Communications publisher"}',
     TRUE, FALSE, 43, 'ACTIVE', 'DELEGATED')
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
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name, catalog.description,
       'ACTIVE', 'SYSTEM', catalog.privileged, catalog.assignable_to_groups,
       catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE catalog.role_code IN ('COMMUNICATIONS_EDITOR', 'COMMUNICATIONS_PUBLISHER')
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    role_type = 'SYSTEM',
    privileged = EXCLUDED.privileged,
    assignable_to_groups = EXCLUDED.assignable_to_groups,
    builtin_role_code = EXCLUDED.builtin_role_code,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO sys_role_assignment_policies (
    grantor_role_code, target_role_code, assignment_mode, lifecycle_state)
SELECT grantor.role_code, target.role_code, 'DIRECT', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
 CROSS JOIN (VALUES ('COMMUNICATIONS_EDITOR'), ('COMMUNICATIONS_PUBLISHER')) target(role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant_id, resource.type, resource.key, resource.name, TRUE, 1, 1
  FROM com_tenants
 CROSS JOIN (VALUES
    ('APP', 'APP.COMMUNICATIONS', 'Employee communications'),
    ('ADMIN', 'ADMIN.COMMUNICATIONS', 'Employee communications administration')
 ) resource(type, key, name)
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code = 'VIEW'
 WHERE (
        catalog.role_family = 'TENANT'
        OR role.code IN ('ADMIN', 'TENANT_ADMIN', 'PLATFORM_ADMIN'))
   AND resource.key = 'APP.COMMUNICATIONS'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

-- Local SKAX personas make the author/publisher separation verifiable without
-- combining both privileges in a tenant-administrator session.
CREATE TEMP TABLE tmp_communications_review_accounts (
    email VARCHAR(255) PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_communications_review_accounts VALUES
    ('gunwoo.choi@sk.com', 'COMMUNICATIONS_EDITOR'),
    ('dohyun.lee@sk.com', 'COMMUNICATIONS_PUBLISHER');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_communications_review_accounts seed
          LEFT JOIN com_users user_record
            ON user_record.tenant_id = 1
           AND user_record.email_normalized = seed.email
         WHERE user_record.user_id IS NULL) THEN
        RAISE EXCEPTION 'A communications review account is missing from the SKAX workforce projection';
    END IF;
END
$$;

UPDATE com_users user_record
   SET status = 'ACTIVE',
       access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_communications_review_accounts seed
 WHERE user_record.tenant_id = 1
   AND user_record.email_normalized = seed.email;

UPDATE com_user_accounts target
   SET password_hash = source.password_hash,
       status = 'ACTIVE',
       failed_login_count = 0,
       last_failed_at = NULL,
       locked_until = NULL,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_communications_review_accounts seed
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
USING com_users user_record, tmp_communications_review_accounts seed
WHERE membership.tenant_id = user_record.tenant_id
  AND membership.user_id = user_record.user_id
  AND user_record.tenant_id = 1
  AND user_record.email_normalized = seed.email;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT user_record.tenant_id, role.role_id, user_record.user_id, 1, 1
  FROM tmp_communications_review_accounts seed
  JOIN com_users user_record
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = seed.email
  JOIN com_roles role
    ON role.tenant_id = user_record.tenant_id
   AND role.code = seed.role_code
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code IN ('VIEW', 'CREATE', 'UPDATE', 'APPROVE', 'MANAGE')
 WHERE role.code IN ('ADMIN', 'TENANT_ADMIN', 'PLATFORM_ADMIN')
   AND resource.key = 'ADMIN.COMMUNICATIONS'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code IN ('VIEW', 'CREATE', 'UPDATE')
 WHERE role.code = 'COMMUNICATIONS_EDITOR'
   AND resource.key = 'ADMIN.COMMUNICATIONS'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code IN ('VIEW', 'APPROVE', 'MANAGE')
 WHERE role.code = 'COMMUNICATIONS_PUBLISHER'
   AND resource.key = 'ADMIN.COMMUNICATIONS'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code = 'VIEW'
 WHERE role.code IN ('COMMUNICATIONS_EDITOR', 'COMMUNICATIONS_PUBLISHER')
   AND resource.key = 'APP.ADMINISTRATION'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;
