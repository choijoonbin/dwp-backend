# FE / Aura 전달 사항 요약

> Aura 팀 전달 문서(작업.txt) 검토 후 정리  
> Drill-down 계약 반영  
> 최종 업데이트: 2026-01-29

---

## 📤 Aura 팀 전달 사항

### 1. Redis Pub/Sub Audit 이벤트 발행 (필수)

**목적**: 통합관제센터 Agent Execution Stream에 에이전트 활동 표시

| 항목 | 내용 |
|------|------|
| **채널** | `audit:events:ingest` |
| **Redis** | Synapse와 동일 인스턴스 (HITL `hitl:channel:*`과 동일) |
| **인코딩** | UTF-8 bytes |
| **형식** | JSON 문자열 |

**필수 필드**: `tenant_id` (Long)

**권장 필드** (snake_case 또는 camelCase):
- `event_category`, `event_type`, `resource_type`, `resource_id`
- `created_at` (ISO 8601), `actor_type`, `channel`, `outcome`, `severity`
- `evidence_json.message` — 스트림에 표시할 메시지
- `trace_id`

**JSON 예시**:
```json
{
  "tenant_id": 1,
  "event_category": "AGENT",
  "event_type": "DETECTION_FOUND",
  "resource_type": "CASE",
  "resource_id": "123",
  "created_at": "2026-02-03T01:10:00Z",
  "actor_type": "AGENT",
  "channel": "AGENT",
  "outcome": "SUCCESS",
  "severity": "INFO",
  "evidence_json": {"message": "Critical anomaly detected: Amount variance 3x"},
  "trace_id": "abc-123"
}
```

**상세**: `docs/guides/AUDIT_EVENTS_SPEC.md` 섹션 6, 8

---

### 2. event_category / event_type 권장값

| event_category | event_type 예시 |
|----------------|-----------------|
| AGENT | SCAN_STARTED, SCAN_COMPLETED, DETECTION_FOUND, RAG_QUERIED, SIMULATION_RUN, DECISION_MADE |
| INTEGRATION | INGEST_RECEIVED, INGEST_FAILED, SAP_WRITE_SUCCESS, SAP_WRITE_FAILED |
| ACTION | ACTION_PROPOSED, ACTION_APPROVED, ACTION_EXECUTED, ACTION_ROLLED_BACK |

---

### 3. 기타 (문서 기준 이미 반영된 사항)

- HITL: `hitl:channel:{sessionId}` 구독, 신호 형식 준수
- SSE: `POST /api/aura/test/stream`, `data: [DONE]` 종료
- 포트: Aura 9000, Auth 8001

---

## 📤 프론트엔드 전달 사항

### 1. Drill-down 공통 Query Param (신규)

**GET /api/synapse/cases** (확장 완료):
- `range`: 1h|6h|24h|7d|30d|90d
- `from`, `to`: ISO8601 (range 없을 때)
- `ids`: comma-separated (예: ids=1,2,3)
- `caseKey`: CS-2026-0001 형식
- `driverType`: Top Risk Drivers 클릭 시 (caseType 별칭)
- `status`, `severity`: multi (comma)
- `company`: multi (comma-separated BUKRS)
- `page`: 1-based (default 1)
- `sort`, `order`: createdAt, desc

**응답**: `filtersApplied` 필드 추가 (현재 필터 상태)
**상세**: `docs/frontend/docs/api-spec/DRILLDOWN_CONTRACT_result.md`

### 2. 통합관제센터 Dashboard API (신규/변경)

**신규 API**:
- `GET /api/synapse/dashboard/team-snapshot?range=24h&teamId=optional`
- `GET /api/synapse/dashboard/agent-activity?range=1h&limit=50`

**기존 API 응답 보강** (links, drill-down 키 추가):
- `GET /api/synapse/dashboard/summary` — `links.casesPath`, `links.actionsPath`, `links.auditPath`
- `GET /api/synapse/dashboard/top-risk-drivers` — `riskTypeKey`, `estimatedLoss`, `links.anomaliesPath`
- `GET /api/synapse/dashboard/action-required` — `primaryActionId`, `reasonShort`, `links.reviewPath`

### 2. 클릭 동선

응답의 `links.*` 값을 FE 라우트로 사용:
- `casesPath`, `actionsPath`, `anomaliesPath`, `reviewPath` → 해당 페이지 이동 + query params
- `auditPath` → `/audit` 페이지에서 `GET /api/synapse/audit/events` 호출 시 query params로 전달

### 4. View Full Audit Log

`/audit` 페이지에서 사용할 query params:
- `from`, `to` (ISO 8601)
- `category`, `resourceType`, `resourceId`, `actorUserId`

**상세**: `docs/frontend/docs/api-spec/DASHBOARD_MOCK_REPLACEMENT_result.md`

---

## 📋 문서 참조

| 대상 | 문서 |
|------|------|
| Aura | `docs/integration/AURA_PLATFORM_UPDATE.md`, `docs/guides/AUDIT_EVENTS_SPEC.md` |
| FE | `docs/frontend/docs/api-spec/DASHBOARD_MOCK_REPLACEMENT_result.md` |
