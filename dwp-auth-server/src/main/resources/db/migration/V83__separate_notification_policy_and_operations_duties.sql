-- Company administration does not implicitly grant Provider contract ownership,
-- policy publication or delivery-queue mutation. Tenant administrators may
-- author company policy and templates, while delegated roles own approval and
-- runtime operations. The notification service also enforces author/approver
-- separation for every immutable policy version.
UPDATE sys_tenant_role_permission_templates
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE (role_code = 'TENANT_ADMIN'
        AND ((resource_key = 'ADMIN.NOTIFICATION_CONTRACT'
              AND permission_code = 'MANAGE')
          OR (resource_key = 'ADMIN.NOTIFICATION_TEMPLATE'
              AND permission_code = 'APPROVE')
          OR (resource_key = 'ADMIN.NOTIFICATION_POLICY'
              AND permission_code = 'APPROVE')
          OR (resource_key = 'ADMIN.NOTIFICATION_OPERATIONS'
              AND permission_code = 'MANAGE')))
    OR (role_code = 'NOTIFICATION_POLICY_APPROVER'
        AND resource_key = 'ADMIN.NOTIFICATION_POLICY'
        AND permission_code = 'MANAGE');

DELETE FROM com_role_permissions assignment
 USING com_roles role,
       com_resources resource,
       com_permissions permission
 WHERE assignment.tenant_id = role.tenant_id
   AND assignment.role_id = role.role_id
   AND assignment.tenant_id = resource.tenant_id
   AND assignment.resource_id = resource.resource_id
   AND assignment.permission_id = permission.permission_id
   AND ((role.code = 'TENANT_ADMIN'
         AND ((resource.key = 'ADMIN.NOTIFICATION_CONTRACT'
               AND permission.code = 'MANAGE')
           OR (resource.key = 'ADMIN.NOTIFICATION_TEMPLATE'
               AND permission.code = 'APPROVE')
           OR (resource.key = 'ADMIN.NOTIFICATION_POLICY'
               AND permission.code = 'APPROVE')
           OR (resource.key = 'ADMIN.NOTIFICATION_OPERATIONS'
               AND permission.code = 'MANAGE')))
     OR (role.code = 'NOTIFICATION_POLICY_APPROVER'
         AND resource.key = 'ADMIN.NOTIFICATION_POLICY'
         AND permission.code = 'MANAGE'));

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
          AND role.code IN ('TENANT_ADMIN', 'NOTIFICATION_POLICY_APPROVER'))
    OR EXISTS (
       SELECT 1
         FROM com_group_members group_member
         JOIN com_group_role_assignments assignment
           ON assignment.tenant_id = group_member.tenant_id
          AND assignment.group_id = group_member.group_id
          AND assignment.lifecycle_state = 'ACTIVE'
         JOIN com_roles role
           ON role.tenant_id = assignment.tenant_id
          AND role.role_id = assignment.role_id
        WHERE group_member.tenant_id = user_record.tenant_id
          AND group_member.user_id = user_record.user_id
          AND role.code = 'NOTIFICATION_POLICY_APPROVER');
