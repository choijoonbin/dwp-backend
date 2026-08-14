-- Local integrated verification identity for the SKAX tenant. This is not a
-- production bootstrap account and intentionally excludes provider and
-- independent auditor control-plane roles.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM com_users
         WHERE user_id = 900018
           AND email_normalized IS DISTINCT FROM 'joonbin@sk.com') THEN
        RAISE EXCEPTION 'Reserved local verification user id 900018 is already in use';
    END IF;
END
$$;

INSERT INTO com_users (
    user_id, tenant_id, display_name, email, status, job_title,
    person_public_id, preferred_locale, source_type, external_id,
    created_by, updated_by)
SELECT 900018, tenant.tenant_id, '최준빈', 'joonbin@sk.com', 'ACTIVE',
       'SKAX integrated verification administrator',
       '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid,
       'ko-KR', 'LOCAL', 'DWP-LOCAL-JOONBIN', 1, 1
  FROM com_tenants tenant
 WHERE tenant.code = 'default' AND tenant.name = 'SKAX'
ON CONFLICT (tenant_id, email_normalized)
    WHERE email_normalized IS NOT NULL
DO UPDATE SET
    display_name = EXCLUDED.display_name,
    status = 'ACTIVE',
    job_title = EXCLUDED.job_title,
    person_public_id = EXCLUDED.person_public_id,
    preferred_locale = EXCLUDED.preferred_locale,
    source_type = 'LOCAL',
    external_id = EXCLUDED.external_id,
    access_revision = com_users.access_revision + 1,
    version = com_users.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_user_accounts (
    tenant_id, user_id, provider_type, provider_id, principal,
    password_hash, status, created_by, updated_by)
SELECT target.tenant_id, target.user_id, 'LOCAL', 'local',
       target.email_normalized, bootstrap_account.password_hash,
       'ACTIVE', 1, 1
  FROM com_users target
  JOIN com_users bootstrap_user
    ON bootstrap_user.tenant_id = target.tenant_id
   AND bootstrap_user.email_normalized = 'admin@dwp.local'
  JOIN com_user_accounts bootstrap_account
    ON bootstrap_account.tenant_id = bootstrap_user.tenant_id
   AND bootstrap_account.user_id = bootstrap_user.user_id
   AND bootstrap_account.provider_type = 'LOCAL'
   AND bootstrap_account.provider_id = 'local'
 WHERE target.email_normalized = 'joonbin@sk.com'
ON CONFLICT (tenant_id, provider_type, provider_id, principal) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    password_hash = EXCLUDED.password_hash,
    status = 'ACTIVE',
    failed_login_count = 0,
    last_failed_at = NULL,
    locked_until = NULL,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

CREATE TEMP TABLE tmp_joonbin_roles (
    role_code VARCHAR(50) PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO tmp_joonbin_roles VALUES
    ('WORKSPACE_MEMBER'),
    ('TENANT_ADMIN'),
    ('IDENTITY_ADMIN'),
    ('APP_CATALOG_ADMIN'),
    ('HR_ADMIN'),
    ('PEOPLE_ADMIN'),
    ('AUDIT_ADMIN'),
    ('COMMUNICATIONS_EDITOR'),
    ('COMMUNICATIONS_PUBLISHER'),
    ('SERVICE_CATALOG_MANAGER'),
    ('SERVICE_AGENT');

DELETE FROM com_role_members membership
USING com_users target, com_roles role
WHERE target.tenant_id = membership.tenant_id
  AND target.user_id = membership.user_id
  AND target.email_normalized = 'joonbin@sk.com'
  AND role.tenant_id = membership.tenant_id
  AND role.role_id = membership.role_id
  AND NOT EXISTS (
      SELECT 1 FROM tmp_joonbin_roles allowed WHERE allowed.role_code = role.code);

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT target.tenant_id, role.role_id, target.user_id, 1, 1
  FROM com_users target
  CROSS JOIN tmp_joonbin_roles allowed
  JOIN com_roles role
    ON role.tenant_id = target.tenant_id
   AND role.code = allowed.role_code
   AND role.status = 'ACTIVE'
 WHERE target.email_normalized = 'joonbin@sk.com'
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

INSERT INTO com_group_members (
    tenant_id, group_id, user_id, source_type, created_by, updated_by)
SELECT target.tenant_id, access_group.group_id, target.user_id, 'LOCAL', 1, 1
  FROM com_users target
  JOIN com_groups access_group
    ON access_group.tenant_id = target.tenant_id
   AND access_group.group_key IN (
       'SKAX_APP_OWNERS',
       'SKAX_APP_CONFIGURATION_ADMINS',
       'SKAX_APP_ACCESS_MANAGERS',
       'SKAX_APP_ACCESS_APPROVERS',
       'SKAX_APP_ACCESS_REVIEWERS')
   AND access_group.status = 'ACTIVE'
 WHERE target.email_normalized = 'joonbin@sk.com'
ON CONFLICT (tenant_id, group_id, user_id) DO UPDATE SET
    source_type = 'LOCAL',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_principal_resource_grants (
    principal_resource_grant_id, tenant_id, principal_type, principal_ref,
    resource_id, permission_id, effect, source_type, source_ref,
    lifecycle_state, valid_from, justification, granted_by, created_by, updated_by)
SELECT md5('joonbin-app-view:' || resource.tenant_id || ':' || resource.key)::uuid,
       target.tenant_id, 'USER', target.user_id::text,
       resource.resource_id, permission.permission_id, 'ALLOW', 'ADMIN_DIRECT',
       'local-joonbin-view:' || resource.key, 'ACTIVE', CURRENT_TIMESTAMP,
       'Local integrated verification access across the SKAX application catalog.',
       target.user_id, target.user_id, target.user_id
  FROM com_users target
  JOIN com_resources resource
    ON resource.tenant_id = target.tenant_id
   AND resource.type = 'APP'
   AND resource.enabled = TRUE
  JOIN com_permissions permission ON permission.code = 'VIEW'
 WHERE target.email_normalized = 'joonbin@sk.com'
ON CONFLICT (tenant_id, source_type, source_ref) DO UPDATE SET
    principal_type = 'USER',
    principal_ref = EXCLUDED.principal_ref,
    resource_id = EXCLUDED.resource_id,
    permission_id = EXCLUDED.permission_id,
    lifecycle_state = 'ACTIVE',
    valid_to = NULL,
    revoked_at = NULL,
    revoked_by = NULL,
    revocation_reason = NULL,
    version = com_principal_resource_grants.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

DO $$
BEGIN
    IF (SELECT COUNT(*)
          FROM com_role_members membership
          JOIN com_users target
            ON target.tenant_id = membership.tenant_id
           AND target.user_id = membership.user_id
          JOIN com_roles role
            ON role.tenant_id = membership.tenant_id
           AND role.role_id = membership.role_id
         WHERE target.email_normalized = 'joonbin@sk.com'
           AND role.code IN (SELECT role_code FROM tmp_joonbin_roles)) <> 11 THEN
        RAISE EXCEPTION 'SKAX integrated verification role seed is incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM com_user_accounts account
          JOIN com_users target
            ON target.tenant_id = account.tenant_id
           AND target.user_id = account.user_id
         WHERE target.email_normalized = 'joonbin@sk.com'
           AND account.provider_type = 'LOCAL'
           AND account.provider_id = 'local'
           AND account.status = 'ACTIVE'
           AND account.password_hash IS NOT NULL) THEN
        RAISE EXCEPTION 'SKAX integrated verification credential is incomplete';
    END IF;
END
$$;

UPDATE sys_auth_sessions
   SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE user_id = (
     SELECT user_id FROM com_users WHERE email_normalized = 'joonbin@sk.com')
   AND revoked_at IS NULL;

SELECT setval(
    pg_get_serial_sequence('com_users', 'user_id'),
    (SELECT MAX(user_id) FROM com_users), TRUE);
