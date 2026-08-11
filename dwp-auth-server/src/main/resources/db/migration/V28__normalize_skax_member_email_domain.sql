-- Normalize bundled SKAX workforce identities to the company's delivery domain.
-- Historical login records remain unchanged because they are immutable evidence.
CREATE TEMP TABLE tmp_skax_member_email_domain (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT PRIMARY KEY,
    previous_email VARCHAR(255) NOT NULL,
    normalized_email VARCHAR(255) NOT NULL,
    UNIQUE (tenant_id, normalized_email)
) ON COMMIT DROP;

INSERT INTO tmp_skax_member_email_domain (
    tenant_id, user_id, previous_email, normalized_email)
SELECT user_record.tenant_id,
       user_record.user_id,
       LOWER(BTRIM(user_record.email)),
       LOWER(SPLIT_PART(BTRIM(user_record.email), '@', 1)) || '@sk.com'
  FROM com_users user_record
  JOIN com_tenants tenant
    ON tenant.tenant_id = user_record.tenant_id
 WHERE tenant.code = 'default'
   AND user_record.source_type = 'HRIS'
   AND LOWER(SPLIT_PART(BTRIM(user_record.email), '@', 2)) IN (
       'skax.example', 'dwp-reference.example');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_skax_member_email_domain target
          JOIN com_users existing
            ON existing.tenant_id = target.tenant_id
           AND existing.user_id <> target.user_id
           AND existing.email_normalized = target.normalized_email
    ) THEN
        RAISE EXCEPTION 'SKAX email normalization would collide with an existing user';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_skax_member_email_domain target
          JOIN com_user_accounts existing
            ON existing.tenant_id = target.tenant_id
           AND existing.provider_type = 'LOCAL'
           AND existing.provider_id = 'local'
           AND LOWER(BTRIM(existing.principal)) = target.normalized_email
           AND existing.user_id <> target.user_id
    ) THEN
        RAISE EXCEPTION 'SKAX email normalization would collide with an existing local account';
    END IF;
END
$$;

-- Revoke sessions issued before the identity attribute change. The next login
-- receives fresh identity claims and permission-cache coordinates.
UPDATE sys_auth_sessions session
   SET revoked_at = COALESCE(session.revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_skax_member_email_domain target
 WHERE session.tenant_id = target.tenant_id
   AND session.user_id = target.user_id
   AND session.revoked_at IS NULL;

UPDATE com_user_accounts account
   SET principal = target.normalized_email,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_skax_member_email_domain target
 WHERE account.tenant_id = target.tenant_id
   AND account.user_id = target.user_id
   AND account.provider_type = 'LOCAL'
   AND account.provider_id = 'local'
   AND LOWER(BTRIM(account.principal)) = target.previous_email;

UPDATE com_users user_record
   SET email = target.normalized_email,
       access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_skax_member_email_domain target
 WHERE user_record.tenant_id = target.tenant_id
   AND user_record.user_id = target.user_id;
