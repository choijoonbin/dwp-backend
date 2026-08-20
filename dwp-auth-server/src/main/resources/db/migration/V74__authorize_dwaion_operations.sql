-- DWAI-ON tenant operations are delegated separately from company
-- administration. Tenant administrators may approve assignment of this role,
-- but do not inherit access to AI operations or retention controls.
INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES (
    'DWAION_ADMIN', 'DWAI-ON administrator',
    'Operates tenant AI runtime health, governed actions, retention, and legal-hold policy without reading conversation content.',
    'WORKSPACE', '{"ko":"DWAI·ON 관리자","en":"DWAI-ON administrator"}',
    TRUE, TRUE, 52, 'ACTIVE', 'DELEGATED')
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
SELECT grantor_role_code, 'DWAION_ADMIN', 'APPROVAL', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(grantor_role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code, lifecycle_state)
VALUES ('AUDITOR', 'DWAION_ADMIN', 'AUDIT_INDEPENDENCE', 'ACTIVE')
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES ('ADMIN.DWAION', 'ADMIN', 'DWAI-ON administration', 'ai.agent-runtime')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('DWAION_ADMIN', 'APP.ASK', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION', 'UPDATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION', 'MANAGE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name,
       catalog.description, 'ACTIVE', 'SYSTEM', catalog.privileged,
       catalog.assignable_to_groups, catalog.role_code, 1, 1
  FROM com_tenants tenant
  JOIN sys_builtin_role_catalog catalog ON catalog.role_code = 'DWAION_ADMIN'
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
  JOIN sys_tenant_resource_templates template
    ON template.resource_key = 'ADMIN.DWAION'
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
   AND template.role_code = 'DWAION_ADMIN'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_groups (
    tenant_id, group_key, display_name, description,
    source_type, external_id, status, created_by, updated_by)
SELECT tenant_id, 'SKAX_DWAION_ADMINS', 'DWAI·ON 운영 관리자',
       'AI 런타임, 통제형 작업 및 보존 정책을 운영하는 위임 관리자 그룹',
       'LOCAL', 'seed:skax_dwaion_admins', 'ACTIVE', 1, 1
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
   AND user_record.email_normalized = 'joonbin@sk.com'
 WHERE access_group.group_key = 'SKAX_DWAION_ADMINS'
ON CONFLICT (tenant_id, group_id, user_id) DO UPDATE SET
    source_type = 'LOCAL', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_group_role_assignments (
    tenant_id, group_id, role_id, assignment_type, scope_type,
    lifecycle_state, justification, created_by, updated_by)
SELECT access_group.tenant_id, access_group.group_id, role.role_id,
       'ACTIVE', 'TENANT', 'ACTIVE',
       'SKAX DWAI-ON 운영 책임을 별도 관리자 그룹에 위임합니다.', 1, 1
  FROM com_groups access_group
  JOIN com_roles role
    ON role.tenant_id = access_group.tenant_id
   AND role.code = 'DWAION_ADMIN'
 WHERE access_group.group_key = 'SKAX_DWAION_ADMINS'
   AND NOT EXISTS (
       SELECT 1
         FROM com_group_role_assignments existing
        WHERE existing.tenant_id = access_group.tenant_id
          AND existing.group_id = access_group.group_id
          AND existing.role_id = role.role_id
          AND existing.scope_type = 'TENANT'
          AND existing.lifecycle_state = 'ACTIVE');

UPDATE com_users user_record
   SET access_revision = access_revision + 1,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE EXISTS (
       SELECT 1
         FROM com_group_members membership
         JOIN com_groups access_group
           ON access_group.tenant_id = membership.tenant_id
          AND access_group.group_id = membership.group_id
        WHERE membership.tenant_id = user_record.tenant_id
          AND membership.user_id = user_record.user_id
          AND access_group.group_key = 'SKAX_DWAION_ADMINS');
