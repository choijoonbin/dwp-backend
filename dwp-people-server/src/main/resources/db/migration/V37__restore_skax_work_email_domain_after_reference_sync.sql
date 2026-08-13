-- The bundled Workday reference fixture was imported after V30 and could
-- overwrite normalized SKAX work emails. Restore the tenant identity contract
-- while preserving collision and encrypted-payload safeguards.
CREATE TEMP TABLE tmp_skax_work_email_restore (
    contact_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    previous_email VARCHAR(320) NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    UNIQUE (tenant_id, normalized_email)
) ON COMMIT DROP;

INSERT INTO tmp_skax_work_email_restore (
    contact_id, tenant_id, previous_email, normalized_email)
SELECT contact.contact_id,
       contact.tenant_id,
       LOWER(BTRIM(contact.display_value)),
       LOWER(SPLIT_PART(BTRIM(contact.display_value), '@', 1)) || '@sk.com'
  FROM ppl_contacts contact
  JOIN sys_service_tenants tenant
    ON tenant.tenant_id = contact.tenant_id
 WHERE tenant.tenant_key = 'default'
   AND contact.contact_type = 'EMAIL'
   AND contact.usage_type = 'WORK'
   AND LOWER(SPLIT_PART(BTRIM(contact.display_value), '@', 2)) <> 'sk.com';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_skax_work_email_restore target
         WHERE target.previous_email !~ '^[^@[:space:]]+@[^@[:space:]]+$'
    ) THEN
        RAISE EXCEPTION 'SKAX email restoration encountered a malformed work email';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_skax_work_email_restore target
          JOIN ppl_contacts contact
            ON contact.contact_id = target.contact_id
           AND contact.encrypted_payload IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'SKAX email restoration cannot rewrite encrypted contact payloads';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_skax_work_email_restore target
          JOIN ppl_contacts existing
            ON existing.tenant_id = target.tenant_id
           AND existing.contact_id <> target.contact_id
           AND existing.contact_type = 'EMAIL'
           AND existing.usage_type = 'WORK'
           AND LOWER(BTRIM(existing.display_value)) = target.normalized_email
    ) THEN
        RAISE EXCEPTION 'SKAX email restoration would collide with an existing work email';
    END IF;
END
$$;

UPDATE ppl_contacts contact
   SET display_value = target.normalized_email,
       version = contact.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_skax_work_email_restore target
 WHERE contact.contact_id = target.contact_id
   AND contact.tenant_id = target.tenant_id;
