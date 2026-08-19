-- Enterprise Space authorization separates app use, Space-scoped ownership,
-- tenant governance, template design, compliance review, and access review.
INSERT INTO com_resource_type_catalog (
    resource_type, display_name, lifecycle_state, sort_order)
VALUES ('SPACE', 'Collaboration Space', 'ACTIVE', 45)
ON CONFLICT (resource_type) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    lifecycle_state = 'ACTIVE',
    sort_order = EXCLUDED.sort_order;

ALTER TABLE com_admin_resource_sets
    DROP CONSTRAINT ck_admin_resource_set_type;
ALTER TABLE com_admin_resource_sets
    ADD CONSTRAINT ck_admin_resource_set_type
        CHECK (resource_type IN ('APP', 'SPACE'));

ALTER TABLE com_principal_resource_grants
    DROP CONSTRAINT ck_principal_resource_grant_source;
ALTER TABLE com_principal_resource_grants
    ADD CONSTRAINT ck_principal_resource_grant_source
        CHECK (source_type IN (
            'APP_ACCESS_REQUEST', 'ADMIN_DIRECT', 'ACCESS_PACKAGE',
            'SPACE_MEMBERSHIP', 'SPACE_ACCESS_REQUEST'));

INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES
    ('SPACE_GOVERNANCE_ADMIN', 'Space governance administrator',
     'Governs Space creation, policy, portfolio, and lifecycle without owning member content.',
     'TENANT', '{"ko":"Space 거버넌스 관리자","en":"Space governance administrator"}',
     TRUE, TRUE, 70, 'ACTIVE', 'DELEGATED'),
    ('SPACE_TEMPLATE_ADMIN', 'Space template administrator',
     'Designs and publishes governed Space templates and default app bundles.',
     'TENANT', '{"ko":"Space 템플릿 관리자","en":"Space template administrator"}',
     FALSE, TRUE, 71, 'ACTIVE', 'DELEGATED'),
    ('SPACE_COMPLIANCE_REVIEWER', 'Space compliance reviewer',
     'Independently reviews restricted content and publication evidence.',
     'TENANT', '{"ko":"Space 컴플라이언스 검토자","en":"Space compliance reviewer"}',
     TRUE, FALSE, 72, 'ACTIVE', 'DELEGATED'),
    ('SPACE_ACCESS_REVIEWER', 'Space access reviewer',
     'Certifies Space memberships and lifecycle access evidence.',
     'TENANT', '{"ko":"Space 접근 검토자","en":"Space access reviewer"}',
     TRUE, FALSE, 73, 'ACTIVE', 'DELEGATED')
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
       CASE WHEN target.role_code IN (
            'SPACE_GOVERNANCE_ADMIN', 'SPACE_COMPLIANCE_REVIEWER', 'SPACE_ACCESS_REVIEWER')
            THEN 'APPROVAL' ELSE 'DIRECT' END,
       'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
 CROSS JOIN (VALUES
    ('SPACE_GOVERNANCE_ADMIN'), ('SPACE_TEMPLATE_ADMIN'),
    ('SPACE_COMPLIANCE_REVIEWER'), ('SPACE_ACCESS_REVIEWER')) target(role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code,
    lifecycle_state, enforcement, risk_level)
VALUES
    ('SPACE_COMPLIANCE_REVIEWER', 'SPACE_TEMPLATE_ADMIN',
     'SPACE_TEMPLATE_COMPLIANCE_SEPARATION', 'ACTIVE', 'DENY', 'HIGH'),
    ('AUDITOR', 'SPACE_GOVERNANCE_ADMIN',
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
    ('APP.SPACES', 'APP', 'Enterprise Spaces', 'core.spaces'),
    ('ACTION.SPACE_REQUEST', 'ACTION', 'Space creation requests', 'core.spaces'),
    ('ACTION.SPACE_CONTENT', 'ACTION', 'Space content lifecycle', 'core.spaces'),
    ('ACTION.SPACE_MEMBERSHIP', 'ACTION', 'Space membership lifecycle', 'core.spaces'),
    ('ADMIN.SPACE_GOVERNANCE', 'ADMIN', 'Space portfolio governance', 'core.spaces'),
    ('ADMIN.SPACE_TEMPLATES', 'ADMIN', 'Space template governance', 'core.spaces'),
    ('ADMIN.SPACE_COMPLIANCE', 'ADMIN', 'Space content compliance', 'core.spaces'),
    ('ADMIN.SPACE_ACCESS_REVIEW', 'ADMIN', 'Space access and lifecycle review', 'core.spaces')
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
    ('WORKSPACE_MEMBER', 'APP.SPACES', 'VIEW'),
    ('WORKSPACE_MEMBER', 'ACTION.SPACE_REQUEST', 'VIEW'),
    ('WORKSPACE_MEMBER', 'ACTION.SPACE_REQUEST', 'CREATE'),
    ('WORKSPACE_MEMBER', 'ACTION.SPACE_CONTENT', 'VIEW'),
    ('WORKSPACE_MEMBER', 'ACTION.SPACE_CONTENT', 'CREATE'),
    ('WORKSPACE_MEMBER', 'ACTION.SPACE_CONTENT', 'UPDATE'),
    ('WORKSPACE_MEMBER', 'ACTION.SPACE_MEMBERSHIP', 'VIEW'),
    ('WORKSPACE_MEMBER', 'ACTION.SPACE_MEMBERSHIP', 'CREATE'),

    ('TENANT_ADMIN', 'APP.SPACES', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.SPACE_GOVERNANCE', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.SPACE_GOVERNANCE', 'MANAGE'),
    ('TENANT_ADMIN', 'ADMIN.SPACE_TEMPLATES', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.SPACE_COMPLIANCE', 'VIEW'),
    ('TENANT_ADMIN', 'ADMIN.SPACE_ACCESS_REVIEW', 'VIEW'),

    ('SPACE_GOVERNANCE_ADMIN', 'APP.SPACES', 'VIEW'),
    ('SPACE_GOVERNANCE_ADMIN', 'ADMIN.SPACE_GOVERNANCE', 'VIEW'),
    ('SPACE_GOVERNANCE_ADMIN', 'ADMIN.SPACE_GOVERNANCE', 'MANAGE'),
    ('SPACE_GOVERNANCE_ADMIN', 'ADMIN.SPACE_TEMPLATES', 'VIEW'),
    ('SPACE_GOVERNANCE_ADMIN', 'ADMIN.SPACE_COMPLIANCE', 'VIEW'),
    ('SPACE_GOVERNANCE_ADMIN', 'ADMIN.SPACE_ACCESS_REVIEW', 'VIEW'),

    ('SPACE_TEMPLATE_ADMIN', 'APP.SPACES', 'VIEW'),
    ('SPACE_TEMPLATE_ADMIN', 'ADMIN.SPACE_TEMPLATES', 'VIEW'),
    ('SPACE_TEMPLATE_ADMIN', 'ADMIN.SPACE_TEMPLATES', 'CREATE'),
    ('SPACE_TEMPLATE_ADMIN', 'ADMIN.SPACE_TEMPLATES', 'UPDATE'),
    ('SPACE_TEMPLATE_ADMIN', 'ADMIN.SPACE_TEMPLATES', 'MANAGE'),

    ('SPACE_COMPLIANCE_REVIEWER', 'APP.SPACES', 'VIEW'),
    ('SPACE_COMPLIANCE_REVIEWER', 'ADMIN.SPACE_COMPLIANCE', 'VIEW'),
    ('SPACE_COMPLIANCE_REVIEWER', 'ADMIN.SPACE_COMPLIANCE', 'APPROVE'),
    ('SPACE_COMPLIANCE_REVIEWER', 'ADMIN.SPACE_COMPLIANCE', 'MANAGE'),

    ('SPACE_ACCESS_REVIEWER', 'APP.SPACES', 'VIEW'),
    ('SPACE_ACCESS_REVIEWER', 'ADMIN.SPACE_ACCESS_REVIEW', 'VIEW'),
    ('SPACE_ACCESS_REVIEWER', 'ADMIN.SPACE_ACCESS_REVIEW', 'APPROVE'),
    ('SPACE_ACCESS_REVIEWER', 'ADMIN.SPACE_ACCESS_REVIEW', 'MANAGE'))
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
    'SPACE_GOVERNANCE_ADMIN', 'SPACE_TEMPLATE_ADMIN',
    'SPACE_COMPLIANCE_REVIEWER', 'SPACE_ACCESS_REVIEWER')
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
 WHERE template.resource_key = 'APP.SPACES'
    OR template.resource_key LIKE 'ACTION.SPACE_%'
    OR template.resource_key LIKE 'ADMIN.SPACE_%'
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
   AND (template.resource_key = 'APP.SPACES'
        OR template.resource_key LIKE 'ACTION.SPACE_%'
        OR template.resource_key LIKE 'ADMIN.SPACE_%')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

CREATE TEMP TABLE tmp_space_review_accounts (
    email VARCHAR(255) PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_space_review_accounts VALUES
    ('yujin.choi@sk.com', 'SPACE_GOVERNANCE_ADMIN'),
    ('taeyeon.kim@sk.com', 'SPACE_TEMPLATE_ADMIN'),
    ('seungmin.yoo@sk.com', 'SPACE_COMPLIANCE_REVIEWER'),
    ('yerin.moon@sk.com', 'SPACE_ACCESS_REVIEWER');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_space_review_accounts seed
          LEFT JOIN com_users user_record
            ON user_record.tenant_id = 1
           AND user_record.email_normalized = seed.email
         WHERE user_record.user_id IS NULL) THEN
        RAISE EXCEPTION 'A Space governance review account is missing from the SKAX workforce projection';
    END IF;
END
$$;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT user_record.tenant_id, role.role_id, user_record.user_id, 1, 1
  FROM tmp_space_review_accounts seed
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
       SELECT email FROM tmp_space_review_accounts)
       OR EXISTS (
           SELECT 1
             FROM com_role_members membership
             JOIN com_roles role
               ON role.tenant_id = membership.tenant_id
              AND role.role_id = membership.role_id
            WHERE membership.tenant_id = user_record.tenant_id
              AND membership.user_id = user_record.user_id
              AND role.code IN ('WORKSPACE_MEMBER', 'TENANT_ADMIN')));
