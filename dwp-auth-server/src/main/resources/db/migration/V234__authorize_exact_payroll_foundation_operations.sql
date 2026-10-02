-- Payroll foundation uses exact owner-native operation verbs. Broad MANAGE
-- authority is deliberately not treated as an alias for these capabilities.
INSERT INTO com_permissions (code, name, created_by, updated_by)
VALUES
    ('SIMULATE', 'Simulate', 1, 1),
    ('PUBLISH', 'Publish', 1, 1),
    ('REVERSE', 'Reverse', 1, 1),
    ('RECONCILE', 'Reconcile', 1, 1)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code, lifecycle_state)
SELECT role_code, 'DATA.HR_PAY', permission_code, 'ACTIVE'
  FROM (VALUES ('HR_ADMIN'), ('PAYROLL_ADMIN')) role(role_code)
 CROSS JOIN (VALUES
    ('SIMULATE'), ('PUBLISH'), ('REVERSE'), ('RECONCILE')) permission(permission_code)
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

-- Materialize the template for existing tenants. New tenants are handled by
-- AuthTenantProvisioningService's normal template synchronizer.
INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id,
    effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id,
       permission.permission_id, 'ALLOW', 1, 1
  FROM sys_tenant_role_permission_templates template
  JOIN com_roles role
    ON role.code = template.role_code
   AND role.status = 'ACTIVE'
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = template.resource_key
   AND resource.enabled = TRUE
  JOIN com_permissions permission
    ON permission.code = template.permission_code
 WHERE template.resource_key = 'DATA.HR_PAY'
   AND template.role_code IN ('HR_ADMIN', 'PAYROLL_ADMIN')
   AND template.permission_code IN (
       'SIMULATE', 'PUBLISH', 'REVERSE', 'RECONCILE')
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

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
          AND role.code IN ('HR_ADMIN', 'PAYROLL_ADMIN'));
