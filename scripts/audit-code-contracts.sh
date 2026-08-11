#!/usr/bin/env bash

set -euo pipefail

BACKEND_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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

assert_same_codes() {
  local label="$1"
  local source_database="$2"
  local source_query="$3"
  local code_set_key="$4"
  local source_codes
  local registered_codes
  source_codes="$(psql_query "$source_database" "$source_query")"
  registered_codes="$(psql_query "$PLATFORM_DB" "
    SELECT COALESCE(string_agg(code, ',' ORDER BY code), '')
      FROM sys_code_values
     WHERE code_set_key = '${code_set_key}'
       AND lifecycle_state = 'ACTIVE'
  ")"
  if [[ "$source_codes" != "$registered_codes" ]]; then
    printf 'FAIL  %s: source=[%s] registry=[%s]\n' \
      "$label" "$source_codes" "$registered_codes" >&2
    return 1
  fi
  printf 'PASS  %s\n' "$label"
}

assert_registry_codes() {
  local label="$1"
  local code_set_key="$2"
  local expected_codes="$3"
  local registered_codes
  registered_codes="$(psql_query "$PLATFORM_DB" "
    SELECT COALESCE(string_agg(code, ',' ORDER BY code), '')
      FROM sys_code_values
     WHERE code_set_key = '${code_set_key}'
       AND lifecycle_state = 'ACTIVE'
  ")"
  if [[ "$expected_codes" != "$registered_codes" ]]; then
    printf 'FAIL  %s: contract=[%s] registry=[%s]\n' \
      "$label" "$expected_codes" "$registered_codes" >&2
    return 1
  fi
  printf 'PASS  %s\n' "$label"
}

assert_java_enum_codes() {
  local label="$1"
  local source_file="$2"
  local enum_name="$3"
  local extraction_mode="$4"
  local code_set_key="$5"
  local source_codes
  local registered_codes
  source_codes="$(python3 - "$BACKEND_ROOT/$source_file" "$enum_name" "$extraction_mode" <<'PYTHON'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
enum_name = sys.argv[2]
mode = sys.argv[3]
source = path.read_text(encoding="utf-8")
source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
source = re.sub(r"//.*", "", source)
match = re.search(rf"\benum\s+{re.escape(enum_name)}\s*\{{", source)
if not match:
    raise SystemExit(f"enum not found: {path}:{enum_name}")

depth = 1
cursor = match.end()
while cursor < len(source) and depth:
    if source[cursor] == "{":
        depth += 1
    elif source[cursor] == "}":
        depth -= 1
    cursor += 1
block = source[match.end():cursor - 1]
constants = block.split(";", 1)[0]
if mode == "wire-error-code":
    values = re.findall(r'"(E\d{4})"', constants)
else:
    values = re.findall(r"(?m)^\s*([A-Z][A-Z0-9_]*)\b", constants)
print(",".join(sorted(set(values))))
PYTHON
)"
  registered_codes="$(psql_query "$PLATFORM_DB" "
    SELECT COALESCE(string_agg(code, ',' ORDER BY code), '')
      FROM sys_code_values
     WHERE code_set_key = '${code_set_key}'
       AND lifecycle_state = 'ACTIVE'
  ")"
  if [[ "$source_codes" != "$registered_codes" ]]; then
    printf 'FAIL  %s: source=[%s] registry=[%s]\n' \
      "$label" "$source_codes" "$registered_codes" >&2
    return 1
  fi
  printf 'PASS  %s\n' "$label"
}

assert_java_enum_inventory() {
  local actual="$work_dir/java-enums.actual"
  local expected="$work_dir/java-enums.expected"
  python3 - "$BACKEND_ROOT" >"$actual" <<'PYTHON'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
for path in sorted(root.glob("dwp-*/src/main/java/**/*.java")):
    source = path.read_text(encoding="utf-8")
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r"//.*", "", source)
    for name in re.findall(r"\benum\s+([A-Za-z][A-Za-z0-9_]*)\s*\{", source):
        print(f"{path.relative_to(root)}|{name}")
PYTHON
  cat >"$expected" <<'ENUMS'
dwp-audit/src/main/java/com/dwp/audit/AuditEventPublisher.java|DeliveryResult
dwp-core/src/main/java/com/dwp/core/common/ErrorCode.java|ErrorCode
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/ConnectorPort.java|Capability
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/ConnectorPort.java|HealthState
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/DataClassification.java|DataClassification
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/RiskTier.java|RiskTier
dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementAudienceType.java|AnnouncementAudienceType
dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementLifecycle.java|AnnouncementLifecycle
dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementSeverity.java|AnnouncementSeverity
dwp-platform-server/src/main/java/com/dwp/services/platform/apihistory/ApiHistoryWindow.java|ApiHistoryWindow
dwp-platform-server/src/main/java/com/dwp/services/platform/auditcontrol/AuditWindow.java|AuditWindow
dwp-platform-server/src/main/java/com/dwp/services/platform/reference/ReferenceLifecycle.java|ReferenceLifecycle
dwp-platform-server/src/main/java/com/dwp/services/platform/registry/RegistryType.java|RegistryType
dwp-platform-server/src/main/java/com/dwp/services/platform/registry/RiskTier.java|RiskTier
ENUMS
  LC_ALL=C sort -o "$actual" "$actual"
  LC_ALL=C sort -o "$expected" "$expected"
  if ! diff -u "$expected" "$actual" >"$work_dir/java-enums.diff"; then
    printf 'FAIL  Java enum inventory changed without a governed code mapping\n' >&2
    cat "$work_dir/java-enums.diff" >&2
    return 1
  fi
  printf 'PASS  every Java enum is included in the governed source inventory\n'
}

assert_revision_bump() {
  if ! docker exec -i "$POSTGRES_CONTAINER" \
      psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
      >/dev/null <<'SQL'
BEGIN;
DO $revision_test$
DECLARE
    before_revision INTEGER;
    after_revision INTEGER;
    regression_blocked BOOLEAN := FALSE;
BEGIN
    SELECT schema_version INTO before_revision
      FROM sys_code_sets
     WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE';

    UPDATE sys_code_values
       SET display_name = display_name || ' '
     WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE'
       AND code = 'system';

    SELECT schema_version INTO after_revision
      FROM sys_code_sets
     WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE';

    IF after_revision <> before_revision + 1 THEN
        RAISE EXCEPTION 'expected revision %, found %',
            before_revision + 1, after_revision;
    END IF;

    BEGIN
        UPDATE sys_code_sets
           SET schema_version = schema_version - 1
         WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE';
    EXCEPTION WHEN check_violation THEN
        regression_blocked := TRUE;
    END;
    IF NOT regression_blocked THEN
        RAISE EXCEPTION 'schema revision regression was accepted';
    END IF;
END;
$revision_test$;
ROLLBACK;
SQL
  then
    printf 'FAIL  code set revision trigger\n' >&2
    return 1
  fi
  printf 'PASS  code set revision trigger\n'
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

assert_zero "$PLATFORM_DB" 'code set revision trigger installation' "
  SELECT CASE WHEN COUNT(*) = 7 THEN 0 ELSE 1 END
    FROM pg_trigger
   WHERE NOT tgisinternal
     AND tgname IN (
       'trg_sys_code_sets_revision_guard',
       'trg_sys_code_values_revision_insert',
       'trg_sys_code_values_revision_delete',
       'trg_sys_code_values_revision_update',
       'trg_sys_code_bindings_revision_insert',
       'trg_sys_code_bindings_revision_delete',
       'trg_sys_code_bindings_revision_update')
"

assert_revision_bump

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

assert_zero dwp_people 'scenario approval role foreign keys' "
  SELECT COUNT(*)
    FROM ppl_organization_scenario_approvals approval
    LEFT JOIN ppl_approval_role_catalog role_catalog
      ON role_catalog.role_code = approval.required_role_code
   WHERE role_catalog.role_code IS NULL
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

assert_zero dwp_auth 'built-in role reservations' "
  SELECT COUNT(*)
    FROM com_roles role_ref
    LEFT JOIN sys_builtin_role_catalog catalog
      ON catalog.role_code = role_ref.builtin_role_code
   WHERE (role_ref.role_type = 'SYSTEM'
          AND (role_ref.builtin_role_code <> role_ref.code
               OR catalog.role_code IS NULL))
      OR (role_ref.role_type = 'CUSTOM'
          AND EXISTS (
              SELECT 1
                FROM sys_builtin_role_catalog reserved
               WHERE reserved.role_code = role_ref.code))
"

assert_same_codes 'built-in role catalog projection' dwp_auth "
  SELECT COALESCE(string_agg(role_code, ',' ORDER BY role_code), '')
    FROM sys_builtin_role_catalog
   WHERE lifecycle_state = 'ACTIVE'
" 'AUTH.BUILT_IN_ROLE'

assert_same_codes 'permission action catalog projection' dwp_auth "
  SELECT COALESCE(string_agg(code, ',' ORDER BY code), '')
    FROM com_permissions
" 'AUTH.PERMISSION_ACTION'

assert_same_codes 'people approval role catalog projection' dwp_people "
  SELECT COALESCE(string_agg(role_code, ',' ORDER BY role_code), '')
    FROM ppl_approval_role_catalog
   WHERE lifecycle_state = 'ACTIVE'
" 'PEOPLE.APPROVAL_ROLE'

assert_java_enum_inventory
assert_java_enum_codes 'core API error code enum' \
  'dwp-core/src/main/java/com/dwp/core/common/ErrorCode.java' \
  'ErrorCode' 'wire-error-code' 'CORE.ERROR_CODE'
assert_java_enum_codes 'audit delivery result enum' \
  'dwp-audit/src/main/java/com/dwp/audit/AuditEventPublisher.java' \
  'DeliveryResult' 'name' 'AUDIT.DELIVERY_RESULT'
assert_java_enum_codes 'connector capability enum' \
  'dwp-platform-contracts/src/main/java/com/dwp/platform/contract/ConnectorPort.java' \
  'Capability' 'name' 'PLATFORM.CONNECTOR.CAPABILITY'
assert_java_enum_codes 'connector health state enum' \
  'dwp-platform-contracts/src/main/java/com/dwp/platform/contract/ConnectorPort.java' \
  'HealthState' 'name' 'PLATFORM.CONNECTOR.HEALTH_STATE'
assert_java_enum_codes 'shared data classification enum' \
  'dwp-platform-contracts/src/main/java/com/dwp/platform/contract/DataClassification.java' \
  'DataClassification' 'name' 'PEOPLE.PPL_ATTRIBUTE_DEFINITIONS.DATA_CLASSIFICATION'
assert_java_enum_codes 'shared execution risk tier enum' \
  'dwp-platform-contracts/src/main/java/com/dwp/platform/contract/RiskTier.java' \
  'RiskTier' 'name' 'PLATFORM.SYS_ADMIN_COMMAND_REQUESTS.RISK_TIER'
assert_java_enum_codes 'reference lifecycle enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/reference/ReferenceLifecycle.java' \
  'ReferenceLifecycle' 'name' 'PLATFORM.REFERENCE_LIFECYCLE'
assert_java_enum_codes 'registry type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/registry/RegistryType.java' \
  'RegistryType' 'name' 'PLATFORM.REGISTRY_TYPE'
assert_java_enum_codes 'registry risk tier enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/registry/RiskTier.java' \
  'RiskTier' 'name' 'PLATFORM.RISK_TIER'
assert_java_enum_codes 'announcement lifecycle enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementLifecycle.java' \
  'AnnouncementLifecycle' 'name' 'PLATFORM.ADM_ANNOUNCEMENTS.LIFECYCLE_STATE'
assert_java_enum_codes 'announcement severity enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementSeverity.java' \
  'AnnouncementSeverity' 'name' 'PLATFORM.ANNOUNCEMENT_SEVERITY'
assert_java_enum_codes 'announcement audience enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementAudienceType.java' \
  'AnnouncementAudienceType' 'name' 'PLATFORM.ANNOUNCEMENT_AUDIENCE'
assert_java_enum_codes 'API history window enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/apihistory/ApiHistoryWindow.java' \
  'ApiHistoryWindow' 'name' 'PLATFORM.API_HISTORY.WINDOW'
assert_java_enum_codes 'audit window enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/auditcontrol/AuditWindow.java' \
  'AuditWindow' 'name' 'PLATFORM.AUDIT.WINDOW'

# API and JSON contracts do not have database CHECK constraints. These
# manifests make their byte-for-byte values an explicit release gate.
assert_registry_codes 'personal color mode contract' \
  'PLATFORM.PREFERENCE.COLOR_MODE' 'dark,light,system'
assert_registry_codes 'personal density contract' \
  'PLATFORM.PREFERENCE.DENSITY' 'comfortable,compact,standard'
assert_registry_codes 'system code runtime visibility contract' \
  'PLATFORM.SYS_CODE_SETS.RUNTIME_VISIBILITY' 'ADMIN_ONLY,RUNTIME'
assert_registry_codes 'home widget contract' \
  'PLATFORM.HOME_WIDGET' 'activity,announcements,daily-brief,focus,schedule'
assert_registry_codes 'API history window contract' \
  'PLATFORM.API_HISTORY.WINDOW' 'D30,D7,H1,H24,H6'
assert_registry_codes 'API observation filter contract' \
  'PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER' 'ALL,GATEWAY,SERVICE'
assert_registry_codes 'API outcome filter contract' \
  'PLATFORM.API_HISTORY.OUTCOME_FILTER' \
  'ALL,CANCELLED,CLIENT_ERROR,REDIRECTION,SERVER_ERROR,SUCCESS'
assert_registry_codes 'API HTTP method filter contract' \
  'PLATFORM.API_HISTORY.HTTP_METHOD_FILTER' 'ALL,DELETE,GET,PATCH,POST,PUT'
assert_registry_codes 'audit window contract' \
  'PLATFORM.AUDIT.WINDOW' 'D30,D7,D90,H24'
assert_registry_codes 'audit category filter contract' \
  'PLATFORM.AUDIT.CATEGORY_FILTER' \
  'ADMIN_CHANGE,AI_ACTION,ALL,AUTHENTICATION,AUTHORIZATION,DATA_ACCESS,DATA_EXPORT,POLICY_DENIED,PROVISIONING,SYSTEM_EVENT'
assert_registry_codes 'audit severity filter contract' \
  'PLATFORM.AUDIT.SEVERITY_FILTER' 'ALL,CRITICAL,HIGH,INFO,LOW,MEDIUM'
assert_registry_codes 'audit outcome filter contract' \
  'PLATFORM.AUDIT.OUTCOME_FILTER' 'ALL,DENIED,FAILED,SUCCESS'

summary="$(psql_query "$PLATFORM_DB" "
  SELECT COUNT(*) || ' contracts, ' ||
         SUM(value_count) || ' active values, ' ||
         SUM(binding_count) || ' bindings'
    FROM sys_code_catalog_health
")"
printf 'Code contract audit complete: %s\n' "$summary"
