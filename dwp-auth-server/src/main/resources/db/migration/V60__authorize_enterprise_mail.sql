-- Mail is baseline workforce functionality. Tenant-wide connector, retention,
-- and AI policy administration is a delegated duty that does not grant access
-- to private message content.
INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES (
    'MAIL_ADMIN', 'Mail administrator',
    'Maintains tenant mail connections, shared inboxes, retention, and AI assistance policies without reading private mailboxes.',
    'WORKSPACE', '{"ko":"메일 관리자","en":"Mail administrator"}',
    TRUE, TRUE, 49, 'ACTIVE', 'DELEGATED')
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
SELECT grantor_role_code, 'MAIL_ADMIN', 'DIRECT', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(grantor_role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code, lifecycle_state)
VALUES ('AUDITOR', 'MAIL_ADMIN', 'AUDIT_INDEPENDENCE', 'ACTIVE')
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('APP.MAIL', 'APP', 'Mail', 'core.workspace'),
    ('ADMIN.MAIL', 'ADMIN', 'Mail administration', 'core.workspace')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('WORKSPACE_MEMBER', 'APP.MAIL', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.MAIL', 'CREATE'),
    ('WORKSPACE_MEMBER', 'APP.MAIL', 'UPDATE'),
    ('TENANT_ADMIN', 'ADMIN.MAIL', 'VIEW'),
    ('MAIL_ADMIN', 'APP.MAIL', 'VIEW'),
    ('MAIL_ADMIN', 'APP.MAIL', 'CREATE'),
    ('MAIL_ADMIN', 'APP.MAIL', 'UPDATE'),
    ('MAIL_ADMIN', 'ADMIN.MAIL', 'VIEW'),
    ('MAIL_ADMIN', 'ADMIN.MAIL', 'CREATE'),
    ('MAIL_ADMIN', 'ADMIN.MAIL', 'UPDATE'),
    ('MAIL_ADMIN', 'ADMIN.MAIL', 'MANAGE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name,
       catalog.description, 'ACTIVE', 'SYSTEM', catalog.privileged,
       catalog.assignable_to_groups, catalog.role_code, 1, 1
  FROM com_tenants tenant
  JOIN sys_builtin_role_catalog catalog ON catalog.role_code = 'MAIL_ADMIN'
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
 WHERE template.resource_key IN ('APP.MAIL', 'ADMIN.MAIL')
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
   AND template.resource_key IN ('APP.MAIL', 'ADMIN.MAIL')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO com_admin_resource_sets (
    resource_set_id, tenant_id, resource_set_key, name, description,
    resource_type, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-set:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id, 'APP_MAIL', resource.name,
       'Administrative boundary for ' || resource.name,
       'APP', 'ACTIVE', 1, 1
  FROM com_resources resource
 WHERE resource.type = 'APP' AND resource.key = 'APP.MAIL'
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
   AND resource_set.resource_set_key = 'APP_MAIL'
 WHERE resource.type = 'APP' AND resource.key = 'APP.MAIL'
ON CONFLICT (resource_set_id, resource_type, resource_key) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

-- Preserve delegated application ownership and access duties while replacing
-- the former combined mail/calendar application boundary.
INSERT INTO com_admin_role_assignments (
    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
    responsibility_code, resource_set_id, assignment_source,
    lifecycle_state, valid_from, valid_to, review_due_at, justification,
    approved_by, approved_at, decision_reason, revoked_by, revoked_at,
    revocation_reason, version, created_at, created_by, updated_at, updated_by)
SELECT md5('mail-scope-migration:' || assignment.admin_role_assignment_id)::uuid,
       assignment.tenant_id, assignment.principal_type, assignment.principal_ref,
       assignment.responsibility_code, target.resource_set_id,
       assignment.assignment_source, assignment.lifecycle_state,
       assignment.valid_from, assignment.valid_to, assignment.review_due_at,
       assignment.justification, assignment.approved_by, assignment.approved_at,
       assignment.decision_reason, assignment.revoked_by, assignment.revoked_at,
       assignment.revocation_reason, assignment.version,
       assignment.created_at, assignment.created_by,
       CURRENT_TIMESTAMP, assignment.updated_by
  FROM com_admin_role_assignments assignment
  JOIN com_admin_resource_sets source
    ON source.resource_set_id = assignment.resource_set_id
   AND source.tenant_id = assignment.tenant_id
   AND source.resource_set_key = 'APP_MAIL_CALENDAR'
  JOIN com_admin_resource_sets target
    ON target.tenant_id = assignment.tenant_id
   AND target.resource_set_key = 'APP_MAIL'
ON CONFLICT DO NOTHING;

INSERT INTO com_groups (
    tenant_id, group_key, display_name, description,
    source_type, external_id, status, created_by, updated_by)
SELECT tenant_id, 'SKAX_MAIL_ADMINS', '메일 운영 관리자',
       '메일 연결, 공유 메일함, 보존 및 AI 지원 정책을 운영하는 기능 관리자 그룹',
       'LOCAL', 'seed:skax_mail_admins', 'ACTIVE', 1, 1
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
   AND user_record.email_normalized = 'minseok.jang@sk.com'
 WHERE access_group.group_key = 'SKAX_MAIL_ADMINS'
ON CONFLICT (tenant_id, group_id, user_id) DO NOTHING;

INSERT INTO com_group_role_assignments (
    tenant_id, group_id, role_id, assignment_type, scope_type,
    lifecycle_state, justification, created_by, updated_by)
SELECT access_group.tenant_id, access_group.group_id, role.role_id,
       'ACTIVE', 'TENANT', 'ACTIVE',
       'SKAX 메일 운영 책임을 업무 그룹 단위로 위임합니다.', 1, 1
  FROM com_groups access_group
  JOIN com_roles role
    ON role.tenant_id = access_group.tenant_id
   AND role.code = 'MAIL_ADMIN'
 WHERE access_group.group_key = 'SKAX_MAIL_ADMINS'
   AND NOT EXISTS (
       SELECT 1
         FROM com_group_role_assignments existing
        WHERE existing.tenant_id = access_group.tenant_id
          AND existing.group_id = access_group.group_id
          AND existing.role_id = role.role_id
          AND existing.scope_type = 'TENANT'
          AND existing.lifecycle_state = 'ACTIVE');

UPDATE sys_tenant_resource_templates
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
 WHERE resource_key = 'APP.MAIL_CALENDAR';

UPDATE sys_tenant_role_permission_templates
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
 WHERE resource_key = 'APP.MAIL_CALENDAR';

UPDATE com_resources
   SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP, updated_by = 1
 WHERE key = 'APP.MAIL_CALENDAR';

UPDATE com_admin_resource_set_members
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP, updated_by = 1
 WHERE resource_key = 'APP.MAIL_CALENDAR';

UPDATE com_admin_resource_sets
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP, updated_by = 1
 WHERE resource_set_key = 'APP_MAIL_CALENDAR';
