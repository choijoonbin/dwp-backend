# 모니터링 API 날짜 파라미터 형식 가이드

## 📋 날짜 파라미터 변환 과정

### 1. 프론트엔드 → 백엔드 (URL 파라미터)

**프론트엔드에서 전송하는 형식:**
```
from=2026-01-20T04%3A27%3A00
to=2026-01-21T04%3A27%3A00
```

**URL 디코딩 후:**
```
from=2026-01-20T04:27:00
to=2026-01-21T04:27:00
```

**형식:** ISO-8601 (`YYYY-MM-DDTHH:mm:ss`)
- `T`로 날짜와 시간 구분
- 타임존 정보 없음 (로컬 시간으로 처리)

### 2. 백엔드 파싱 (Spring)

**컨트롤러:**
```java
@RequestParam(required = false) 
@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) 
LocalDateTime from
```

**파싱 결과:**
- `2026-01-20T04:27:00` → `LocalDateTime` 객체
- `LocalDateTime.of(2026, 1, 20, 4, 27, 0)`

### 3. 데이터베이스 쿼리 (JPA/Hibernate)

**JPQL 쿼리:**
```java
@Query("SELECT COUNT(p) FROM PageViewEvent p 
        WHERE p.tenantId = :tenantId 
        AND p.createdAt BETWEEN :from AND :to")
```

**실제 SQL 변환:**
```sql
SELECT COUNT(p.*) 
FROM sys_page_view_events p 
WHERE p.tenant_id = 1 
  AND p.created_at BETWEEN '2026-01-20 04:27:00' AND '2026-01-21 04:27:00'
```

**형식:** PostgreSQL TIMESTAMP (`'YYYY-MM-DD HH:mm:ss'`)
- 공백으로 날짜와 시간 구분
- 작은따옴표로 감싸짐

## ✅ 정리

| 단계 | 형식 | 예시 |
|------|------|------|
| **프론트엔드 URL** | ISO-8601 (T로 구분) | `2026-01-20T04:27:00` |
| **백엔드 파싱** | LocalDateTime 객체 | `LocalDateTime.of(2026, 1, 20, 4, 27, 0)` |
| **DB 쿼리** | PostgreSQL TIMESTAMP (공백으로 구분) | `'2026-01-20 04:27:00'` |

## 🔍 확인 방법

### 1. 애플리케이션 로그 확인

서버를 재시작하고 API를 호출하면 다음 로그가 출력됩니다:

```
DEBUG ... AdminMonitoringController : getSummary 호출: tenantId=1, from=2026-01-20T04:27:00, to=2026-01-21T04:27:00
DEBUG ... AdminMonitoringController : getSummary 파라미터 적용: tenantId=1, defaultFrom=2026-01-20T04:27:00, defaultTo=2026-01-21T04:27:00
```

### 2. 데이터베이스 쿼리 확인

PostgreSQL 로그를 활성화하거나, 애플리케이션 로그에서 실제 실행된 SQL을 확인:

```yaml
# application.yml
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
```

실행된 SQL 예시:
```sql
SELECT COUNT(p.*) 
FROM sys_page_view_events p 
WHERE p.tenant_id = ? 
  AND p.created_at BETWEEN ? AND ?
```

바인딩된 파라미터:
- `tenant_id = 1`
- `from = '2026-01-20 04:27:00'`
- `to = '2026-01-21 04:27:00'`

## ⚠️ 주의사항

### 1. 타임존 처리
- 프론트엔드에서 타임존 없이 전송하면 서버 로컬 시간으로 처리됩니다
- 한국 시간(KST, UTC+9)을 사용하는 경우, 프론트엔드에서도 동일한 타임존을 사용해야 합니다

### 2. BETWEEN 연산자
- `BETWEEN :from AND :to`는 **포함(inclusive)**입니다
- 즉, `2026-01-20 04:27:00`부터 `2026-01-21 04:27:00`까지 모두 포함됩니다
- 정확한 범위를 원하면 `to` 파라미터를 `2026-01-21 04:26:59`로 설정하거나, `<=` 대신 `<`를 사용해야 합니다

### 3. 날짜 범위 확인
데이터베이스에 실제로 해당 기간의 데이터가 있는지 확인:

```sql
-- 실제 데이터 확인
SELECT tenant_id, created_at, COUNT(*) 
FROM sys_page_view_events 
WHERE tenant_id = 1 
  AND created_at BETWEEN '2026-01-20 04:27:00' AND '2026-01-21 04:27:00'
GROUP BY tenant_id, created_at
ORDER BY created_at;
```

## 📝 결론

**프론트엔드에서 넘긴 조회 조건:**
- URL: `from=2026-01-20T04%3A27%3A00&to=2026-01-21T04%3A27%3A00`
- 실제 값: `2026-01-20T04:27:00` (ISO-8601 형식)

**데이터베이스 쿼리에서 사용되는 형식:**
- `'2026-01-20 04:27:00' AND '2026-01-21 04:27:00'` (PostgreSQL TIMESTAMP 형식)

**변환은 자동으로 이루어지므로, 프론트엔드에서는 ISO-8601 형식(`YYYY-MM-DDTHH:mm:ss`)을 사용하면 됩니다.**
