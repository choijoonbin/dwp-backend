-- Approval assurance is performed by the existing independent AUDITOR role.
-- The role receives read-only operational evidence and remains incompatible
-- with design, publication, and queue-operation duties.
INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code,
    lifecycle_state, enforcement, risk_level)
VALUES
    (LEAST('AUDITOR', 'APPROVAL_DESIGNER'), GREATEST('AUDITOR', 'APPROVAL_DESIGNER'),
     'APPROVAL_AUDIT_INDEPENDENCE', 'ACTIVE', 'DENY', 'HIGH'),
    (LEAST('AUDITOR', 'APPROVAL_PUBLISHER'), GREATEST('AUDITOR', 'APPROVAL_PUBLISHER'),
     'APPROVAL_AUDIT_INDEPENDENCE', 'ACTIVE', 'DENY', 'CRITICAL'),
    (LEAST('AUDITOR', 'APPROVAL_OPERATOR'), GREATEST('AUDITOR', 'APPROVAL_OPERATOR'),
     'APPROVAL_AUDIT_INDEPENDENCE', 'ACTIVE', 'DENY', 'HIGH'),
    (LEAST('APPROVAL_OPERATOR', 'APPROVAL_PUBLISHER'),
     GREATEST('APPROVAL_OPERATOR', 'APPROVAL_PUBLISHER'),
     'APPROVAL_POLICY_OPERATION_SEPARATION', 'ACTIVE', 'DENY', 'HIGH')
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    enforcement = EXCLUDED.enforcement,
    risk_level = EXCLUDED.risk_level,
    version = sys_role_conflict_policies.version + 1,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
VALUES
    ('AUDITOR', 'APP.APPROVALS', 'VIEW'),
    ('AUDITOR', 'ADMIN.APPROVAL_OPERATIONS', 'VIEW')
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id,
    effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id,
       permission.permission_id, 'ALLOW', 1, 1
  FROM (VALUES
      ('AUDITOR', 'APP.APPROVALS', 'VIEW'),
      ('AUDITOR', 'ADMIN.APPROVAL_OPERATIONS', 'VIEW'))
       matrix(role_code, resource_key, permission_code)
  JOIN com_roles role
    ON role.code = matrix.role_code AND role.status = 'ACTIVE'
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = matrix.resource_key
   AND resource.enabled = TRUE
  JOIN com_permissions permission
    ON permission.code = matrix.permission_code
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

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
          AND role.code = 'AUDITOR');
