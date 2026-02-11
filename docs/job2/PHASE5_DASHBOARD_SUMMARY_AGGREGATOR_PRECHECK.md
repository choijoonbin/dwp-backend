# Phase 5: Dashboard Summary Aggregator — Pre-Check

## Pre-Check (MUST ANSWER)

### 1. 대시보드 진입 시 발생하는 이 대량 조회 쿼리가 성능상 문제가 없는지(Index 활용 여부) 확인했습니까?

**답변:**

| 소스 | 쿼리 | 인덱스 | 비고 |
|------|------|--------|------|
| **analytics_kpi_daily** | tenant_id + ymd = today, order by metric_key | `ix_analytics_kpi_tenant_ymd (tenant_id, ymd)` (V14) | 1일 1tenant → 소량 행, 인덱스 활용 |
| **agent_activity_log** | tenant_id + occurred_at DESC, limit 10 | `ix_agent_activity_log_tenant_occurred (tenant_id, occurred_at DESC)` (V19) | 정렬·limit에 적합 |
| **recon_result** | tenant_id + status = 'FAIL', order by result_id DESC, limit 5 / count | `ix_recon_result_tenant_run (tenant_id, run_id)` (V14) | status 조건은 인덱스 미포함 → tenant_id로 범위 축소 후 애플리케이션/소량 스캔. FAIL 건이 많지 않으면 수용 가능. 필요 시 `(tenant_id, status)` 복합 인덱스 추가 검토 |

**결론:** KPI·activity 로그는 기존 인덱스로 충분. recon_result는 tenant 단위로 스캔하므로 데이터량이 크면 추후 `(tenant_id, status)` 인덱스 추가 권장.

---

### 2. `tenant_id` 격리가 모든 집계 로직에 엄격히 적용되어 있나요?

**답변:** **예.**

- **analytics_kpi_daily**: `findByTenantIdAndYmdBetween(tenantId, today, today)` → tenant_id 필수.
- **agent_activity_log**: `findByTenantIdAndOccurredAtAfter(tenantId, since, pageable)` → tenant_id 필수.
- **recon_result**: `findByTenantIdAndStatus(tenantId, "FAIL", pageable)`, `countByTenantIdAndStatus(tenantId, "FAIL")` → 모든 조회에 tenant_id 조건 적용.

Controller에서 `X-Tenant-ID` 헤더로 tenantId를 받아 서비스에 전달하며, 서비스는 해당 tenantId로만 조회합니다.

---

## 구현 요약

- **Endpoint**: `GET /api/v1/synapse/dashboard/summary` → `SynapseDashboardSummaryDto`
- **DTO**: `SynapseDashboardSummaryDto` (asOf, kpiDaily, recentActivity, reconFail)
- **Repository**: `ReconResultRepository`에 `countByTenantIdAndStatus`, `findByTenantIdAndStatusOrderByResultIdDesc` 추가
- **Service**: `DashboardQueryService.getSynapseDashboardSummary(tenantId)` — KPI(오늘·4종), 활동(10건·reasoning), recon FAIL(건수+5건)
