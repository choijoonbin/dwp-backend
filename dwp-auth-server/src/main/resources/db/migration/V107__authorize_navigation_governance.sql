-- Navigation publishing changes every user's product entry points. Keep it on
-- a dedicated tenant authority instead of reusing a broad administration role.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES ('ADMIN.NAVIGATION', 'ADMIN', 'Navigation governance', NULL)
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code, lifecycle_state)
VALUES
    ('ADMIN', 'ADMIN.NAVIGATION', 'VIEW', 'ACTIVE'),
    ('ADMIN', 'ADMIN.NAVIGATION', 'MANAGE', 'ACTIVE'),
    ('PLATFORM_ADMIN', 'ADMIN.NAVIGATION', 'VIEW', 'ACTIVE'),
    ('PLATFORM_ADMIN', 'ADMIN.NAVIGATION', 'MANAGE', 'ACTIVE'),
    ('TENANT_ADMIN', 'ADMIN.NAVIGATION', 'VIEW', 'ACTIVE'),
    ('TENANT_ADMIN', 'ADMIN.NAVIGATION', 'MANAGE', 'ACTIVE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key = 'ADMIN.NAVIGATION'
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
 WHERE template.resource_key = 'ADMIN.NAVIGATION'
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
          AND role.code IN ('ADMIN', 'PLATFORM_ADMIN', 'TENANT_ADMIN'))
    OR EXISTS (
       SELECT 1
         FROM com_group_members group_member
         JOIN com_group_role_assignments assignment
           ON assignment.tenant_id = group_member.tenant_id
          AND assignment.group_id = group_member.group_id
          AND assignment.assignment_type = 'ACTIVE'
          AND assignment.lifecycle_state = 'ACTIVE'
          AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
          AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
         JOIN com_roles role
           ON role.tenant_id = assignment.tenant_id
          AND role.role_id = assignment.role_id
        WHERE group_member.tenant_id = user_record.tenant_id
          AND group_member.user_id = user_record.user_id
          AND role.code IN ('ADMIN', 'PLATFORM_ADMIN', 'TENANT_ADMIN'));
