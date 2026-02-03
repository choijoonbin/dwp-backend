# Drill-down Filter Query Standard v1.0 — BE 갭 분석

> 공통 계약 문서 대비 현재 SynapseX 구현 상태  
> 작성: 2026-02-03

---

## 1. 요약

| 영역 | 현재 상태 | 갭 |
|------|-----------|-----|
| 공통 Query 규칙 | ✅ 대부분 준수 | range/from 동시 400 검증 없음 |
| Enum (app_codes) | ✅ V18 SoT | 일부 spec 값 매핑 필요 |
| Cases/Anomalies/Actions API | ✅ 구현됨 | 일부 파라미터 추가 필요 |
| Dashboard API | ✅ 구현됨 | 필드명/경로 차이 |
| Audit 로그 | ⚠️ 부분 | category=UI 미적용 |
| Validation (400) | ⚠️ 부분 | enum 외 값 400 미적용 |

---

## 2. 공통 규칙 (0절)

| 규칙 | 현재 | 비고 |
|------|------|------|
| X-Tenant-ID 헤더만 | ✅ | 쿼리 파라미터 없음 |
| 다중값 comma | ✅ | DrillDownParamUtil.parseMulti |
| range \| from/to | ✅ | DrillDownParamUtil.resolve |
| range: 1h\|6h\|24h\|7d\|30d\|90d | ✅ | |
| page 0-based | ⚠️ | **현재 1-based** (page=1 → offset 0) |
| sort=field,dir | ⚠️ | **현재 sort + order 분리** |
| q 검색 | ✅ | Cases/Anomalies/Actions 지원 |

**갭**: range와 from/to 동시 제공 시 400 반환 미구현. page는 FE 계약상 1-based 유지 중.

---

## 3. Enum 매핑 (1절)

### Severity
| Spec | BE app_codes | 비고 |
|------|--------------|------|
| LOW, MEDIUM, HIGH, CRITICAL | ✅ 동일 | INFO 추가됨 |

### CaseStatus
| Spec | BE app_codes | 매핑 |
|------|--------------|------|
| OPEN | OPEN | ✅ |
| TRIAGE | TRIAGED | **TRIAGE → TRIAGED** |
| WAITING_APPROVAL | PENDING_APPROVAL | **WAITING_APPROVAL → PENDING_APPROVAL** |
| IN_PROGRESS | IN_PROGRESS | ✅ |
| RESOLVED | RESOLVED | ✅ |
| DISMISSED | DISMISSED | ✅ |

**갭**: spec의 TRIAGE, WAITING_APPROVAL을 BE 값으로 매핑하거나 app_codes에 추가 검토.

### CaseType / AnomalyType (Top Risk Drivers)
| Spec | BE DRIVER_TYPE | 비고 |
|------|----------------|------|
| DUPLICATE_INVOICE | ✅ | |
| BANK_CHANGE_RISK | ✅ | |
| POLICY_VIOLATION | ✅ | |
| DATA_INTEGRITY | ✅ | |
| OVERDUE_RISK | ❌ | **미정의** |
| — | THRESHOLD_BREACH, ANOMALY, BANK_CHANGE, DEFAULT | BE 추가값 |

### ActionStatus
| Spec | BE app_codes | 매핑 |
|------|--------------|------|
| PENDING_APPROVAL | ✅ | |
| QUEUED | ✅ | |
| EXECUTING | ✅ | |
| SUCCEEDED | SUCCESS, EXECUTED | **SUCCEEDED → SUCCESS/EXECUTED** |
| FAILED | FAILED | ✅ |
| CANCELLED | CANCELLED | ✅ |

### ActionType
| Spec | BE | 비고 |
|------|-----|------|
| PAYMENT_BLOCK, UNBLOCK 등 | agent_action.action_type | 별도 app_code 그룹 없음, 자유 문자열 가능 |

### Outcome
| Spec | BE | 비고 |
|------|-----|------|
| SUCCESS, FAILURE, PARTIAL, SKIPPED | audit_event_log.outcome | SUCCESS, FAILED, DENIED, NOOP 사용 중 |

---

## 4. 라우트별 Query 파라미터 (2절)

### /cases
| Spec | 현재 | 비고 |
|------|------|------|
| severity, status, type | ✅ | type=driverType/caseType |
| assigneeUserId | ✅ | |
| ownerTeam | ❌ | **미지원** |
| bukrs | ✅ | company/bukrs |
| waers | ✅ | |
| vendorId, customerId | ❌ | **미지원** |
| amountMin, amountMax | ❌ | **미지원** |
| range, from/to, page, size, sort, q | ✅ | |

### /anomalies
| Spec | 현재 | 비고 |
|------|------|------|
| severity, type | ✅ | |
| bukrs, waers | ✅ | company |
| vendorId, docKey | ❌ | **미지원** |
| range, page, size, sort, q | ✅ | |

### /actions
| Spec | 현재 | 비고 |
|------|------|------|
| severity, status, type | ✅ | |
| requiresApproval | ❌ | **미지원** |
| actorType | ❌ | **미지원** |
| range, page, size, sort, q | ✅ | |

---

## 5. Dashboard API (Backend Prompt)

### Team Snapshot

| Spec 필드 | 현재 필드 | 비고 |
|-----------|-----------|------|
| assigneeUserId | analystUserId | ✅ 동일 개념 |
| assigneeName | analystName | ✅ (com_users Feign 연동) |
| role | title | "Analyst" 하드코딩 |
| openCases | openCases | ✅ |
| slaRiskCount | slaRisk (AT_RISK/ON_TRACK) | **다름**: spec은 count, 현재는 레이블 |
| avgLeadTimeHours | avgLeadTimeHours | ✅ |

**집계 기준**:
- Spec: status in (OPEN, TRIAGE, IN_PROGRESS, WAITING_APPROVAL)
- 현재: OPEN, ACTIVE, IN_PROGRESS, IN_REVIEW, TRIAGED
- Spec: sla_due_at, is_sla_risk — **agent_case에 해당 컬럼 없음**
- avgLeadTimeHours: created_at 기준 계산 ✅

### Agent Execution Stream

| Spec | 현재 | 비고 |
|------|------|------|
| 경로 agent-execution-stream | agent-activity, agent-stream | **별칭 추가 가능** |
| eventType | stage | SCAN, DETECT, ANALYZE 등 |
| message | message | ✅ |
| caseId, actionId | ✅ | |
| traceId | ✅ | (audit fallback 시 traceId 전달) |
| gatewayRequestId | ❌ | **AgentActivityItemDto에 없음** |

**데이터 소스**: agent_activity_log 우선, 없으면 audit_event_log (AGENT, ACTION, INTEGRATION) ✅

---

## 6. Audit 로그 (Audit 로그 연동)

| Spec | 현재 | 비고 |
|------|------|------|
| category=UI, type=VIEW_DASHBOARD | category=DASHBOARD, type=DASHBOARD_VIEWED | **다름** |
| category=UI, type=NAV_DRILLDOWN | 미구현 | 옵션 |
| category=UI, type=VIEW_TEAM_SNAPSHOT | DASHBOARD_VIEWED, resource=team-snapshot | **다름** |
| category=UI, type=VIEW_AGENT_STREAM | DASHBOARD_VIEWED, resource=agent-activity | **다름** |
| channel=UI | channel=API | 대시보드 호출이 API 경유 |
| evidence_json (필터 정보) | ✅ | filters 맵 전달 |

**갭**: spec은 category=UI를 요구하나, 현재는 DASHBOARD 사용. 기획/감사 요건에 따라 UI vs DASHBOARD 정리 필요.

---

## 7. Validation 규칙

| 규칙 | 현재 | 비고 |
|------|------|------|
| range와 from/to 동시 제공 시 400 | ❌ | 무시만 함 (from/to 우선) |
| enum 외 값 400 | ⚠️ | DrillDownCodeResolver는 **무시** (필터에서 제외), 400 아님 |

---

## 8. 대시보드 클릭 경로 (3절)

| 클릭 | Spec 경로 | 현재 links | 비고 |
|------|-----------|------------|------|
| KPI Open Cases | /cases?severity=CRITICAL,HIGH&status=OPEN,TRIAGE&range=24h | links.casesPath | ✅ |
| AI Action Success | /actions?status=SUCCEEDED,FAILED&range=7d | links.actionsPath | SUCCEEDED 매핑 |
| Top Risk Drivers | /anomalies?type=DUPLICATE_INVOICE&range=24h | links.anomaliesPath | ✅ |
| Action Required | /cases/{caseId} | reviewPath=/cases/{id} | ✅ caseId 포함 |
| Team Snapshot row | /cases?assigneeUserId={id}&range=7d | links.casesPath | ✅ |
| Agent Stream | /audit?category=AGENT&type=EXECUTION_EVENT&range=24h | links.auditPath | eventType 차이 |

---

## 9. SQL/인덱스

| Spec | 현재 | 비고 |
|------|------|------|
| agent_case(tenant_id, created_at, status, severity, assignee_user_id) | ix_agent_case_tenant_status_severity 등 | 부분 존재 |
| audit_event_log(tenant_id, event_category, event_type, created_at) | ix_audit_event_log_tenant_category_type | ✅ |

---

## 10. 권장 조치 (우선순위)

### P0 (계약 준수)
1. **range + from/to 동시 시 400** — DrillDownParamUtil 또는 Controller에서 검증
2. **Action Required caseId** — ✅ 이미 포함 (caseId, caseNumber, reviewPath)
3. **Top Risk Drivers type** — ✅ links.anomaliesPath에 type 포함

### P1 (spec 정합성)
4. **AgentActivityItemDto에 gatewayRequestId** 추가 (audit_event_log에 존재)
5. **agent-execution-stream** 별칭 추가 (선택)
6. **Enum 매핑**: TRIAGE↔TRIAGED, WAITING_APPROVAL↔PENDING_APPROVAL, SUCCEEDED↔SUCCESS 문서화

### P2 (확장)
7. ownerTeam, vendorId, customerId, amountMin/Max, requiresApproval, actorType — 요구 시 추가
8. slaRiskCount (sla_due_at, is_sla_risk) — agent_case 스키마 확장 필요
9. Audit category=UI vs DASHBOARD — 기획 확정 후 적용

---

## 11. 샘플 요청/응답

기존 `DRILLDOWN_CONTRACT_result.md` 및 `docs/frontend/docs/api-spec/` 문서 참조.  
추가 필요 시 `SYNAPSE_DASHBOARD_API.md` 별도 작성.
