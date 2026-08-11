-- Tenant administration is an authorization assignment on a customer workforce
-- identity. Keep provider identities separate and retire the earlier local-only
-- company administrator without deleting its audit history.
CREATE TEMP TABLE tmp_skax_tenant_admin (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO tmp_skax_tenant_admin (tenant_id, user_id)
SELECT user_record.tenant_id, user_record.user_id
  FROM com_users user_record
  JOIN com_tenants tenant
    ON tenant.tenant_id = user_record.tenant_id
 WHERE tenant.code = 'default'
   AND user_record.email_normalized = 'hyunwoo.park@sk.com'
   AND user_record.source_type = 'HRIS'
   AND user_record.person_public_id IS NOT NULL;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM tmp_skax_tenant_admin) <> 1 THEN
        RAISE EXCEPTION 'Exactly one HRIS-linked SKAX tenant administrator candidate is required';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM tmp_skax_tenant_admin candidate
          JOIN com_roles role
            ON role.tenant_id = candidate.tenant_id
           AND role.code = 'TENANT_ADMIN'
           AND role.status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'The active TENANT_ADMIN role is required';
    END IF;
END
$$;

-- Identity and role changes invalidate every session for the promoted member
-- and for the retired local-only administrator.
UPDATE sys_auth_sessions session
   SET revoked_at = COALESCE(session.revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE session.revoked_at IS NULL
   AND (
       EXISTS (
           SELECT 1
             FROM tmp_skax_tenant_admin candidate
            WHERE candidate.tenant_id = session.tenant_id
              AND candidate.user_id = session.user_id)
       OR EXISTS (
           SELECT 1
             FROM com_users legacy
            WHERE legacy.tenant_id = session.tenant_id
              AND legacy.user_id = session.user_id
              AND legacy.email_normalized = 'company.admin@dwp.local'));

UPDATE com_users user_record
   SET status = 'ACTIVE',
       access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_skax_tenant_admin candidate
 WHERE user_record.tenant_id = candidate.tenant_id
   AND user_record.user_id = candidate.user_id;

UPDATE com_user_accounts target
   SET password_hash = source.password_hash,
       status = 'ACTIVE',
       failed_login_count = 0,
       last_failed_at = NULL,
       locked_until = NULL,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_skax_tenant_admin candidate
  JOIN com_users bootstrap
    ON bootstrap.tenant_id = candidate.tenant_id
   AND bootstrap.email_normalized = 'admin@dwp.local'
  JOIN com_user_accounts source
    ON source.tenant_id = bootstrap.tenant_id
   AND source.user_id = bootstrap.user_id
   AND source.provider_type = 'LOCAL'
   AND source.provider_id = 'local'
 WHERE target.tenant_id = candidate.tenant_id
   AND target.user_id = candidate.user_id
   AND target.provider_type = 'LOCAL'
   AND target.provider_id = 'local';

DELETE FROM com_role_members membership
USING tmp_skax_tenant_admin candidate
 WHERE membership.tenant_id = candidate.tenant_id
   AND membership.user_id = candidate.user_id;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT candidate.tenant_id, role.role_id, candidate.user_id, 1, 1
  FROM tmp_skax_tenant_admin candidate
  JOIN com_roles role
    ON role.tenant_id = candidate.tenant_id
   AND role.code = 'TENANT_ADMIN'
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

DELETE FROM com_role_members membership
USING com_users legacy
 WHERE membership.tenant_id = legacy.tenant_id
   AND membership.user_id = legacy.user_id
   AND legacy.email_normalized = 'company.admin@dwp.local';

UPDATE com_user_accounts account
   SET password_hash = NULL,
       status = 'RETIRED',
       failed_login_count = 0,
       last_failed_at = NULL,
       locked_until = NULL,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_users legacy
 WHERE legacy.email_normalized = 'company.admin@dwp.local'
   AND account.tenant_id = legacy.tenant_id
   AND account.user_id = legacy.user_id
   AND account.provider_type = 'LOCAL'
   AND account.provider_id = 'local';

UPDATE com_users legacy
   SET status = 'INACTIVE',
       access_revision = legacy.access_revision + 1,
       version = legacy.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE legacy.email_normalized = 'company.admin@dwp.local';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM tmp_skax_tenant_admin candidate
          JOIN com_users user_record
            ON user_record.tenant_id = candidate.tenant_id
           AND user_record.user_id = candidate.user_id
           AND user_record.status = 'ACTIVE'
          JOIN com_user_accounts account
            ON account.tenant_id = candidate.tenant_id
           AND account.user_id = candidate.user_id
           AND account.provider_type = 'LOCAL'
           AND account.provider_id = 'local'
           AND account.status = 'ACTIVE'
           AND account.password_hash IS NOT NULL
          JOIN com_role_members membership
            ON membership.tenant_id = candidate.tenant_id
           AND membership.user_id = candidate.user_id
          JOIN com_roles role
            ON role.tenant_id = membership.tenant_id
           AND role.role_id = membership.role_id
           AND role.code = 'TENANT_ADMIN'
    ) THEN
        RAISE EXCEPTION 'The SKAX workforce tenant administrator was not activated completely';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_skax_tenant_admin candidate
          JOIN com_role_members membership
            ON membership.tenant_id = candidate.tenant_id
           AND membership.user_id = candidate.user_id
          JOIN com_roles role
            ON role.tenant_id = membership.tenant_id
           AND role.role_id = membership.role_id
         WHERE role.code <> 'TENANT_ADMIN'
    ) THEN
        RAISE EXCEPTION 'The SKAX tenant administrator review identity is not role-isolated';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM com_users legacy
          LEFT JOIN com_user_accounts account
            ON account.tenant_id = legacy.tenant_id
           AND account.user_id = legacy.user_id
           AND account.provider_type = 'LOCAL'
           AND account.provider_id = 'local'
          LEFT JOIN com_role_members membership
            ON membership.tenant_id = legacy.tenant_id
           AND membership.user_id = legacy.user_id
         WHERE legacy.email_normalized = 'company.admin@dwp.local'
           AND (legacy.status <> 'INACTIVE'
                OR account.status IS DISTINCT FROM 'RETIRED'
                OR account.password_hash IS NOT NULL
                OR membership.role_member_id IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'The local-only company administrator was not retired completely';
    END IF;
END
$$;
