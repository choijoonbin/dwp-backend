-- Template publication is privileged and must not become a standing group
-- grant. Preserve eligible approvers as direct assignments while enforcing
-- maker-checker separation from template editors.
UPDATE sys_builtin_role_catalog
   SET assignable_to_groups = FALSE,
       updated_at = CURRENT_TIMESTAMP
 WHERE role_code = 'NOTIFICATION_TEMPLATE_APPROVER';

UPDATE com_roles
   SET assignable_to_groups = FALSE,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE code = 'NOTIFICATION_TEMPLATE_APPROVER';

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT DISTINCT assignment.tenant_id, assignment.role_id,
       member.user_id, 1, 1
  FROM com_group_role_assignments assignment
  JOIN com_roles approver_role
    ON approver_role.tenant_id = assignment.tenant_id
   AND approver_role.role_id = assignment.role_id
   AND approver_role.code = 'NOTIFICATION_TEMPLATE_APPROVER'
  JOIN com_group_members member
    ON member.tenant_id = assignment.tenant_id
   AND member.group_id = assignment.group_id
 WHERE assignment.lifecycle_state = 'ACTIVE'
   AND NOT EXISTS (
       SELECT 1
         FROM com_role_members direct_editor
         JOIN com_roles editor_role
           ON editor_role.tenant_id = direct_editor.tenant_id
          AND editor_role.role_id = direct_editor.role_id
          AND editor_role.code = 'NOTIFICATION_TEMPLATE_EDITOR'
        WHERE direct_editor.tenant_id = member.tenant_id
          AND direct_editor.user_id = member.user_id)
   AND NOT EXISTS (
       SELECT 1
         FROM com_group_members editor_member
         JOIN com_group_role_assignments editor_assignment
           ON editor_assignment.tenant_id = editor_member.tenant_id
          AND editor_assignment.group_id = editor_member.group_id
          AND editor_assignment.lifecycle_state = 'ACTIVE'
         JOIN com_roles editor_role
           ON editor_role.tenant_id = editor_assignment.tenant_id
          AND editor_role.role_id = editor_assignment.role_id
          AND editor_role.code = 'NOTIFICATION_TEMPLATE_EDITOR'
        WHERE editor_member.tenant_id = member.tenant_id
          AND editor_member.user_id = member.user_id)
   AND NOT EXISTS (
       SELECT 1
         FROM com_role_members existing
        WHERE existing.tenant_id = assignment.tenant_id
          AND existing.role_id = assignment.role_id
          AND existing.user_id = member.user_id);

UPDATE com_group_role_assignments assignment
   SET lifecycle_state = 'REVOKED',
       version = assignment.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_roles role
 WHERE role.tenant_id = assignment.tenant_id
   AND role.role_id = assignment.role_id
   AND role.code = 'NOTIFICATION_TEMPLATE_APPROVER'
   AND assignment.lifecycle_state = 'ACTIVE';

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
          AND role.code = 'NOTIFICATION_TEMPLATE_APPROVER');
