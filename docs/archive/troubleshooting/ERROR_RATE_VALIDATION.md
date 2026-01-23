# 에러율 계산 검증

**문제**: `/api/admin/monitoring/summary` API에서 에러율이 0.0%로 나오는데, 테이블에 500과 401 에러가 존재함

---

## 코드 분석 결과

### 에러 카운트 쿼리
```java
@Query("SELECT COUNT(a) FROM ApiCallHistory a WHERE a.tenantId = :tenantId AND a.statusCode >= 400 AND a.createdAt BETWEEN :from AND :to")
long countErrorsByTenantIdAndCreatedAtBetween(...)
```

**결론**: `statusCode >= 400` 조건이므로 **500과 401은 모두 에러로 카운트되어야 합니다.**

### 에러율 계산 로직
```java
long currentApiTotal = apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, from, to);
long currentApiErrors = apiCallHistoryRepository.countErrorsByTenantIdAndCreatedAtBetween(tenantId, from, to);
double apiErrorRate = currentApiTotal > 0 ? (double) currentApiErrors / currentApiTotal * 100 : 0.0;
```

**문제 가능성**:
1. `currentApiTotal`이 0이면 에러율이 0.0%로 반환됨
2. 날짜 범위가 맞지 않을 수 있음
3. `tenant_id`가 맞지 않을 수 있음

---

## 검증 쿼리

다음 SQL을 실행하여 실제 데이터를 확인하세요:

```sql
-- 1. 전체 API 호출 수 및 에러 수 확인
SELECT 
    COUNT(*) as total_calls,
    COUNT(CASE WHEN status_code >= 400 THEN 1 END) as error_calls,
    COUNT(CASE WHEN status_code = 500 THEN 1 END) as error_500,
    COUNT(CASE WHEN status_code = 401 THEN 1 END) as error_401,
    COUNT(CASE WHEN status_code = 400 THEN 1 END) as error_400,
    COUNT(CASE WHEN status_code = 403 THEN 1 END) as error_403,
    COUNT(CASE WHEN status_code = 404 THEN 1 END) as error_404,
    ROUND(COUNT(CASE WHEN status_code >= 400 THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0), 2) as error_rate_percent
FROM sys_api_call_histories
WHERE tenant_id = 1
  AND created_at >= '2025-12-21 06:12:00'
  AND created_at <= '2026-01-20 06:12:00';

-- 2. status_code 분포 확인
SELECT 
    status_code,
    COUNT(*) as count
FROM sys_api_call_histories
WHERE tenant_id = 1
  AND created_at >= '2025-12-21 06:12:00'
  AND created_at <= '2026-01-20 06:12:00'
GROUP BY status_code
ORDER BY status_code;

-- 3. NULL status_code 확인
SELECT 
    COUNT(*) as null_status_count
FROM sys_api_call_histories
WHERE tenant_id = 1
  AND created_at >= '2025-12-21 06:12:00'
  AND created_at <= '2026-01-20 06:12:00'
  AND status_code IS NULL;

-- 4. 날짜 범위 확인 (실제 데이터 범위)
SELECT 
    MIN(created_at) as min_date,
    MAX(created_at) as max_date,
    COUNT(*) as total_count
FROM sys_api_call_histories
WHERE tenant_id = 1;

-- 5. tenant_id별 통계 확인
SELECT 
    tenant_id,
    COUNT(*) as total_calls,
    COUNT(CASE WHEN status_code >= 400 THEN 1 END) as error_calls
FROM sys_api_call_histories
WHERE created_at >= '2025-12-21 06:12:00'
  AND created_at <= '2026-01-20 06:12:00'
GROUP BY tenant_id;
```

---

## 디버그 로깅 추가

코드에 디버그 로깅을 추가했습니다. 다음 로그를 확인하세요:

```
MonitoringSummary 계산: tenantId={}, from={}, to={}, currentApiTotal={}, currentApiErrors={}
에러율 계산 결과: apiErrorRate={}%, prevApiErrorRate={}%
```

---

## 예상 원인

### 1. currentApiTotal이 0인 경우
- 날짜 범위에 데이터가 없음
- tenant_id가 맞지 않음

### 2. 날짜 범위 문제
- URL 파라미터: `from=2025-12-21T06:12:00&to=2026-01-20T06:12:00`
- 실제 데이터의 `created_at`이 이 범위 밖에 있을 수 있음

### 3. tenant_id 문제
- 요청 헤더의 `X-Tenant-ID`와 실제 데이터의 `tenant_id`가 다를 수 있음

---

## 해결 방법

1. **검증 쿼리 실행**: 위의 SQL을 실행하여 실제 데이터 확인
2. **로그 확인**: 애플리케이션 로그에서 디버그 메시지 확인
3. **날짜 범위 확인**: 실제 데이터의 `created_at` 범위 확인
4. **tenant_id 확인**: 요청 헤더의 `X-Tenant-ID`와 실제 데이터의 `tenant_id` 일치 여부 확인

---

**작성일**: 2026-01-20
