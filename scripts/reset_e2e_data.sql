-- ======================================================================
-- E2E 재테스트 데이터 리셋 스크립트
-- 참고: docs/job/PROMPT_BE_E2E_RETEST_CHECKLIST_AND_DATA_RESET.txt
-- tenant_id 기준 파생 테이블 purge (원천 입력은 seed 재사용 시 유지)
-- ======================================================================
-- 사용: psql -h localhost -U dwp_user -d dwp_db -v tenant_id=1 -f scripts/reset_e2e_data.sql
-- 또는: psql ... -f scripts/reset_e2e_data.sql (기본 tenant_id=1)
-- ======================================================================

SET search_path TO dwp_aura, public;

\set tenant_id 1

-- 파생 테이블 purge (순서: FK 의존성 고려)
-- 1) agent_action (agent_case 참조)
DELETE FROM dwp_aura.agent_action WHERE tenant_id = :tenant_id;

-- 2) case_comment (agent_case 참조)
DELETE FROM dwp_aura.case_comment WHERE tenant_id = :tenant_id;

-- 3) agent_case
DELETE FROM dwp_aura.agent_case WHERE tenant_id = :tenant_id;

-- 4) audit_event_log
DELETE FROM dwp_aura.audit_event_log WHERE tenant_id = :tenant_id;

-- 5) detect_run
DELETE FROM dwp_aura.detect_run WHERE tenant_id = :tenant_id;

-- 6) ingest_run (있다면)
DELETE FROM dwp_aura.ingest_run WHERE tenant_id = :tenant_id;

-- 7) idempotency_key (있다면)
DELETE FROM dwp_aura.idempotency_key WHERE tenant_id = :tenant_id;

-- 8) agent_activity_log (있다면)
DELETE FROM dwp_aura.agent_activity_log WHERE tenant_id = :tenant_id;

-- 9) integration_outbox (있다면)
DELETE FROM dwp_aura.integration_outbox WHERE tenant_id = :tenant_id;

\echo 'E2E 데이터 리셋 완료 (tenant_id=' :tenant_id ')'
\echo '원천 입력(fi_doc_header, fi_open_item, bp_party)은 유지. seed 재실행 후 detect 배치 실행.'
