# Dashboard Drill-down URL 파라미터 API — BE 응답

> FE 요청 문서: 작업.txt (2026-02-02)  
> 응답 작성: 2026-01-29

---

## 1. 2-1. Top Risk Drivers → Anomalies (기획 준수)

**FE 선택**: (A) Anomalies 연결

**BE 조치**: `links.anomaliesPath`를 `/anomalies?type={riskTypeKey}&range={range}` 형태로 변경

- 예: `/anomalies?type=DUPLICATE_INVOICE&range=24h`
- FE는 `links.anomaliesPath`를 그대로 사용

---

## 2. 2-2. Cases API 파라미터

| Param | 지원 | 비고 |
|-------|------|------|
| assignee | ✅ | assigneeUserId와 동일. string 파싱 후 Long으로 처리 |
| assigneeUserId | ✅ | 기존 지원 |
| slaRisk | ✅ | AT_RISK \| ON_TRACK. assignee별 open case 수 기준 (5초과=AT_RISK) |
| ids | ✅ | `ids=1,2,3` (숫자). `ids=case-1,case-2`는 숫자 부분만 파싱 |

---

## 3. 2-3. Actions API 파라미터

| Param | 지원 | 비고 |
|-------|------|------|
| assignee | ✅ | assigneeUserId 별칭 (string 파싱) |
| focus | ⚪ | FE 전용. BE는 필터 없이 응답. FE에서 해당 actionId 행 하이라이트 |

---

## 4. 2-4. Anomalies API 파라미터

| Param | 지원 | 비고 |
|-------|------|------|
| type | ✅ | anomalyType, driverType 별칭 |
| range | ✅ | 1h, 24h, 7d, 30d 지원 |

---

## 5. 4. 감사 로그 (audit_event_log)

- **DASHBOARD_VIEWED**: BE에서 각 대시보드 위젯 조회 시 `logDashboardViewed`로 기록 중
- **DRILLDOWN_CLICKED**: FE가 drill-down 클릭 시 별도 API 호출하지 않음. BE는 drill-down 대상 API(Cases/Actions/Anomalies/Audit) 호출 시 `event_type=VIEW_LIST` 등으로 기록.  
  - FE에서 "클릭" 자체를 별도 이벤트로 남기려면 `POST /audit/events` 등 ingest API가 필요하며, 현재는 미구현.  
  - **결론**: DASHBOARD_VIEWED로 충분. DRILLDOWN_CLICKED는 추후 요구 시 별도 API 검토.
