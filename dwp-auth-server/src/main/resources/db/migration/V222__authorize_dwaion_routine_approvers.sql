-- Cross-user routine review is a tenant-scoped privileged duty. Ordinary
-- workspace members keep self-service VIEW/MANAGE but never inherit APPROVE.
INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES (
    'DWAION_ROUTINE_APPROVER', 'DWAI-ON routine approver',
    'Independently reviews another member''s high-risk personal routine definition change.',
    'WORKSPACE', '{"ko":"DWAI·ON 루틴 승인자","en":"DWAI-ON routine approver"}',
    TRUE, TRUE, 64, 'ACTIVE', 'DELEGATED')
ON CONFLICT (role_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    role_family = EXCLUDED.role_family,
    label_i18n = EXCLUDED.label_i18n,
    privileged = EXCLUDED.privileged,
    assignable_to_groups = EXCLUDED.assignable_to_groups,
    lifecycle_state = 'ACTIVE',
    assignment_class = EXCLUDED.assignment_class,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_assignment_policies (
    grantor_role_code, target_role_code, assignment_mode, lifecycle_state)
SELECT grantor.role_code, 'DWAION_ROUTINE_APPROVER', 'APPROVAL', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code, lifecycle_state)
VALUES
    ('DWAION_ROUTINE_APPROVER', 'APP.ASK', 'VIEW', 'ACTIVE'),
    ('DWAION_ROUTINE_APPROVER', 'APP.DWAION_ROUTINES', 'APPROVE', 'ACTIVE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name,
       catalog.description, 'ACTIVE', 'SYSTEM', catalog.privileged,
       catalog.assignable_to_groups, catalog.role_code, 1, 1
  FROM com_tenants tenant
  JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = 'DWAION_ROUTINE_APPROVER'
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
 WHERE template.role_code = 'DWAION_ROUTINE_APPROVER'
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

-- Defense in depth for upgraded tenants: a historical workspace template must
-- never accidentally grant cross-user routine approval.
UPDATE sys_tenant_role_permission_templates
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
 WHERE role_code = 'WORKSPACE_MEMBER'
   AND resource_key = 'APP.DWAION_ROUTINES'
   AND permission_code = 'APPROVE';

DELETE FROM com_role_permissions grant_record
 USING com_roles role, com_resources resource, com_permissions permission
 WHERE grant_record.tenant_id = role.tenant_id
   AND grant_record.role_id = role.role_id
   AND grant_record.tenant_id = resource.tenant_id
   AND grant_record.resource_id = resource.resource_id
   AND grant_record.permission_id = permission.permission_id
   AND role.code = 'WORKSPACE_MEMBER'
   AND resource.key = 'APP.DWAION_ROUTINES'
   AND permission.code = 'APPROVE';
