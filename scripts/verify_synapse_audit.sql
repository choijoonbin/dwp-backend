-- ======================================================================
-- SynapseX Audit 의무 이벤트 검증
-- 실행: psql -h localhost -U dwp_user -d dwp_aura -v tenantId=1 -f scripts/verify_synapse_audit.sql
-- CI에서 선택적 실행 가능
-- ======================================================================

SET search_path TO dwp_aura, public;

\echo '=== (1) Audit 의무 이벤트 존재 여부 (최근 7일) ==='
SELECT event_category, event_type, count(*) AS cnt
FROM dwp_aura.audit_event_log
WHERE tenant_id = :tenantId
  AND created_at >= now() - interval '7 days'
GROUP BY 1, 2
ORDER BY 1, 2;

\echo ''
\echo '=== (2) Tenant scope 누락 탐지 (tenant_id IS NULL) ==='
SELECT 'agent_case' AS tbl, count(*) AS null_tenant_count FROM dwp_aura.agent_case WHERE tenant_id IS NULL
UNION ALL
SELECT 'agent_action', count(*) FROM dwp_aura.agent_action WHERE tenant_id IS NULL
UNION ALL
SELECT 'audit_event_log', count(*) FROM dwp_aura.audit_event_log WHERE tenant_id IS NULL;

\echo ''
\echo '=== (3) action simulate/execute 흐름 존재 여부 ==='
SELECT outcome, event_type, count(*) AS cnt
FROM dwp_aura.audit_event_log
WHERE tenant_id = :tenantId
  AND event_category = 'ACTION'
GROUP BY 1, 2
ORDER BY 1, 2;

\echo ''
\echo '=== (4) 정책 변경 감사 기록 (ADMIN, 최근 50건) ==='
SELECT audit_id, event_type, resource_type, resource_id, outcome, created_at
FROM dwp_aura.audit_event_log
WHERE tenant_id = :tenantId
  AND event_category = 'ADMIN'
ORDER BY created_at DESC
LIMIT 50;
