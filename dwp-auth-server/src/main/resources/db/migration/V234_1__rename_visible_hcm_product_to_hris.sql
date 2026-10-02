-- Preserve APP.HCM and APP.HRIS as authorization identifiers while exposing
-- one product name through permission and application-governance APIs.
UPDATE sys_tenant_resource_templates
   SET display_name = 'HRIS',
       updated_at = CURRENT_TIMESTAMP
 WHERE resource_key = 'APP.HCM'
   AND display_name IS DISTINCT FROM 'HRIS';

UPDATE com_resources
   SET name = 'HRIS',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE type = 'APP'
   AND key = 'APP.HCM'
   AND name IS DISTINCT FROM 'HRIS';

UPDATE com_admin_resource_sets
   SET name = 'HRIS',
       description = 'Administrative boundary for HRIS and its APP.HRIS compatibility alias',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE resource_set_key IN ('APP_HRIS', 'RS_HCM_CONFIG')
   AND (name, description) IS DISTINCT FROM (
       'HRIS',
       'Administrative boundary for HRIS and its APP.HRIS compatibility alias');
