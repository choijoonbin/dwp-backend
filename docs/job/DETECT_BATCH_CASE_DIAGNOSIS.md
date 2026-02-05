# Detect 배치 케이스 미생성 원인 진단

## 1. 배치 케이스 생성 조건 (DetectBatchService)

케이스가 생성되려면 **다음 두 조건을 모두 만족**해야 합니다.

### 1.1 윈도우 조건 (핵심)

배치는 **고정 15분 윈도우**로만 조회합니다:

- `windowFrom` = now - 15분
- `windowTo` = now

| 원천 테이블 | 조건 |
|------------|------|
| `fi_doc_header` | `created_at >= windowFrom AND created_at < windowTo` |
| `fi_open_item` | `last_update_ts >= windowFrom AND last_update_ts < windowTo` |

→ **created_at / last_update_ts가 배치 실행 시점 기준 최근 15분 이내**여야 함.

### 1.2 Tenant 조건

- 스케줄러: `detect.batch.tenant-ids`에 포함된 tenant만 실행 (기본: `[1]`)
- 수동 트리거: `X-Tenant-ID` 헤더로 전달한 tenant만 실행

---

## 2. 케이스가 안 생기는 주요 원인

### 2.1 윈도우 밖 (가장 흔함)

- 시드/재테스트 데이터가 **15분 이상 전**에 적재된 경우
- 예: `now() - interval '10 minutes'`로 시드 후 **10분 이상 지나서** 배치 실행
- 서비스 재기동 후 배치만 돌리면, 기존 시드 데이터는 대부분 윈도우 밖

### 2.2 원천 데이터 없음

- `fi_doc_header`, `fi_open_item`에 해당 tenant_id 데이터가 없음

### 2.3 Advisory lock (SKIPPED)

- 다른 인스턴스가 같은 tenant에 대해 detect를 실행 중이면 SKIPPED
- 재기동 직후에는 가능성 낮음

---

## 3. 진단용 SQL

아래를 DB에서 실행해 원인을 확인하세요.

```sql
-- 1) 현재 시각과 15분 윈도우
SELECT
  now() AS now_utc,
  now() - interval '15 minutes' AS window_from,
  now() AS window_to;

-- 2) fi_doc_header: 윈도우 내 건수 (tenant_id=1 기준)
SELECT COUNT(*) AS doc_in_window
FROM dwp_aura.fi_doc_header
WHERE tenant_id = 1
  AND created_at >= now() - interval '15 minutes'
  AND created_at < now();

-- 3) fi_open_item: 윈도우 내 건수 (tenant_id=1 기준)
SELECT COUNT(*) AS oi_in_window
FROM dwp_aura.fi_open_item
WHERE tenant_id = 1
  AND last_update_ts >= now() - interval '15 minutes'
  AND last_update_ts < now();

-- 4) 전체 데이터 존재 여부 (윈도우 무관)
SELECT
  (SELECT COUNT(*) FROM dwp_aura.fi_doc_header WHERE tenant_id = 1) AS total_doc,
  (SELECT COUNT(*) FROM dwp_aura.fi_open_item WHERE tenant_id = 1) AS total_oi;

-- 5) 최근 created_at / last_update_ts (데이터가 얼마나 오래됐는지 확인)
SELECT 'fi_doc_header' AS tbl, MAX(created_at) AS max_ts FROM dwp_aura.fi_doc_header WHERE tenant_id = 1
UNION ALL
SELECT 'fi_open_item', MAX(last_update_ts) FROM dwp_aura.fi_open_item WHERE tenant_id = 1;
```

- `doc_in_window`, `oi_in_window`가 0이면 → **윈도우 밖**이 원인
- `total_doc`, `total_oi`가 0이면 → **원천 데이터 없음**

---

## 4. 해결 방법

### 4.1 시드 직후 배치 실행 (권장)

시드 스크립트 실행 직후 **15분 이내**에 배치를 실행:

```bash
# 1) 시드 실행
psql ... -f scripts/seed/phase2-4-seed.sql

# 2) 바로 배치 수동 트리거 (15분 이내)
curl -X POST "http://localhost:8080/api/synapse/admin/detect/run" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer <JWT>"
```

### 4.2 윈도우 확대 (수동 트리거)

수동 트리거 시 `windowMinutes`를 늘려서 과거 데이터까지 포함:

```bash
curl -X POST "http://localhost:8080/api/synapse/admin/detect/run" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"windowMinutes": 1440}'
```

→ 24시간(1440분) 윈도우로 조회.

### 4.3 시드 시 타임스탬프 갱신

배치 실행 직전에 시드 데이터의 `created_at`/`last_update_ts`를 현재 시각 근처로 갱신:

```sql
-- fi_doc_header: created_at을 5분 전으로
UPDATE dwp_aura.fi_doc_header
SET created_at = now() - interval '5 minutes', updated_at = now()
WHERE tenant_id = 1;

-- fi_open_item: last_update_ts를 5분 전으로
UPDATE dwp_aura.fi_open_item
SET last_update_ts = now() - interval '5 minutes'
WHERE tenant_id = 1;
```

이후 배치를 실행하면 윈도우 내에 포함됩니다.

---

## 5. 요약

| 원인 | 확인 방법 | 대응 |
|------|-----------|------|
| 윈도우 밖 | `doc_in_window`, `oi_in_window` = 0 | 시드 직후 배치 실행, 또는 windowMinutes 확대, 또는 시드 타임스탬프 갱신 |
| 원천 데이터 없음 | `total_doc`, `total_oi` = 0 | 시드 스크립트 실행 |
| Advisory lock | detect_run 상태가 SKIPPED | 다른 인스턴스 완료 대기 |
