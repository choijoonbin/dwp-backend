#!/usr/bin/env bash

set -euo pipefail

POSTGRES_CONTAINER="${DWP_POSTGRES_CONTAINER:-dwp-postgres}"
POSTGRES_USER="${DWP_POSTGRES_USER:-dwp_user}"
PLATFORM_DB="${DWP_PLATFORM_DB:-dwp_platform}"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

psql_query() {
  local database="$1"
  local query="$2"
  docker exec "$POSTGRES_CONTAINER" \
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$database" -AtF $'\t' -c "$query"
}

assert_zero() {
  local database="$1"
  local label="$2"
  local query="$3"
  local count
  count="$(psql_query "$database" "$query")"
  if [[ "$count" != "0" ]]; then
    printf 'FAIL  %s: %s violation(s)\n' "$label" "$count" >&2
    return 1
  fi
  printf 'PASS  %s\n' "$label"
}

if ! docker inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
  printf 'PostgreSQL container not found: %s\n' "$POSTGRES_CONTAINER" >&2
  exit 1
fi

actual_file="$work_dir/actual-check-contracts.tsv"
expected_file="$work_dir/registered-check-contracts.tsv"

while IFS='|' read -r database owner_service; do
  psql_query "$database" "
    WITH checks AS (
      SELECT constraint_ref.conrelid::regclass::text AS table_name,
             pg_get_constraintdef(constraint_ref.oid) AS definition
        FROM pg_constraint constraint_ref
        JOIN pg_namespace namespace_ref
          ON namespace_ref.oid = constraint_ref.connamespace
        JOIN pg_class relation_ref
          ON relation_ref.oid = constraint_ref.conrelid
       WHERE namespace_ref.nspname = 'public'
         AND constraint_ref.contype = 'c'
         AND NOT relation_ref.relispartition
         AND pg_get_constraintdef(constraint_ref.oid) ~ '\\)::text = ANY'
    ), extracted AS (
      SELECT table_name,
             substring(definition FROM '\\(\\(([a-zA-Z0-9_]+)\\)::text = ANY') AS column_name,
             match[1] AS code
        FROM checks
       CROSS JOIN LATERAL regexp_matches(
           definition, '''([^'']+)''::character varying', 'g') match
    )
    SELECT '${owner_service}', table_name || '.' || column_name,
           string_agg(DISTINCT code, ',' ORDER BY code)
      FROM extracted
     GROUP BY table_name, column_name
     ORDER BY table_name, column_name
  " >>"$actual_file"
done <<'DATABASES'
dwp_auth|dwp-auth-server
dwp_people|dwp-people-server
dwp_platform|dwp-platform-server
dwp_provider|dwp-provider-server
DATABASES

psql_query "$PLATFORM_DB" "
  SELECT code_set.owner_service,
         binding.source_reference,
         string_agg(DISTINCT code_value.code, ',' ORDER BY code_value.code)
    FROM sys_code_sets code_set
    JOIN sys_code_bindings binding
      ON binding.code_set_key = code_set.code_set_key
     AND binding.lifecycle_state = 'ACTIVE'
     AND binding.usage_type = 'DATABASE_COLUMN'
     AND binding.enforcement_type = 'CHECK'
    JOIN sys_code_values code_value
      ON code_value.code_set_key = code_set.code_set_key
     AND code_value.lifecycle_state = 'ACTIVE'
   WHERE code_set.lifecycle_state = 'ACTIVE'
   GROUP BY code_set.owner_service, binding.source_reference
   ORDER BY code_set.owner_service, binding.source_reference
" >"$expected_file"

LC_ALL=C sort -u -o "$actual_file" "$actual_file"
LC_ALL=C sort -u -o "$expected_file" "$expected_file"

if ! diff -u "$actual_file" "$expected_file" >"$work_dir/check-contract.diff"; then
  printf 'FAIL  database CHECK contracts differ from the central registry\n' >&2
  cat "$work_dir/check-contract.diff" >&2
  exit 1
fi
printf 'PASS  every database enum CHECK is registered with the same values\n'

assert_zero "$PLATFORM_DB" 'registry completeness' \
  "SELECT COUNT(*) FROM sys_code_catalog_health WHERE registration_state <> 'REGISTERED'"

assert_zero dwp_auth 'normalized login policy codes' "
  SELECT COUNT(*)
    FROM sys_auth_policies policy
   WHERE NOT EXISTS (
         SELECT 1
           FROM sys_auth_policy_login_types allowed
          WHERE allowed.tenant_id = policy.tenant_id
            AND allowed.login_type = policy.default_login_type)
"

assert_zero dwp_people 'assignment change reason foreign keys' "
  SELECT COUNT(*)
    FROM ppl_assignments assignment
    LEFT JOIN ppl_assignment_change_reason_catalog reason
      ON reason.tenant_id = assignment.tenant_id
     AND reason.reason_code = assignment.change_reason_code
   WHERE assignment.change_reason_code IS NOT NULL
     AND reason.assignment_change_reason_id IS NULL
"

assert_zero dwp_people 'position type and criticality foreign keys' "
  SELECT COUNT(*)
    FROM ppl_positions position
    LEFT JOIN ppl_position_type_catalog position_type
      ON position_type.position_type = position.position_type
    LEFT JOIN ppl_position_criticality_catalog criticality
      ON criticality.criticality = position.criticality
   WHERE position_type.position_type IS NULL OR criticality.criticality IS NULL
"

assert_zero dwp_people 'organization role foreign keys' "
  SELECT COUNT(*)
    FROM ppl_organization_role_assignments assignment
    LEFT JOIN ppl_organization_role_catalog role_catalog
      ON role_catalog.tenant_id = assignment.tenant_id
     AND role_catalog.role_code = assignment.role_code
   WHERE role_catalog.organization_role_id IS NULL
"

assert_zero dwp_provider 'provider operation catalog references' "
  SELECT COUNT(*)
    FROM prv_governance_controls control
    LEFT JOIN prv_operation_type_catalog operation_type
      ON operation_type.operation_type = control.remediation_operation_type
   WHERE control.remediation_operation_type IS NOT NULL
     AND operation_type.operation_type IS NULL
"

assert_zero dwp_provider 'provider operator role references' "
  SELECT COUNT(*)
    FROM prv_operators operator_ref
    LEFT JOIN prv_operator_roles role_ref
      ON role_ref.role_code = operator_ref.role_code
   WHERE role_ref.role_code IS NULL
"

assert_zero dwp_provider 'tenant administrator role references' "
  SELECT COUNT(*)
    FROM prv_tenant_administrators administrator
    LEFT JOIN prv_tenant_administrator_roles role_ref
      ON role_ref.role_code = administrator.role_code
   WHERE role_ref.role_code IS NULL
"

summary="$(psql_query "$PLATFORM_DB" "
  SELECT COUNT(*) || ' contracts, ' ||
         SUM(value_count) || ' active values, ' ||
         SUM(binding_count) || ' bindings'
    FROM sys_code_catalog_health
")"
printf 'Code contract audit complete: %s\n' "$summary"
