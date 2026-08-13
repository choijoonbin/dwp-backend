CREATE TEMP TABLE tmp_service_center_review_accounts (
    email VARCHAR(255) PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_service_center_review_accounts VALUES
    ('seojin.yoon@sk.com', 'SERVICE_CATALOG_MANAGER'),
    ('jiwoo.bae@sk.com', 'SERVICE_AGENT');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_service_center_review_accounts seed
          LEFT JOIN com_users user_record
            ON user_record.tenant_id = 1
           AND user_record.email_normalized = seed.email
         WHERE user_record.user_id IS NULL) THEN
        RAISE EXCEPTION 'An employee-service review account is missing from the SKAX workforce projection';
    END IF;
END
$$;

UPDATE com_users user_record
   SET status = 'ACTIVE',
       access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_service_center_review_accounts seed
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
  FROM tmp_service_center_review_accounts seed
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
USING com_users user_record, tmp_service_center_review_accounts seed
WHERE membership.tenant_id = user_record.tenant_id
  AND membership.user_id = user_record.user_id
  AND user_record.tenant_id = 1
  AND user_record.email_normalized = seed.email;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT user_record.tenant_id, role.role_id, user_record.user_id, 1, 1
  FROM tmp_service_center_review_accounts seed
  JOIN com_users user_record
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = seed.email
  JOIN com_roles role
    ON role.tenant_id = user_record.tenant_id
   AND role.code = seed.role_code
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;
