-- Mail administrator routes use purpose-specific permissions so a narrow duty
-- cannot mutate policy, retention, recovery, exports, and writing assets with
-- one generic MANAGE grant.
INSERT INTO com_permissions (code, name, created_by, updated_by)
VALUES
    ('POLICY_MANAGE', 'Manage Mail policy', 1, 1),
    ('CONNECTION_MANAGE', 'Manage Mail connections', 1, 1),
    ('SHARED_INBOX_MANAGE', 'Manage shared inbox access', 1, 1),
    ('HOLD_MANAGE', 'Manage Mail legal holds', 1, 1),
    ('PURGE_AUTHORIZE', 'Authorize Mail purge', 1, 1),
    ('PURGE_EXECUTE', 'Execute Mail purge', 1, 1),
    ('AUDIT_READ', 'Read Mail delivery audit', 1, 1),
    ('EXPORT', 'Export Mail delivery audit', 1, 1),
    ('RECOVERY', 'Recover Mail delivery', 1, 1),
    ('WRITING_ASSET_EDIT', 'Edit Mail organization writing assets', 1, 1),
    ('WRITING_ASSET_SUBMIT', 'Submit Mail organization writing assets', 1, 1),
    ('WRITING_ASSET_APPROVE', 'Approve Mail organization writing assets', 1, 1),
    ('WRITING_ASSET_PUBLISH', 'Publish Mail organization writing assets', 1, 1),
    ('WRITING_ASSET_RETIRE', 'Retire Mail organization writing assets', 1, 1)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
SELECT 'MAIL_ADMIN', 'ADMIN.MAIL', permission_code
  FROM (VALUES
      ('POLICY_MANAGE'),
      ('CONNECTION_MANAGE'),
      ('SHARED_INBOX_MANAGE'),
      ('HOLD_MANAGE'),
      ('PURGE_AUTHORIZE'),
      ('PURGE_EXECUTE'),
      ('AUDIT_READ'),
      ('EXPORT'),
      ('RECOVERY'),
      ('WRITING_ASSET_EDIT'),
      ('WRITING_ASSET_SUBMIT'),
      ('WRITING_ASSET_APPROVE'),
      ('WRITING_ASSET_PUBLISH'),
      ('WRITING_ASSET_RETIRE')) capability(permission_code)
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id,
    effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id,
       permission.permission_id, 'ALLOW', 1, 1
  FROM sys_tenant_role_permission_templates template
  JOIN com_roles role
    ON role.code = template.role_code AND role.status = 'ACTIVE'
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = template.resource_key
   AND resource.enabled = TRUE
  JOIN com_permissions permission ON permission.code = template.permission_code
 WHERE template.lifecycle_state = 'ACTIVE'
   AND template.role_code = 'MAIL_ADMIN'
   AND template.resource_key = 'ADMIN.MAIL'
   AND template.permission_code IN (
      'POLICY_MANAGE', 'CONNECTION_MANAGE', 'SHARED_INBOX_MANAGE',
      'HOLD_MANAGE', 'PURGE_AUTHORIZE', 'PURGE_EXECUTE',
      'AUDIT_READ', 'EXPORT', 'RECOVERY',
      'WRITING_ASSET_EDIT', 'WRITING_ASSET_SUBMIT',
      'WRITING_ASSET_APPROVE', 'WRITING_ASSET_PUBLISH',
      'WRITING_ASSET_RETIRE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;
