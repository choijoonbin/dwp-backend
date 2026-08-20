-- DWAI-ON governance is split by operational responsibility so a delegated
-- operator never receives every AI control simply by opening the product.
INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES
    ('DWAION_AGENT_EDITOR', 'DWAI-ON agent editor',
     'Authors draft agent definitions without publication authority.',
     'WORKSPACE', '{"ko":"DWAI·ON 에이전트 편집자","en":"DWAI-ON agent editor"}',
     FALSE, TRUE, 53, 'ACTIVE', 'DELEGATED'),
    ('DWAION_AGENT_PUBLISHER', 'DWAI-ON agent publisher',
     'Reviews, publishes, and retires validated agent revisions without draft authoring authority.',
     'WORKSPACE', '{"ko":"DWAI·ON 에이전트 게시 승인자","en":"DWAI-ON agent publisher"}',
     TRUE, FALSE, 54, 'ACTIVE', 'DELEGATED'),
    ('DWAION_GOVERNANCE_MANAGER', 'DWAI-ON governance manager',
     'Operates source, action, safety, and retention policy without conversation-content access.',
     'WORKSPACE', '{"ko":"DWAI·ON 거버넌스 관리자","en":"DWAI-ON governance manager"}',
     TRUE, FALSE, 55, 'ACTIVE', 'DELEGATED'),
    ('DWAION_EVALUATOR', 'DWAI-ON evaluator',
     'Maintains encrypted repeatable evaluation sets and runs quality checks.',
     'WORKSPACE', '{"ko":"DWAI·ON 평가자","en":"DWAI-ON evaluator"}',
     TRUE, FALSE, 56, 'ACTIVE', 'DELEGATED'),
    ('DWAION_AUDITOR', 'DWAI-ON auditor',
     'Reviews append-only AI governance and retention evidence without policy mutation authority.',
     'WORKSPACE', '{"ko":"DWAI·ON 감사자","en":"DWAI-ON auditor"}',
     TRUE, FALSE, 57, 'ACTIVE', 'DELEGATED')
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
    ('DWAION_AGENT_EDITOR'),
    ('DWAION_AGENT_PUBLISHER'),
    ('DWAION_GOVERNANCE_MANAGER'),
    ('DWAION_EVALUATOR'),
    ('DWAION_AUDITOR')) target(role_code)
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code, lifecycle_state)
VALUES
    ('DWAION_AGENT_EDITOR', 'DWAION_AGENT_PUBLISHER', 'AI_MAKER_CHECKER', 'ACTIVE'),
    ('DWAION_AUDITOR', 'DWAION_GOVERNANCE_MANAGER', 'AUDIT_INDEPENDENCE', 'ACTIVE'),
    ('DWAION_AUDITOR', 'DWAION_EVALUATOR', 'EVALUATION_INDEPENDENCE', 'ACTIVE')
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('ADMIN.DWAION_OPERATIONS', 'ADMIN', 'DWAI-ON operations overview', 'ai.agent-runtime'),
    ('ADMIN.DWAION_AGENTS', 'ADMIN', 'DWAI-ON agent publishing', 'ai.agent-runtime'),
    ('ADMIN.DWAION_SOURCES', 'ADMIN', 'DWAI-ON data sources and connectors', 'ai.agent-runtime'),
    ('ADMIN.DWAION_ACTIONS', 'ADMIN', 'DWAI-ON action permissions', 'ai.agent-runtime'),
    ('ADMIN.DWAION_SAFETY', 'ADMIN', 'DWAI-ON policy and safety controls', 'ai.agent-runtime'),
    ('ADMIN.DWAION_EVALUATION', 'ADMIN', 'DWAI-ON response evaluation', 'ai.agent-runtime'),
    ('ADMIN.DWAION_RETENTION', 'ADMIN', 'DWAI-ON retention and legal hold', 'ai.agent-runtime'),
    ('ADMIN.DWAION_AUDIT', 'ADMIN', 'DWAI-ON audit evidence', 'ai.agent-runtime')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('DWAION_ADMIN', 'ADMIN.DWAION_OPERATIONS', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_AGENTS', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_AGENTS', 'CREATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_AGENTS', 'UPDATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_AGENTS', 'APPROVE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_AGENTS', 'MANAGE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_SOURCES', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_SOURCES', 'UPDATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_SOURCES', 'MANAGE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_ACTIONS', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_ACTIONS', 'UPDATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_ACTIONS', 'MANAGE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_SAFETY', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_SAFETY', 'UPDATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_SAFETY', 'MANAGE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_EVALUATION', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_EVALUATION', 'CREATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_EVALUATION', 'UPDATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_EVALUATION', 'EXECUTE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_EVALUATION', 'MANAGE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_RETENTION', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_RETENTION', 'UPDATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_RETENTION', 'MANAGE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_AUDIT', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_AUDIT', 'EXPORT'),
    ('DWAION_AGENT_EDITOR', 'APP.ASK', 'VIEW'),
    ('DWAION_AGENT_EDITOR', 'ADMIN.DWAION_AGENTS', 'VIEW'),
    ('DWAION_AGENT_EDITOR', 'ADMIN.DWAION_AGENTS', 'CREATE'),
    ('DWAION_AGENT_EDITOR', 'ADMIN.DWAION_AGENTS', 'UPDATE'),
    ('DWAION_AGENT_PUBLISHER', 'APP.ASK', 'VIEW'),
    ('DWAION_AGENT_PUBLISHER', 'ADMIN.DWAION_AGENTS', 'VIEW'),
    ('DWAION_AGENT_PUBLISHER', 'ADMIN.DWAION_AGENTS', 'APPROVE'),
    ('DWAION_AGENT_PUBLISHER', 'ADMIN.DWAION_AGENTS', 'MANAGE'),
    ('DWAION_GOVERNANCE_MANAGER', 'APP.ASK', 'VIEW'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_OPERATIONS', 'VIEW'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_SOURCES', 'VIEW'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_SOURCES', 'UPDATE'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_ACTIONS', 'VIEW'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_ACTIONS', 'UPDATE'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_SAFETY', 'VIEW'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_SAFETY', 'UPDATE'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_RETENTION', 'VIEW'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_RETENTION', 'UPDATE'),
    ('DWAION_EVALUATOR', 'APP.ASK', 'VIEW'),
    ('DWAION_EVALUATOR', 'ADMIN.DWAION_OPERATIONS', 'VIEW'),
    ('DWAION_EVALUATOR', 'ADMIN.DWAION_EVALUATION', 'VIEW'),
    ('DWAION_EVALUATOR', 'ADMIN.DWAION_EVALUATION', 'CREATE'),
    ('DWAION_EVALUATOR', 'ADMIN.DWAION_EVALUATION', 'UPDATE'),
    ('DWAION_EVALUATOR', 'ADMIN.DWAION_EVALUATION', 'EXECUTE'),
    ('DWAION_EVALUATOR', 'ADMIN.DWAION_EVALUATION', 'MANAGE'),
    ('DWAION_AUDITOR', 'APP.ASK', 'VIEW'),
    ('DWAION_AUDITOR', 'ADMIN.DWAION_OPERATIONS', 'VIEW'),
    ('DWAION_AUDITOR', 'ADMIN.DWAION_RETENTION', 'VIEW'),
    ('DWAION_AUDITOR', 'ADMIN.DWAION_AUDIT', 'VIEW'),
    ('DWAION_AUDITOR', 'ADMIN.DWAION_AUDIT', 'EXPORT')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name,
       catalog.description, 'ACTIVE', 'SYSTEM', catalog.privileged,
       catalog.assignable_to_groups, catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE catalog.role_code IN (
       'DWAION_AGENT_EDITOR', 'DWAION_AGENT_PUBLISHER',
       'DWAION_GOVERNANCE_MANAGER', 'DWAION_EVALUATOR', 'DWAION_AUDITOR')
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
 WHERE template.resource_key LIKE 'ADMIN.DWAION\_%' ESCAPE '\'
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
   AND template.role_code IN (
       'DWAION_ADMIN', 'DWAION_AGENT_EDITOR', 'DWAION_AGENT_PUBLISHER',
       'DWAION_GOVERNANCE_MANAGER', 'DWAION_EVALUATOR', 'DWAION_AUDITOR')
   AND (template.resource_key LIKE 'ADMIN.DWAION\_%' ESCAPE '\'
        OR template.resource_key = 'APP.ASK')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_privileged_access_policies (
    tenant_id, role_id, activation_mode, maximum_duration_minutes,
    assurance_level, approval_quorum, emergency_mode, ticket_required,
    lifecycle_state, created_by, updated_by)
SELECT role.tenant_id, role.role_id, 'APPROVAL', 120, 'MFA', 1,
       'DISABLED', TRUE, 'ACTIVE', 1, 1
  FROM com_roles role
 WHERE role.code IN (
       'DWAION_AGENT_PUBLISHER', 'DWAION_GOVERNANCE_MANAGER',
       'DWAION_EVALUATOR', 'DWAION_AUDITOR')
   AND role.privileged = TRUE
ON CONFLICT (tenant_id, role_id) DO UPDATE SET
    activation_mode = 'APPROVAL',
    maximum_duration_minutes = EXCLUDED.maximum_duration_minutes,
    assurance_level = EXCLUDED.assurance_level,
    approval_quorum = EXCLUDED.approval_quorum,
    emergency_mode = EXCLUDED.emergency_mode,
    ticket_required = EXCLUDED.ticket_required,
    lifecycle_state = 'ACTIVE',
    version = com_privileged_access_policies.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

UPDATE sys_tenant_resource_templates
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
 WHERE resource_key = 'ADMIN.DWAION';

UPDATE sys_tenant_role_permission_templates
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
 WHERE resource_key = 'ADMIN.DWAION';

DELETE FROM com_role_permissions permission_grant
 USING com_resources resource
 WHERE resource.resource_id = permission_grant.resource_id
   AND resource.tenant_id = permission_grant.tenant_id
   AND resource.key = 'ADMIN.DWAION';

UPDATE com_resources
   SET enabled = FALSE, updated_at = CURRENT_TIMESTAMP, updated_by = 1
 WHERE key = 'ADMIN.DWAION';

UPDATE com_users user_record
   SET access_revision = access_revision + 1,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE EXISTS (
       SELECT 1
         FROM com_role_members membership
         JOIN com_roles role
           ON role.tenant_id = membership.tenant_id
          AND role.role_id = membership.role_id
        WHERE membership.tenant_id = user_record.tenant_id
          AND membership.user_id = user_record.user_id
          AND role.code = 'DWAION_ADMIN');
