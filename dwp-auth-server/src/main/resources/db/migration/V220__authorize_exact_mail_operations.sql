-- Mail command and administration routes use exact permissions. Keep the
-- historical CREATE/EXPORT/RECOVERY grants as compatibility aliases, but do
-- not use them to authorize the new exact routes.
INSERT INTO com_permissions (code, name, created_by, updated_by)
VALUES
    ('SEND', 'Send Mail', 1, 1),
    ('DELETE', 'Delete Mail', 1, 1),
    ('DECIDE', 'Decide Mail proposals', 1, 1),
    ('PURGE_PREVIEW', 'Preview Mail purge candidates', 1, 1),
    ('EVIDENCE_EXPORT', 'Export Mail evidence', 1, 1),
    ('AUDIT_REVEAL', 'Reveal sensitive Mail delivery audit evidence', 1, 1),
    ('DELIVERY_RECONCILE', 'Reconcile Mail delivery', 1, 1),
    ('DELIVERY_RETRY', 'Retry Mail delivery', 1, 1),
    ('DELIVERY_CANCEL', 'Cancel Mail delivery', 1, 1)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
SELECT role_code, resource_key, permission_code
  FROM (VALUES
      ('WORKSPACE_MEMBER', 'APP.MAIL', 'SEND'),
      ('WORKSPACE_MEMBER', 'APP.MAIL', 'DELETE'),
      ('WORKSPACE_MEMBER', 'APP.MAIL', 'DECIDE'),
      ('MAIL_ADMIN', 'APP.MAIL', 'SEND'),
      ('MAIL_ADMIN', 'APP.MAIL', 'DELETE'),
      ('MAIL_ADMIN', 'APP.MAIL', 'DECIDE'),
      ('MAIL_ADMIN', 'ADMIN.MAIL', 'PURGE_PREVIEW'),
      ('MAIL_ADMIN', 'ADMIN.MAIL', 'EVIDENCE_EXPORT'),
      ('MAIL_ADMIN', 'ADMIN.MAIL', 'AUDIT_REVEAL'),
      ('MAIL_ADMIN', 'ADMIN.MAIL', 'DELIVERY_RECONCILE'),
      ('MAIL_ADMIN', 'ADMIN.MAIL', 'DELIVERY_RETRY'),
      ('MAIL_ADMIN', 'ADMIN.MAIL', 'DELIVERY_CANCEL'))
       capability(role_code, resource_key, permission_code)
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
   AND ((template.role_code IN ('WORKSPACE_MEMBER', 'MAIL_ADMIN')
         AND template.resource_key = 'APP.MAIL'
         AND template.permission_code IN ('SEND', 'DELETE', 'DECIDE'))
     OR (template.role_code = 'MAIL_ADMIN'
         AND template.resource_key = 'ADMIN.MAIL'
         AND template.permission_code IN (
             'PURGE_PREVIEW', 'EVIDENCE_EXPORT', 'AUDIT_REVEAL', 'DELIVERY_RECONCILE',
             'DELIVERY_RETRY', 'DELIVERY_CANCEL')))
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

COMMENT ON TABLE sys_tenant_role_permission_templates IS
    'Role templates include exact Mail send, proposal decision, purge preview, evidence export, and delivery recovery permissions; legacy aliases remain non-authoritative.';
