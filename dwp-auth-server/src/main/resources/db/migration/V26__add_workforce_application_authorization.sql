INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, seed.code, seed.name, seed.description, 'ACTIVE', 'SYSTEM',
       seed.privileged, seed.assignable_to_groups, seed.code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN (VALUES
    ('WORKSPACE_MEMBER', 'Workspace member', 'Default workspace access role.', FALSE, TRUE),
    ('HR_ADMIN', 'HR administrator', 'Workforce data and organization administrator.', TRUE, FALSE),
    ('PEOPLE_ADMIN', 'People administrator', 'People service administrator.', TRUE, FALSE)
) AS seed(code, name, description, privileged, assignable_to_groups)
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    role_type = 'SYSTEM',
    privileged = EXCLUDED.privileged,
    assignable_to_groups = EXCLUDED.assignable_to_groups,
    builtin_role_code = EXCLUDED.builtin_role_code,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (tenant_id, type, key, name, enabled)
SELECT tenant.tenant_id, resource.type, resource.key, resource.name, TRUE
  FROM com_tenants tenant
 CROSS JOIN (VALUES
    ('APP', 'APP.WORKFORCE_MANAGEMENT', 'Workforce management'),
    ('DATA', 'DATA.WORKFORCE', 'Workforce projection'),
    ('ACTION', 'ACTION.WORKFORCE_REFERENCE', 'Workforce reference data'),
    ('ACTION', 'ACTION.WORKFORCE_DATA_OPERATIONS', 'Workforce data operations')
) AS resource(type, key, name)
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id, 'ALLOW'
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code = CASE
      WHEN resource.key = 'APP.WORKFORCE_MANAGEMENT' THEN 'VIEW'
      WHEN role.code = 'PEOPLE_ADMIN' THEN 'VIEW'
      ELSE 'MANAGE' END
 WHERE role.code IN ('ADMIN', 'HR_ADMIN', 'PEOPLE_ADMIN')
   AND resource.key IN (
       'APP.WORKFORCE_MANAGEMENT', 'DATA.WORKFORCE',
       'ACTION.WORKFORCE_REFERENCE', 'ACTION.WORKFORCE_DATA_OPERATIONS')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id, 'ALLOW'
  FROM com_roles role
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id AND resource.key = 'APP.PEOPLE_DIRECTORY'
  JOIN com_permissions permission ON permission.code = 'VIEW'
 WHERE role.code IN ('WORKSPACE_MEMBER', 'ADMIN', 'HR_ADMIN', 'PEOPLE_ADMIN')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP;
