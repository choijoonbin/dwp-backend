# Admin 배치 모니터링 API 구현 결과

> 기준: `docs/job/PROMPT_BE_ADMIN_BATCH_MONITORING_APIS_AND_SCHEMA.txt`

## 1. 구현 완료 항목

### A) Detect Run 관제 API

| API | Method | Path | 설명 |
|-----|--------|------|------|
| 목록 조회 | GET | `/api/synapse/admin/detect/runs` | from, to, status, page, size, sort |
| 상세 조회 | GET | `/api/synapse/admin/detect/runs/{runId}` | counts_json 포함 |
| 수동 트리거 | POST | `/api/synapse/admin/detect/run` | body: windowMinutes, from/to |

**POST 요청 본문 예시**
```json
{ "windowMinutes": 1440 }
```
또는 backfill:
```json
{ "from": "2025-01-28T00:00:00Z", "to": "2025-01-29T00:00:00Z" }
```

**응답 예시 (성공)**
```json
{
  "success": true,
  "data": {
    "runId": 1,
    "tenantId": 1,
    "windowFrom": "2025-01-28T00:00:00Z",
    "windowTo": "2025-01-29T00:00:00Z",
    "status": "COMPLETED",
    "countsJson": { "caseCreated": 5, "caseUpdated": 2, "created_count": 5, "updated_count": 2, "suppressed_count": 0 },
    "startedAt": "2025-01-29T10:00:00Z",
    "completedAt": "2025-01-29T10:00:05Z"
  }
}
```

**응답 예시 (SKIPPED - advisory lock)**
```json
{
  "success": true,
  "data": {
    "status": "SKIPPED",
    "errorMessage": "Advisory lock not acquired (another instance running)"
  }
}
```

### B) Ingest Run 관제 API

| API | Method | Path |
|-----|--------|------|
| 목록 조회 | GET | `/api/synapse/admin/ingest/runs` |
| 상세 조회 | GET | `/api/synapse/admin/ingest/runs/{runId}` |

쿼리: from, to, status, page, size, sort

### C) Audit runId 필터

- `GET /api/synapse/audit/events?runId={runId}` — tags JSONB의 runId로 필터링
- Run Detail 화면에서 해당 run과 연관된 감사 이벤트 조회 가능

### D) Audit-Ready (Manual Run)

- Manual Run 실행 시 `audit_event_log`에 기록
- `event_category=RUN`, `event_type=RUN_DETECT_MANUAL_TRIGGERED`
- payload(after_json): tenant_id, user_id, result_status, run_id, window_from, window_to, trace_id

### E) RBAC

- `/api/synapse/admin/**` 경로는 Gateway를 통해 synapsex-service로 라우팅
- 현재 synapsex-service는 auth-server의 AdminGuardInterceptor 적용 대상이 아님 (`/api/admin/**`만 적용)
- **권한 강제**: Gateway JWT 검증 후 X-Tenant-ID, X-User-ID 전파. ADMIN_OPERATIONS/ADMIN_OPERATIONS_EXECUTE 세부 권한은 auth-server 연동(Feign 또는 Gateway 레벨)으로 추후 확장 가능

## 2. Page 응답 형식

프로젝트 표준 준수: `page`, `size`, `totalElements`, `totalPages`, `content`

## 3. 스키마

- `detect_run`, `ingest_run` 테이블 기존 스키마 사용 (V21, V22)
- `duration_ms` 미추가 — `completedAt - startedAt`으로 클라이언트 계산 가능

## 4. 검증용 curl

```bash
# List
curl -X GET "$BASE_URL/api/synapse/admin/detect/runs?page=0&size=20&sort=startedAt,desc" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: $TENANT" -H "X-User-ID: $USER" -H "X-Trace-ID: $TRACE"

# Detail
curl -X GET "$BASE_URL/api/synapse/admin/detect/runs/{runId}" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: $TENANT" -H "X-User-ID: $USER" -H "X-Trace-ID: $TRACE"

# Manual Run (backfill)
curl -X POST "$BASE_URL/api/synapse/admin/detect/run" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: $TENANT" -H "X-User-ID: $USER" -H "X-Trace-ID: $TRACE" \
  -H "Content-Type: application/json" \
  -d '{ "windowMinutes": 1440 }'

# Audit by runId
curl -X GET "$BASE_URL/api/synapse/audit/events?runId=1&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: $TENANT"
```

## 5. OpenAPI

- springdoc `@Operation` 어노테이션 추가
- Swagger UI: `http://{host}:8085/swagger-ui.html` (synapse 그룹)

## 6. 메뉴 테이블 (V26 마이그레이션)

- **통합 모니터링**: `menu.admin.monitoring` → `/admin/monitoring`
- **배치 모니터링**: `menu.admin.batch-monitoring` → `/admin/batch-monitoring` (Detect/Ingest Run 관제)
- sys_menus, com_resources, com_role_permissions에 등록되어 admin 권한 사용자에게 메뉴 트리로 전달됨
