# 모니터링 API 속도 개선 방안

> 조회 기간 1달 이상 시 summary / visitors / timeseries 응답이 7초 이상 소요되는 문제에 대한 원인 분석 및 개선 방안.

---

## 1. 현상

- `GET /api/admin/monitoring/summary?from=...&to=...` (약 1달)
- `GET /api/admin/monitoring/visitors?from=...&to=...`
- `GET /api/admin/monitoring/timeseries?from=...&to=...&interval=DAY&metric=PV|UV`

위 API 호출 시 **7초 이상** 소요되며, 조회 기간이 길어질수록 더 느려질 수 있음.

---

## 2. 원인 분석

### 2.1 Summary API (`/summary`)

| 원인 | 설명 |
|------|------|
| **과다 DB 라운드트립** | 현재 구간 + 비교 구간에 대해 **상위 집계만 20회** (PV, UV, events, apiTotal, apiErrors × 2구간). 그 위에 `buildKpi()`가 **현재/비교 각 1회씩** 호출되며, KPI 1회당 약 15개 이상 쿼리( count, downtime, statusHistory, topCause, percentiles, topSlow, maxInWindow, topTraffic, topError 등) 실행. **총 50회 이상** DB 호출 가능. |
| **대용량 스캔** | 1달 구간 `sys_api_call_histories`, `sys_page_view_events`를 반복 스캔. `tenant_id` + `created_at` 복합 인덱스가 없으면 풀 스캔 또는 비효율적 인덱스 병합 발생. |
| **순차 실행** | 모든 쿼리가 순차 실행되어 지연이 그대로 합산됨. |

### 2.2 Visitors API (`/visitors`)

| 원인 | 설명 |
|------|------|
| **DB 레벨 페이징 없음** | `findVisitorSummariesByTenantIdAndCreatedAtBetween`로 **기간 내 전체 방문자**를 한 번에 조회한 뒤, 애플리케이션에서 `subList`로 페이징. 1달이면 수천~수만 행을 메모리로 로딩. |
| **N+1 스타일** | 각 방문자 행마다 `eventLogRepository.countByTenantIdAndOccurredAtBetween(tenantId, from, to)` 호출. (현재는 기간 전체 동일 값 반환하는 TODO 상태이나, 호출 횟수만으로도 부담.) |

### 2.3 Timeseries API (`/timeseries` & `interval=DAY`, `metric=PV`/`UV`)

| 원인 | 설명 |
|------|------|
| **일별 루프 쿼리** | `interval=1d`일 때 **날짜마다** `findByTenantIdAndStatDateBetween(tenantId, date, date)` 호출. 31일이면 **31회** 쿼리. |
| **일 단위 1회 조회로 대체 가능** | 한 번에 `findByTenantIdAndStatDateBetween(tenantId, startDate, endDate)`로 조회한 뒤, `stat_date`별로 PV/UV 합계만 묶으면 **1회** 쿼리로 동일 결과 생성 가능. |

### 2.4 인덱스

| 테이블 | 현재 인덱스 | 비고 |
|--------|-------------|------|
| `sys_api_call_histories` | `(tenant_id)`, `(created_at)` 분리 | 기간+테넌트 조건 시 `(tenant_id, created_at)` 복합 인덱스가 유리. |
| `sys_page_view_events` | `(tenant_id)`, `(created_at)` 분리 | 동일하게 `(tenant_id, created_at)` 복합 권장. |
| `sys_page_view_daily_stats` | `(tenant_id)`, `(stat_date)` 분리 | `(tenant_id, stat_date)` 복합 시 일 범위 조회에 유리. |

---

## 3. 개선 방안

### 3.1 DB 인덱스 추가 (우선 적용 권장)

**목적**: 기간+테넌트 조건 쿼리의 스캔 비용 감소.

- Flyway 마이그레이션으로 아래 인덱스 추가.

```sql
-- sys_api_call_histories: 기간 조회 시 tenant_id + created_at 동시 사용
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sys_api_call_histories_tenant_created
ON sys_api_call_histories(tenant_id, created_at);

-- sys_page_view_events: 동일
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sys_page_view_events_tenant_created
ON sys_page_view_events(tenant_id, created_at);

-- sys_page_view_daily_stats: 일별 구간 조회
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sys_page_view_daily_stats_tenant_stat_date
ON sys_page_view_daily_stats(tenant_id, stat_date);
```

- `CONCURRENTLY`는 테이블 락 없이 인덱스 생성(운영 중 적용 시 유리). 개발/스테이징에서는 생략 가능.

**기대 효과**: summary/timeseries/visitors의 기간 조건 쿼리 전반에서 스캔 비용 감소 (수 초 단위 개선 가능).

---

### 3.2 Timeseries 일별(DAY) PV/UV: 1회 쿼리로 통합

**현재**: 날짜 루프마다 `findByTenantIdAndStatDateBetween(tenantId, date, date)` 호출 → 31일이면 31회.

**개선**:

1. 한 번만 `findByTenantIdAndStatDateBetween(tenantId, startDate, endDate)` 호출.
2. 결과 리스트를 `stat_date`별로 그룹핑하여 일별 `pv_count`/`uv_count` 합산.
3. `labels`는 `startDate`~`endDate`까지 모든 일자를 순회하며, 해당 일자에 데이터가 없으면 0으로 채움.

**수정 위치**: `AdminMonitoringService.getTimeseries()` 내 `"1d".equals(normalizedInterval)` 분기.

**기대 효과**: timeseries DAY 호출 시 DB 라운드트립 31회 → 1회로 감소.

---

### 3.3 Summary: 병렬 쿼리 및 캐시

**3.3.1 병렬 실행**

- **현재 구간 vs 비교 구간**: 상위 10개 count(current)와 10개 count(compare)는 서로 독립이므로, `CompletableFuture` 등으로 두 그룹를 나누어 병렬 실행.
- **buildKpi(current) vs buildKpi(compare)**: 두 번의 `buildKpi`도 독립이므로 병렬 호출 가능.
- **buildKpi 내부**: 한 구간 안에서도 서로 의존하지 않는 쿼리들(count, downtime, topCause, percentiles, topSlow, topTraffic, topError 등)은 스레드풀에서 병렬 실행 후 결과만 조합.

**주의**: `@Transactional(readOnly = true)` 안에서 별도 스레드로 DB 접근 시 트랜잭션/컨텍스트 전파 방식 확인 필요. 필요 시 트랜잭션 경계를 나누거나, 병렬 부분만 별도 `@Transactional` 메서드로 분리.

**기대 효과**: 순차 50회 호출을 병렬로 줄이면 체감 지연 크게 감소 (2~4배 수준 개선 가능).

**3.3.2 응답 캐시**

- 동일 `(tenantId, from, to, compareFrom, compareTo)` 재요청 시 **짧은 TTL**(예: 1~2분) 메모리 캐시 적용.
- Caffeine 등으로 `MonitoringSummaryResponse` 캐싱. timeseries와 동일하게 키에 tenant/from/to 포함.

**기대 효과**: 대시 새로고침/탭 전환 시 동일 기간 재요청 시 DB 부하 제거.

---

### 3.4 Visitors: DB 레벨 페이징

**현재**: 기간 내 전체 방문자 조회 후 애플리케이션에서 `subList` 페이징.

**개선**:

- DB에서 **페이징된 결과만** 조회하도록 변경.
  - 예: `findVisitorSummariesByTenantIdAndCreatedAtBetween(tenantId, from, to, Pageable pageable)` 형태로 `LIMIT/OFFSET` 또는 `FETCH FIRST ... ONLY` 적용.
- JPA라면 `Pageable`을 그대로 repository에 전달하고, `COUNT` 쿼리는 별도로 두거나 필요 시 최소화(예: 일부 UI에서는 “다음 페이지 유무”만 필요할 수 있음).

**추가**: 방문자별 `eventCount`가 현재는 기간 전체 동일 값(TODO)이므로, 이 부분을 “방문자별 이벤트 수”로 정확히 집계할 경우에는 별도 집계 쿼리(또는 배치 조회) 설계 필요. 우선은 **전체 조회 제거 + DB 페이징**만으로도 메모리와 1회 쿼리 비용을 크게 줄일 수 있음.

**기대 효과**: 1달 조회 시 수천~수만 행을 한 번에 로딩하지 않아 1회 요청 비용 및 메모리 사용량 감소.

---

### 3.5 Summary: KPI 쿼리 통합/경량화 (선택)

- **statusHistory / downtime**: 1달처럼 기간이 길면 버킷 수가 많아지고, `findAvailabilityBucketStats` 등이 무거워질 수 있음. 기간이 길 때는 버킷 크기를 더 크게 두어 포인트 수를 제한하는 방식 검토.
- **Top 계열(topCause, topSlow, topTraffic, topError)**: 한 번의 “다목적” 네이티브 쿼리로 4가지 top을 모두 가져오는 방식은 쿼리 수를 4 → 1로 줄일 수 있으나, 가독성·유지보수성과의 타협 필요. 단기에는 병렬화만 적용하고, 중장기에서 검토 가능.

---

## 4. 적용 우선순위 제안

| 순위 | 항목 | 난이도 | 효과 | 비고 |
|------|------|--------|------|------|
| 1 | **인덱스 추가** (3.1) | 낮음 | 높음 | 스키마 마이그레이션만. 다른 개선의 기반. |
| 2 | **Timeseries DAY 1회 쿼리** (3.2) | 낮음 | 중간 | 로직 변경 작음, 쿼리 수 31→1. |
| 3 | **Summary 응답 캐시** (3.3.2) | 낮음 | 중간 | 동일 파라미터 재요청 시 즉시 개선. |
| 4 | **Visitors DB 페이징** (3.4) | 중간 | 높음 | 1달 조회 시 메모리·쿼리 비용 대폭 감소. |
| 5 | **Summary 병렬 쿼리** (3.3.1) | 중간 | 높음 | 트랜잭션/스레드 모델 확인 필요. |
| 6 | KPI 통합/경량화 (3.5) | 높음 | 중간 | 필요 시 단계적 도입. |

---

## 5. 요약

- **즉시 적용 권장**: 복합 인덱스 추가, timeseries 일별 PV/UV 1회 쿼리, summary 단기 캐시.
- **단기**: visitors DB 페이징, summary 병렬 실행 도입.
- **중장기**: KPI 쿼리 통합·경량화, 방문자별 eventCount 정확 집계.

이 순서로 적용하면 1달 조회 시 7초 이상이었던 응답 시간을 수 초 이하로 줄이는 것을 목표로 할 수 있음.
