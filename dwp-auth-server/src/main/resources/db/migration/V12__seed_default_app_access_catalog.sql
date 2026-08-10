INSERT INTO com_resources (tenant_id, type, key, name)
VALUES
    (1, 'APP', 'APP.WORK', 'Work'),
    (1, 'APP', 'APP.ASK', 'Ask DWP'),
    (1, 'APP', 'APP.ACTIVITY', 'Activity'),
    (1, 'APP', 'APP.APPS', 'Apps'),
    (1, 'APP', 'APP.MAIL_CALENDAR', 'Mail and calendar'),
    (1, 'APP', 'APP.COLLABORATION', 'Collaboration'),
    (1, 'APP', 'APP.EMPLOYEE_SERVICES', 'Employee services'),
    (1, 'APP', 'APP.PEOPLE_DIRECTORY', 'People directory'),
    (1, 'APP', 'APP.KNOWLEDGE', 'Knowledge'),
    (1, 'APP', 'APP.BUSINESS_ERP', 'Business ERP'),
    (1, 'APP', 'APP.LEGACY_OPERATIONS', 'Legacy operations'),
    (1, 'APP', 'APP.ADMINISTRATION', 'Administration')
ON CONFLICT (tenant_id, type, key) DO UPDATE
SET name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_role_permissions (
    tenant_id,
    role_id,
    resource_id,
    permission_id,
    effect)
SELECT
    role.tenant_id,
    role.role_id,
    resource.resource_id,
    permission.permission_id,
    'ALLOW'
FROM com_roles role
JOIN com_resources resource
  ON resource.tenant_id = role.tenant_id
 AND resource.type = 'APP'
JOIN com_permissions permission
  ON permission.code = 'VIEW'
WHERE role.tenant_id = 1
  AND role.code = 'ADMIN'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE
SET effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP;
