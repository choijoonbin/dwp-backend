-- E2E Seed 검증 쿼리
-- 실행: psql -h localhost -U dwp_user -d dwp_aura -f docs/e2e/seed/VERIFY_QUERIES.sql

SET search_path TO dwp_aura, public;

\echo '=== fi_doc_header (E2E) ==='
SELECT COUNT(*) AS doc_count FROM dwp_aura.fi_doc_header WHERE tenant_id = 1 AND belnr LIKE 'E2E%';

\echo '=== fi_open_item (E2E) ==='
SELECT COUNT(*) AS oi_count FROM dwp_aura.fi_open_item WHERE tenant_id = 1 AND belnr LIKE 'E2E%';

\echo '=== detect_run 최근 5건 ==='
SELECT run_id, tenant_id, window_from, window_to, status, counts_json, started_at
FROM dwp_aura.detect_run WHERE tenant_id = 1 ORDER BY started_at DESC LIMIT 5;

\echo '=== agent_case 최근 20건 ==='
SELECT case_id, tenant_id, bukrs, belnr, gjahr, case_type, severity, status, dedup_key, last_detect_run_id
FROM dwp_aura.agent_case WHERE tenant_id = 1 ORDER BY detected_at DESC LIMIT 20;

\echo '=== audit_event_log (AGENT_CASE) 최근 20건 ==='
SELECT audit_id, event_type, resource_type, resource_id, created_at
FROM dwp_aura.audit_event_log
WHERE tenant_id = 1 AND resource_type = 'AGENT_CASE'
ORDER BY created_at DESC LIMIT 20;

\echo '=== audit_event_log (RUN_DETECT_* ↔ CASE_* 연계) ==='
SELECT audit_id, event_category, event_type, resource_type, resource_id, created_at,
       after_json->>'runId' AS run_id, after_json->>'caseId' AS case_id, after_json->>'dedupKey' AS dedup_key
FROM dwp_aura.audit_event_log
WHERE tenant_id = 1 AND (event_type LIKE 'RUN_DETECT_%' OR event_type IN ('CASE_CREATED', 'CASE_UPDATED'))
ORDER BY created_at DESC LIMIT 30;
