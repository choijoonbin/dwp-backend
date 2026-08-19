INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('APPROVAL_DESIGNER', 'ADMIN.APPROVAL_POLICY', 'VIEW'),
    ('APPROVAL_DESIGNER', 'ADMIN.APPROVAL_POLICY', 'UPDATE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

UPDATE sys_tenant_role_permission_templates
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
 WHERE role_code = 'APPROVAL_PUBLISHER'
   AND resource_key = 'ADMIN.APPROVAL_POLICY'
   AND permission_code = 'MANAGE';

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id,
    effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id,
       permission.permission_id, 'ALLOW', 1, 1
  FROM (VALUES
      ('APPROVAL_DESIGNER', 'ADMIN.APPROVAL_POLICY', 'VIEW'),
      ('APPROVAL_DESIGNER', 'ADMIN.APPROVAL_POLICY', 'UPDATE'))
       matrix(role_code, resource_key, permission_code)
  JOIN com_roles role
    ON role.code = matrix.role_code AND role.status = 'ACTIVE'
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = matrix.resource_key
   AND resource.enabled = TRUE
  JOIN com_permissions permission ON permission.code = matrix.permission_code
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

DELETE FROM com_role_permissions assignment
 USING com_roles role, com_resources resource, com_permissions permission
 WHERE assignment.tenant_id = role.tenant_id
   AND assignment.role_id = role.role_id
   AND assignment.resource_id = resource.resource_id
   AND assignment.permission_id = permission.permission_id
   AND role.code = 'APPROVAL_PUBLISHER'
   AND resource.key = 'ADMIN.APPROVAL_POLICY'
   AND permission.code = 'MANAGE';

UPDATE com_users user_record
   SET access_revision = access_revision + 1,
       version = version + 1,
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
          AND role.code IN ('APPROVAL_DESIGNER', 'APPROVAL_PUBLISHER'));
