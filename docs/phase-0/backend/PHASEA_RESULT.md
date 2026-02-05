# Phase A — Case E2E HITL Audit 결과

> 완료일: 2026-01-29

---

## 1. 완료 항목

### 1.1 Case Detail API
- **GET** `/api/synapse/cases/{caseId}` ✅
- 응답: caseId, status, severity, created_at, updated_at, assignee_user_id, evidence, reasoning, action

### 1.2 Evidence Pack (3종 이상)
- **documentOrOpenItem**: DOCUMENT | OPEN_ITEM (docKey: bukrs-belnr-gjahr)
- **reversalChainSummary**: nodeDocKeys, edgeCount (lineage)
- **relatedPartyIds**: bp_party 기반 vendor/customer

### 1.3 HITL APIs
| 메서드 | 경로 | 비고 |
|--------|------|------|
| POST | /actions | createAction (propose) |
| POST | /actions/{id}/approve | idempotent (이미 APPROVED → 200 no-op) |
| POST | /actions/{id}/reject | idempotent (이미 CANCELED → 200 no-op) |
| POST | /actions/{id}/resume | **추가** execute와 동일 (승인된 proposal 실행) |
| POST | /actions/{id}/execute | APPROVED만 실행 가능 |

### 1.4 중복 제안 방지
- 동일 caseId + actionType에 PROPOSED/PENDING_APPROVAL/APPROVED/PLANNED 존재 시 **409 DUPLICATE_ENTITY**

### 1.5 Audit 이벤트
- ACTION_PROPOSED, APPROVED, REJECTED, EXECUTE, FAILED
- trace_id 전파: X-Trace-Id 헤더 → audit_event_log.trace_id

### 1.6 Audit 조회 API
- **GET** `/api/synapse/audit/events` (traceId, resourceType, resourceId 필터 지원)

---

## 2. curl 재현 예시

```bash
# Case Detail
curl -H "X-Tenant-ID: 1" -H "X-User-ID: 1" "http://localhost:8080/api/synapse/cases/1"

# Evidence (Case Detail 응답 내 evidence 필드)
# documentOrOpenItem, reversalChainSummary, relatedPartyIds 포함

# Propose
curl -X POST -H "X-Tenant-ID: 1" -H "X-User-ID: 1" \
  -H "Content-Type: application/json" \
  -d '{"caseId":1,"actionType":"PAYMENT_CLEAR","payload":{}}' \
  "http://localhost:8080/api/synapse/actions"

# Approve
curl -X POST -H "X-Tenant-ID: 1" -H "X-User-ID: 1" \
  "http://localhost:8080/api/synapse/actions/1/approve"

# Reject
curl -X POST -H "X-Tenant-ID: 1" -H "X-User-ID: 1" \
  "http://localhost:8080/api/synapse/actions/1/reject"

# Resume (= Execute)
curl -X POST -H "X-Tenant-ID: 1" -H "X-User-ID: 1" \
  "http://localhost:8080/api/synapse/actions/1/resume"

# Audit 조회
curl -H "X-Tenant-ID: 1" "http://localhost:8080/api/synapse/audit/events?resourceType=AGENT_CASE&resourceId=1"
```

---

## 3. 검증
- tenant 섞임 0: X-Tenant-ID 필수, Repository tenant 조건
- RBAC 위반 0: /api/admin/** 외 일반 API는 tenant scope
- audit 누락 0: approve/reject/execute 시 audit_event_log 기록, trace_id 전파
