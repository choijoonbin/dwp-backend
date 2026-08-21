-- Notification template publication is an independent responsibility from
-- policy approval. Notification audit evidence is consumed through the
-- central Audit Control Plane rather than a duplicate product-local resource.
INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES (
    'NOTIFICATION_TEMPLATE_APPROVER', 'Notification template approver',
    'Independently validates and publishes tenant notification template revisions.',
    'WORKSPACE', '{"ko":"알림 템플릿 승인자","en":"Notification template approver"}',
    TRUE, TRUE, 63, 'ACTIVE', 'DELEGATED')
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
SELECT grantor.role_code, 'NOTIFICATION_TEMPLATE_APPROVER', 'APPROVAL', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

UPDATE sys_role_conflict_policies
   SET lifecycle_state = 'RETIRED',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP
 WHERE left_role_code = 'NOTIFICATION_POLICY_APPROVER'
   AND right_role_code = 'NOTIFICATION_TEMPLATE_EDITOR'
   AND reason_code = 'NOTIFICATION_FOUR_EYES';

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code,
    lifecycle_state, enforcement, risk_level)
VALUES (
    'NOTIFICATION_TEMPLATE_APPROVER', 'NOTIFICATION_TEMPLATE_EDITOR',
    'NOTIFICATION_TEMPLATE_FOUR_EYES', 'ACTIVE', 'DENY', 'HIGH')
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    enforcement = 'DENY',
    risk_level = 'HIGH',
    version = sys_role_conflict_policies.version + 1,
    updated_at = CURRENT_TIMESTAMP;

UPDATE sys_tenant_role_permission_templates
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
 WHERE (role_code = 'NOTIFICATION_POLICY_APPROVER'
        AND resource_key = 'ADMIN.NOTIFICATION_TEMPLATE'
        AND permission_code = 'APPROVE')
    OR resource_key = 'ADMIN.NOTIFICATION_AUDIT';

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code, lifecycle_state)
VALUES
    ('NOTIFICATION_TEMPLATE_APPROVER', 'APP.NOTIFICATIONS', 'VIEW', 'ACTIVE'),
    ('NOTIFICATION_TEMPLATE_APPROVER', 'ADMIN.NOTIFICATION_TEMPLATE', 'VIEW', 'ACTIVE'),
    ('NOTIFICATION_TEMPLATE_APPROVER', 'ADMIN.NOTIFICATION_TEMPLATE', 'APPROVE', 'ACTIVE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

UPDATE sys_tenant_resource_templates
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
 WHERE resource_key = 'ADMIN.NOTIFICATION_AUDIT';

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name,
       catalog.description, 'ACTIVE', 'SYSTEM', catalog.privileged,
       catalog.assignable_to_groups, catalog.role_code, 1, 1
  FROM com_tenants tenant
  JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = 'NOTIFICATION_TEMPLATE_APPROVER'
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

DELETE FROM com_role_permissions assignment
 USING com_roles role,
       com_resources resource,
       com_permissions permission
 WHERE assignment.tenant_id = role.tenant_id
   AND assignment.role_id = role.role_id
   AND assignment.tenant_id = resource.tenant_id
   AND assignment.resource_id = resource.resource_id
   AND assignment.permission_id = permission.permission_id
   AND ((role.code = 'NOTIFICATION_POLICY_APPROVER'
         AND resource.key = 'ADMIN.NOTIFICATION_TEMPLATE'
         AND permission.code = 'APPROVE')
        OR resource.key = 'ADMIN.NOTIFICATION_AUDIT');

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
 WHERE template.role_code = 'NOTIFICATION_TEMPLATE_APPROVER'
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

UPDATE com_resources
   SET enabled = FALSE,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE key = 'ADMIN.NOTIFICATION_AUDIT';

INSERT INTO com_group_role_assignments (
    tenant_id, group_id, role_id, assignment_type, scope_type,
    lifecycle_state, justification, created_by, updated_by)
SELECT access_group.tenant_id, access_group.group_id, role.role_id,
       'ACTIVE', 'TENANT', 'ACTIVE',
       '알림 템플릿 게시를 작성 책임과 분리된 승인 역할에 위임합니다.', 1, 1
  FROM com_groups access_group
  JOIN com_roles role
    ON role.tenant_id = access_group.tenant_id
   AND role.code = 'NOTIFICATION_TEMPLATE_APPROVER'
 WHERE access_group.group_key = 'SKAX_NOTIFICATION_GOVERNORS'
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
         FROM com_group_members member
         JOIN com_groups access_group
           ON access_group.tenant_id = member.tenant_id
          AND access_group.group_id = member.group_id
        WHERE member.tenant_id = user_record.tenant_id
          AND member.user_id = user_record.user_id
          AND access_group.group_key = 'SKAX_NOTIFICATION_GOVERNORS')
    OR EXISTS (
       SELECT 1
         FROM com_role_members membership
         JOIN com_roles role
           ON role.tenant_id = membership.tenant_id
          AND role.role_id = membership.role_id
        WHERE membership.tenant_id = user_record.tenant_id
          AND membership.user_id = user_record.user_id
          AND role.code IN ('NOTIFICATION_POLICY_APPROVER', 'AUDITOR', 'TENANT_ADMIN'));
