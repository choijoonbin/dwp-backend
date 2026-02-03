# Drill-down 계약 + 대시보드 클릭 API — 구현 결과

> FE/Aura 전달 사항 포함  
> 최종 업데이트: 2026-01-29

---

## 1. 공통 Query Param 표준

### 1-1. 페이징/정렬

| Param | Type | Default | 설명 |
|-------|------|--------|------|
| page | int | 1 | 1-based |
| size | int | 20 | max 200 |
| sort | string | createdAt | 정렬 필드 |
| order | enum | desc | asc \| desc |

### 1-2. 기간 필터 (range vs from/to)

| Param | Type | 설명 |
|-------|------|------|
| range | enum | 1h \| 6h \| 24h \| 7d \| 30d \| 90d |
| from | ISO8601 | range 없을 때 사용 |
| to | ISO8601 | range 없을 때 사용 (없으면 now) |

**규칙**: range가 있으면 from/to 무시. range 없으면 from/to 사용 (from 없으면 to-24h).

### 1-3. 검색/식별자

| Param | Type | 설명 |
|-------|------|------|
| q | string | 부분일치 검색 |
| ids | string | comma-separated (예: ids=1,2,3) |

### 1-4. Tenant Scope (company/currency)

| Param | Type | 설명 |
|-------|------|------|
| company | string | comma-separated BUKRS |
| currency | string | comma-separated (추후) |

**참고**: Scope 검증(policy_scope_company 교집합)은 Phase 2에서 적용 예정.

---

## 2. 리소스별 추가 필터

### GET /api/synapse/cases

| Param | 설명 |
|-------|------|
| status | CaseStatus, multi (comma) |
| severity | Severity, multi |
| driverType | DriverType (caseType 별칭) |
| assigneeUserId | Team Snapshot row 클릭 |
| caseKey | CS-2026-0001 형식 |
| documentKey | 문서 drill-down |
| hasPendingAction | (추후) |

### GET /api/synapse/actions

| Param | 설명 |
|-------|------|
| status | ActionStatus, multi |
| severity | (case join) |
| caseId | 케이스별 조치 |
| actionType | (추후) |

### GET /api/synapse/anomalies

| Param | 설명 |
|-------|------|
| status | AnomalyStatus, multi |
| severity | multi |
| driverType | Top Risk Drivers 클릭 |
| caseId | (추후) |

---

## 3. 응답 공통 스키마

```json
{
  "status": "SUCCESS",
  "data": {
    "items": [...],
    "data": [...],
    "total": 123,
    "pageInfo": { "page": 1, "size": 20, "hasNext": true },
    "sort": "createdAt",
    "order": "desc",
    "filtersApplied": {
      "range": "24h",
      "status": ["OPEN", "IN_REVIEW"],
      "severity": ["CRITICAL", "HIGH"],
      "company": ["1000"]
    }
  }
}
```

- `data` / `items`: 동일 (FE 호환)
- `filtersApplied`: 현재 적용된 필터 (배지/칩 표시용)

---

## 4. 대시보드 클릭 → API 필터 매핑

| 클릭 대상 | API 호출 예시 |
|-----------|---------------|
| Open Cases by Severity (CRITICAL) | `/cases?range=24h&severity=CRITICAL,HIGH&status=OPEN,IN_REVIEW` |
| Action Required row | `/cases?caseKey=CS-2026-0001` 또는 `/cases?ids=123` |
| View All Pending Actions | `/actions?range=24h&status=PENDING,QUEUED&severity=CRITICAL,HIGH` |
| Top Risk Drivers (Duplicate Invoices) | `/cases?range=24h&driverType=DUPLICATE_INVOICE&severity=HIGH,CRITICAL` |
| Team Snapshot row (Analyst) | `/cases?range=24h&assigneeUserId=11001&status=OPEN,IN_REVIEW` |
| View Full Audit Log | `/audit/events?range=6h&category=AGENT,CASE,ACTION` |

---

## 5. API 엔드포인트

| API | 경로 | 비고 |
|-----|------|------|
| Cases | GET /api/synapse/cases | range, from, to, ids, caseKey, driverType, company 지원 |
| Actions | GET /api/synapse/actions | (기존 파라미터 유지, 추후 확장) |
| Anomalies | GET /api/synapse/anomalies | (기존 파라미터 유지, 추후 확장) |
| Team Snapshot | GET /api/synapse/dashboard/team-snapshot?range=24h | links.actionsPath 포함 |
| Agent Stream | GET /api/synapse/dashboard/agent-stream?limit=50&range=6h | agent-activity와 동일 |
| Agent Activity | GET /api/synapse/dashboard/agent-activity | agent-stream 별칭 |
| Audit | GET /api/synapse/audit/events | range, eventCategory(multi), eventType(multi) 지원 |

---

## 6. FE 전달 사항

1. **경로 prefix**: BE는 `/cases`, `/audit` 등 반환. FE에서 `/synapse` 추가.
2. **page**: 1-based (기본 1)
3. **filtersApplied**: 응답에 포함. "현재 필터" 배지/칩 표시용.
4. **data/items**: 동일 값. FE는 `data` 또는 `items` 사용 가능.
5. **sort**: `sort=createdAt,desc` (Audit API)

---

## 7. Aura 전달 사항

- 변경 없음 (기존 Redis Pub/Sub, audit_event_log 규격 유지)

---

## 8. Phase 2 완료 (2026-01-29)

- **Tenant Scope**: Cases/Actions/Anomalies에서 `company` 파라미터 시 `ScopeEnforcementService.resolveCompanyFilter` 적용. scope 밖 요청 시 400.
- **app_code 기반 Enum SoT**: `DrillDownCodeResolver`로 status/severity/driverType 등 app_codes 검증. V18 마이그레이션.
- **/actions, /anomalies 공통 파라미터**: range, from, to, ids, company, status(multi), severity(multi), order, filtersApplied 지원.
- **hasPendingAction, documentKey**: /cases에서 documentKey(bukrs-belnr-gjahr), hasPendingAction(PENDING_APPROVAL 등) 필터 구현.
