# GET /api/synapse/dashboard/summary — 응답 항목별 데이터 소스

**엔드포인트:** `GET /api/synapse/dashboard/summary`  
**Controller:** `DashboardController.getSummary()`  
**Service:** `DashboardQueryService.getSummary()`

---

## 응답 항목별 상세

| 응답 필드 | 데이터 소스 | 테이블/컬럼 | 설명 |
|-----------|-------------|-------------|------|
| `tenantId` | 요청 헤더 | - | `X-Tenant-ID` 그대로 반환 |
| `asOf` | 런타임 | - | 조회 시점 `Instant.now()` |
| `financialHealthIndex` | **하드코딩** | - | 고정값 `87` (0~100 재무 건전성 지수) |
| `financialHealthTrend` | **하드코딩** | - | 고정값 `2.3` (추세 %) |
| `openCasesBySeverity.critical` | DB 집계 | `dwp_aura.agent_case` | `status` IN (OPEN, ACTIVE, IN_PROGRESS, IN_REVIEW, TRIAGED) AND `severity` = 'CRITICAL' 건수 |
| `openCasesBySeverity.high` | DB 집계 | `dwp_aura.agent_case` | 동일 status 조건, `severity` = 'HIGH' 건수 |
| `openCasesBySeverity.medium` | DB 집계 | `dwp_aura.agent_case` | 동일 status 조건, `severity` = 'MEDIUM' 건수 |
| `openCasesBySeverity.low` | DB 집계 | `dwp_aura.agent_case` | 동일 status 조건, `severity` = 'LOW' 건수 |
| `aiActionSuccessRate` | DB 집계 | `dwp_aura.agent_action` | 최근 7일 `created_at` 이후, status IN (SUCCEEDED, SUCCESS, EXECUTED, DONE) / (성공+실패) × 100 |
| `aiActionSuccessTrend` | **하드코딩** | - | `aiActionSuccessRate`가 있으면 `1.2`, 없으면 null |
| `estimatedPreventedLoss` | DB 집계 | `dwp_aura.agent_case` | 최근 90일 `created_at` 이후, `evidence_json->>'prevented_loss'` 합계 |
| `preventedLossTrend` | **하드코딩** | - | 고정값 `15.5` |
| `agentLiveStatus` | DB 집계 | `dwp_aura.audit_event_log` | 최근 5분 `created_at`, `actor_type`='AGENT' OR `event_category`='INTEGRATION' |
| `pendingApprovals` | DB 집계 | `dwp_aura.agent_action` | `status` IN (PLANNED, PENDING, REVIEW, WAITING_APPROVAL, PROPOSED, PENDING_APPROVAL) 건수 |
| `slaAtRisk` | **하드코딩** | - | 고정값 `0` |
| `avgLeadTime` | DB 집계 | `dwp_aura.agent_case` | open 케이스 기준 `created_at` ~ `updated_at`(미종료 시 now) 평균 시간(시간 단위) |
| `backlogCount` | DB 집계 | `dwp_aura.agent_case` | open 케이스 총 건수 (= critical + high + medium + low) |
| `links.casesPath` | **하드코딩** | - | `"/cases?status=OPEN"` |
| `links.actionsPath` | **하드코딩** | - | `"/actions?status=PENDING_APPROVAL"` |
| `links.auditPath` | **하드코딩** | - | `"/audit?category=ACTION"` |

---

## agentLiveStatus 계산 로직

| 조건 | 반환값 |
|------|--------|
| 최근 5분 audit_event_log 건수 = 0 | `"idle"` |
| 최근 5분 내 outcome IN (FAIL, FAILED, ERROR) 존재 | `"processing"` |
| 그 외 | `"active"` |

---

## 참조 테이블 요약

| 테이블 | 스키마 | 용도 |
|--------|--------|------|
| `agent_case` | `dwp_aura` | openCasesBySeverity, estimatedPreventedLoss, avgLeadTime, backlogCount |
| `agent_action` | `dwp_aura` | aiActionSuccessRate, pendingApprovals |
| `audit_event_log` | `dwp_aura` | agentLiveStatus |

---

## 하드코딩 항목 (실데이터 미사용)

- `financialHealthIndex`, `financialHealthTrend`
- `aiActionSuccessTrend`, `preventedLossTrend`
- `slaAtRisk`
- `links.*`

위 항목은 향후 KPI/정책 테이블 연동 시 실데이터로 교체 가능.
