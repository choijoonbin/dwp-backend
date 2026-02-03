# Dashboard "미구현 3종" API + 클릭 동선 지원 — 구현 결과

## 개요
통합관제센터 Team Snapshot / Agent Execution Stream API 신규 추가, 기존 3종 API 응답 보강, Audit 조회 호환 확인, Dashboard 감사 로그 기록을 완료했습니다.

---

## 📌 FE 전달 사항 (필수)

### 1. 신규 API 호출
- **team-snapshot**: mock → `GET /api/synapse/dashboard/team-snapshot?range=24h&teamId=optional`
- **agent-activity**: mock → `GET /api/synapse/dashboard/agent-activity?range=1h&limit=50`

### 2. 응답 스키마 변경 (기존 3개 API)
- **summary**: `links` 추가 → `{ casesPath, actionsPath, auditPath }`
- **top-risk-drivers**: `riskTypeKey`, `estimatedLoss`, `links.anomaliesPath` 추가
- **action-required**: `primaryActionId`, `reasonShort`, `links.reviewPath` 추가

### 3. 클릭 동선 구현
응답의 `links.*` 값을 FE 라우트로 사용:
- `casesPath`, `actionsPath`, `anomaliesPath`, `reviewPath` → 해당 페이지로 이동 + query params 적용
- `auditPath` → `/audit` 페이지로 이동 시 query params로 `GET /api/synapse/audit/events` 호출

### 4. "View Full Audit Log" 버튼
`/audit` 페이지에서 `GET /api/synapse/audit/events` 호출 시 사용할 query params:
- `from`, `to`: ISO 8601 (예: 2026-02-03T00:00:00Z)
- `category`: INTEGRATION, ACTION, AGENT 등
- `resourceType`, `resourceId`: 리소스 필터
- `actorUserId`: 담당자 필터

---

## A-1. 신규 API 2개

### 1) Team Snapshot
**GET** `/api/synapse/dashboard/team-snapshot?range=24h&teamId=optional`

| Param | Type | Default | 설명 |
|-------|------|---------|------|
| range | string | 24h | 1h, 24h, 7D, 30D |
| teamId | long | - | 특정 assignee(분석가) 필터 (optional) |

**Response 예시**
```json
{
  "status": "SUCCESS",
  "data": {
    "range": "24h",
    "items": [
      {
        "analystUserId": 11001,
        "analystName": "Analyst 11001",
        "title": "Analyst",
        "openCases": 5,
        "slaRisk": "AT_RISK",
        "avgLeadTimeHours": 3.2,
        "pendingApprovals": 2,
        "topQueue": "DUPLICATE_INVOICE",
        "links": {
          "casesPath": "/cases?assignee=11001&status=OPEN",
          "auditPath": "/audit?actorUserId=11001&from=...&to=..."
        }
      }
    ]
  }
}
```

**데이터 산출**
- openCases: agent_case (tenant_id, status IN OPEN/IN_PROGRESS, assignee_user_id)
- pendingApprovals: agent_action (PENDING_APPROVAL 등) + case assignee 기준
- avgLeadTimeHours: 케이스 생성~종료 평균 (미종료는 now-생성)
- slaRisk: openCases > 5 이면 AT_RISK, else ON_TRACK
- topQueue: 해당 분석가의 가장 많은 case_type

### 2) Agent Execution Stream
**GET** `/api/synapse/dashboard/agent-activity?range=1h&limit=50`

| Param | Type | Default | 설명 |
|-------|------|---------|------|
| range | string | 1h | 1h, 24h, 7D, 30D |
| limit | int | 50 | 최대 100 |

**Response 예시**
```json
{
  "status": "SUCCESS",
  "data": {
    "range": "1h",
    "items": [
      {
        "ts": "2026-02-03T01:10:00Z",
        "level": "INFO",
        "stage": "DETECT",
        "message": "[DETECT] Critical anomaly detected",
        "caseId": "CS-2026-0001",
        "actionId": "AC-2026-0321",
        "resourceType": "CASE",
        "resourceId": "123",
        "traceId": "...",
        "links": {
          "casePath": "/cases/123",
          "auditPath": "/audit?resourceType=CASE&resourceId=123"
        }
      }
    ]
  }
}
```

**데이터 소스**: audit_event_log (event_category IN AGENT, ACTION, INTEGRATION)

---

## A-2. Audit 조회 API 호환

**GET** `/api/synapse/audit/events` — 기존 구현으로 "View Full Audit Log" 요구사항 충족

| Param | 설명 |
|-------|------|
| from, to | ISO datetime (예: 2026-02-03T00:00:00Z) |
| category | event_category (INTEGRATION, ACTION, AGENT 등) |
| type | event_type |
| outcome, severity | 필터 |
| actorUserId | 담당자 필터 |
| resourceType, resourceId | 리소스 필터 |

**FE 링크 예시**
- `/audit?category=INTEGRATION&from=...&to=...`
- `/audit?resourceType=CASE&resourceId=CS-2026-0001`
- `/audit?actorUserId=11001&from=...&to=...`

---

## A-3. 기존 3개 API 응답 보강

### GET /api/synapse/dashboard/summary
추가 필드:
- `links.casesPath`: `/cases?status=OPEN`
- `links.actionsPath`: `/actions?status=PENDING_APPROVAL`
- `links.auditPath`: `/audit?category=ACTION`
- `avgLeadTime`: 실제 케이스 lead time 계산

### GET /api/synapse/dashboard/top-risk-drivers
추가 필드:
- `riskTypeKey`: driverKey와 동일 (DUPLICATE_INVOICE 등)
- `estimatedLoss`: impactAmount 별칭
- `links.anomaliesPath`: `/cases?caseType={key}&status=OPEN`

### GET /api/synapse/dashboard/action-required
추가 필드:
- `primaryActionId`: actionId
- `reasonShort`: reasonText 요약 (80자)
- `links.reviewPath`: `/cases/{caseId}`

---

## A-4. Dashboard 감사 로그

각 Dashboard API 호출 시 `audit_event_log`에 기록:
- `event_category`: DASHBOARD
- `event_type`: DASHBOARD_VIEWED
- `resource_type`: DASHBOARD
- `resource_id`: summary, top-risk-drivers, action-required, team-snapshot, agent-activity
- `evidence_json`: range, teamId, severity 등 필터 정보

---

## Gateway 라우트

`/api/synapse/dashboard/**` → synapsex-dashboard (기존 설정 유지)
