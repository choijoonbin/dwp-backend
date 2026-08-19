-- Space operating roles use the tenant control center shell, but remain limited
-- to their explicitly granted Space resources by the authorization matrix.
INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
SELECT role_code, 'APP.ADMINISTRATION', 'VIEW'
  FROM (VALUES
    ('SPACE_GOVERNANCE_ADMIN'),
    ('SPACE_TEMPLATE_ADMIN'),
    ('SPACE_COMPLIANCE_REVIEWER'),
    ('SPACE_ACCESS_REVIEWER')) roles(role_code)
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id,
    effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id,
       permission.permission_id, 'ALLOW', 1, 1
  FROM com_roles role
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = 'APP.ADMINISTRATION'
   AND resource.enabled = TRUE
  JOIN com_permissions permission
    ON permission.code = 'VIEW'
 WHERE role.code IN (
    'SPACE_GOVERNANCE_ADMIN', 'SPACE_TEMPLATE_ADMIN',
    'SPACE_COMPLIANCE_REVIEWER', 'SPACE_ACCESS_REVIEWER')
   AND role.status = 'ACTIVE'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
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
             'SPACE_GOVERNANCE_ADMIN', 'SPACE_TEMPLATE_ADMIN',
             'SPACE_COMPLIANCE_REVIEWER', 'SPACE_ACCESS_REVIEWER'));
