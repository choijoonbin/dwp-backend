-- Enterprise approval access model; V52 follows the calendar authorization baseline.
INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES
    ('APPROVAL_DESIGNER', 'Approval process designer',
     'Designs approval workflows and forms without publication authority.',
     'TENANT', '{"ko":"결재 프로세스 설계자","en":"Approval process designer"}',
     FALSE, TRUE, 60, 'ACTIVE', 'DELEGATED'),
    ('APPROVAL_PUBLISHER', 'Approval policy publisher',
     'Independently reviews and publishes approval workflows and policies.',
     'TENANT', '{"ko":"결재 정책 게시 책임자","en":"Approval policy publisher"}',
     TRUE, FALSE, 61, 'ACTIVE', 'DELEGATED'),
    ('APPROVAL_OPERATOR', 'Approval operations manager',
     'Operates approval queues, SLA exceptions, delegation, and delivery incidents.',
     'TENANT', '{"ko":"결재 운영 담당자","en":"Approval operations manager"}',
     TRUE, FALSE, 62, 'ACTIVE', 'DELEGATED')
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
SELECT grantor.role_code, target.role_code,
       CASE WHEN target.role_code IN ('APPROVAL_PUBLISHER', 'APPROVAL_OPERATOR')
            THEN 'APPROVAL' ELSE 'DIRECT' END,
       'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
 CROSS JOIN (VALUES
    ('APPROVAL_DESIGNER'), ('APPROVAL_PUBLISHER'), ('APPROVAL_OPERATOR')) target(role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code,
    lifecycle_state, enforcement, risk_level)
VALUES
    ('APPROVAL_DESIGNER', 'APPROVAL_PUBLISHER',
     'APPROVAL_DESIGN_PUBLISH_SEPARATION', 'ACTIVE', 'DENY', 'CRITICAL'),
    ('APPROVAL_OPERATOR', 'AUDITOR',
     'AUDIT_INDEPENDENCE', 'ACTIVE', 'DENY', 'HIGH')
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
    ('APP.APPROVALS', 'APP', 'Approval decision hub', 'core.approvals'),
    ('ACTION.APPROVAL_REQUEST', 'ACTION', 'Approval request lifecycle', 'core.approvals'),
    ('ACTION.APPROVAL_TASK', 'ACTION', 'Approval task decisions', 'core.approvals'),
    ('ACTION.APPROVAL_DELEGATION', 'ACTION', 'Personal approval delegation', 'core.approvals'),
    ('ADMIN.APPROVAL_DESIGN', 'ADMIN', 'Approval workflow and form design', 'core.approvals'),
    ('ADMIN.APPROVAL_POLICY', 'ADMIN', 'Approval policy publication', 'core.approvals'),
    ('ADMIN.APPROVAL_OPERATIONS', 'ADMIN', 'Approval operations and SLA control', 'core.approvals'),
    ('ADMIN.APPROVAL_SIGNATURE', 'ADMIN', 'Approval signature provider control', 'core.approvals')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
SELECT role_code, resource_key, permission_code
  FROM (VALUES
    ('WORKSPACE_MEMBER', 'APP.APPROVALS', 'VIEW'),
    ('WORKSPACE_MEMBER', 'ACTION.APPROVAL_REQUEST', 'VIEW'),
    ('WORKSPACE_MEMBER', 'ACTION.APPROVAL_REQUEST', 'CREATE'),
    ('WORKSPACE_MEMBER', 'ACTION.APPROVAL_REQUEST', 'UPDATE'),
    ('WORKSPACE_MEMBER', 'ACTION.APPROVAL_TASK', 'VIEW'),
    ('WORKSPACE_MEMBER', 'ACTION.APPROVAL_TASK', 'UPDATE'),
    ('WORKSPACE_MEMBER', 'ACTION.APPROVAL_TASK', 'APPROVE'),
    ('WORKSPACE_MEMBER', 'ACTION.APPROVAL_DELEGATION', 'VIEW'),
    ('WORKSPACE_MEMBER', 'ACTION.APPROVAL_DELEGATION', 'MANAGE'),

    ('TENANT_ADMIN', 'APP.APPROVALS', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.APPROVAL_DESIGN', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.APPROVAL_POLICY', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.APPROVAL_OPERATIONS', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.APPROVAL_SIGNATURE', 'VIEW'),

    ('APPROVAL_DESIGNER', 'APP.APPROVALS', 'VIEW'),
    ('APPROVAL_DESIGNER', 'ADMIN.APPROVAL_DESIGN', 'VIEW'),
    ('APPROVAL_DESIGNER', 'ADMIN.APPROVAL_DESIGN', 'CREATE'),
    ('APPROVAL_DESIGNER', 'ADMIN.APPROVAL_DESIGN', 'UPDATE'),

    ('APPROVAL_PUBLISHER', 'APP.APPROVALS', 'VIEW'),
    ('APPROVAL_PUBLISHER', 'ADMIN.APPROVAL_DESIGN', 'VIEW'),
    ('APPROVAL_PUBLISHER', 'ADMIN.APPROVAL_DESIGN', 'APPROVE'),
    ('APPROVAL_PUBLISHER', 'ADMIN.APPROVAL_POLICY', 'VIEW'),
    ('APPROVAL_PUBLISHER', 'ADMIN.APPROVAL_POLICY', 'APPROVE'),
    ('APPROVAL_PUBLISHER', 'ADMIN.APPROVAL_POLICY', 'MANAGE'),

    ('APPROVAL_OPERATOR', 'APP.APPROVALS', 'VIEW'),
    ('APPROVAL_OPERATOR', 'ACTION.APPROVAL_TASK', 'VIEW'),
    ('APPROVAL_OPERATOR', 'ACTION.APPROVAL_TASK', 'UPDATE'),
    ('APPROVAL_OPERATOR', 'ACTION.APPROVAL_TASK', 'APPROVE'),
    ('APPROVAL_OPERATOR', 'ADMIN.APPROVAL_OPERATIONS', 'VIEW'),
    ('APPROVAL_OPERATOR', 'ADMIN.APPROVAL_OPERATIONS', 'UPDATE'),
    ('APPROVAL_OPERATOR', 'ADMIN.APPROVAL_OPERATIONS', 'MANAGE'),
    ('APPROVAL_OPERATOR', 'ADMIN.APPROVAL_SIGNATURE', 'VIEW'))
       permission_matrix(role_code, resource_key, permission_code)
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
    'APPROVAL_DESIGNER', 'APPROVAL_PUBLISHER', 'APPROVAL_OPERATOR')
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
 WHERE template.resource_key = 'APP.APPROVALS'
    OR template.resource_key LIKE 'ACTION.APPROVAL_%'
    OR template.resource_key LIKE 'ADMIN.APPROVAL_%'
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
 WHERE template.lifecycle_state = 'ACTIVE'
   AND (template.resource_key = 'APP.APPROVALS'
        OR template.resource_key LIKE 'ACTION.APPROVAL_%'
        OR template.resource_key LIKE 'ADMIN.APPROVAL_%')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

CREATE TEMP TABLE tmp_approval_review_accounts (
    email VARCHAR(255) PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_approval_review_accounts VALUES
    ('taeyeon.kim@sk.com', 'APPROVAL_DESIGNER'),
    ('seungmin.yoo@sk.com', 'APPROVAL_PUBLISHER'),
    ('james.wilson@sk.com', 'APPROVAL_OPERATOR');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_approval_review_accounts seed
          LEFT JOIN com_users user_record
            ON user_record.tenant_id = 1
           AND user_record.email_normalized = seed.email
         WHERE user_record.user_id IS NULL) THEN
        RAISE EXCEPTION 'An approval review account is missing from the SKAX workforce projection';
    END IF;
END
$$;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT user_record.tenant_id, role.role_id, user_record.user_id, 1, 1
  FROM tmp_approval_review_accounts seed
  JOIN com_users user_record
    ON user_record.tenant_id = 1
   AND user_record.email_normalized = seed.email
  JOIN com_roles role
    ON role.tenant_id = user_record.tenant_id
   AND role.code = seed.role_code
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE user_record.tenant_id = 1
   AND (user_record.email_normalized IN (
       SELECT email FROM tmp_approval_review_accounts)
       OR EXISTS (
           SELECT 1 FROM com_role_members membership
           JOIN com_roles role
             ON role.tenant_id = membership.tenant_id
            AND role.role_id = membership.role_id
          WHERE membership.tenant_id = user_record.tenant_id
            AND membership.user_id = user_record.user_id
            AND role.code IN ('WORKSPACE_MEMBER', 'TENANT_ADMIN')));
