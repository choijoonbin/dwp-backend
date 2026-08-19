-- Calendar administrators govern meeting-room inventory through ADMIN.ROOMS.
-- Workplace policy, desk, locker, and building operations remain a distinct
-- tenant-administration boundary.
UPDATE sys_tenant_role_permission_templates
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE role_code = 'CALENDAR_ADMIN'
   AND resource_key = 'ADMIN.WORKPLACE'
   AND permission_code IN ('VIEW', 'CREATE', 'UPDATE', 'MANAGE');

DELETE FROM com_role_permissions role_permission
 USING com_roles role,
       com_resources resource,
       com_permissions permission
 WHERE role_permission.tenant_id = role.tenant_id
   AND role_permission.role_id = role.role_id
   AND role_permission.tenant_id = resource.tenant_id
   AND role_permission.resource_id = resource.resource_id
   AND role_permission.permission_id = permission.permission_id
   AND role.code = 'CALENDAR_ADMIN'
   AND resource.key = 'ADMIN.WORKPLACE'
   AND permission.code IN ('VIEW', 'CREATE', 'UPDATE', 'MANAGE');
