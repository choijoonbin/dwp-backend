-- Customer delivery gates reuse the existing governance-manager/auditor
-- separation. Gate configuration and approval remain distinct permissions.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES (
    'ADMIN.DWAION_GATES', 'ADMIN', 'DWAI-ON operational delivery gates',
    'ai.agent-runtime')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('DWAION_ADMIN', 'ADMIN.DWAION_GATES', 'VIEW'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_GATES', 'CREATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_GATES', 'UPDATE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_GATES', 'APPROVE'),
    ('DWAION_ADMIN', 'ADMIN.DWAION_GATES', 'MANAGE'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_GATES', 'VIEW'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_GATES', 'CREATE'),
    ('DWAION_GOVERNANCE_MANAGER', 'ADMIN.DWAION_GATES', 'UPDATE'),
    ('DWAION_EVALUATOR', 'ADMIN.DWAION_GATES', 'VIEW'),
    ('DWAION_AUDITOR', 'ADMIN.DWAION_GATES', 'VIEW'),
    ('DWAION_AUDITOR', 'ADMIN.DWAION_GATES', 'APPROVE')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
  JOIN sys_tenant_resource_templates template
    ON template.resource_key = 'ADMIN.DWAION_GATES'
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
   AND template.resource_key = 'ADMIN.DWAION_GATES'
   AND template.role_code IN (
       'DWAION_ADMIN', 'DWAION_GOVERNANCE_MANAGER',
       'DWAION_EVALUATOR', 'DWAION_AUDITOR')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

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
          AND role.code IN (
              'DWAION_ADMIN', 'DWAION_GOVERNANCE_MANAGER',
              'DWAION_EVALUATOR', 'DWAION_AUDITOR'));
