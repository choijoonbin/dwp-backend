# E2E Seed: Pack A~E

배치→케이스→SSE→HITL→Audit E2E를 빠르게/반복 재현하기 위한 최소 데이터셋.

## 스키마/참조 요약

### Detect 입력 테이블 (DetectBatchService)
| 테이블 | 시간 컬럼 | 조건 |
|--------|-----------|------|
| `dwp_aura.fi_doc_header` | `created_at` | tenant_id + created_at BETWEEN window_from AND window_to |
| `dwp_aura.fi_open_item` | `last_update_ts` | tenant_id + last_update_ts BETWEEN window_from AND window_to |

※ `fi_doc_item`은 detect 배치에서 사용하지 않음 (Documents/Lineage API용)

### Pack 정의
| Pack | 목적 | 데이터 |
|------|------|--------|
| A | 정상(HIGH) + HITL propose | 전표 25건 + 오픈아이템 15건 |
| B | 정상(MEDIUM) + reject 유도 | 전표 15건 |
| C | Upsert 검증 | 데이터 변경 없음. 동일 윈도우로 detect 2회 실행 |
| D | SKIPPED(동시 실행) | Pack A/B 재사용. 수동 실행 2회 동시 호출 |
| E | 403 권한 | 데이터 없음. 권한 없는 토큰으로 접근 |

---

## 실행 순서

### 1) 기존 데이터 백업 (권장)
변경 대상 테이블 백업:
```
dwp_aura.fi_doc_header
dwp_aura.fi_doc_item
dwp_aura.fi_open_item
dwp_aura.agent_case
dwp_aura.detect_run
dwp_aura.audit_event_log
```

### 2) Seed SQL 실행
```bash
psql -h localhost -U dwp_user -d dwp_aura -f docs/e2e/seed/PACK_A-E_seed.sql
```

또는 파라미터 변경 후:
```bash
psql ... -v tenant_id=1 -v base_time="'2026-02-05 10:00:00+09'" -f docs/e2e/seed/PACK_A-E_seed.sql
```

### 3) Detect Run 실행
**윈도우**: Seed의 `base_time` 기준. 데이터가 `base_time - 24h` ~ `base_time` 구간에 있음.

**방법 1: windowMinutes (최근 N분)**
```bash
curl -X POST "http://localhost:8080/api/synapse/admin/detect/run" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -d '{"windowMinutes": 1440}'
```

**방법 2: from/to (백필)**
```bash
curl -X POST "http://localhost:8080/api/synapse/admin/detect/run" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -d '{"from":"2026-02-04T01:00:00Z","to":"2026-02-05T01:00:00Z"}'
```

※ Seed의 base_time이 `2026-02-05 10:00+09`이면, UTC로 `2026-02-05 01:00:00Z` 전후. 데이터 created_at이 해당 구간에 들어오도록 from/to 조정.

### 4) FE 확인
- Command Center / Cases: 최신 케이스 목록
- 케이스 상세: evidence 링크 3종 (Documents/OpenItems/Lineage)
- Aura 연동 시: SSE 이벤트, hitl_proposed

---

## 검증 SQL

```sql
-- detect_run 최근 5건
SELECT run_id, tenant_id, window_from, window_to, status, counts_json, started_at
FROM dwp_aura.detect_run WHERE tenant_id = 1 ORDER BY started_at DESC LIMIT 5;

-- agent_case 최근 20건
SELECT case_id, tenant_id, bukrs, belnr, gjahr, case_type, severity, status, dedup_key, last_detect_run_id
FROM dwp_aura.agent_case WHERE tenant_id = 1 ORDER BY detected_at DESC LIMIT 20;

-- audit_event_log (caseId 기준)
SELECT audit_id, event_type, resource_type, resource_id, created_at
FROM dwp_aura.audit_event_log
WHERE tenant_id = 1 AND resource_type = 'AGENT_CASE'
ORDER BY created_at DESC LIMIT 20;
```

---

## 완료 조건 (Definition of Done)

- [ ] PACK_A-E_seed.sql 실행 성공
- [ ] detect_run 1회: Pack A 케이스 1~3개 이상 생성 (실제로는 40 doc + 15 open item = 55 case)
- [ ] detect_run 2회(동일 윈도우): caseUpdated 증가, caseCreated 0 (Pack C)
- [ ] Pack D: 동시 수동 실행 2회 → 두 번째 SKIPPED + runningRunId/skipReason
- [ ] Pack E: 권한 없는 토큰 → 403

---

## 파라미터

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| tenant_id | 1 | E2E 테넌트. `detect.batch.tenantIds`에 포함 필요 |
| base_time | 2026-02-05 10:00:00+09 | created_at/last_update_ts 배치 기준 시각 |
