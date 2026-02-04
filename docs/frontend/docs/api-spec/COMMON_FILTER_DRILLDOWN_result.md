# 공통 Filter DTO 표준 — BE 구현 결과

> 프롬프트 A (품질업 3종 + Filter 표준 계약 고정) 구현 완료  
> 작성: 2026-01-29

---

## 1. 구현 완료 항목

### A1) 공통 필터 계약 고정

| 항목 | 구현 |
|------|------|
| range \| from/to 규칙 | `DrillDownParamUtil.resolve` |
| **range + from/to 동시 시 400** | `DrillDownParamUtil.validateRangeExclusive` 추가 |
| severity/status 다중값 | `DrillDownParamUtil.parseMulti` |
| sort=field,dir 형식 | `DrillDownParamUtil.parseSortAndOrder` |
| page 기본 0 (0-based) | Case/Anomaly/Action Controller 모두 `defaultValue="0"` |
| size 기본 20 | 유지 |
| Cases approvalState | `REQUIRES_REVIEW` → hasPendingAction=true, `NONE` → false |
| Audit category=UI | `AuditEventConstants.CATEGORY_UI` 추가 |

### A2) POST /api/synapse/audit/ui-events

| 항목 | 구현 |
|------|------|
| 경로 | `POST /api/synapse/audit/ui-events` |
| Header | X-Tenant-ID (필수), X-User-ID (선택) |
| Body | eventType, targetRoute, query, metadata |
| event_category | UI |
| evidence_json | query, metadata, target_route 저장 |
| outcome | SUCCESS, severity=INFO |

**eventType 예시**: DASHBOARD_DRILLDOWN, DASHBOARD_REVIEW_CASE, DASHBOARD_VIEW_AUDIT, FILTER_APPLY

### A3) team-snapshot, agent-stream

| API | 기본 range | 비고 |
|-----|------------|------|
| GET /api/synapse/dashboard/team-snapshot | 7d | analystName, openCases, slaRisk, avgLeadTime |
| GET /api/synapse/dashboard/agent-activity | 6h | agent-stream 별칭 동일 |
| GET /api/synapse/dashboard/agent-stream | 6h | agent-activity와 동일 응답 |

AgentActivityItemDto: traceId, gatewayRequestId 포함 (audit_event_log 기반 시)

### A4) GET /api/synapse/audit/events category=UI

- `category=UI` 또는 `eventCategory=UI` 파라미터로 UI 이벤트 필터링 가능
- POST /ui-events로 기록된 이벤트 조회

---

## 2. API 변경 요약

### Cases (GET /api/synapse/cases)

- `approvalState`: REQUIRES_REVIEW | NONE (Action Required용)
- `page`: 기본 0 (0-based)
- range + from/to 동시 제공 시 400

### Anomalies (GET /api/synapse/anomalies)

- `page`: 기본 0 (0-based)
- range + from/to 동시 제공 시 400

### Actions (GET /api/synapse/actions)

- `page`: 기본 0 (0-based)
- range + from/to 동시 제공 시 400

### Audit (GET /api/synapse/audit/events)

- range + from/to 동시 제공 시 400
- category=UI 필터 지원 (기존 다중 category 로직)

---

## 3. FE 연동 참고

1. **URL 쿼리**: range와 from/to 중 하나만 사용. 동시 전달 시 400.
2. **page**: 0-based. 첫 페이지 = page=0.
3. **UI 이벤트**: 대시보드 클릭/필터 적용 시 `POST /api/synapse/audit/ui-events` 호출 권장 (선택).
4. **approvalState**: "Action Required" 필터 시 `approvalState=REQUIRES_REVIEW` 사용.
