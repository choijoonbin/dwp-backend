-- Local reference identities used to verify role-isolated product shells.
-- These accounts only target the bundled sample tenants and must not be
-- promoted to a production identity source.

-- The original default tenant predates tenant provisioning. Bring its tenant
-- administrator role in line with subsequently provisioned customer tenants.
INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT
    tenant_id, 'TENANT_ADMIN', 'Tenant administrator',
    'Administrator for one customer tenant.', 'ACTIVE', 'SYSTEM',
    TRUE, FALSE, 'TENANT_ADMIN', 1, 1
FROM com_tenants
WHERE code = 'default'
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    role_type = 'SYSTEM',
    privileged = TRUE,
    assignable_to_groups = FALSE,
    builtin_role_code = 'TENANT_ADMIN',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect,
    created_by, updated_by)
SELECT
    tenant_admin.tenant_id, tenant_admin.role_id,
    source.resource_id, source.permission_id, source.effect, 1, 1
FROM com_tenants tenant
JOIN com_roles administrator
  ON administrator.tenant_id = tenant.tenant_id
 AND administrator.code = 'ADMIN'
JOIN com_roles tenant_admin
  ON tenant_admin.tenant_id = tenant.tenant_id
 AND tenant_admin.code = 'TENANT_ADMIN'
JOIN com_role_permissions source
  ON source.tenant_id = administrator.tenant_id
 AND source.role_id = administrator.role_id
WHERE tenant.code = 'default'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = EXCLUDED.effect,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

-- Tenant administration does not imply HR data operations. Those capabilities
-- remain isolated behind HR_ADMIN or PEOPLE_ADMIN.
DELETE FROM com_role_permissions permission
USING com_tenants tenant, com_roles role, com_resources resource
WHERE tenant.code = 'default'
  AND role.tenant_id = tenant.tenant_id
  AND role.code = 'TENANT_ADMIN'
  AND permission.tenant_id = role.tenant_id
  AND permission.role_id = role.role_id
  AND resource.resource_id = permission.resource_id
  AND resource.key IN (
      'APP.WORKFORCE_MANAGEMENT',
      'DATA.WORKFORCE',
      'ACTION.WORKFORCE_REFERENCE',
      'ACTION.WORKFORCE_DATA_OPERATIONS');

INSERT INTO com_users (
    user_id, tenant_id, display_name, email, status, job_title,
    preferred_locale, source_type, created_by, updated_by)
SELECT
    900001, tenant_id, 'Provider Review Administrator',
    'provider.admin@dwp.local', 'ACTIVE', 'Provider administrator',
    'ko', 'LOCAL', 1, 1
FROM com_tenants
WHERE code = 'default'
ON CONFLICT (user_id) DO UPDATE SET
    tenant_id = EXCLUDED.tenant_id,
    display_name = EXCLUDED.display_name,
    email = EXCLUDED.email,
    status = 'ACTIVE',
    job_title = EXCLUDED.job_title,
    preferred_locale = EXCLUDED.preferred_locale,
    source_type = 'LOCAL',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO com_users (
    user_id, tenant_id, display_name, email, status, job_title,
    preferred_locale, source_type, created_by, updated_by)
SELECT
    900002, tenant_id, 'SKAX Company Administrator',
    'company.admin@dwp.local', 'ACTIVE', 'Company administrator',
    'ko', 'LOCAL', 1, 1
FROM com_tenants
WHERE code = 'default'
ON CONFLICT (user_id) DO UPDATE SET
    tenant_id = EXCLUDED.tenant_id,
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
SELECT
    target.tenant_id, target.user_id, 'LOCAL', 'local',
    LOWER(BTRIM(target.email)), source.password_hash, 'ACTIVE', 1, 1
FROM com_users target
JOIN com_user_accounts source
  ON source.tenant_id = 1
 AND source.user_id = 1
 AND source.provider_type = 'LOCAL'
 AND source.provider_id = 'local'
WHERE target.user_id IN (900001, 900002)
ON CONFLICT (tenant_id, provider_type, provider_id, principal) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    password_hash = EXCLUDED.password_hash,
    status = 'ACTIVE',
    failed_login_count = 0,
    last_failed_at = NULL,
    locked_until = NULL,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

-- Activate one real workforce projection identity so member-level checks also
-- exercise People profile and organization joins.
UPDATE com_users
SET status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE tenant_id = (SELECT tenant_id FROM com_tenants WHERE code = 'default')
  AND email_normalized = 'minseo.kim@skax.example';

UPDATE com_user_accounts target
SET password_hash = source.password_hash,
    status = 'ACTIVE',
    failed_login_count = 0,
    last_failed_at = NULL,
    locked_until = NULL,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
FROM com_user_accounts source
JOIN com_tenants tenant ON tenant.tenant_id = source.tenant_id
WHERE tenant.code = 'default'
  AND source.user_id = 1
  AND source.provider_type = 'LOCAL'
  AND source.provider_id = 'local'
  AND target.tenant_id = tenant.tenant_id
  AND LOWER(BTRIM(target.principal)) = 'minseo.kim@skax.example'
  AND target.provider_type = 'LOCAL'
  AND target.provider_id = 'local';

-- Keep each review identity on exactly one authority boundary.
DELETE FROM com_role_members membership
USING com_users user_record, com_roles role
WHERE membership.tenant_id = user_record.tenant_id
  AND membership.user_id = user_record.user_id
  AND role.tenant_id = membership.tenant_id
  AND role.role_id = membership.role_id
  AND user_record.email_normalized IN (
      'minseo.kim@skax.example',
      'company.admin@dwp.local',
      'provider.admin@dwp.local')
  AND role.code <> CASE user_record.email_normalized
      WHEN 'minseo.kim@skax.example' THEN 'WORKSPACE_MEMBER'
      WHEN 'company.admin@dwp.local' THEN 'TENANT_ADMIN'
      WHEN 'provider.admin@dwp.local' THEN 'PROVIDER_ADMIN'
  END;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT user_record.tenant_id, role.role_id, user_record.user_id, 1, 1
FROM com_users user_record
JOIN com_roles role
  ON role.tenant_id = user_record.tenant_id
 AND role.code = CASE user_record.email_normalized
     WHEN 'minseo.kim@skax.example' THEN 'WORKSPACE_MEMBER'
     WHEN 'company.admin@dwp.local' THEN 'TENANT_ADMIN'
     WHEN 'provider.admin@dwp.local' THEN 'PROVIDER_ADMIN'
 END
WHERE user_record.email_normalized IN (
    'minseo.kim@skax.example',
    'company.admin@dwp.local',
    'provider.admin@dwp.local')
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('com_users', 'user_id'),
    (SELECT MAX(user_id) FROM com_users),
    TRUE);
