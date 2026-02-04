# 공통 Filter DTO 표준 (FE/BE 계약)

> 프론트/백엔드 공통 문서 참조  
> 작성: 2026-01-29

---

## 1. 공통 Query 파라미터

| 파라미터 | 타입 | 기본값 | 비고 |
|----------|------|--------|------|
| range | 1h\|6h\|24h\|7d\|30d | 24h | from/to 없을 때 사용 |
| from, to | ISO-8601 | - | **있으면 range보다 우선** |
| severity | CSV | - | CRITICAL,HIGH,MEDIUM,LOW |
| status | CSV | - | 리소스별 enum |
| q | string | - | 검색 |
| page | int | 0 | 0-based |
| size | int | 20 | |
| sort | string | createdAt,desc | field,dir |

---

## 2. Enum (app_codes SoT)

### Severity
CRITICAL | HIGH | MEDIUM | LOW

### RiskType (driverType / type)
DUPLICATE_INVOICE | BANK_CHANGE_RISK | POLICY_VIOLATION | DATA_INTEGRITY | UNUSUAL_AMOUNT | VENDOR_RISK

### CaseStatus
OPEN | TRIAGE | IN_PROGRESS | RESOLVED | DISMISSED

### AnomalyStatus
NEW | TRIAGED | LINKED_TO_CASE | IGNORED

### ActionStatus
PENDING | APPROVED | REJECTED | EXECUTED | FAILED | ROLLED_BACK

---

## 3. 리소스별 추가 파라미터

### Cases
- caseId, caseKey, driverType(RiskType), status, assigneeUserId
- approvalState: REQUIRES_REVIEW | NONE
- ids (다중)

### Anomalies
- type(RiskType), status, entityId, documentId, bukrs, waers

### Actions
- status, actionType, resourceType, resourceId, requiresApproval

---

## 4. 대시보드 클릭 → drill-down

| 클릭 | 경로 |
|------|------|
| Top Risk Drivers | /anomalies?type={TYPE}&severity=HIGH,CRITICAL&range=24h |
| Action Required row | /cases/{caseId} 또는 /cases?caseKey=CS-xxx |
| Open Cases by Severity | /cases?status=OPEN,TRIAGE&severity=CRITICAL,HIGH&range=24h |
| View All Pending Actions | /actions?requiresApproval=true&status=PENDING&range=24h |
| View Full Audit Log | /audit?range=24h |
