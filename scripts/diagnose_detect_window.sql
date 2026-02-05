-- Detect 배치 케이스 미생성 원인 진단
-- 실행: psql -h localhost -U dwp_user -d dwp_aura -f scripts/diagnose_detect_window.sql

SET search_path TO dwp_aura, public;

\echo '=== 1) 현재 시각과 15분 윈도우 ==='
SELECT
  now() AS now_utc,
  now() - interval '15 minutes' AS window_from,
  now() AS window_to;

\echo ''
\echo '=== 2) fi_doc_header: 윈도우 내 건수 (tenant_id=1) ==='
SELECT COUNT(*) AS doc_in_window
FROM dwp_aura.fi_doc_header
WHERE tenant_id = 1
  AND created_at >= now() - interval '15 minutes'
  AND created_at < now();

\echo ''
\echo '=== 3) fi_open_item: 윈도우 내 건수 (tenant_id=1) ==='
SELECT COUNT(*) AS oi_in_window
FROM dwp_aura.fi_open_item
WHERE tenant_id = 1
  AND last_update_ts >= now() - interval '15 minutes'
  AND last_update_ts < now();

\echo ''
\echo '=== 4) 전체 데이터 존재 여부 ==='
SELECT
  (SELECT COUNT(*) FROM dwp_aura.fi_doc_header WHERE tenant_id = 1) AS total_doc,
  (SELECT COUNT(*) FROM dwp_aura.fi_open_item WHERE tenant_id = 1) AS total_oi;

\echo ''
\echo '=== 5) 최근 타임스탬프 (데이터가 얼마나 오래됐는지) ==='
SELECT 'fi_doc_header' AS tbl, MAX(created_at) AS max_ts FROM dwp_aura.fi_doc_header WHERE tenant_id = 1
UNION ALL
SELECT 'fi_open_item', MAX(last_update_ts) FROM dwp_aura.fi_open_item WHERE tenant_id = 1;

\echo ''
\echo '=== 6) 진단 요약 ==='
SELECT
  CASE
    WHEN (SELECT COUNT(*) FROM dwp_aura.fi_doc_header WHERE tenant_id = 1) = 0
     AND (SELECT COUNT(*) FROM dwp_aura.fi_open_item WHERE tenant_id = 1) = 0
    THEN '원인: 원천 데이터 없음 → 시드 스크립트 실행 필요'
    WHEN (SELECT COUNT(*) FROM dwp_aura.fi_doc_header WHERE tenant_id = 1
          AND created_at >= now() - interval '15 minutes' AND created_at < now()) = 0
     AND (SELECT COUNT(*) FROM dwp_aura.fi_open_item WHERE tenant_id = 1
          AND last_update_ts >= now() - interval '15 minutes' AND last_update_ts < now()) = 0
    THEN '원인: 윈도우 밖 → created_at/last_update_ts 갱신 후 배치 실행'
    ELSE '윈도우 내 데이터 있음 → 배치 실행 시 케이스 생성됨'
  END AS diagnosis;
