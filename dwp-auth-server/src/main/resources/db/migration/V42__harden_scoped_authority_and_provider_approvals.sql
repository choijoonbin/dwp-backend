-- Preserve immutable migration history while hardening open assignments and
-- connecting provider approval duties that already exist in the provider DB.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM com_admin_role_assignments
         WHERE lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE')
         GROUP BY tenant_id, principal_type, principal_ref,
                  responsibility_code, resource_set_id
        HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION
            'Duplicate open application responsibility assignments must be resolved before V42';
    END IF;
END
$$;

DROP INDEX IF EXISTS uk_admin_role_assignment_active;
CREATE UNIQUE INDEX uk_admin_role_assignment_open
    ON com_admin_role_assignments (
        tenant_id, principal_type, principal_ref,
        responsibility_code, resource_set_id)
    WHERE lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE');

INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES
    ('PROVIDER_RELEASE_APPROVER', 'Provider release approver',
     'Approves production feature rollouts independently from release operators.',
     'PROVIDER', '{"ko":"프로바이더 릴리스 승인자","en":"Provider release approver"}',
     TRUE, FALSE, 104, 'ACTIVE', 'CONTROL_PLANE'),
    ('PROVIDER_DATA_APPROVER', 'Provider data policy approver',
     'Approves provider data-policy lifecycle changes independently from policy authors.',
     'PROVIDER', '{"ko":"프로바이더 데이터 정책 승인자","en":"Provider data policy approver"}',
     TRUE, FALSE, 105, 'ACTIVE', 'CONTROL_PLANE')
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
       'ACTIVE', 'SYSTEM', TRUE, FALSE, catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE tenant.code = 'default'
   AND catalog.role_code IN ('PROVIDER_RELEASE_APPROVER', 'PROVIDER_DATA_APPROVER')
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

CREATE TEMP TABLE tmp_provider_approval_accounts (
    user_id BIGINT PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    job_title VARCHAR(160) NOT NULL,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_provider_approval_accounts VALUES
    (900016, 'provider.release.approver@dwp.local', 'Provider Release Approver',
     'Independent production release approver', 'PROVIDER_RELEASE_APPROVER'),
    (900017, 'provider.data.approver@dwp.local', 'Provider Data Policy Approver',
     'Independent data policy approver', 'PROVIDER_DATA_APPROVER');

INSERT INTO com_users (
    user_id, tenant_id, display_name, email, status, job_title,
    preferred_locale, source_type, created_by, updated_by)
SELECT seed.user_id, tenant.tenant_id, seed.display_name, seed.email,
       'ACTIVE', seed.job_title, 'ko-KR', 'LOCAL', 1, 1
  FROM tmp_provider_approval_accounts seed
  JOIN com_tenants tenant ON tenant.code = 'default'
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
SELECT tenant.tenant_id, seed.user_id, 'LOCAL', 'local', seed.email,
       source.password_hash, 'ACTIVE', 1, 1
  FROM tmp_provider_approval_accounts seed
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
USING tmp_provider_approval_accounts seed, com_tenants tenant
WHERE tenant.code = 'default'
  AND membership.tenant_id = tenant.tenant_id
  AND membership.user_id = seed.user_id;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT role.tenant_id, role.role_id, seed.user_id, 1, 1
  FROM tmp_provider_approval_accounts seed
  JOIN com_tenants tenant ON tenant.code = 'default'
  JOIN com_roles role
    ON role.tenant_id = tenant.tenant_id
   AND role.code = seed.role_code
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('com_users', 'user_id'),
    (SELECT MAX(user_id) FROM com_users), TRUE);

UPDATE sys_auth_sessions session
   SET revoked_at = COALESCE(session.revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = NULL
  FROM tmp_provider_approval_accounts seed, com_tenants tenant
 WHERE tenant.code = 'default'
   AND session.tenant_id = tenant.tenant_id
   AND session.user_id = seed.user_id
   AND session.revoked_at IS NULL;
