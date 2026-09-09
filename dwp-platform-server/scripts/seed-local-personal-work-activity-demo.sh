#!/usr/bin/env bash
set -euo pipefail

# Creates real Personal Work commands for the local Activity UI. The fixed command IDs make
# the script replay-safe; it never rewrites a task that the tester changed after seeding.
if [[ "${DWP_ACTIVITY_SEED_PROFILE:-}" != "local-joonbin" ]]; then
  echo "Set DWP_ACTIVITY_SEED_PROFILE=local-joonbin to confirm this local-only seed." >&2
  exit 64
fi

platform_url="${DWP_LOCAL_PLATFORM_URL:-http://127.0.0.1:8002}"
case "$platform_url" in
  http://127.0.0.1:*|http://localhost:*) ;;
  *)
    echo "The personal Activity seed only accepts a loopback Platform URL." >&2
    exit 64
    ;;
esac

: "${DWP_PLATFORM_SERVICE_TOKEN:?Set the local Platform service token}"
command -v curl >/dev/null
command -v jq >/dev/null

common_headers=(
  -H "X-DWP-Service-Token: ${DWP_PLATFORM_SERVICE_TOKEN}"
  -H "X-DWP-Tenant-ID: 1"
  -H "X-DWP-User-ID: 900018"
  -H "X-DWP-Identity-Plane: TENANT"
)
work_permissions="APP.WORK:VIEW,APP.WORK:UPDATE"

seed_task() {
  local create_key="$1"
  local title="$2"
  local desired_state="$3"
  local transition_key="$4"
  local create_response task_id version transition_response current_response

  create_response="$(curl --fail-with-body --silent --show-error \
    "${common_headers[@]}" \
    -H "X-DWP-Permissions: ${work_permissions}" \
    -H "Idempotency-Key: ${create_key}" \
    -H "X-Correlation-ID: activity-local-personal-${create_key}" \
    -H "Content-Type: application/json" \
    --data "$(jq -nc --arg title "$title" '{title:$title,priority:"NORMAL"}')" \
    "${platform_url}/v1/workspace/work-hub/personal-tasks")"
  task_id="$(jq -er '.data.taskId' <<<"$create_response")"
  version="$(jq -er '.data.version' <<<"$create_response")"

  if [[ "$desired_state" != "OPEN" ]]; then
    transition_response="$(curl --fail-with-body --silent --show-error \
      "${common_headers[@]}" \
      -H "X-DWP-Permissions: ${work_permissions}" \
      -H "Idempotency-Key: ${transition_key}" \
      -H "X-Correlation-ID: activity-local-personal-${transition_key}" \
      -H "Content-Type: application/json" \
      --data "$(jq -nc --arg status "$desired_state" --argjson version "$version" \
        '{status:$status,version:$version}')" \
      "${platform_url}/v1/workspace/work-hub/personal-tasks/${task_id}/status")"
    jq -e '.success == true' >/dev/null <<<"$transition_response"
  fi

  current_response="$(curl --fail-with-body --silent --show-error \
    "${common_headers[@]}" \
    -H "X-DWP-Permissions: ${work_permissions}" \
    "${platform_url}/v1/workspace/work-hub/personal-tasks/${task_id}")"
  jq -r '"\(.data.taskId)\t\(.data.status)\t\(.data.title)"' <<<"$current_response"
}

seed_task "a7100000-0000-4000-8000-000000000001" \
  "활동 앱 검증 · 새 업무" "OPEN" "a7100000-0000-4000-8000-000000000101"
seed_task "a7100000-0000-4000-8000-000000000002" \
  "활동 앱 검증 · 진행 중 업무" "IN_PROGRESS" "a7100000-0000-4000-8000-000000000102"
seed_task "a7100000-0000-4000-8000-000000000003" \
  "활동 앱 검증 · 대기 업무" "WAITING" "a7100000-0000-4000-8000-000000000103"
seed_task "a7100000-0000-4000-8000-000000000004" \
  "활동 앱 검증 · 완료 업무" "COMPLETED" "a7100000-0000-4000-8000-000000000104"

activity_response="$(curl --fail-with-body --silent --show-error \
  "${common_headers[@]}" \
  -H "X-DWP-Permissions: APP.ACTIVITY:VIEW,APP.WORK:VIEW" \
  "${platform_url}/v1/workspace/activity?source=PERSONAL_TASK&limit=50")"
jq -r '"PERSONAL_TASK Activity events visible to joonbin@sk.com: \(.data.events | length)"' \
  <<<"$activity_response"
