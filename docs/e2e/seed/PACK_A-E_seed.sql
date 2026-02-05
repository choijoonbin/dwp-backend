-- ======================================================================
-- E2E Seed: Pack A~E — 배치→케이스→SSE→HITL→Audit 재현용 최소 데이터셋
-- 대상: DWP Backend / PostgreSQL (dwp_aura 스키마)
-- Detect 입력: fi_doc_header(created_at), fi_open_item(last_update_ts)
-- ======================================================================
-- 파라미터 (실행 전 psql \set 또는 수동 치환):
--   :tenant_id     = 1 (기본, detect.batch.tenantIds에 포함되어야 함)
--   :base_time     = '2026-02-05 10:00:00+09' (detect 윈도우 내로 created_at/last_update_ts 설정)
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ========================================
-- 0) 변수 설정 (psql 사용 시)
-- ========================================
\set tenant_id 1
\set base_time '''2026-02-05 10:00:00+09'''

-- ========================================
-- 1) 기존 E2E 테넌트 데이터 정리 (선택)
-- ========================================
-- 아래 belnr 범위(E2E001~E2E099)만 삭제. 기존 운영 데이터는 유지.
DELETE FROM dwp_aura.agent_case
WHERE tenant_id = :tenant_id
  AND bukrs = '1000' AND belnr LIKE 'E2E%';

DELETE FROM dwp_aura.fi_open_item
WHERE tenant_id = :tenant_id
  AND bukrs = '1000' AND belnr LIKE 'E2E%';

DELETE FROM dwp_aura.fi_doc_item
WHERE tenant_id = :tenant_id
  AND bukrs = '1000' AND belnr LIKE 'E2E%';

DELETE FROM dwp_aura.fi_doc_header
WHERE tenant_id = :tenant_id
  AND bukrs = '1000' AND belnr LIKE 'E2E%';

-- ========================================
-- 2) Pack A: 정상(HIGH) — 25 전표 + 15 오픈아이템
-- 목적: detect_run 1회 후 케이스 1~3개 생성, HITL propose 가능
-- ========================================
INSERT INTO dwp_aura.fi_doc_header (
  tenant_id, bukrs, belnr, gjahr, doc_source, budat, waers, xblnr, status_code,
  created_at, updated_at
)
SELECT :tenant_id, '1000', 'E2E' || LPAD(s::text, 3, '0'), '2026', 'SAP', '2026-02-05', 'KRW', 'INV-E2E-' || LPAD(s::text, 3, '0'), 'POSTED',
  :base_time::timestamptz - interval '30 minutes' * s,
  :base_time::timestamptz - interval '30 minutes' * s
FROM generate_series(1, 25) s
ON CONFLICT (tenant_id, bukrs, belnr, gjahr) DO UPDATE SET
  created_at = EXCLUDED.created_at,
  updated_at = EXCLUDED.updated_at;

-- fi_doc_item (lineage/evidence용, Pack A 전표 1~5에만)
INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, created_at)
SELECT h.tenant_id, h.bukrs, h.belnr, h.gjahr, '001', '600000', 'V001', 1000000, 'KRW', h.created_at
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = :tenant_id AND h.bukrs = '1000' AND h.belnr IN ('E2E001','E2E002','E2E003','E2E004','E2E005')
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

-- fi_open_item 15건 (전표 E2E001~E2E015와 연결)
INSERT INTO dwp_aura.fi_open_item (
  tenant_id, bukrs, belnr, gjahr, buzei, item_type, lifnr, due_date, open_amount, currency, cleared, last_update_ts
)
SELECT :tenant_id, '1000', 'E2E' || LPAD(s::text, 3, '0'), '2026', '001', 'AP'::dwp_aura.open_item_type, 'V001',
  '2026-03-15'::date, 500000 + (s * 10000), 'KRW', false,
  :base_time::timestamptz - interval '20 minutes' * s
FROM generate_series(1, 15) s
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO UPDATE SET
  last_update_ts = EXCLUDED.last_update_ts;

-- ========================================
-- 3) Pack B: 정상(MEDIUM) — 15 전표
-- 목적: 케이스 생성되거나 propose 없음, reject 테스트
-- ========================================
INSERT INTO dwp_aura.fi_doc_header (
  tenant_id, bukrs, belnr, gjahr, doc_source, budat, waers, xblnr, status_code,
  created_at, updated_at
)
SELECT :tenant_id, '1000', 'E2E' || LPAD((25 + s)::text, 3, '0'), '2026', 'SAP', '2026-02-05', 'KRW', 'INV-E2E-B-' || s, 'POSTED',
  :base_time::timestamptz - interval '45 minutes' * s,
  :base_time::timestamptz - interval '45 minutes' * s
FROM generate_series(1, 15) s
ON CONFLICT (tenant_id, bukrs, belnr, gjahr) DO UPDATE SET
  created_at = EXCLUDED.created_at,
  updated_at = EXCLUDED.updated_at;

-- ========================================
-- 4) Pack C: Upsert 검증 (데이터 변경 없음)
-- 동일 윈도우로 detect 2회 실행 시: 1회=caseCreated, 2회=caseUpdated
-- dedup_key 기준 케이스 1개 유지 확인
-- ========================================

-- ========================================
-- 5) 검증 쿼리
-- ========================================
\echo '=== Pack A-E Seed 완료 ==='
\echo 'fi_doc_header (E2E):'
SELECT COUNT(*) AS doc_count FROM dwp_aura.fi_doc_header WHERE tenant_id = :tenant_id AND belnr LIKE 'E2E%';
\echo 'fi_open_item (E2E):'
SELECT COUNT(*) AS oi_count FROM dwp_aura.fi_open_item WHERE tenant_id = :tenant_id AND belnr LIKE 'E2E%';
\echo 'detect_run 최근 5건:'
SELECT run_id, tenant_id, window_from, window_to, status, counts_json, started_at
FROM dwp_aura.detect_run WHERE tenant_id = :tenant_id ORDER BY started_at DESC LIMIT 5;
\echo 'agent_case 최근 20건:'
SELECT case_id, tenant_id, bukrs, belnr, gjahr, case_type, severity, status, dedup_key, last_detect_run_id
FROM dwp_aura.agent_case WHERE tenant_id = :tenant_id ORDER BY detected_at DESC LIMIT 20;
