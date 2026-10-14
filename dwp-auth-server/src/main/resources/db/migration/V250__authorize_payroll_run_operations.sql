-- Payroll-run execution, approval, release, status, and originating-command
-- receipts use exact resources. Broad DATA.HR_PAY:MANAGE authority is not an
-- alias for any of these operation capabilities.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('ACTION.HRIS_PAYROLL_RUN', 'ACTION',
     'HRIS payroll run execution', 'core.people'),
    ('DATA.HRIS_PAYROLL_STATUS', 'DATA',
     'HRIS payroll run status', 'core.people'),
    ('ACTION.HRIS_PAYROLL_APPROVE', 'ACTION',
     'HRIS payroll run approval', 'core.people'),
    ('ACTION.HRIS_PAYROLL_RELEASE', 'ACTION',
     'HRIS payroll run release', 'core.people'),
    ('DATA.HRIS_COMMAND_RECEIPT', 'DATA',
     'HRIS originating command receipt', 'core.people')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

-- PAYROLL_ADMIN retains the coarse built-in administration role, but each
-- runtime decision still requires the exact capability and Payroll-owned
-- legal-entity/pay-group predicate. Approval and release additionally require
-- step-up and owner-enforced maker-checker separation. WORKSPACE_MEMBER can
-- read only a receipt whose sealed originating-command scope still authorizes
-- the current actor; cross-tenant and out-of-scope lookups remain opaque.
INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code, lifecycle_state)
VALUES
    ('PAYROLL_ADMIN', 'ACTION.HRIS_PAYROLL_RUN', 'EXECUTE', 'ACTIVE'),
    ('PAYROLL_ADMIN', 'DATA.HRIS_PAYROLL_STATUS', 'VIEW', 'ACTIVE'),
    ('PAYROLL_ADMIN', 'ACTION.HRIS_PAYROLL_APPROVE', 'APPROVE', 'ACTIVE'),
    ('PAYROLL_ADMIN', 'ACTION.HRIS_PAYROLL_RELEASE', 'EXECUTE', 'ACTIVE'),
    ('PAYROLL_ADMIN', 'DATA.HRIS_COMMAND_RECEIPT', 'VIEW', 'ACTIVE'),
    ('HR_ADMIN', 'DATA.HRIS_PAYROLL_STATUS', 'VIEW', 'ACTIVE'),
    ('HR_ADMIN', 'DATA.HRIS_COMMAND_RECEIPT', 'VIEW', 'ACTIVE'),
    ('WORKSPACE_MEMBER', 'DATA.HRIS_COMMAND_RECEIPT', 'VIEW', 'ACTIVE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key IN (
       'ACTION.HRIS_PAYROLL_RUN',
       'DATA.HRIS_PAYROLL_STATUS',
       'ACTION.HRIS_PAYROLL_APPROVE',
       'ACTION.HRIS_PAYROLL_RELEASE',
       'DATA.HRIS_COMMAND_RECEIPT')
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

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
 WHERE template.lifecycle_state = 'ACTIVE'
   AND template.resource_key IN (
       'ACTION.HRIS_PAYROLL_RUN',
       'DATA.HRIS_PAYROLL_STATUS',
       'ACTION.HRIS_PAYROLL_APPROVE',
       'ACTION.HRIS_PAYROLL_RELEASE',
       'DATA.HRIS_COMMAND_RECEIPT')
   AND template.role_code IN ('PAYROLL_ADMIN', 'HR_ADMIN', 'WORKSPACE_MEMBER')
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
          AND role.code IN ('PAYROLL_ADMIN', 'HR_ADMIN', 'WORKSPACE_MEMBER'));

-- Declare the append-only v41 bridge as a DRAFT seed only. The seed loader is
-- the sole importer; Flyway does not approve, activate, or assign the bundle.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    41,
    '026157acf598ebabf156b40e3c084c40d52346ebfd2019e2476f30ecdd2b8d93',
    'product-surfaces-v1.bundle-v41.generated.json',
    'DRAFT',
    FALSE);
