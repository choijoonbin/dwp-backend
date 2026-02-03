# Agent Tool API 명세 (Aura 호출용)

> **Base**: `/api/synapse/agent-tools/**`  
> **Gateway**: 8080 경유 → synapsex-service 8085  
> **공통**: X-Tenant-ID 필수, X-User-ID 선택, ApiResponse&lt;T&gt;, 감사로그 기록

---

## 공통 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| X-Tenant-ID | ✅ | 테넌트 식별자 |
| X-User-ID | - | 사용자 식별자 (변경 이력 시) |
| X-Agent-ID | - | Aura 에이전트 세션 ID (감사 시 ACTOR_AGENT) |
| Authorization | ✅ | Bearer JWT |

---

## Read Tools (조회)

### GET /agent-tools/cases/{caseId}
케이스 상세 조회.

### GET /agent-tools/documents
전표 목록.  
**Query**: bukrs, gjahr, vendorId, customerId, fromDate, toDate, amountMin, amountMax, anomalyFlags, page, size, sort

### GET /agent-tools/documents/{bukrs}/{belnr}/{gjahr}
전표 상세 (header + lines + reversal chain).

### GET /agent-tools/entities/{entityId}
Entity 360 (profile + change log summary + linked objects).

### GET /agent-tools/open-items
오픈아이템 목록.  
**Query**: type (AR\|AP), overdueBucket (0=current, 1=1-30일, 2=31-90일, 3=90+일), page, size, sort

### GET /agent-tools/lineage?caseId=...
Journey 조회 (raw→ingestion→scoring→case→action).  
**Query**: caseId (필수), asOf (datetime)

---

## Write Tools (조치/시뮬레이션)

### POST /agent-tools/actions/simulate
시뮬레이션 (DB 기록: agent_action_simulation).

**Request**
```json
{
  "caseId": 1,
  "actionType": "PAYMENT_BLOCK",
  "payload": { "reason": "..." }
}
```

**Response**
```json
{
  "beforePreview": { "caseId": 1, "status": "OPEN", "actionType": "..." },
  "afterPreview": { "caseId": 1, "actionType": "...", "payload": {...} },
  "validationErrors": [],
  "predictedSapImpact": { "estimated": "PLUGGABLE", "note": "..." },
  "simulationId": 1
}
```

### POST /agent-tools/actions/propose
승인 필요 여부 판정 (Guardrail/Autonomy Level).  
승인 필요 시 HITL request 생성 (= pending, status=PROPOSED).

**Request**: 동일 (caseId, actionType, payload)

**Response**
```json
{
  "requiresApproval": true,
  "actionId": 1,
  "guardrailResult": "APPROVAL_REQUIRED"
}
```
- guardrailResult: `ALLOWED` \| `APPROVAL_REQUIRED` \| `DENIED`

### POST /agent-tools/actions/{actionId}/execute
승인 완료된 action만 실행 (status=APPROVED 필수).

**Response**: ActionDetailDto

---

## 정책/가드레일 집행

- **config_profile** 기반: Autonomy Level, Guardrail 규칙, PII handling, saved views scope
- **AUTONOMY_LEVEL** (config_kv): `FULL` \| `APPROVAL_REQUIRED` \| `READ_ONLY`
- actionType별 허용/승인필요/금지 판단: PolicyEngine + GuardrailEvaluateService
- 모든 propose/execute/simulate → audit_event_log 기록 (category=ACTION/INTEGRATION/POLICY)

---

## DDL (V15)

- **agent_action_simulation**: 시뮬레이션 전용 (propose 전 단독 호출)
- **integration_outbox**: SAP 등 외부 연동 이벤트 큐 (플러그 가능)
