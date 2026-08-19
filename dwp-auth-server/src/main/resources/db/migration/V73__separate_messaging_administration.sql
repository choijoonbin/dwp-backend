-- Tenant administrators may delegate the messaging administrator role, but do
-- not inherit messaging policy authority simply from company administration.
UPDATE sys_tenant_role_permission_templates
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE role_code = 'TENANT_ADMIN'
   AND resource_key = 'ADMIN.MESSAGING'
   AND permission_code IN ('VIEW', 'UPDATE', 'MANAGE');

DELETE FROM com_role_permissions assignment
 USING com_roles role,
       com_resources resource,
       com_permissions permission
 WHERE assignment.tenant_id = role.tenant_id
   AND assignment.role_id = role.role_id
   AND assignment.tenant_id = resource.tenant_id
   AND assignment.resource_id = resource.resource_id
   AND assignment.permission_id = permission.permission_id
   AND role.code = 'TENANT_ADMIN'
   AND resource.key = 'ADMIN.MESSAGING'
   AND permission.code IN ('VIEW', 'UPDATE', 'MANAGE');

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
          AND role.code = 'TENANT_ADMIN');
