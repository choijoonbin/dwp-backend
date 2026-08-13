INSERT INTO com_resources (tenant_id, type, key, name, enabled)
SELECT tenant_id, 'APP', 'APP.HRIS', 'HR', TRUE
  FROM com_tenants
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP;

UPDATE com_resources
   SET name = 'Workplace services',
       updated_at = CURRENT_TIMESTAMP
 WHERE key = 'APP.EMPLOYEE_SERVICES';

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect)
SELECT role.tenant_id, role.role_id, resource.resource_id,
       permission.permission_id, 'ALLOW'
  FROM com_roles role
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.type = 'APP'
   AND resource.key = 'APP.HRIS'
  JOIN com_permissions permission ON permission.code = 'VIEW'
 WHERE role.code IN (
     'WORKSPACE_MEMBER', 'ADMIN', 'TENANT_ADMIN', 'HR_ADMIN', 'PEOPLE_ADMIN')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP;
