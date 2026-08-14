-- Calendar access is baseline workforce functionality. Calendar administration
-- is a delegated operational duty and never follows from tenant ownership.
INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES (
    'CALENDAR_ADMIN', 'Calendar administrator',
    'Maintains scheduling policies, workplace resources, and calendar integrations without reading private event details.',
    'WORKSPACE', '{"ko":"캘린더 관리자","en":"Calendar administrator"}',
    FALSE, TRUE, 48, 'ACTIVE', 'DELEGATED')
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

INSERT INTO sys_role_assignment_policies (
    grantor_role_code, target_role_code, assignment_mode, lifecycle_state)
SELECT grantor_role_code, 'CALENDAR_ADMIN', 'DIRECT', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(grantor_role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code, lifecycle_state)
VALUES ('AUDITOR', 'CALENDAR_ADMIN', 'AUDIT_INDEPENDENCE', 'ACTIVE')
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('APP.CALENDAR', 'APP', 'Calendar', 'core.workspace'),
    ('ADMIN.CALENDAR', 'ADMIN', 'Calendar administration', 'core.workspace')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('WORKSPACE_MEMBER', 'APP.CALENDAR', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.CALENDAR', 'CREATE'),
    ('WORKSPACE_MEMBER', 'APP.CALENDAR', 'UPDATE'),
    ('TENANT_ADMIN', 'ADMIN.CALENDAR', 'VIEW'),
    ('CALENDAR_ADMIN', 'APP.CALENDAR', 'VIEW'),
    ('CALENDAR_ADMIN', 'APP.CALENDAR', 'CREATE'),
    ('CALENDAR_ADMIN', 'APP.CALENDAR', 'UPDATE'),
    ('CALENDAR_ADMIN', 'ADMIN.CALENDAR', 'VIEW'),
    ('CALENDAR_ADMIN', 'ADMIN.CALENDAR', 'CREATE'),
    ('CALENDAR_ADMIN', 'ADMIN.CALENDAR', 'UPDATE'),
    ('CALENDAR_ADMIN', 'ADMIN.CALENDAR', 'MANAGE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name,
       catalog.description, 'ACTIVE', 'SYSTEM', catalog.privileged,
       catalog.assignable_to_groups, catalog.role_code, 1, 1
  FROM com_tenants tenant
  JOIN sys_builtin_role_catalog catalog ON catalog.role_code = 'CALENDAR_ADMIN'
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

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key IN ('APP.CALENDAR', 'ADMIN.CALENDAR')
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
   AND template.resource_key IN ('APP.CALENDAR', 'ADMIN.CALENDAR')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

-- Materialize the application boundary for app ownership and access governance.
INSERT INTO com_admin_resource_sets (
    resource_set_id, tenant_id, resource_set_key, name, description,
    resource_type, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-set:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id, 'APP_CALENDAR', resource.name,
       'Administrative boundary for ' || resource.name,
       'APP', 'ACTIVE', 1, 1
  FROM com_resources resource
 WHERE resource.type = 'APP' AND resource.key = 'APP.CALENDAR'
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
   AND resource_set.resource_set_key = 'APP_CALENDAR'
 WHERE resource.type = 'APP' AND resource.key = 'APP.CALENDAR'
ON CONFLICT (resource_set_id, resource_type, resource_key) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

-- SKAX development assignment. Production membership comes from SCIM/IAM.
INSERT INTO com_groups (
    tenant_id, group_key, display_name, description,
    source_type, external_id, status, created_by, updated_by)
SELECT tenant_id, 'SKAX_CALENDAR_ADMINS', '캘린더 운영 관리자',
       '일정 정책, 회의실 및 예약 자원을 운영하는 기능 관리자 그룹',
       'LOCAL', 'seed:skax_calendar_admins', 'ACTIVE', 1, 1
  FROM com_tenants
 WHERE code = 'default' AND name = 'SKAX'
ON CONFLICT (tenant_id, group_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    source_type = 'LOCAL',
    external_id = EXCLUDED.external_id,
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_group_members (
    tenant_id, group_id, user_id, source_type, created_by, updated_by)
SELECT access_group.tenant_id, access_group.group_id, user_record.user_id,
       'LOCAL', 1, 1
  FROM com_groups access_group
  JOIN com_users user_record
    ON user_record.tenant_id = access_group.tenant_id
   AND user_record.email_normalized = 'seoyeon.lee@sk.com'
 WHERE access_group.group_key = 'SKAX_CALENDAR_ADMINS'
ON CONFLICT (tenant_id, group_id, user_id) DO NOTHING;

INSERT INTO com_group_role_assignments (
    tenant_id, group_id, role_id, assignment_type, scope_type,
    lifecycle_state, justification, created_by, updated_by)
SELECT access_group.tenant_id, access_group.group_id, role.role_id,
       'ACTIVE', 'TENANT', 'ACTIVE',
       'SKAX 캘린더 운영 책임을 업무 그룹 단위로 위임합니다.', 1, 1
  FROM com_groups access_group
  JOIN com_roles role
    ON role.tenant_id = access_group.tenant_id
   AND role.code = 'CALENDAR_ADMIN'
 WHERE access_group.group_key = 'SKAX_CALENDAR_ADMINS'
   AND NOT EXISTS (
       SELECT 1
         FROM com_group_role_assignments existing
        WHERE existing.tenant_id = access_group.tenant_id
          AND existing.group_id = access_group.group_id
          AND existing.role_id = role.role_id
          AND existing.scope_type = 'TENANT'
          AND existing.lifecycle_state = 'ACTIVE');
