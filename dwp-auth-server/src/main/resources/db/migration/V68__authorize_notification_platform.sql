-- Notification governance separates producer contracts, content authoring,
-- policy approval and delivery operations. No role grants access to another
-- user's notification body.
INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES
    ('NOTIFICATION_CONTRACT_OWNER', 'Notification contract owner',
     'Owns notification type and producer contracts without delivery operations access.',
     'WORKSPACE', '{"ko":"알림 계약 소유자","en":"Notification contract owner"}',
     TRUE, TRUE, 61, 'ACTIVE', 'DELEGATED'),
    ('NOTIFICATION_TEMPLATE_EDITOR', 'Notification template editor',
     'Authors localized notification templates without publishing policy changes.',
     'WORKSPACE', '{"ko":"알림 템플릿 편집자","en":"Notification template editor"}',
     TRUE, TRUE, 62, 'ACTIVE', 'DELEGATED'),
    ('NOTIFICATION_POLICY_APPROVER', 'Notification policy approver',
     'Reviews and approves tenant notification routing, mandatory and urgency policy.',
     'WORKSPACE', '{"ko":"알림 정책 승인자","en":"Notification policy approver"}',
     TRUE, TRUE, 63, 'ACTIVE', 'DELEGATED'),
    ('NOTIFICATION_OPERATOR', 'Notification operator',
     'Operates queues, suppression, reconciliation and governed replay without tenant content access.',
     'WORKSPACE', '{"ko":"알림 운영자","en":"Notification operator"}',
     TRUE, TRUE, 64, 'ACTIVE', 'DELEGATED')
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
SELECT grantor.role_code, target.role_code, 'APPROVAL', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
 CROSS JOIN (VALUES
     ('NOTIFICATION_CONTRACT_OWNER'),
     ('NOTIFICATION_TEMPLATE_EDITOR'),
     ('NOTIFICATION_POLICY_APPROVER'),
     ('NOTIFICATION_OPERATOR')) target(role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code,
    lifecycle_state, enforcement, risk_level)
VALUES
    ('AUDITOR', 'NOTIFICATION_OPERATOR', 'AUDIT_INDEPENDENCE', 'ACTIVE', 'DENY', 'HIGH'),
    ('NOTIFICATION_POLICY_APPROVER', 'NOTIFICATION_TEMPLATE_EDITOR',
     'NOTIFICATION_FOUR_EYES', 'ACTIVE', 'DENY', 'HIGH')
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    enforcement = EXCLUDED.enforcement,
    risk_level = EXCLUDED.risk_level,
    version = sys_role_conflict_policies.version + 1,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('APP.NOTIFICATIONS', 'APP', 'Notification center', 'core.workspace'),
    ('ADMIN.NOTIFICATION_CONTRACT', 'ADMIN', 'Notification contracts', 'core.workspace'),
    ('ADMIN.NOTIFICATION_TEMPLATE', 'ADMIN', 'Notification templates', 'core.workspace'),
    ('ADMIN.NOTIFICATION_POLICY', 'ADMIN', 'Notification policy', 'core.workspace'),
    ('ADMIN.NOTIFICATION_OPERATIONS', 'ADMIN', 'Notification operations', 'core.workspace'),
    ('ADMIN.NOTIFICATION_AUDIT', 'ADMIN', 'Notification audit', 'core.workspace')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('WORKSPACE_MEMBER', 'APP.NOTIFICATIONS', 'VIEW'),
    ('TENANT_ADMIN', 'APP.NOTIFICATIONS', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_CONTRACT', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_CONTRACT', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_TEMPLATE', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_TEMPLATE', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_TEMPLATE', 'APPROVE'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_POLICY', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_POLICY', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_POLICY', 'APPROVE'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_OPERATIONS', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_OPERATIONS', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.NOTIFICATION_AUDIT', 'VIEW'),
    ('NOTIFICATION_CONTRACT_OWNER', 'APP.NOTIFICATIONS', 'VIEW'),
    ('NOTIFICATION_CONTRACT_OWNER', 'ADMIN.NOTIFICATION_CONTRACT', 'VIEW'),
    ('NOTIFICATION_CONTRACT_OWNER', 'ADMIN.NOTIFICATION_CONTRACT', 'MANAGE'),
    ('NOTIFICATION_TEMPLATE_EDITOR', 'APP.NOTIFICATIONS', 'VIEW'),
    ('NOTIFICATION_TEMPLATE_EDITOR', 'ADMIN.NOTIFICATION_TEMPLATE', 'VIEW'),
    ('NOTIFICATION_TEMPLATE_EDITOR', 'ADMIN.NOTIFICATION_TEMPLATE', 'MANAGE'),
    ('NOTIFICATION_POLICY_APPROVER', 'APP.NOTIFICATIONS', 'VIEW'),
    ('NOTIFICATION_POLICY_APPROVER', 'ADMIN.NOTIFICATION_TEMPLATE', 'VIEW'),
    ('NOTIFICATION_POLICY_APPROVER', 'ADMIN.NOTIFICATION_TEMPLATE', 'APPROVE'),
    ('NOTIFICATION_POLICY_APPROVER', 'ADMIN.NOTIFICATION_POLICY', 'VIEW'),
    ('NOTIFICATION_POLICY_APPROVER', 'ADMIN.NOTIFICATION_POLICY', 'MANAGE'),
    ('NOTIFICATION_POLICY_APPROVER', 'ADMIN.NOTIFICATION_POLICY', 'APPROVE'),
    ('NOTIFICATION_OPERATOR', 'APP.NOTIFICATIONS', 'VIEW'),
    ('NOTIFICATION_OPERATOR', 'ADMIN.NOTIFICATION_OPERATIONS', 'VIEW'),
    ('NOTIFICATION_OPERATOR', 'ADMIN.NOTIFICATION_OPERATIONS', 'MANAGE'),
    ('NOTIFICATION_OPERATOR', 'ADMIN.NOTIFICATION_AUDIT', 'VIEW'),
    ('AUDITOR', 'ADMIN.NOTIFICATION_AUDIT', 'VIEW')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name,
       catalog.description, 'ACTIVE', 'SYSTEM', catalog.privileged,
       catalog.assignable_to_groups, catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE catalog.role_code IN (
     'NOTIFICATION_CONTRACT_OWNER', 'NOTIFICATION_TEMPLATE_EDITOR',
     'NOTIFICATION_POLICY_APPROVER', 'NOTIFICATION_OPERATOR')
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
 WHERE template.resource_key LIKE 'APP.NOTIFICATIONS'
    OR template.resource_key LIKE 'ADMIN.NOTIFICATION_%'
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
   AND (template.resource_key = 'APP.NOTIFICATIONS'
        OR template.resource_key LIKE 'ADMIN.NOTIFICATION_%')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_groups (
    tenant_id, group_key, display_name, description,
    source_type, external_id, status, created_by, updated_by)
SELECT tenant_id, group_key, display_name, description,
       'LOCAL', external_id, 'ACTIVE', 1, 1
  FROM com_tenants
 CROSS JOIN (VALUES
     ('SKAX_NOTIFICATION_BUILDERS', '알림 계약 및 템플릿 운영',
      '알림 계약과 다국어 템플릿을 운영하는 기능 관리자 그룹',
      'seed:skax_notification_builders'),
     ('SKAX_NOTIFICATION_GOVERNORS', '알림 정책 및 전달 운영',
      '알림 정책 승인과 전달 운영을 분리 수행하는 기능 관리자 그룹',
      'seed:skax_notification_governors')) seed(group_key, display_name, description, external_id)
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
  JOIN com_users user_record ON user_record.tenant_id = access_group.tenant_id
 WHERE (access_group.group_key = 'SKAX_NOTIFICATION_BUILDERS'
        AND user_record.email_normalized = 'yujin.choi@sk.com')
    OR (access_group.group_key = 'SKAX_NOTIFICATION_GOVERNORS'
        AND user_record.email_normalized = 'hyunwoo.park@sk.com')
ON CONFLICT (tenant_id, group_id, user_id) DO NOTHING;

INSERT INTO com_group_role_assignments (
    tenant_id, group_id, role_id, assignment_type, scope_type,
    lifecycle_state, justification, created_by, updated_by)
SELECT access_group.tenant_id, access_group.group_id, role.role_id,
       'ACTIVE', 'TENANT', 'ACTIVE',
       '알림 플랫폼 운영 책임을 기능 관리자 그룹 단위로 위임합니다.', 1, 1
  FROM com_groups access_group
  JOIN com_roles role ON role.tenant_id = access_group.tenant_id
 WHERE ((access_group.group_key = 'SKAX_NOTIFICATION_BUILDERS'
         AND role.code IN ('NOTIFICATION_CONTRACT_OWNER', 'NOTIFICATION_TEMPLATE_EDITOR'))
     OR (access_group.group_key = 'SKAX_NOTIFICATION_GOVERNORS'
         AND role.code IN ('NOTIFICATION_POLICY_APPROVER', 'NOTIFICATION_OPERATOR')))
   AND NOT EXISTS (
       SELECT 1
         FROM com_group_role_assignments existing
        WHERE existing.tenant_id = access_group.tenant_id
          AND existing.group_id = access_group.group_id
          AND existing.role_id = role.role_id
          AND existing.scope_type = 'TENANT'
          AND existing.lifecycle_state = 'ACTIVE');

UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE user_record.email_normalized IN ('yujin.choi@sk.com', 'hyunwoo.park@sk.com');
