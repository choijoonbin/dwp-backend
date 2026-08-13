INSERT INTO com_resources (tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant_id,
       'ADMIN',
       'ADMIN.WORKFORCE_ACCESS',
       'Workforce population and field access governance',
       TRUE,
       1,
       1
  FROM com_tenants
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
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
   AND resource.key = 'ADMIN.WORKFORCE_ACCESS'
  JOIN com_permissions permission
    ON permission.code IN ('VIEW', 'MANAGE')
 WHERE role.code IN ('ADMIN', 'TENANT_ADMIN')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

UPDATE sys_auth_sessions session
   SET revoked_at = COALESCE(session.revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE session.revoked_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM com_role_members membership
         JOIN com_roles role
           ON role.tenant_id = membership.tenant_id
          AND role.role_id = membership.role_id
        WHERE membership.tenant_id = session.tenant_id
          AND membership.user_id = session.user_id
          AND role.code IN ('ADMIN', 'TENANT_ADMIN'));

UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE EXISTS (
       SELECT 1
         FROM com_role_members membership
         JOIN com_roles role
           ON role.tenant_id = membership.tenant_id
          AND role.role_id = membership.role_id
        WHERE membership.tenant_id = user_record.tenant_id
          AND membership.user_id = user_record.user_id
          AND role.code IN ('ADMIN', 'TENANT_ADMIN'));
