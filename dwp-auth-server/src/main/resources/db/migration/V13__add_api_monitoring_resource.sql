INSERT INTO com_resources (tenant_id, type, key, name)
VALUES (1, 'ADMIN', 'ADMIN.API_MONITORING', 'API monitoring')
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
 AND resource.type = 'ADMIN'
 AND resource.key = 'ADMIN.API_MONITORING'
JOIN com_permissions permission
  ON permission.code = 'VIEW'
WHERE role.tenant_id = 1
  AND role.code = 'ADMIN'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE
SET effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP;
