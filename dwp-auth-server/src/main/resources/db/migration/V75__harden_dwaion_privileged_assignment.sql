-- DWAI-ON operations remain privileged. Groups may establish eligibility for
-- approval, but must never become a standing privileged runtime grant.
UPDATE sys_builtin_role_catalog
   SET assignable_to_groups = FALSE,
       updated_at = CURRENT_TIMESTAMP
 WHERE role_code = 'DWAION_ADMIN';

UPDATE com_roles
   SET assignable_to_groups = FALSE,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE code = 'DWAION_ADMIN';

UPDATE com_group_role_assignments assignment
   SET lifecycle_state = 'REVOKED',
       version = assignment.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_roles role
 WHERE role.tenant_id = assignment.tenant_id
   AND role.role_id = assignment.role_id
   AND role.code = 'DWAION_ADMIN'
   AND assignment.lifecycle_state = 'ACTIVE';

UPDATE com_groups
   SET description = 'DWAI·ON 승인형 특권 접근을 요청할 수 있는 운영 담당자 그룹',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE group_key = 'SKAX_DWAION_ADMINS';

INSERT INTO com_privileged_access_policies (
    tenant_id, role_id, activation_mode, maximum_duration_minutes,
    assurance_level, approval_quorum, emergency_mode, ticket_required,
    lifecycle_state, created_by, updated_by)
SELECT role.tenant_id, role.role_id, 'APPROVAL', 120, 'MFA', 1,
       'DISABLED', TRUE, 'ACTIVE', 1, 1
  FROM com_roles role
 WHERE role.code = 'DWAION_ADMIN' AND role.privileged = TRUE
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

INSERT INTO com_privileged_role_eligibilities (
    tenant_id, principal_type, principal_id, role_id, scope_type,
    valid_from, justification, lifecycle_state, created_by, updated_by)
SELECT access_group.tenant_id, 'GROUP', access_group.group_id, role.role_id,
       'TENANT', CURRENT_TIMESTAMP,
       'DWAI·ON 운영 담당자는 MFA와 승인을 거쳐 시간제 권한을 활성화합니다.',
       'ACTIVE', 1, 1
  FROM com_groups access_group
  JOIN com_roles role
    ON role.tenant_id = access_group.tenant_id
   AND role.code = 'DWAION_ADMIN'
 WHERE access_group.group_key = 'SKAX_DWAION_ADMINS'
   AND access_group.status = 'ACTIVE'
   AND NOT EXISTS (
       SELECT 1
         FROM com_privileged_role_eligibilities eligibility
        WHERE eligibility.tenant_id = access_group.tenant_id
          AND eligibility.principal_type = 'GROUP'
          AND eligibility.principal_id = access_group.group_id
          AND eligibility.role_id = role.role_id
          AND eligibility.scope_type = 'TENANT'
          AND eligibility.lifecycle_state = 'ACTIVE');

-- The local integrated verification identity is intentionally long-lived so
-- automated browser checks can exercise the operations surface. Customer
-- operators use the approval and active-grant lifecycle above.
INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT user_record.tenant_id, role.role_id, user_record.user_id, 1, 1
  FROM com_users user_record
  JOIN com_roles role
    ON role.tenant_id = user_record.tenant_id
   AND role.code = 'DWAION_ADMIN'
 WHERE user_record.email_normalized = 'joonbin@sk.com'
   AND user_record.source_type = 'LOCAL'
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

UPDATE com_users user_record
   SET access_revision = access_revision + 1,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE user_record.email_normalized = 'joonbin@sk.com'
   AND user_record.source_type = 'LOCAL';
