CREATE TEMP TABLE tmp_privileged_group_roles (
    group_key VARCHAR(100) NOT NULL,
    role_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (group_key, role_code)
) ON COMMIT DROP;

INSERT INTO tmp_privileged_group_roles (group_key, role_code)
VALUES
    ('SKAX_MAIL_ADMINS', 'MAIL_ADMIN'),
    ('SKAX_MESSAGING_ADMINS', 'MESSAGING_ADMIN'),
    ('SKAX_NOTIFICATION_BUILDERS', 'NOTIFICATION_CONTRACT_OWNER'),
    ('SKAX_NOTIFICATION_BUILDERS', 'NOTIFICATION_TEMPLATE_EDITOR'),
    ('SKAX_NOTIFICATION_GOVERNORS', 'NOTIFICATION_POLICY_APPROVER'),
    ('SKAX_NOTIFICATION_GOVERNORS', 'NOTIFICATION_OPERATOR');

-- Privileged product operations use eligible, time-bound activation. A group
-- can establish eligibility, but it must not become a standing runtime grant.
UPDATE sys_builtin_role_catalog
   SET assignable_to_groups = FALSE,
       updated_at = CURRENT_TIMESTAMP
 WHERE role_code IN (SELECT DISTINCT role_code FROM tmp_privileged_group_roles);

UPDATE com_roles
   SET assignable_to_groups = FALSE,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE code IN (SELECT DISTINCT role_code FROM tmp_privileged_group_roles);

UPDATE sys_role_assignment_policies
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE target_role_code IN (SELECT DISTINCT role_code FROM tmp_privileged_group_roles)
   AND assignment_mode <> 'APPROVAL'
   AND lifecycle_state = 'ACTIVE';

INSERT INTO sys_role_assignment_policies (
    grantor_role_code, target_role_code, assignment_mode, lifecycle_state)
SELECT grantor.role_code, target.role_code, 'APPROVAL', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
 CROSS JOIN (
      SELECT DISTINCT role_code FROM tmp_privileged_group_roles
  ) target
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

UPDATE com_group_role_assignments assignment
   SET lifecycle_state = 'REVOKED',
       version = assignment.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_roles role
 WHERE role.tenant_id = assignment.tenant_id
   AND role.role_id = assignment.role_id
   AND role.code IN (SELECT DISTINCT role_code FROM tmp_privileged_group_roles)
   AND assignment.lifecycle_state = 'ACTIVE';

INSERT INTO com_privileged_access_policies (
    tenant_id, role_id, activation_mode, maximum_duration_minutes,
    assurance_level, approval_quorum, emergency_mode, ticket_required,
    lifecycle_state, created_by, updated_by)
SELECT role.tenant_id, role.role_id, 'APPROVAL', 120,
       'MFA', 1, 'DISABLED', TRUE, 'ACTIVE', 1, 1
  FROM com_roles role
 WHERE role.code IN (SELECT DISTINCT role_code FROM tmp_privileged_group_roles)
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

INSERT INTO com_privileged_role_eligibilities (
    tenant_id, principal_type, principal_id, role_id, scope_type,
    valid_from, justification, lifecycle_state, created_by, updated_by)
SELECT access_group.tenant_id, 'GROUP', access_group.group_id,
       role.role_id, 'TENANT', CURRENT_TIMESTAMP,
       '제품 운영 담당자는 MFA와 독립 승인을 거쳐 시간제 특권을 활성화합니다.',
       'ACTIVE', 1, 1
  FROM tmp_privileged_group_roles seed
  JOIN com_groups access_group
    ON access_group.group_key = seed.group_key
   AND access_group.status = 'ACTIVE'
  JOIN com_roles role
    ON role.tenant_id = access_group.tenant_id
   AND role.code = seed.role_code
 WHERE NOT EXISTS (
       SELECT 1
         FROM com_privileged_role_eligibilities eligibility
        WHERE eligibility.tenant_id = access_group.tenant_id
          AND eligibility.principal_type = 'GROUP'
          AND eligibility.principal_id = access_group.group_id
          AND eligibility.role_id = role.role_id
          AND eligibility.scope_type = 'TENANT'
          AND eligibility.lifecycle_state = 'ACTIVE');

UPDATE com_groups access_group
   SET description = CASE access_group.group_key
           WHEN 'SKAX_MAIL_ADMINS'
               THEN '메일 운영 특권을 승인 후 활성화할 수 있는 담당자 그룹'
           WHEN 'SKAX_MESSAGING_ADMINS'
               THEN '메신저 운영 특권을 승인 후 활성화할 수 있는 담당자 그룹'
           WHEN 'SKAX_NOTIFICATION_BUILDERS'
               THEN '알림 계약 및 템플릿 특권을 승인 후 활성화할 수 있는 담당자 그룹'
           ELSE '알림 정책 및 전달 운영 특권을 승인 후 활성화할 수 있는 담당자 그룹'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE access_group.group_key IN (
     SELECT DISTINCT group_key FROM tmp_privileged_group_roles);

-- The combined Mail/Calendar application has been retired. Its historical
-- records remain auditable, while live access and accountability are revoked.
UPDATE com_principal_resource_grants grant_record
   SET lifecycle_state = 'REVOKED',
       revoked_at = CURRENT_TIMESTAMP,
       revoked_by = grant_record.granted_by,
       revocation_reason = 'Replaced by the governed Mail and Calendar product boundaries.',
       version = grant_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_resources resource
 WHERE resource.tenant_id = grant_record.tenant_id
   AND resource.resource_id = grant_record.resource_id
   AND resource.key = 'APP.MAIL_CALENDAR'
   AND grant_record.lifecycle_state = 'ACTIVE';

UPDATE com_admin_role_assignments assignment
   SET lifecycle_state = 'REVOKED',
       revoked_by = COALESCE(assignment.approved_by, assignment.created_by),
       revoked_at = CURRENT_TIMESTAMP,
       revocation_reason = 'The APP_MAIL_CALENDAR responsibility boundary was retired.',
       version = assignment.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_admin_resource_sets resource_set
 WHERE resource_set.tenant_id = assignment.tenant_id
   AND resource_set.resource_set_id = assignment.resource_set_id
   AND resource_set.resource_set_key = 'APP_MAIL_CALENDAR'
   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE');

-- Every active SKAX application has explicit product ownership and
-- configuration accountability. These duties do not grant runtime app access.
CREATE TEMP TABLE tmp_application_responsibilities (
    group_key VARCHAR(100) NOT NULL,
    responsibility_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (group_key, responsibility_code)
) ON COMMIT DROP;

INSERT INTO tmp_application_responsibilities (group_key, responsibility_code)
VALUES
    ('SKAX_APP_OWNERS', 'APP_OWNER'),
    ('SKAX_APP_CONFIGURATION_ADMINS', 'APP_CONFIG_ADMIN');

INSERT INTO com_admin_role_assignments (
    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
    responsibility_code, resource_set_id, assignment_source,
    lifecycle_state, valid_from, review_due_at, justification,
    approved_by, approved_at, decision_reason, created_by, updated_by)
SELECT gen_random_uuid(), tenant.tenant_id, 'GROUP', access_group.group_id::text,
       seed.responsibility_code, resource_set.resource_set_id, 'GROUP',
       'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '180 days',
       'SKAX 제품 앱의 소유권과 구성 책임을 공통 거버넌스 그룹에 배정합니다.',
       tenant_admin.user_id, CURRENT_TIMESTAMP,
       'Approved to close the product accountability boundary.',
       tenant_admin.user_id, tenant_admin.user_id
  FROM tmp_application_responsibilities seed
  JOIN com_tenants tenant
    ON tenant.code = 'default' AND tenant.name = 'SKAX'
  JOIN com_groups access_group
    ON access_group.tenant_id = tenant.tenant_id
   AND access_group.group_key = seed.group_key
   AND access_group.status = 'ACTIVE'
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = tenant.tenant_id
   AND resource_set.resource_set_key IN ('APP_MESSAGING', 'APP_WORKPLACE', 'APP_ROOMS')
   AND resource_set.lifecycle_state = 'ACTIVE'
  JOIN com_users tenant_admin
    ON tenant_admin.tenant_id = tenant.tenant_id
   AND tenant_admin.email_normalized = 'hyunwoo.park@sk.com'
 WHERE NOT EXISTS (
       SELECT 1
         FROM com_admin_role_assignments existing
        WHERE existing.tenant_id = tenant.tenant_id
          AND existing.principal_type = 'GROUP'
          AND existing.principal_ref = access_group.group_id::text
          AND existing.responsibility_code = seed.responsibility_code
          AND existing.resource_set_id = resource_set.resource_set_id
          AND existing.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE'));

-- Permission and role changes invalidate effective-access snapshots for every
-- user represented by the affected groups or retired grants.
UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE EXISTS (
       SELECT 1
         FROM com_group_members membership
         JOIN com_groups access_group
           ON access_group.tenant_id = membership.tenant_id
          AND access_group.group_id = membership.group_id
        WHERE membership.tenant_id = user_record.tenant_id
          AND membership.user_id = user_record.user_id
          AND access_group.group_key IN (
              SELECT DISTINCT group_key FROM tmp_privileged_group_roles))
    OR EXISTS (
       SELECT 1
         FROM com_principal_resource_grants grant_record
         JOIN com_resources resource
           ON resource.tenant_id = grant_record.tenant_id
          AND resource.resource_id = grant_record.resource_id
        WHERE grant_record.tenant_id = user_record.tenant_id
          AND resource.key = 'APP.MAIL_CALENDAR'
          AND ((grant_record.principal_type = 'USER'
                AND grant_record.principal_ref = user_record.user_id::text)
            OR (grant_record.principal_type = 'GROUP'
                AND EXISTS (
                    SELECT 1
                      FROM com_group_members membership
                     WHERE membership.tenant_id = user_record.tenant_id
                       AND membership.group_id::text = grant_record.principal_ref
                       AND membership.user_id = user_record.user_id))));
