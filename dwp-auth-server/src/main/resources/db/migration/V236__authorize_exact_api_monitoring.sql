-- API Monitoring is a tenant control-plane resource. Reads and future mutations
-- use distinct authorities; broad administrator role names are not a PEP.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement, lifecycle_state)
VALUES (
    'ADMIN.API_MONITORING', 'ADMIN', 'API monitoring', NULL, 'ACTIVE')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code, lifecycle_state)
SELECT role_code, 'ADMIN.API_MONITORING', permission_code, 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) roles(role_code)
 CROSS JOIN (VALUES ('VIEW'), ('MANAGE')) permissions(permission_code)
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, 'ADMIN', 'ADMIN.API_MONITORING',
       'API monitoring', TRUE, 1, 1
  FROM com_tenants tenant
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
 WHERE template.resource_key = 'ADMIN.API_MONITORING'
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- Force durable sessions that inherit one of these grants to re-project their
-- permission evidence. Explicit DENY rows remain authoritative and unchanged.
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
          AND role.status = 'ACTIVE'
        WHERE membership.tenant_id = user_record.tenant_id
          AND membership.user_id = user_record.user_id
          AND role.code IN ('ADMIN', 'PLATFORM_ADMIN', 'TENANT_ADMIN'))
    OR EXISTS (
       SELECT 1
         FROM com_group_members group_member
         JOIN com_groups access_group
           ON access_group.tenant_id = group_member.tenant_id
          AND access_group.group_id = group_member.group_id
          AND access_group.status = 'ACTIVE'
         JOIN com_group_role_assignments assignment
           ON assignment.tenant_id = group_member.tenant_id
          AND assignment.group_id = group_member.group_id
          AND assignment.assignment_type = 'ACTIVE'
          AND assignment.lifecycle_state = 'ACTIVE'
          AND assignment.scope_type = 'TENANT'
          AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
          AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
         JOIN com_roles role
           ON role.tenant_id = assignment.tenant_id
          AND role.role_id = assignment.role_id
          AND role.status = 'ACTIVE'
        WHERE group_member.tenant_id = user_record.tenant_id
          AND group_member.user_id = user_record.user_id
          AND role.code IN ('ADMIN', 'PLATFORM_ADMIN', 'TENANT_ADMIN'));
