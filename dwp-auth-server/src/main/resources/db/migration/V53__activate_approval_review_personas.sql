-- Local-only approval personas use the bootstrap credential so design,
-- publication, and operations duties can be verified independently.
CREATE TEMP TABLE tmp_approval_review_personas (
    email VARCHAR(255) PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO tmp_approval_review_personas VALUES
    ('taeyeon.kim@sk.com'),
    ('seungmin.yoo@sk.com'),
    ('james.wilson@sk.com');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_approval_review_personas seed
          LEFT JOIN com_users user_record
            ON user_record.tenant_id = 1
           AND user_record.email_normalized = seed.email
         WHERE user_record.user_id IS NULL) THEN
        RAISE EXCEPTION 'An approval review persona is missing from the SKAX workforce projection';
    END IF;
END
$$;

UPDATE com_users user_record
   SET status = 'ACTIVE',
       access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_approval_review_personas seed
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
  FROM tmp_approval_review_personas seed
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

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_approval_review_personas seed
          JOIN com_users user_record
            ON user_record.tenant_id = 1
           AND user_record.email_normalized = seed.email
          LEFT JOIN com_user_accounts account
            ON account.tenant_id = user_record.tenant_id
           AND account.user_id = user_record.user_id
           AND account.provider_type = 'LOCAL'
           AND account.provider_id = 'local'
           AND account.status = 'ACTIVE'
           AND account.password_hash IS NOT NULL
         WHERE account.user_account_id IS NULL) THEN
        RAISE EXCEPTION 'An approval review persona has no active local credential';
    END IF;
END
$$;
