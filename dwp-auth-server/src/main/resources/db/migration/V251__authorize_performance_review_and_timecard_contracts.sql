-- Assigned performance reviews use one exact resource. Permission alone never
-- grants object access: People still requires the sealed SELF or assigned-
-- reviewer relationship/population predicate for every review route.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('DATA.HRIS_PERFORMANCE_REVIEW', 'DATA',
     'HRIS performance assigned review', 'core.people')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

-- These are the only static role grants. Timecard v42 routes reuse existing
-- DATA.HR_TIME and DATA.HRIS_COMMAND_RECEIPT permissions and receive no grant
-- expansion in this bridge.
INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code, lifecycle_state)
VALUES
    ('WORKSPACE_MEMBER', 'DATA.HRIS_PERFORMANCE_REVIEW', 'UPDATE', 'ACTIVE'),
    ('TALENT_ADMIN', 'DATA.HRIS_PERFORMANCE_REVIEW', 'UPDATE', 'ACTIVE'),
    ('HR_ADMIN', 'DATA.HRIS_PERFORMANCE_REVIEW', 'UPDATE', 'ACTIVE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key = 'DATA.HRIS_PERFORMANCE_REVIEW'
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
   AND template.resource_key = 'DATA.HRIS_PERFORMANCE_REVIEW'
   AND template.role_code IN ('WORKSPACE_MEMBER', 'TALENT_ADMIN', 'HR_ADMIN')
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
          AND role.code IN ('WORKSPACE_MEMBER', 'TALENT_ADMIN', 'HR_ADMIN'));

-- Declare the combined SYS/PER/TIM v42 bridge as a DRAFT seed only. Flyway
-- does not import, approve, activate, assign, or otherwise enable the bundle.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    42,
    'f73f9a64816ac8f5567a2a7c292821f4a40e6d675915ebc3c3ac8aad7d1fd983',
    'product-surfaces-v1.bundle-v42.generated.json',
    'DRAFT',
    FALSE);
