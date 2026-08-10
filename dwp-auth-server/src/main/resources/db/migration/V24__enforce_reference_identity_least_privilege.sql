-- Synthetic workforce identities exist to validate directory joins, not to
-- manufacture privileged operators. Keep one non-privileged baseline role and
-- leave administrative scenarios to explicit test administrators.
INSERT INTO com_roles (
    tenant_id, code, name, description, status,
    role_type, privileged, assignable_to_groups,
    created_by, updated_by)
SELECT tenant_id,
       'WORKSPACE_MEMBER',
       'Workspace member',
       'Non-privileged baseline role for authenticated workforce members.',
       'ACTIVE',
       'SYSTEM',
       FALSE,
       TRUE,
       1,
       1
  FROM com_tenants
 WHERE tenant_id = 1
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    role_type = 'SYSTEM',
    privileged = FALSE,
    assignable_to_groups = TRUE,
    version = com_roles.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

DELETE FROM com_role_members membership
 USING com_users user_record,
       com_roles role
 WHERE membership.tenant_id = 1
   AND user_record.tenant_id = membership.tenant_id
   AND user_record.user_id = membership.user_id
   AND role.tenant_id = membership.tenant_id
   AND role.role_id = membership.role_id
   AND (
       user_record.email_normalized LIKE '%@skax.example'
       OR user_record.email_normalized LIKE '%@dwp-reference.example')
   AND role.code <> 'WORKSPACE_MEMBER';

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT user_record.tenant_id,
       role.role_id,
       user_record.user_id,
       1,
       1
  FROM com_users user_record
  JOIN com_roles role
    ON role.tenant_id = user_record.tenant_id
   AND role.code = 'WORKSPACE_MEMBER'
 WHERE user_record.tenant_id = 1
   AND (
       user_record.email_normalized LIKE '%@skax.example'
       OR user_record.email_normalized LIKE '%@dwp-reference.example')
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

COMMENT ON COLUMN com_roles.privileged IS
    'Marks elevated roles whose membership requires a governed assignment; bulk workforce seeds must never grant them.';
