# Drill-down Filter Standard v1.0 — 백엔드 구현 결과

> 공통 계약 문서 기반 SynapseX 백엔드 구현 완료  
> 작성: 2026-01-29

---

## 1. 구현 요약

| 항목 | 상태 | 비고 |
|------|------|------|
| Cases: caseId, needsApproval | ✅ | caseId→ids 매핑, needsApproval→hasPendingAction 별칭 |
| app_codes: TRIAGE | ✅ | V20 migration, TRIAGE→TRIAGED 매핑 |
| Anomalies: confidenceMin, waers, q | ✅ | fi_doc_header 조인 기반 waers 필터 |
| Actions: actionStatus, actionType, requiresApproval | ✅ | status/type 별칭, requiresApproval 필터 |
| Audit: traceId, gatewayRequestId, q | ✅ | 검색 필터 추가 |
| Dashboard: team-snapshot 7d, gatewayRequestId | ✅ | 기본 range=7d, AgentActivityItemDto 확장 |
| sort=field,dir 형식 | ✅ | DrillDownParamUtil.parseSortAndOrder |

---

## 2. API별 변경 사항

### 2.1 Cases API (`GET /api/synapse/cases`)

| 파라미터 | 설명 |
|----------|------|
| `caseId` | 단건 drill-down. ids에 병합되어 필터 적용 |
| `needsApproval` | `hasPendingAction` 별칭. 승인 대기 케이스만 |
| `status=OPEN,TRIAGE` | TRIAGE → TRIAGED 매핑 후 조회 |

### 2.2 Anomalies API (`GET /api/synapse/anomalies`)

| 파라미터 | 설명 |
|----------|------|
| `confidenceMin` | 0~100. score >= confidenceMin/100 |
| `waers` | CSV. fi_doc_header 조인 기반 통화 필터 |
| `q` | belnr, reasonText 검색 |
| `severity` | CSV 다중값 지원 (LOW,MEDIUM,HIGH,CRITICAL) |

### 2.3 Actions API (`GET /api/synapse/actions`)

| 파라미터 | 설명 |
|----------|------|
| `actionStatus` | `status` 별칭 |
| `actionType` | `type` 별칭 |
| `requiresApproval` | true 시 PENDING_APPROVAL 등 승인 대기만 |

### 2.4 Audit API (`GET /api/synapse/audit/events`)

| 파라미터 | 설명 |
|----------|------|
| `traceId` | 정확 일치 |
| `gatewayRequestId` | 정확 일치 |
| `q` | resourceId, traceId, gatewayRequestId 부분 검색 |

### 2.5 Dashboard API

| 엔드포인트 | 변경 |
|------------|------|
| `GET /api/synapse/dashboard/team-snapshot` | 기본 `range=7d` |
| `GET /api/synapse/dashboard/agent-activity` | 응답에 `gatewayRequestId` 추가, `links.auditPath`에 traceId/gatewayRequestId 포함 |

---

## 3. sort 파라미터 형식

- **형식 1**: `sort=createdAt,desc` (한 번에)
- **형식 2**: `sort=createdAt&order=desc` (기존 분리)

둘 다 지원. `sort`에 쉼표가 있으면 `field,dir`로 파싱.

---

## 4. DB Migration

- **V20__add_triage_to_case_status.sql**: `CASE_STATUS` 그룹에 `TRIAGE` 코드 추가

---

## 5. FE 확인 사항

1. **status=TRIAGE**: BE에서 TRIAGED로 매핑. 기존 TRIAGED 사용 클라이언트와 호환.
2. **caseId vs ids**: `caseId` 단건 또는 `ids` 다건 모두 지원. `caseId`는 내부적으로 `ids`에 병합.
3. **auditPath**: Agent Stream row의 `links.auditPath`에 `traceId` 또는 `gatewayRequestId`가 있으면 drill-down 시 해당 파라미터 포함.

---

## 6. 테스트 권장

```
# Cases
GET /api/synapse/cases?caseId=1
GET /api/synapse/cases?needsApproval=true
GET /api/synapse/cases?status=OPEN,TRIAGE

# Anomalies
GET /api/synapse/anomalies?confidenceMin=80&waers=KRW,USD
GET /api/synapse/anomalies?q=12345

# Actions
GET /api/synapse/actions?actionStatus=PENDING_APPROVAL
GET /api/synapse/actions?requiresApproval=true

# Audit
GET /api/synapse/audit/events?traceId=xxx
GET /api/synapse/audit/events?gatewayRequestId=yyy

# Dashboard
GET /api/synapse/dashboard/team-snapshot  # range=7d 기본
GET /api/synapse/dashboard/agent-activity # gatewayRequestId 응답 확인
```
