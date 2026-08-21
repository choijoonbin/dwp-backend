CREATE TEMP TABLE tmp_product_application_boundaries (
    resource_key VARCHAR(100) PRIMARY KEY,
    resource_set_key VARCHAR(100) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_product_application_boundaries (resource_key, resource_set_key)
VALUES
    ('APP.SPACES', 'APP_SPACES'),
    ('APP.NOTIFICATIONS', 'APP_NOTIFICATIONS');

INSERT INTO com_admin_resource_sets (
    resource_set_id, tenant_id, resource_set_key, name, description,
    resource_type, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-set:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id, boundary.resource_set_key, resource.name,
       'Administrative accountability boundary for ' || resource.name,
       'APP', 'ACTIVE', 1, 1
  FROM tmp_product_application_boundaries boundary
  JOIN com_resources resource
    ON resource.key = boundary.resource_key
   AND resource.type = 'APP'
   AND resource.enabled = TRUE
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
  FROM tmp_product_application_boundaries boundary
  JOIN com_resources resource
    ON resource.key = boundary.resource_key
   AND resource.type = 'APP'
   AND resource.enabled = TRUE
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = resource.tenant_id
   AND resource_set.resource_set_key = boundary.resource_set_key
ON CONFLICT (resource_set_id, resource_type, resource_key) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

CREATE TEMP TABLE tmp_product_responsibilities (
    group_key VARCHAR(100) NOT NULL,
    responsibility_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (group_key, responsibility_code)
) ON COMMIT DROP;

INSERT INTO tmp_product_responsibilities (group_key, responsibility_code)
VALUES
    ('SKAX_APP_OWNERS', 'APP_OWNER'),
    ('SKAX_APP_CONFIGURATION_ADMINS', 'APP_CONFIG_ADMIN');

INSERT INTO com_admin_role_assignments (
    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
    responsibility_code, resource_set_id, assignment_source,
    lifecycle_state, valid_from, review_due_at, justification,
    approved_by, approved_at, decision_reason, created_by, updated_by)
SELECT gen_random_uuid(), tenant.tenant_id, 'GROUP', access_group.group_id::text,
       responsibility.responsibility_code, resource_set.resource_set_id, 'GROUP',
       'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '180 days',
       'SKAX 제품 앱의 소유권과 구성 책임을 공통 거버넌스 그룹에 배정합니다.',
       tenant_admin.user_id, CURRENT_TIMESTAMP,
       'Approved for a newly governed product application boundary.',
       tenant_admin.user_id, tenant_admin.user_id
  FROM tmp_product_responsibilities responsibility
  JOIN com_tenants tenant
    ON tenant.code = 'default' AND tenant.name = 'SKAX'
  JOIN com_groups access_group
    ON access_group.tenant_id = tenant.tenant_id
   AND access_group.group_key = responsibility.group_key
   AND access_group.status = 'ACTIVE'
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = tenant.tenant_id
   AND resource_set.resource_set_key IN (
       SELECT resource_set_key FROM tmp_product_application_boundaries)
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
          AND existing.responsibility_code = responsibility.responsibility_code
          AND existing.resource_set_id = resource_set.resource_set_id
          AND existing.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE'));

-- Other tenants receive a temporary named owner until their IAM responsibility
-- groups are connected during delivery.
INSERT INTO com_admin_role_assignments (
    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
    responsibility_code, resource_set_id, assignment_source,
    lifecycle_state, valid_from, review_due_at, justification,
    approved_by, approved_at, decision_reason, created_by, updated_by)
SELECT gen_random_uuid(), resource_set.tenant_id, 'USER', administrator.user_id::text,
       'APP_OWNER', resource_set.resource_set_id, 'PROVISIONING', 'ACTIVE',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '90 days',
       'Bootstrap owner pending customer IAM responsibility assignment.',
       administrator.user_id, CURRENT_TIMESTAMP,
       'Created to prevent an ownerless product application boundary.',
       administrator.user_id, administrator.user_id
  FROM com_admin_resource_sets resource_set
  JOIN com_tenants tenant ON tenant.tenant_id = resource_set.tenant_id
  JOIN LATERAL (
      SELECT user_record.user_id
        FROM com_role_members membership
        JOIN com_roles role
          ON role.tenant_id = membership.tenant_id
         AND role.role_id = membership.role_id
         AND role.code = 'TENANT_ADMIN'
        JOIN com_users user_record
          ON user_record.tenant_id = membership.tenant_id
         AND user_record.user_id = membership.user_id
       WHERE membership.tenant_id = resource_set.tenant_id
         AND user_record.status IN ('ACTIVE', 'INVITED')
       ORDER BY user_record.user_id
       LIMIT 1
  ) administrator ON TRUE
 WHERE resource_set.resource_set_key IN (
       SELECT resource_set_key FROM tmp_product_application_boundaries)
   AND resource_set.lifecycle_state = 'ACTIVE'
   AND NOT (tenant.code = 'default' AND tenant.name = 'SKAX')
   AND NOT EXISTS (
       SELECT 1
         FROM com_admin_role_assignments existing
        WHERE existing.tenant_id = resource_set.tenant_id
          AND existing.resource_set_id = resource_set.resource_set_id
          AND existing.responsibility_code = 'APP_OWNER'
          AND existing.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE'));
