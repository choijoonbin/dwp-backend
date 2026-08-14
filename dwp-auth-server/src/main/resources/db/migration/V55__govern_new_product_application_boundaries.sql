-- Product applications introduced after the initial SKAX responsibility seed
-- must enter the same ownership and configuration-governance boundary.

INSERT INTO com_admin_resource_sets (
    resource_set_id, tenant_id, resource_set_key, name, description,
    resource_type, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-set:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id, 'APP_APPROVALS', resource.name,
       'Administrative boundary for ' || resource.name,
       'APP', 'ACTIVE', 1, 1
  FROM com_resources resource
 WHERE resource.type = 'APP' AND resource.key = 'APP.APPROVALS'
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
  FROM com_resources resource
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = resource.tenant_id
   AND resource_set.resource_set_key = 'APP_APPROVALS'
 WHERE resource.type = 'APP' AND resource.key = 'APP.APPROVALS'
ON CONFLICT (resource_set_id, resource_type, resource_key) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

-- APP.HCM is the canonical permission name for the existing HR product. Keep
-- it in the same resource set as APP.HRIS so ownership is not duplicated.
INSERT INTO com_admin_resource_set_members (
    resource_set_member_id, tenant_id, resource_set_id,
    resource_type, resource_key, lifecycle_state, created_by, updated_by)
SELECT md5('app-resource-member:' || resource.tenant_id || ':' || resource.key)::uuid,
       resource.tenant_id, resource_set.resource_set_id,
       'APP', resource.key, 'ACTIVE', 1, 1
  FROM com_resources resource
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = resource.tenant_id
   AND resource_set.resource_set_key = 'APP_HRIS'
 WHERE resource.type = 'APP' AND resource.key = 'APP.HCM'
ON CONFLICT (resource_set_id, resource_type, resource_key) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP, updated_by = 1;

UPDATE com_admin_resource_sets resource_set
   SET name = 'DWP HCM',
       description = 'Administrative boundary for DWP HCM and its HRIS compatibility alias',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE resource_set.resource_set_key = 'APP_HRIS'
   AND EXISTS (
       SELECT 1
         FROM com_admin_resource_set_members member
        WHERE member.tenant_id = resource_set.tenant_id
          AND member.resource_set_id = resource_set.resource_set_id
          AND member.resource_key = 'APP.HCM'
          AND member.lifecycle_state = 'ACTIVE');

CREATE TEMP TABLE tmp_skax_product_responsibilities (
    group_key VARCHAR(100) NOT NULL,
    responsibility_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (group_key, responsibility_code)
) ON COMMIT DROP;

INSERT INTO tmp_skax_product_responsibilities VALUES
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
       'SKAX 애플리케이션 책임을 업무 그룹 단위로 운영합니다.',
       tenant_admin.user_id, CURRENT_TIMESTAMP,
       'Approved for a product application introduced after the initial governance baseline.',
       tenant_admin.user_id, tenant_admin.user_id
  FROM tmp_skax_product_responsibilities seed
  JOIN com_tenants tenant
    ON tenant.code = 'default' AND tenant.name = 'SKAX'
  JOIN com_groups access_group
    ON access_group.tenant_id = tenant.tenant_id
   AND access_group.group_key = seed.group_key
   AND access_group.status = 'ACTIVE'
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = tenant.tenant_id
   AND resource_set.resource_type = 'APP'
   AND resource_set.lifecycle_state = 'ACTIVE'
  JOIN com_users tenant_admin
    ON tenant_admin.tenant_id = tenant.tenant_id
   AND tenant_admin.email_normalized = 'hyunwoo.park@sk.com'
 WHERE EXISTS (
       SELECT 1
         FROM com_admin_resource_set_members member
        WHERE member.tenant_id = resource_set.tenant_id
          AND member.resource_set_id = resource_set.resource_set_id
          AND member.resource_type = 'APP'
          AND member.resource_key <> 'APP.ADMINISTRATION'
          AND member.lifecycle_state = 'ACTIVE')
   AND NOT EXISTS (
       SELECT 1
         FROM com_admin_role_assignments existing
        WHERE existing.tenant_id = tenant.tenant_id
          AND existing.principal_type = 'GROUP'
          AND existing.principal_ref = access_group.group_id::text
          AND existing.responsibility_code = seed.responsibility_code
          AND existing.resource_set_id = resource_set.resource_set_id
          AND existing.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE'));

-- Other tenants retain a named accountable owner until their IAM groups are
-- configured. This is the same bootstrap contract used by tenant provisioning.
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
       'Created by product-boundary migration to avoid an ownerless application.',
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
 WHERE resource_set.resource_type = 'APP'
   AND resource_set.lifecycle_state = 'ACTIVE'
   AND NOT (tenant.code = 'default' AND tenant.name = 'SKAX')
   AND NOT EXISTS (
       SELECT 1
         FROM com_admin_role_assignments existing
        WHERE existing.tenant_id = resource_set.tenant_id
          AND existing.resource_set_id = resource_set.resource_set_id
          AND existing.responsibility_code = 'APP_OWNER'
          AND existing.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE'));
