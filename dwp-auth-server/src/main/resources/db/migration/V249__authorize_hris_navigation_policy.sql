-- HRIS navigation policy is a Platform-owned configuration surface. Exact
-- resources prevent broad tenant administration from satisfying the route
-- contract without an explicit navigation-policy grant.
INSERT INTO com_resource_type_catalog (
    resource_type, display_name, lifecycle_state, sort_order)
VALUES ('CONFIG', 'Configuration', 'ACTIVE', 55)
ON CONFLICT (resource_type) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    lifecycle_state = 'ACTIVE',
    sort_order = EXCLUDED.sort_order;

ALTER TABLE sys_tenant_resource_templates
    DROP CONSTRAINT ck_tenant_resource_template_type;
ALTER TABLE sys_tenant_resource_templates
    ADD CONSTRAINT ck_tenant_resource_template_type
        CHECK (resource_type IN ('APP', 'ADMIN', 'DATA', 'ACTION', 'CONFIG'));

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('CONFIG.HRIS_NAVIGATION_POLICY', 'CONFIG',
     'HRIS navigation policy', 'core.people'),
    ('ACTION.HRIS_NAVIGATION_POLICY_PUBLISH', 'ACTION',
     'HRIS navigation policy publication', 'core.people')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

-- Both built-in HR control-plane roles receive exact grants. The Platform
-- owner still enforces maker-checker actor separation for approve, publish,
-- and restore operations.
INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code, lifecycle_state)
VALUES
    ('ADMIN', 'CONFIG.HRIS_NAVIGATION_POLICY', 'VIEW', 'ACTIVE'),
    ('ADMIN', 'CONFIG.HRIS_NAVIGATION_POLICY', 'UPDATE', 'ACTIVE'),
    ('ADMIN', 'ACTION.HRIS_NAVIGATION_POLICY_PUBLISH', 'PUBLISH', 'ACTIVE'),
    ('HR_ADMIN', 'CONFIG.HRIS_NAVIGATION_POLICY', 'VIEW', 'ACTIVE'),
    ('HR_ADMIN', 'CONFIG.HRIS_NAVIGATION_POLICY', 'UPDATE', 'ACTIVE'),
    ('HR_ADMIN', 'ACTION.HRIS_NAVIGATION_POLICY_PUBLISH', 'PUBLISH', 'ACTIVE')
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
       'CONFIG.HRIS_NAVIGATION_POLICY',
       'ACTION.HRIS_NAVIGATION_POLICY_PUBLISH')
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
       'CONFIG.HRIS_NAVIGATION_POLICY',
       'ACTION.HRIS_NAVIGATION_POLICY_PUBLISH')
   AND template.role_code IN ('ADMIN', 'HR_ADMIN')
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
          AND role.code IN ('ADMIN', 'HR_ADMIN'));

-- Declare v40 as an immutable DRAFT seed. The seed loader remains the sole
-- importer; Flyway never approves, activates, or assigns it to a tenant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    40,
    '4dca145cbc9169c2d1fde71771b83a1a0635679a7190a39d0a21b4e76bbb9329',
    'product-surfaces-v1.bundle-v40.generated.json',
    'DRAFT',
    FALSE);
