# Admin Batch Monitoring 보완(P0) 구현 결과

> 기준: `docs/job/PROMPT_BE_ADMIN_BATCH_MONITORING_HARDENING_RBAC_STATUS.txt`

## 1. 구현 완료 항목

### A) 서비스 레벨 RBAC 강제

- **auth-server**: `GET /internal/permission/check` API 추가
  - 파라미터: userId, tenantId, resourceKey, permissionCode
  - `AdminGuardService.canAccess()` 호출
- **synapsex-service**: `SynapseAdminGuardFilter` 추가
  - `/synapse/admin/**` 경로 보호
  - `menu.admin.batch-monitoring` + VIEW (GET) / EXECUTE (POST /detect/run)
  - auth-server Feign 호출로 권한 검증
  - 미통과 시 403

### B) Scheduler Status 조회 API

- **GET** `/api/synapse/admin/detect/scheduler/status`
- 응답 필드:
  - enabled, scheduleType, intervalMinutes, cronExpression
  - lastRunId, lastSuccessAt, lastFailAt
  - running, runningRunId, runningSince
  - nextPlannedAt (enabled 시)

### C) SKIPPED 보강

- Manual Run 응답(status=SKIPPED)에 추가:
  - `runningRunId`: 현재 실행 중인 run ID
  - `runningSince`: 해당 run 시작 시각
  - `skipReason`: "Another detect run is in progress (advisory lock held)"

## 2. 신규/수정 파일

| 파일 | 변경 |
|------|------|
| dwp-auth-server/.../InternalPermissionController.java | 신규 |
| synapsex-service/.../AuthServerPermissionClient.java | 신규 |
| synapsex-service/.../SynapseAdminGuardFilter.java | 신규 |
| synapsex-service/.../DetectBatchConfig.java | intervalMinutes 추가 |
| synapsex-service/.../DetectRunRepository.java | findTopByTenantIdAndStatusOrderByStartedAtDesc |
| synapsex-service/.../SchedulerStatusDto.java | 신규 |
| synapsex-service/.../DetectSchedulerStatusService.java | 신규 |
| synapsex-service/.../DetectBatchService.java | getSkippedRunInfo 추가 |
| synapsex-service/.../DetectRunDto.java | runningRunId, runningSince, skipReason |
| synapsex-service/.../DetectBatchController.java | scheduler/status, SKIPPED 보강 |

## 3. 검증용 curl

```bash
# 권한 테스트 (권한 없는 토큰 → 403)
curl -i -X GET "$BASE_URL/api/synapse/admin/detect/runs?page=0&size=5" \
  -H "Authorization: Bearer $TOKEN_NO_ADMIN" -H "X-Tenant-ID: $TENANT" -H "X-User-ID: $USER" -H "X-Trace-ID: rbac-test"

# Scheduler status
curl -X GET "$BASE_URL/api/synapse/admin/detect/scheduler/status" \
  -H "Authorization: Bearer $TOKEN_ADMIN" -H "X-Tenant-ID: $TENANT" -H "X-User-ID: $USER" -H "X-Trace-ID: status-001"

# Manual run SKIPPED 확인 (동시 호출 시 두 번째에 runningRunId/skipReason 포함)
curl -X POST "$BASE_URL/api/synapse/admin/detect/run" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: $TENANT" -H "X-User-ID: $USER" -H "X-Trace-ID: run-001" \
  -H "Content-Type: application/json" -d '{ "windowMinutes": 60 }'

curl -X POST "$BASE_URL/api/synapse/admin/detect/run" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: $TENANT" -H "X-User-ID: $USER" -H "X-Trace-ID: run-002" \
  -H "Content-Type: application/json" -d '{ "windowMinutes": 60 }'
```

## 4. 전제사항

- Gateway가 요청 시 `X-Tenant-ID`, `X-User-ID` 헤더를 synapsex로 전파해야 함
- `menu.admin.batch-monitoring` 리소스에 대한 VIEW/EXECUTE 권한이 ADMIN 역할 또는 해당 사용자에게 부여되어 있어야 함

## 5. 메뉴 테이블 보강 (V26)

- **sys_menus**: `menu.admin.monitoring`(통합 모니터링), `menu.admin.batch-monitoring`(배치 모니터링) UPSERT
- **com_resources**: 두 메뉴 리소스 등록
- **com_role_permissions**: ADMIN에 VIEW/USE/EDIT/EXECUTE 부여
- 프론트 `GET /api/auth/menus/tree` 호출 시 admin 권한 사용자에게 두 메뉴 노출
