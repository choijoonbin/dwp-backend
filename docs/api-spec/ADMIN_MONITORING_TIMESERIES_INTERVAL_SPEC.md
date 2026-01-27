# [BACKEND] 시계열 API 인터벌 파라미터 및 집계 규격

> **API**: `GET /api/admin/monitoring/timeseries`  
> **목적**: 프론트엔드가 전달한 `interval`에 맞춰 시계열 데이터를 어떻게 집계·반환할지 백엔드 구현 기준을 명시한다.

---

## 1. 동적 그룹화 (Dynamic Grouping)

- **파라미터**: `interval` — 문자열 `1m`, `5m`, `1h`, `1d` (대소문자 무시). 레거시 `HOUR` → `1h`, `DAY` → `1d` 정규화.
- **적용 규칙**  
  - 요청으로 들어온 `interval`을 파싱하여 **SQL의 GROUP BY 시간 단위**에 그대로 반영한다.  
  - 허용 값별 매핑은 아래와 같다.

| interval (요청) | 정규화 값 | GROUP BY 표현 (PostgreSQL) |
|-----------------|-----------|----------------------------|
| 1m              | 1m        | `date_trunc('minute', created_at)` |
| 5m              | 5m        | `date_trunc('hour', created_at) + (EXTRACT(MINUTE FROM created_at)::int / 5) * INTERVAL '5 min'` |
| 1h, HOUR        | 1h        | `date_trunc('hour', created_at)` |
| 1d, DAY         | 1d        | `date_trunc('day', created_at)` |

- **출력 포인트**: `from` ~ `to` 구간을 위 시간 단위로 나눈 **모든 버킷**에 대해 라벨·값을 한 개씩 생성한다. (데이터 유무와 관계없이 구간별로 한 포인트.)

---

## 2. P95 계산의 정확성

- **요구사항**: 각 **인터벌 구간 내**에 있는 **모든 응답 시간(latency_ms)** 에 대해, **상위 5% 지점(p95)** 을 산출한다.
- **금지 사항**:  
  - 구간별로 **단순 평균(AVG)** 을 사용하지 않는다.  
  - 해당 구간의 **전체 모수에서 통계적인 p95** 를 사용해야 한다.
- **구현**:  
  - PostgreSQL **`percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)`** 를 사용한다.  
  - `FILTER (WHERE latency_ms IS NOT NULL)` 로 NULL 은 제외한다.  
  - 구간에 유효한 지연 데이터가 없으면 해당 버킷 값은 **null/NaN** 으로 두고, 이후 “빈 구간 처리” 단계에서 보정한다.

---

## 3. 빈 구간 처리 (Zero-filling / Gap-filling)

- **상황**: 특정 인터벌 구간에 **데이터가 전혀 없을 때** (해당 버킷에 행이 없거나, LATENCY_* 메트릭만 있고 유효한 p95가 없을 때).
- **요구사항**:  
  - **null 을 그대로 반환하지 않는다.**  
  - **0** 또는 **직전 구간의 값**을 넣어서 **차트 선이 끊어지지 않도록** 한다.
- **구현**:  
  - LATENCY_P50 / LATENCY_P95 / LATENCY_P99 메트릭에 한해,  
    - 버킷에 값이 없으면 우선 **NaN** 으로 두고,  
    - 응답 배열을 채운 뒤 **한 번 더 순회**하여  
    - **NaN 인 위치를 “직전 유효값”으로 치환** (앞에 유효값이 없으면 **0** 사용).

---

## 4. API_TOTAL (트래픽 스파크라인)

- **metric=API_TOTAL** 요청 시, 각 버킷별 **요청 건수**가 아닌 **해당 버킷의 평균 RPS**를 반환한다.
- **계산**: `버킷 내 요청 건수 / 버킷 길이(초)` (1m → 60, 5m → 300, 1h → 3600, 1d → 86400). 소수점 2자리.
- **용도**: 프론트엔드에서 interval에 맞춰 받은 values 배열을 그대로 스파크라인(시간대별 RPS 추이)으로 사용.

---

## 5. 구현 위치 참고

| 규칙           | 코드 위치 |
|----------------|-----------|
| 동적 그룹화    | `AdminMonitoringService`: `normalizeInterval()`, `fillTimeseriesFromApiBuckets()` → interval별 Repository 메서드 선택. `ApiCallHistoryRepository`: `findTimeseriesBucketStatsMinute`, `findTimeseriesBucketStats5Min`, `findTimeseriesBucketStatsHour`, `findTimeseriesBucketStatsDay` |
| P95 정확성     | `ApiCallHistoryRepository`: 각 버킷 쿼리 내 `percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms IS NOT NULL)` |
| 빈 구간 처리   | `AdminMonitoringService`: `fillLatencyGaps(List<Double> values)` — NaN 을 직전 값(또는 0)으로 대체 |

---

## 6. 문서 이력

| 날짜       | 내용 |
|------------|------|
| 2026-01-26 | 최초 작성 — 인터벌 기반 동적 그룹화, P95 통계값, 빈 구간 처리 규격 정리 |
| 2026-01-26 | **API_TOTAL**: 시간대별 평균 RPS(버킷당 요청건수/버킷초) 반환으로 스파크라인 직접 사용 가능 명시 |