# Phase3 Execute(sim) API 스펙

**목적**: Execute(sim)이 "어떤 proposal을 실행했는지" 서버가 확실히 알 수 있게 스키마/DB/감사 로그를 고정.  
**운영**: Phase3에서는 simulate=true만 우선(실제 실행은 Phase4 이후).

---

## 1. 표준안

### 권장(A) Proposal 기반 Execute
- FE가 execute 요청에 **proposalId** 전달.
- BE는 proposalId로 proposal 조회 → actionType/payload/rationale 확정 → 정합 검증 → 실행 기록 → simulate 결과 저장.

### 대안(B) ActionType+Payload 기반 Execute
- FE가 **actionType + payload** 직접 전달.
- BE는 동일 runId/caseId/actionType의 APPROVED proposal이 있으면 연결; 없으면 proposal_id=null로 실행 기록.

---

## 2. API

### 2.1 POST /api/synapse/actions/execute

**Request (권장 A)**
```json
{
  "caseId": 85114,
  "runId": "6da9d686-a126-4d39-91ac-dc111254fc01",
  "proposalId": "prp_01HXYZ...",
  "simulate": true,
  "gatewayRequestId": "gw-req-123"
}
```

**Request (대안 B)**
```json
{
  "caseId": 85114,
  "runId": "6da9d686-a126-4d39-91ac-dc111254fc01",
  "actionType": "PAYMENT_BLOCK",
  "payload": { "bukrs": "1000", "belnr": "1900000005", "gjahr": "2024" },
  "simulate": true,
  "gatewayRequestId": "gw-req-123"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| caseId | Long | ✅ | 케이스 ID |
| runId | UUID | B 시 필수 | 분석 run ID |
| proposalId | UUID | A 시 필수 | 액션 제안 ID |
| actionType | String | B 시 필수 | PAYMENT_BLOCK, REQUEST_INFO 등 |
| payload | JsonNode | B 선택 | 액션별 payload |
| simulate | Boolean | - | true=시뮬(기본), null이면 true |
| gatewayRequestId | String | - | 멱등/추적용 |

**Response (성공)** — `ApiResponse<ProposalExecuteResponseDto>`
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "actionId": "execution_id(uuid 문자열)",
    "proposalId": "prp_01HXYZ...",
    "executionId": "...",
    "status": "COMPLETED",
    "mode": "SIMULATION",
    "executedAt": "2026-02-10T...",
    "simulation": {
      "result": "BLOCK_WOULD_BE_APPLIED",
      "affected": 1,
      "details": { "rule": "PAYMENT_BLOCK_RULE_V1" }
    }
  },
  "success": true
}
```

**Response (실패)** — 유효성/비즈니스 오류 시 `ApiResponse` error, data에 error 상세 가능.

---

## 3. DB (case_action_execution)

- **V37**: execution_id(UUID PK), tenant_id, case_id, run_id, proposal_id, mode, status, result_json, error_message, executed_by, executed_at, created_at.
- **V38**: gateway_request_id, UNIQUE(tenant_id, gateway_request_id) WHERE gateway_request_id IS NOT NULL.
- **V39**: request_json(JSONB), action_type(VARCHAR(64)), proposal_id nullable; ix_case_action_execution_tenant_case_run.

실행 시 request_json에 요청 본문 저장, action_type은 proposal에서 복사 또는 B 시 직접 저장.

---

## 4. 감사 로그

- **event_type**: `ACTION_EXECUTE_SIM`
- **resource_type**: CASE, **resource_id**: caseId
- **after_json**: result_json 최소 포함
- **evidence_json**: runId, proposalId, actionType, simulate(, gateway_request_id)
- **event_category**: CASE

---

## 5. 정책

- 동일 runId + 동일 proposalId에서 simulate 실행은 **기본 허용**(이력 누적).
- FE에서 로딩 중 중복 클릭 방지(disable). UNIQUE로 1회 강제는 Phase4에서 결정.
- gatewayRequestId 있으면 동일 ID 재요청 시 **기존 결과 반환**(멱등).

---

## 6. 기존 엔드포인트 호환

- `POST /api/synapse/cases/{caseId}/actions/execute` — body에 proposalId (기존 유지).
- `POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/execute` — path로 proposalId. **Body(선택)**: `{ runId?, simulate?, gatewayRequestId? }` → BE에서 **runId 일치 검증**·**gatewayRequestId 멱등**에 활용.
- `POST /api/synapse/actions/execute` — **본 스펙** (body에 caseId 포함, A 또는 B).
