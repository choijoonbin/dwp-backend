UPDATE sys_tenant_resource_templates
   SET display_name = 'DWAI·ON Workspace',
       updated_at = CURRENT_TIMESTAMP
 WHERE resource_key = 'APP.ASK';

UPDATE com_resources
   SET name = 'DWAI·ON Workspace',
       updated_at = CURRENT_TIMESTAMP
 WHERE type = 'APP'
   AND key = 'APP.ASK';
