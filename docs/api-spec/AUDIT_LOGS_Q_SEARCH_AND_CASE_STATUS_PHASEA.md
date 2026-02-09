# 감사로그 q 검색 + 케이스 상태 PhaseA (P0)

## A) 감사로그 조회 API

### 경로

- `GET /api/synapse/audit/events` (기존)
- `GET /api/synapse/audit/logs` (동일, FE 통합 검색용)

### 파라미터

| 파라미터 | 타입 | 기본 | 설명 |
|----------|------|------|------|
| **q** | string | - | 통합 검색. OR 매칭 (exact/prefix) |
| dateFrom, from | datetime | - | 시작일시 |
| dateTo, to | datetime | - | 종료일시 |
| range | string | 24h | 1h\|6h\|24h\|7d\|30d\|90d. from/to 미입력 시 적용 |
| eventCategory, category | string | - | CASE, ACTION, AUDIT, RUN 등 |
| eventType, type | string | - | 이벤트 유형 |
| outcome | string | - | SUCCESS, FAILED, DENIED |
| actorType | string | - | HUMAN, AGENT, SYSTEM |
| actorUserId | long | - | 행위자 user_id |
| resourceType | string | - | AGENT_CASE, DETECT_RUN 등 |
| resourceId | string | - | 리소스 ID |
| traceId | string | - | 추적 ID |
| gatewayRequestId | string | - | 게이트웨이 요청 ID |
| runId | long | - | tags.runId |
| **caseId** | long | - | 케이스 관련 이벤트만 (resourceType=AGENT_CASE AND resourceId=caseId OR tags.caseId=caseId) |
| page, size | int | 0, 20 | 페이징 |
| sort | string | createdAt | 정렬 필드 |

### q 검색 대상 (OR 매칭)

- `resource_id`
- `actor_user_id` (q가 숫자일 때)
- `actor_agent_id`
- `gateway_request_id`
- `trace_id`
- `span_id`

매칭: exact 또는 prefix (`field = q` OR `field LIKE q%`). 대소문자 무시.

### dateFrom/dateTo 미입력 시

- `range` 미입력: 기본 **24h**
- `range` 입력: 1h\|6h\|24h\|7d\|30d\|90d

### curl 예시

```bash
# q로 traceId 검색 (기본 24h)
curl -H "X-Tenant-ID: 1" "http://localhost:8080/api/synapse/audit/logs?q=abc-123-xyz"

# q로 gatewayRequestId 검색
curl -H "X-Tenant-ID: 1" "http://localhost:8080/api/synapse/audit/logs?q=gw-req-001"

# dateFrom/dateTo + eventCategory
curl -H "X-Tenant-ID: 1" "http://localhost:8080/api/synapse/audit/logs?dateFrom=2026-02-01T00:00:00Z&dateTo=2026-02-09T23:59:59Z&eventCategory=CASE&q=trace-001"

# range 7d + outcome
curl -H "X-Tenant-ID: 1" "http://localhost:8080/api/synapse/audit/logs?range=7d&outcome=SUCCESS"
```

### 인덱스

- `(tenant_id, created_at DESC)`
- `(tenant_id, event_category, event_type, created_at DESC)`
- `(tenant_id, outcome, created_at DESC)`
- `(tenant_id, actor_type, actor_user_id, created_at DESC)`
- `(tenant_id, trace_id)` WHERE trace_id IS NOT NULL
- `(tenant_id, gateway_request_id)` WHERE gateway_request_id IS NOT NULL
- `(tenant_id, span_id)` WHERE span_id IS NOT NULL
- `(tenant_id, resource_id)` WHERE resource_id IS NOT NULL

---

## B) 케이스 상태 PhaseA

### 표준 흐름

```
배치 생성 → OPEN(신규/미해결)
     ↓
사용자 진행 → IN_PROGRESS(검토중/진행중)
     ↓
종료 → RESOLVED(해결) | DISMISSED(무시)
```

### PhaseA 노출 (sys_codes is_active=true)

| code | name_ko |
|------|---------|
| OPEN | 신규/오픈(미해결) |
| IN_PROGRESS | 진행중 |
| RESOLVED | 해결됨 |
| DISMISSED | 무시됨 |

### 비노출 (is_active=false)

TRIAGE, TRIAGED, PENDING, REVIEW, IN_REVIEW, PENDING_APPROVAL, APPROVED, REJECTED, EXECUTED, FAILED, COMPLETED, CLOSED, ARCHIVED

### FE 코드 조회

`GET /api/admin/codes?groupKey=CASE_STATUS&enabled=true` → PhaseA 노출 4개만 반환

### DoD 확인

- [x] 배치 생성 케이스: OPEN
- [x] POST /cases/{id}/status 200 + 상세 반영 + audit STATUS_CHANGE
- [x] CASE_STATUS PhaseA만 active (V36)

---

## C) Audit 필터 코드 (Phase A)

### 코드 그룹 (GET /api/admin/codes?groupKey=...)

| groupKey | 설명 |
|----------|------|
| AUDIT_CATEGORY | event_category (CASE, ACTION, ADMIN, AUDIT, RUN, UI, DASHBOARD 등) |
| AUDIT_EVENT_TYPE | event_type (STATUS_CHANGE, CASE_VIEW_LIST, DOCUMENT_VIEW_DETAIL 등) |
| AUDIT_OUTCOME | outcome (SUCCESS, FAILED, DENIED, NOOP) |
| AUDIT_ACTOR_TYPE | actor_type (HUMAN, AGENT, SYSTEM) |
| AUDIT_SEVERITY | severity (INFO, WARN, HIGH, CRITICAL) |
| AUDIT_RESOURCE_TYPE | resource_type (AGENT_CASE, AGENT_ACTION, DETECT_RUN 등) |

### 메뉴별 코드 조회 (감사 화면)

`GET /api/admin/codes/usage?resourceKey=menu.admin.audit`

### 케이스 단위 감사 API

`GET /api/synapse/cases/{caseId}/audit-events?page=0&size=20`

케이스 상세 '감사 스트림' 탭용. 반환: auditId, createdAt, eventCategory, eventType, outcome, severity, actorType, actorDisplayName, resourceType, resourceId
