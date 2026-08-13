INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES
    ('SERVICE_CATALOG_MANAGER', 'Service catalog manager',
     'Designs and governs tenant employee-service catalog definitions.',
     'TENANT', '{"ko":"서비스 카탈로그 관리자","en":"Service catalog manager"}',
     FALSE, TRUE, 44, 'ACTIVE', 'DELEGATED'),
    ('SERVICE_AGENT', 'Service operations agent',
     'Triages and resolves employee-service requests without catalog design authority.',
     'TENANT', '{"ko":"서비스 처리 담당자","en":"Service operations agent"}',
     FALSE, TRUE, 45, 'ACTIVE', 'DELEGATED')
ON CONFLICT (role_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    role_family = EXCLUDED.role_family,
    label_i18n = EXCLUDED.label_i18n,
    privileged = EXCLUDED.privileged,
    assignable_to_groups = EXCLUDED.assignable_to_groups,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    assignment_class = EXCLUDED.assignment_class,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name, catalog.description,
       'ACTIVE', 'SYSTEM', catalog.privileged, catalog.assignable_to_groups,
       catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE catalog.role_code IN ('SERVICE_CATALOG_MANAGER', 'SERVICE_AGENT')
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    role_type = 'SYSTEM',
    privileged = EXCLUDED.privileged,
    assignable_to_groups = EXCLUDED.assignable_to_groups,
    builtin_role_code = EXCLUDED.builtin_role_code,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO sys_role_assignment_policies (
    grantor_role_code, target_role_code, assignment_mode, lifecycle_state)
SELECT grantor.role_code, target.role_code, 'DIRECT', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
 CROSS JOIN (VALUES ('SERVICE_CATALOG_MANAGER'), ('SERVICE_AGENT')) target(role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant_id, resource.type, resource.key, resource.name, TRUE, 1, 1
  FROM com_tenants
 CROSS JOIN (VALUES
    ('ADMIN', 'ADMIN.SERVICE_CATALOG', 'Employee service catalog administration'),
    ('ADMIN', 'ADMIN.SERVICE_OPERATIONS', 'Employee service request operations')
 ) resource(type, key, name)
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission
    ON permission.code IN ('VIEW', 'CREATE', 'UPDATE', 'MANAGE')
 WHERE role.code IN ('ADMIN', 'TENANT_ADMIN', 'PLATFORM_ADMIN')
   AND resource.key IN ('ADMIN.SERVICE_CATALOG', 'ADMIN.SERVICE_OPERATIONS')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission
    ON permission.code IN ('VIEW', 'CREATE', 'UPDATE', 'MANAGE')
 WHERE role.code = 'SERVICE_CATALOG_MANAGER'
   AND resource.key = 'ADMIN.SERVICE_CATALOG'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code IN ('VIEW', 'UPDATE', 'MANAGE')
 WHERE role.code = 'SERVICE_AGENT'
   AND resource.key = 'ADMIN.SERVICE_OPERATIONS'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id,
       'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource ON resource.tenant_id = role.tenant_id
  JOIN com_permissions permission ON permission.code = 'VIEW'
 WHERE role.code IN ('SERVICE_CATALOG_MANAGER', 'SERVICE_AGENT')
   AND resource.key IN ('APP.ADMINISTRATION', 'APP.EMPLOYEE_SERVICES')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;
