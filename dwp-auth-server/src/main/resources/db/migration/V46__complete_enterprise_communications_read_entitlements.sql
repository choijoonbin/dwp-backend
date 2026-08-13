-- Employee communications is a workforce-wide capability. Built-in tenant,
-- people, audit, and platform operators remain employees; provider operators
-- are deliberately excluded from customer-tenant news by default.
INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = 'APP.COMMUNICATIONS'
  JOIN com_permissions permission
    ON permission.code = 'VIEW'
 WHERE role.status = 'ACTIVE'
   AND catalog.lifecycle_state = 'ACTIVE'
   AND catalog.role_family IN ('WORKSPACE', 'TENANT', 'PEOPLE', 'AUDIT', 'PLATFORM')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;
