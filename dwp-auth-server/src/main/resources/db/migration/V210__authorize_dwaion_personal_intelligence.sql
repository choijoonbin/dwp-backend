-- DWAI-ON personal intelligence capabilities remain subordinate to APP.ASK
-- and are scoped to the authenticated user by the Agent owner service. This
-- migration only materializes the minimum application permissions required
-- for those self-service APIs. Tenant retention administration remains on the
-- existing privileged ADMIN.DWAION_RETENTION resource.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('APP.DWAION_ROUTINES', 'APP', 'DWAI-ON personal routines', 'ai.agent-runtime'),
    ('APP.DWAION_MEMORY', 'APP', 'DWAI-ON explicit personal memory', 'ai.agent-runtime'),
    ('APP.DWAION_PRIVACY', 'APP', 'DWAI-ON personal data controls', 'ai.agent-runtime'),
    ('APP.DWAION_ARTIFACTS', 'APP', 'DWAI-ON governed artifacts', 'ai.agent-runtime')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('WORKSPACE_MEMBER', 'APP.DWAION_ROUTINES', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_ROUTINES', 'MANAGE'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_MEMORY', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_MEMORY', 'MANAGE'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_PRIVACY', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_PRIVACY', 'MANAGE'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'VIEW'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'CREATE'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'UPDATE'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'PUBLISH'),
    ('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'EXPORT')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id,
       template.resource_type,
       template.resource_key,
       template.display_name,
       TRUE,
       1,
       1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key IN (
       'APP.DWAION_ROUTINES',
       'APP.DWAION_MEMORY',
       'APP.DWAION_PRIVACY',
       'APP.DWAION_ARTIFACTS')
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id,
    effect, created_by, updated_by)
SELECT role.tenant_id,
       role.role_id,
       resource.resource_id,
       permission.permission_id,
       'ALLOW',
       1,
       1
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
   AND template.role_code = 'WORKSPACE_MEMBER'
   AND template.resource_key IN (
       'APP.DWAION_ROUTINES',
       'APP.DWAION_MEMORY',
       'APP.DWAION_PRIVACY',
       'APP.DWAION_ARTIFACTS')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

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
          AND role.code = 'WORKSPACE_MEMBER'
          AND role.status = 'ACTIVE');
