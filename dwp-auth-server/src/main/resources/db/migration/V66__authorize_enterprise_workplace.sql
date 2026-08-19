-- Workplace supersedes the meeting-room-only product without removing the
-- legacy authorities needed by existing sessions and bookmarked routes.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('APP.WORKPLACE', 'APP', 'Workplace experience', 'core.workspace'),
    ('ADMIN.WORKPLACE', 'ADMIN', 'Workplace administration', 'core.workspace')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('WORKSPACE_MEMBER', 'APP.WORKPLACE', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.WORKPLACE', 'CREATE'),
    ('WORKSPACE_MEMBER', 'APP.WORKPLACE', 'UPDATE'),
    ('TENANT_ADMIN', 'APP.WORKPLACE', 'VIEW'),
    ('TENANT_ADMIN', 'APP.WORKPLACE', 'CREATE'),
    ('TENANT_ADMIN', 'APP.WORKPLACE', 'UPDATE'),
    ('TENANT_ADMIN', 'ADMIN.WORKPLACE', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.WORKPLACE', 'CREATE'),
    ('TENANT_ADMIN', 'ADMIN.WORKPLACE', 'UPDATE'),
    ('TENANT_ADMIN', 'ADMIN.WORKPLACE', 'MANAGE'),
    ('CALENDAR_ADMIN', 'APP.WORKPLACE', 'VIEW'),
    ('CALENDAR_ADMIN', 'APP.WORKPLACE', 'CREATE'),
    ('CALENDAR_ADMIN', 'APP.WORKPLACE', 'UPDATE'),
    ('CALENDAR_ADMIN', 'ADMIN.WORKPLACE', 'VIEW'),
    ('CALENDAR_ADMIN', 'ADMIN.WORKPLACE', 'CREATE'),
    ('CALENDAR_ADMIN', 'ADMIN.WORKPLACE', 'UPDATE'),
    ('CALENDAR_ADMIN', 'ADMIN.WORKPLACE', 'MANAGE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
  JOIN sys_tenant_resource_templates template
    ON template.resource_key IN ('APP.WORKPLACE', 'ADMIN.WORKPLACE')
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
    ON role.code = template.role_code AND role.status = 'ACTIVE'
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = template.resource_key
   AND resource.enabled = TRUE
  JOIN com_permissions permission ON permission.code = template.permission_code
 WHERE template.lifecycle_state = 'ACTIVE'
   AND template.resource_key IN ('APP.WORKPLACE', 'ADMIN.WORKPLACE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_admin_resource_sets (
    resource_set_id, tenant_id, resource_set_key, name, description,
    resource_type, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-set:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id, 'APP_WORKPLACE', resource.name,
       'Administrative boundary for ' || resource.name,
       'APP', 'ACTIVE', 1, 1
  FROM com_resources resource
 WHERE resource.type = 'APP' AND resource.key = 'APP.WORKPLACE'
ON CONFLICT (tenant_id, resource_set_key) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_admin_resource_set_members (
    resource_set_member_id, tenant_id, resource_set_id,
    resource_type, resource_key, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-member:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id, resource_set.resource_set_id,
       'APP', resource.key, 'ACTIVE', 1, 1
  FROM com_resources resource
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = resource.tenant_id
   AND resource_set.resource_set_key = 'APP_WORKPLACE'
 WHERE resource.type = 'APP' AND resource.key = 'APP.WORKPLACE'
ON CONFLICT (resource_set_id, resource_type, resource_key) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP, updated_by = 1;
