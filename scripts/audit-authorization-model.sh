#!/usr/bin/env bash

set -euo pipefail

POSTGRES_CONTAINER="${DWP_POSTGRES_CONTAINER:-dwp-postgres}"
POSTGRES_USER="${DWP_POSTGRES_USER:-dwp_user}"
AUTH_DB="${DWP_AUTH_DB:-dwp_auth}"
PLATFORM_DB="${DWP_PLATFORM_DB:-dwp_platform}"

query() {
  local database="$1"
  local sql="$2"
  docker exec "$POSTGRES_CONTAINER" \
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$database" -AtF '|' -c "$sql"
}

assert_zero() {
  local label="$1"
  local sql="$2"
  local count
  count="$(query "$AUTH_DB" "$sql")"
  if [[ "$count" != "0" ]]; then
    printf 'FAIL  %s: %s violation(s)\n' "$label" "$count" >&2
    return 1
  fi
  printf 'PASS  %s\n' "$label"
}

assert_minimum() {
  local label="$1"
  local minimum="$2"
  local sql="$3"
  local count
  count="$(query "$AUTH_DB" "$sql")"
  if (( count < minimum )); then
    printf 'FAIL  %s: expected at least %s, found %s\n' \
      "$label" "$minimum" "$count" >&2
    return 1
  fi
  printf 'PASS  %s (%s)\n' "$label" "$count"
}

assert_zero "authorization integrity findings" \
  "SELECT COUNT(*) FROM sys_authorization_integrity_findings"

assert_zero "active applications without a governance boundary" "
  SELECT COUNT(*)
    FROM com_resources resource
   WHERE resource.type = 'APP'
     AND resource.enabled = TRUE
     AND resource.key <> 'APP.ADMINISTRATION'
     AND NOT EXISTS (
       SELECT 1
         FROM com_admin_resource_set_members member
         JOIN com_admin_resource_sets resource_set
           ON resource_set.tenant_id = member.tenant_id
          AND resource_set.resource_set_id = member.resource_set_id
          AND resource_set.lifecycle_state = 'ACTIVE'
        WHERE member.tenant_id = resource.tenant_id
          AND member.resource_type = 'APP'
          AND member.resource_key = resource.key
          AND member.lifecycle_state = 'ACTIVE')
"

assert_zero "tenant administrator operational privilege leakage" "
  SELECT COUNT(*)
    FROM com_role_permissions role_permission
    JOIN com_roles role ON role.role_id = role_permission.role_id
    JOIN com_resources resource ON resource.resource_id = role_permission.resource_id
    JOIN com_permissions permission ON permission.permission_id = role_permission.permission_id
   WHERE role.code = 'TENANT_ADMIN'
     AND (resource.key = 'ADMIN.APP_ACCESS_REQUESTS'
       OR (resource.key = 'ADMIN.COMMUNICATIONS' AND permission.code <> 'VIEW')
       OR (resource.key IN ('ADMIN.SERVICE_CATALOG', 'ADMIN.SERVICE_OPERATIONS')
           AND permission.code <> 'VIEW')
       OR resource.key IN (
           'APP.COLLABORATION', 'APP.KNOWLEDGE',
           'APP.BUSINESS_ERP', 'APP.LEGACY_OPERATIONS'))
"

assert_zero "workspace baseline optional app leakage" "
  SELECT COUNT(*)
    FROM com_role_permissions role_permission
    JOIN com_roles role ON role.role_id = role_permission.role_id
    JOIN com_resources resource ON resource.resource_id = role_permission.resource_id
   WHERE role.code = 'WORKSPACE_MEMBER'
     AND resource.key IN (
       'APP.COLLABORATION', 'APP.KNOWLEDGE',
       'APP.BUSINESS_ERP', 'APP.LEGACY_OPERATIONS')
"

assert_zero "privileged roles assigned through groups" "
  SELECT COUNT(*)
    FROM com_group_role_assignments assignment
    JOIN com_roles role ON role.role_id = assignment.role_id
   WHERE assignment.lifecycle_state = 'ACTIVE'
     AND (role.privileged = TRUE OR role.assignable_to_groups = FALSE)
"

assert_minimum "SKAX authorization groups" 11 "
  SELECT COUNT(*)
    FROM com_groups access_group
    JOIN com_tenants tenant ON tenant.tenant_id = access_group.tenant_id
   WHERE tenant.code = 'default' AND tenant.name = 'SKAX'
     AND access_group.status = 'ACTIVE'
"

assert_zero "SKAX seeded group membership gaps" "
  WITH expected(group_key) AS (
      VALUES
        ('SKAX_ALL_EMPLOYEES'),
        ('SKAX_COMMUNICATIONS_EDITORS'),
        ('SKAX_SERVICE_CATALOG_MANAGERS'),
        ('SKAX_SERVICE_AGENTS'),
        ('SKAX_APP_OWNERS'),
        ('SKAX_APP_CONFIGURATION_ADMINS'),
        ('SKAX_APP_ACCESS_MANAGERS'),
        ('SKAX_APP_ACCESS_APPROVERS'),
        ('SKAX_APP_ACCESS_REVIEWERS'),
        ('SKAX_ERP_USERS'),
        ('SKAX_LEGACY_OPERATIONS_USERS')
  )
  SELECT COUNT(*)
    FROM expected
   WHERE NOT EXISTS (
      SELECT 1
        FROM com_tenants tenant
        JOIN com_groups access_group
          ON access_group.tenant_id = tenant.tenant_id
         AND access_group.group_key = expected.group_key
         AND access_group.status = 'ACTIVE'
        JOIN com_group_members membership
          ON membership.tenant_id = access_group.tenant_id
         AND membership.group_id = access_group.group_id
       WHERE tenant.code = 'default' AND tenant.name = 'SKAX')
"

assert_zero "SKAX all-employees group membership drift" "
  WITH tenant_record AS (
      SELECT tenant_id FROM com_tenants
       WHERE code = 'default' AND name = 'SKAX'
  ), expected AS (
      SELECT user_record.user_id
        FROM com_users user_record
        JOIN tenant_record ON tenant_record.tenant_id = user_record.tenant_id
       WHERE user_record.status IN ('ACTIVE', 'INVITED')
         AND user_record.email_normalized LIKE '%@sk.com'
  ), actual AS (
      SELECT membership.user_id
        FROM com_groups access_group
        JOIN tenant_record ON tenant_record.tenant_id = access_group.tenant_id
        JOIN com_group_members membership
          ON membership.tenant_id = access_group.tenant_id
         AND membership.group_id = access_group.group_id
       WHERE access_group.group_key = 'SKAX_ALL_EMPLOYEES'
  )
  SELECT COUNT(*) FROM (
      (SELECT user_id FROM expected EXCEPT SELECT user_id FROM actual)
      UNION ALL
      (SELECT user_id FROM actual EXCEPT SELECT user_id FROM expected)
  ) drift
"

assert_zero "SKAX access package matrix gaps" "
  WITH expected(group_key, resource_key) AS (
      VALUES
        ('SKAX_ALL_EMPLOYEES', 'APP.COLLABORATION'),
        ('SKAX_ALL_EMPLOYEES', 'APP.KNOWLEDGE'),
        ('SKAX_ERP_USERS', 'APP.BUSINESS_ERP'),
        ('SKAX_LEGACY_OPERATIONS_USERS', 'APP.LEGACY_OPERATIONS')
  )
  SELECT COUNT(*)
    FROM expected
   WHERE NOT EXISTS (
      SELECT 1
        FROM com_tenants tenant
        JOIN com_groups access_group
          ON access_group.tenant_id = tenant.tenant_id
         AND access_group.group_key = expected.group_key
        JOIN com_principal_resource_grants grant_record
          ON grant_record.tenant_id = access_group.tenant_id
         AND grant_record.principal_type = 'GROUP'
         AND grant_record.principal_ref = access_group.group_id::TEXT
         AND grant_record.source_type = 'ACCESS_PACKAGE'
         AND grant_record.lifecycle_state = 'ACTIVE'
        JOIN com_resources resource
          ON resource.resource_id = grant_record.resource_id
         AND resource.key = expected.resource_key
       WHERE tenant.code = 'default' AND tenant.name = 'SKAX')
"

assert_zero "SKAX functional group role gaps" "
  WITH expected(group_key, role_code) AS (
      VALUES
        ('SKAX_COMMUNICATIONS_EDITORS', 'COMMUNICATIONS_EDITOR'),
        ('SKAX_SERVICE_CATALOG_MANAGERS', 'SERVICE_CATALOG_MANAGER'),
        ('SKAX_SERVICE_AGENTS', 'SERVICE_AGENT')
  )
  SELECT COUNT(*)
    FROM expected
   WHERE NOT EXISTS (
      SELECT 1
        FROM com_tenants tenant
        JOIN com_groups access_group
          ON access_group.tenant_id = tenant.tenant_id
         AND access_group.group_key = expected.group_key
        JOIN com_group_role_assignments assignment
          ON assignment.tenant_id = access_group.tenant_id
         AND assignment.group_id = access_group.group_id
         AND assignment.lifecycle_state = 'ACTIVE'
        JOIN com_roles role
          ON role.tenant_id = assignment.tenant_id
         AND role.role_id = assignment.role_id
         AND role.code = expected.role_code
       WHERE tenant.code = 'default' AND tenant.name = 'SKAX')
"

assert_zero "SKAX active apps missing owner or configuration administrator" "
  WITH required(responsibility_code) AS (
      VALUES ('APP_OWNER'), ('APP_CONFIG_ADMIN')
  )
  SELECT COUNT(*)
    FROM com_tenants tenant
    JOIN com_admin_resource_sets resource_set
      ON resource_set.tenant_id = tenant.tenant_id
     AND resource_set.lifecycle_state = 'ACTIVE'
    CROSS JOIN required
   WHERE tenant.code = 'default' AND tenant.name = 'SKAX'
     AND NOT EXISTS (
       SELECT 1
         FROM com_admin_role_assignments assignment
        WHERE assignment.tenant_id = resource_set.tenant_id
          AND assignment.resource_set_id = resource_set.resource_set_id
          AND assignment.responsibility_code = required.responsibility_code
          AND assignment.lifecycle_state = 'ACTIVE')
"

assert_zero "SKAX app accountability group assignment gaps" "
  WITH expected(group_key, responsibility_code) AS (
      VALUES
        ('SKAX_APP_OWNERS', 'APP_OWNER'),
        ('SKAX_APP_CONFIGURATION_ADMINS', 'APP_CONFIG_ADMIN')
  )
  SELECT COUNT(*)
    FROM com_tenants tenant
    JOIN com_admin_resource_sets resource_set
      ON resource_set.tenant_id = tenant.tenant_id
     AND resource_set.lifecycle_state = 'ACTIVE'
    CROSS JOIN expected
   WHERE tenant.code = 'default' AND tenant.name = 'SKAX'
     AND NOT EXISTS (
       SELECT 1
         FROM com_groups access_group
         JOIN com_admin_role_assignments assignment
           ON assignment.tenant_id = access_group.tenant_id
          AND assignment.principal_type = 'GROUP'
          AND assignment.principal_ref = access_group.group_id::TEXT
          AND assignment.responsibility_code = expected.responsibility_code
          AND assignment.resource_set_id = resource_set.resource_set_id
          AND assignment.lifecycle_state = 'ACTIVE'
        WHERE access_group.tenant_id = tenant.tenant_id
          AND access_group.group_key = expected.group_key
          AND access_group.status = 'ACTIVE')
"

assert_zero "SKAX external apps missing an access duty" "
  WITH required(resource_key, responsibility_code) AS (
      SELECT resource_key, responsibility_code
        FROM (VALUES
          ('APP.COLLABORATION'), ('APP.KNOWLEDGE'),
          ('APP.BUSINESS_ERP'), ('APP.LEGACY_OPERATIONS')
        ) app(resource_key)
        CROSS JOIN (VALUES
          ('APP_ACCESS_MANAGER'), ('APP_ACCESS_APPROVER'), ('APP_ACCESS_REVIEWER')
        ) duty(responsibility_code)
  )
  SELECT COUNT(*)
    FROM required
   WHERE NOT EXISTS (
      SELECT 1
        FROM com_tenants tenant
        JOIN com_admin_resource_set_members member
          ON member.tenant_id = tenant.tenant_id
         AND member.resource_key = required.resource_key
         AND member.lifecycle_state = 'ACTIVE'
        JOIN com_admin_role_assignments assignment
          ON assignment.tenant_id = member.tenant_id
         AND assignment.resource_set_id = member.resource_set_id
         AND assignment.responsibility_code = required.responsibility_code
         AND assignment.lifecycle_state = 'ACTIVE'
       WHERE tenant.code = 'default' AND tenant.name = 'SKAX')
"

assert_zero "SKAX external app duty group assignment gaps" "
  WITH expected(resource_key, group_key, responsibility_code) AS (
      SELECT resource_key, group_key, responsibility_code
        FROM (VALUES
          ('APP.COLLABORATION'), ('APP.KNOWLEDGE'),
          ('APP.BUSINESS_ERP'), ('APP.LEGACY_OPERATIONS')
        ) app(resource_key)
        CROSS JOIN (VALUES
          ('SKAX_APP_ACCESS_MANAGERS', 'APP_ACCESS_MANAGER'),
          ('SKAX_APP_ACCESS_APPROVERS', 'APP_ACCESS_APPROVER'),
          ('SKAX_APP_ACCESS_REVIEWERS', 'APP_ACCESS_REVIEWER')
        ) duty(group_key, responsibility_code)
  )
  SELECT COUNT(*)
    FROM expected
   WHERE NOT EXISTS (
      SELECT 1
        FROM com_tenants tenant
        JOIN com_groups access_group
          ON access_group.tenant_id = tenant.tenant_id
         AND access_group.group_key = expected.group_key
         AND access_group.status = 'ACTIVE'
        JOIN com_admin_resource_set_members member
          ON member.tenant_id = tenant.tenant_id
         AND member.resource_key = expected.resource_key
         AND member.lifecycle_state = 'ACTIVE'
        JOIN com_admin_role_assignments assignment
          ON assignment.tenant_id = tenant.tenant_id
         AND assignment.principal_type = 'GROUP'
         AND assignment.principal_ref = access_group.group_id::TEXT
         AND assignment.responsibility_code = expected.responsibility_code
         AND assignment.resource_set_id = member.resource_set_id
         AND assignment.lifecycle_state = 'ACTIVE'
       WHERE tenant.code = 'default' AND tenant.name = 'SKAX')
"

while IFS='|' read -r tenant_id resource_key; do
  [[ -z "$tenant_id" || -z "$resource_key" ]] && continue
  count="$(query "$AUTH_DB" "
    SELECT COUNT(*) FROM com_resources
     WHERE tenant_id = ${tenant_id} AND type = 'APP'
       AND key = '${resource_key}' AND enabled = TRUE
  ")"
  if [[ "$count" != "1" ]]; then
    printf 'FAIL  platform app has no enabled Auth resource: tenant=%s resource=%s\n' \
      "$tenant_id" "$resource_key" >&2
    exit 1
  fi
done < <(query "$PLATFORM_DB" "
  SELECT DISTINCT tenant_id, resource_key
    FROM adm_workspace_apps
   WHERE lifecycle_state = 'ACTIVE'
   ORDER BY tenant_id, resource_key
")
printf 'PASS  platform application resources match Auth contracts\n'

printf 'Authorization model audit completed successfully.\n'
