-- Tenant settings commands authorize persisted resource permissions rather than JWT role names.
-- Actor separation remains enforced by the versioned change workflow.

INSERT INTO com_permissions (code, name, created_by, updated_by)
VALUES
    ('APPROVE', 'Approve', 1, 1),
    ('PUBLISH', 'Publish', 1, 1)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('TENANT_ADMIN', 'ADMIN.IDENTITY_PROVISIONING', 'APPROVE'),
    ('TENANT_ADMIN', 'ADMIN.IDENTITY_PROVISIONING', 'PUBLISH'),
    ('IDENTITY_ADMIN', 'ADMIN.IDENTITY_PROVISIONING', 'APPROVE'),
    ('IDENTITY_ADMIN', 'ADMIN.IDENTITY_PROVISIONING', 'PUBLISH')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = 'ADMIN.IDENTITY_PROVISIONING'
   AND resource.enabled = TRUE
  JOIN com_permissions permission ON permission.code IN ('APPROVE', 'PUBLISH')
 WHERE role.status = 'ACTIVE'
   AND role.code IN ('TENANT_ADMIN', 'IDENTITY_ADMIN')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

ALTER TABLE com_tenant_setting_change_sets
    ADD CONSTRAINT ck_tenant_setting_change_publish_sod CHECK (
        lifecycle_state <> 'PUBLISHED'
        OR (published_by <> requested_by AND published_by <> decided_by));
