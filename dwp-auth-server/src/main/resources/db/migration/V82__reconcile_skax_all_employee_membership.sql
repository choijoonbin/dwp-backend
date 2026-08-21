CREATE TEMP TABLE tmp_missing_skax_all_employees (
    tenant_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, group_id, user_id)
) ON COMMIT DROP;

INSERT INTO tmp_missing_skax_all_employees (tenant_id, group_id, user_id)
SELECT user_record.tenant_id, access_group.group_id, user_record.user_id
  FROM com_users user_record
  JOIN com_tenants tenant
    ON tenant.tenant_id = user_record.tenant_id
   AND tenant.code = 'default'
   AND tenant.name = 'SKAX'
  JOIN com_groups access_group
    ON access_group.tenant_id = tenant.tenant_id
   AND access_group.group_key = 'SKAX_ALL_EMPLOYEES'
   AND access_group.status = 'ACTIVE'
 WHERE user_record.status IN ('ACTIVE', 'INVITED')
   AND user_record.email_normalized LIKE '%@sk.com'
   AND NOT EXISTS (
       SELECT 1
         FROM com_group_members membership
        WHERE membership.tenant_id = user_record.tenant_id
          AND membership.group_id = access_group.group_id
          AND membership.user_id = user_record.user_id);

INSERT INTO com_group_members (
    tenant_id, group_id, user_id, source_type, created_by, updated_by)
SELECT tenant_id, group_id, user_id, 'LOCAL', 1, 1
  FROM tmp_missing_skax_all_employees
ON CONFLICT (tenant_id, group_id, user_id) DO NOTHING;

UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE EXISTS (
       SELECT 1
         FROM tmp_missing_skax_all_employees missing
        WHERE missing.tenant_id = user_record.tenant_id
          AND missing.user_id = user_record.user_id);
