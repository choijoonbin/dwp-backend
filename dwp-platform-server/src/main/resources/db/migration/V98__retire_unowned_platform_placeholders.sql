DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM adm_message_overrides LIMIT 1) THEN
        RAISE EXCEPTION 'adm_message_overrides contains data and requires an explicit migration plan';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_admin_command_approvals LIMIT 1) THEN
        RAISE EXCEPTION 'sys_admin_command_approvals contains data and requires an explicit migration plan';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_admin_command_requests LIMIT 1) THEN
        RAISE EXCEPTION 'sys_admin_command_requests contains data and requires an explicit migration plan';
    END IF;
END
$$;

-- Localization revisions supersede the unused message override draft. Administrative
-- commands remain preview-only until an approved execution ledger is implemented.
DROP TABLE adm_message_overrides;
DROP TABLE sys_admin_command_approvals;
DROP TABLE sys_admin_command_requests;

WITH retired AS (
    SELECT code_set_key
      FROM sys_code_sets
     WHERE source_reference ~ '^(com_role_hierarchy|com_separation_of_duty_rules|ppl_attribute_definitions|adm_message_overrides|sys_admin_command_requests|sys_admin_command_approvals|prv_service_health_observations)\.'
)
UPDATE sys_code_values value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM retired
 WHERE value.code_set_key = retired.code_set_key;

WITH retired AS (
    SELECT code_set_key
      FROM sys_code_sets
     WHERE source_reference ~ '^(com_role_hierarchy|com_separation_of_duty_rules|ppl_attribute_definitions|adm_message_overrides|sys_admin_command_requests|sys_admin_command_approvals|prv_service_health_observations)\.'
)
UPDATE sys_code_bindings binding
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM retired
 WHERE binding.code_set_key = retired.code_set_key;

UPDATE sys_code_sets
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE source_reference ~ '^(com_role_hierarchy|com_separation_of_duty_rules|ppl_attribute_definitions|adm_message_overrides|sys_admin_command_requests|sys_admin_command_approvals|prv_service_health_observations)\.';
