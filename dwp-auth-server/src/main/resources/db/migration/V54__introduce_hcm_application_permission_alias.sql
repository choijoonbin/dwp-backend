-- DWP HCM is the canonical product permission. APP.HRIS remains active as a
-- compatibility alias while existing sessions and managed clients migrate.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES ('APP.HCM', 'APP', 'DWP HCM', 'core.people')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code, lifecycle_state)
SELECT role_code, 'APP.HCM', permission_code, lifecycle_state
  FROM sys_tenant_role_permission_templates
 WHERE resource_key = 'APP.HRIS'
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = EXCLUDED.lifecycle_state,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant_id, 'APP', 'APP.HCM', 'DWP HCM', TRUE, 1, 1
  FROM com_tenants
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id,
    effect, created_by, updated_by)
SELECT legacy_permission.tenant_id,
       legacy_permission.role_id,
       hcm_resource.resource_id,
       legacy_permission.permission_id,
       legacy_permission.effect,
       1,
       1
  FROM com_role_permissions legacy_permission
  JOIN com_resources legacy_resource
    ON legacy_resource.tenant_id = legacy_permission.tenant_id
   AND legacy_resource.resource_id = legacy_permission.resource_id
   AND legacy_resource.key = 'APP.HRIS'
  JOIN com_resources hcm_resource
    ON hcm_resource.tenant_id = legacy_permission.tenant_id
   AND hcm_resource.key = 'APP.HCM'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = EXCLUDED.effect,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

UPDATE com_resources
   SET name = 'HRIS compatibility alias',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE key = 'APP.HRIS';
