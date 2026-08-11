-- Workspace members need the minimum application permissions required to use
-- their own work queue and launchpad. Administrative and workforce control
-- plane resources remain excluded.
INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id,
       role.role_id,
       resource.resource_id,
       permission.permission_id,
       'ALLOW',
       1,
       1
  FROM com_roles role
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.enabled = TRUE
  JOIN com_permissions permission
    ON permission.code = 'VIEW'
 WHERE role.code = 'WORKSPACE_MEMBER'
   AND resource.key IN (
       'APP.WORK',
       'APP.ASK',
       'APP.ACTIVITY',
       'APP.APPS',
       'APP.MAIL_CALENDAR',
       'APP.COLLABORATION',
       'APP.EMPLOYEE_SERVICES',
       'APP.PEOPLE_DIRECTORY',
       'APP.KNOWLEDGE',
       'APP.BUSINESS_ERP',
       'APP.LEGACY_OPERATIONS')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id,
       role.role_id,
       resource.resource_id,
       permission.permission_id,
       'ALLOW',
       1,
       1
  FROM com_roles role
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.enabled = TRUE
  JOIN com_permissions permission
    ON permission.code = 'UPDATE'
 WHERE role.code = 'WORKSPACE_MEMBER'
   AND resource.key IN ('APP.WORK', 'APP.APPS')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
