# 모니터링 API 0 응답 문제 진단 가이드

## 문제 상황
모니터링 API들이 모두 0을 반환하는 경우

## 확인 사항

### 1. 데이터베이스에 실제 데이터가 있는지 확인

다음 SQL 쿼리로 데이터 존재 여부를 확인하세요:

```sql
-- 테넌트 확인
SELECT tenant_id, COUNT(*) as total FROM com_tenants GROUP BY tenant_id;

-- PageViewEvent 데이터 확인
SELECT tenant_id, COUNT(*) as pv_count, 
       MIN(created_at) as first_pv, MAX(created_at) as last_pv
FROM sys_page_view_events 
GROUP BY tenant_id;

-- 특정 기간 데이터 확인
SELECT tenant_id, COUNT(*) as pv_count
FROM sys_page_view_events 
WHERE tenant_id = 1 
  AND created_at BETWEEN '2026-01-20 04:27:00' AND '2026-01-21 04:27:00'
GROUP BY tenant_id;

-- PageViewDailyStat 확인
SELECT tenant_id, stat_date, SUM(pv_count) as total_pv
FROM sys_page_view_daily_stats
WHERE tenant_id = 1
  AND stat_date BETWEEN '2026-01-20' AND '2026-01-21'
GROUP BY tenant_id, stat_date
ORDER BY stat_date;

-- EventLog 확인
SELECT tenant_id, COUNT(*) as event_count
FROM sys_event_logs
WHERE tenant_id = 1
  AND occurred_at BETWEEN '2026-01-20 04:27:00' AND '2026-01-21 04:27:00'
GROUP BY tenant_id;

-- ApiCallHistory 확인
SELECT tenant_id, COUNT(*) as api_count
FROM sys_api_call_histories
WHERE tenant_id = 1
  AND created_at BETWEEN '2026-01-20 04:27:00' AND '2026-01-21 04:27:00'
GROUP BY tenant_id;
```

### 2. 파라미터 파싱 확인

애플리케이션 로그에서 다음 로그를 확인하세요:

```
DEBUG ... AdminMonitoringController : getSummary 호출: tenantId=?, from=?, to=?
DEBUG ... AdminMonitoringController : getSummary 파라미터 적용: tenantId=?, defaultFrom=?, defaultTo=?
DEBUG ... MonitoringService : MonitoringSummary 계산: tenantId=?, from=?, to=?, currentApiTotal=?, currentApiErrors=?
```

### 3. 가능한 원인

#### 원인 1: 데이터가 실제로 없음
- **증상**: 모든 API가 0 반환
- **해결**: 프론트엔드에서 `/api/monitoring/page-view` 또는 `/api/monitoring/event` API를 호출하여 데이터를 수집해야 합니다.

#### 원인 2: tenant_id 불일치
- **증상**: 데이터는 있지만 다른 tenant_id로 저장됨
- **해결**: X-Tenant-ID 헤더 값과 실제 데이터의 tenant_id가 일치하는지 확인

#### 원인 3: 날짜 파라미터 파싱 실패
- **증상**: 로그에서 from/to가 null로 표시됨
- **해결**: 
  - URL 인코딩 확인: `2026-01-20T04%3A27%3A00` → `2026-01-20T04:27:00`
  - ISO-8601 형식 확인: `YYYY-MM-DDTHH:mm:ss` 또는 `YYYY-MM-DDTHH:mm:ssZ`

#### 원인 4: 날짜 범위에 데이터가 없음
- **증상**: 특정 기간에만 0 반환
- **해결**: 더 넓은 날짜 범위로 테스트하거나, 실제 데이터가 있는 기간으로 조회

### 4. 테스트 방법

#### 테스트 1: 데이터 수집 API 호출
```bash
# 페이지뷰 수집
curl -X POST "http://localhost:8080/api/monitoring/page-view" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -d '{
    "path": "/admin/monitoring",
    "menuKey": "menu.admin.monitoring",
    "title": "통합 모니터링 대시보드",
    "visitorId": "test_visitor_001",
    "device": "desktop",
    "referrer": "http://localhost:4200/dashboard"
  }'
```

#### 테스트 2: 조회 API 호출 (로그 확인)
```bash
# Summary 조회
curl -X GET "http://localhost:8080/api/admin/monitoring/summary?from=2026-01-20T04:27:00&to=2026-01-21T04:27:00" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1"
```

애플리케이션 로그에서 다음을 확인:
- 파라미터가 제대로 파싱되었는지
- 실제 쿼리가 실행되었는지
- 결과 값이 0인지

### 5. 로그 레벨 설정

`application.yml` 또는 `application-dev.yml`에서 로그 레벨을 DEBUG로 설정:

```yaml
logging:
  level:
    com.dwp.services.auth.controller.admin.monitoring: DEBUG
    com.dwp.services.auth.service.MonitoringService: DEBUG
    com.dwp.services.auth.service.monitoring.AdminMonitoringService: DEBUG
```

### 6. 다음 단계

1. 데이터베이스에 실제 데이터가 있는지 확인
2. 애플리케이션 로그에서 파라미터 파싱 확인
3. 데이터가 없다면 수집 API 호출하여 데이터 생성
4. 여전히 0이면 Repository 쿼리 로직 확인
