-- Specialized audit roles need entry to the control center shell in addition to
-- their granular audit resources. Keep this follow-up separate from V17 because
-- applied Flyway migrations are immutable evidence themselves.
INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id, 'ALLOW'
FROM com_roles role
JOIN com_resources resource
  ON resource.tenant_id = role.tenant_id
 AND resource.type = 'APP'
 AND resource.key = 'APP.ADMINISTRATION'
JOIN com_permissions permission ON permission.code = 'VIEW'
WHERE role.code IN ('AUDITOR', 'AUDIT_ADMIN')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE
SET effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP;
