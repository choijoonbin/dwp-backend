-- ======================================================================
-- Phase2~4 시드 스크립트 (tenant=1 기준)
-- 스펙: documents 5, open-items 5, entities 3, cases 3, actions 2, audit 10
-- 실행: ./scripts/run_synapse_seed.sh 또는 psql -f scripts/seed/phase2-4-seed.sql
-- 전제: Flyway 마이그레이션 완료 후 실행
-- ======================================================================

SET search_path TO dwp_aura, public;

-- 1) Documents (fi_doc_header 5건 + fi_doc_item)
-- created_at을 10분 전으로 설정 → Detect 배치(기본 15분 윈도우)가 탐지 가능하도록
INSERT INTO dwp_aura.fi_doc_header (tenant_id, bukrs, belnr, gjahr, doc_source, budat, waers, xblnr, created_at, updated_at)
VALUES
  (1, '1000', '1900000001', '2024', 'SAP', '2024-01-15', 'KRW', 'INV-001', now() - interval '10 minutes', now()),
  (1, '1000', '1900000002', '2024', 'SAP', '2024-01-16', 'KRW', 'INV-002', now() - interval '10 minutes', now()),
  (1, '1000', '1900000003', '2024', 'SAP', '2024-01-17', 'KRW', 'INV-003', now() - interval '10 minutes', now()),
  (1, '1000', '1900000004', '2024', 'SAP', '2024-01-18', 'KRW', 'INV-004', now() - interval '10 minutes', now()),
  (1, '1000', '1900000005', '2024', 'SAP', '2024-01-19', 'KRW', 'INV-005', now() - interval '10 minutes', now())
ON CONFLICT (tenant_id, bukrs, belnr, gjahr) DO UPDATE SET
  created_at = EXCLUDED.created_at,
  updated_at = EXCLUDED.updated_at;

INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, created_at)
SELECT 1, h.bukrs, h.belnr, h.gjahr, '001', '600000', 'V001', 1000000, 'KRW', now()
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = 1 AND h.bukrs = '1000' AND h.belnr IN ('1900000001','1900000002','1900000003','1900000004','1900000005')
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

-- 2) Open Items (5건)
-- last_update_ts를 10분 전으로 설정 → Detect 배치(기본 15분 윈도우)가 탐지 가능하도록
INSERT INTO dwp_aura.fi_open_item (tenant_id, bukrs, belnr, gjahr, buzei, item_type, lifnr, due_date, open_amount, currency, cleared, last_update_ts)
VALUES
  (1, '1000', '1900000001', '2024', '001', 'AP'::dwp_aura.open_item_type, 'V001', '2024-02-15', 1000000, 'KRW', false, now() - interval '10 minutes'),
  (1, '1000', '1900000002', '2024', '001', 'AP'::dwp_aura.open_item_type, 'V001', '2024-02-16', 500000, 'KRW', false, now() - interval '10 minutes'),
  (1, '1000', '1900000003', '2024', '001', 'AR'::dwp_aura.open_item_type, 'C001', '2024-02-17', 750000, 'KRW', false, now() - interval '10 minutes'),
  (1, '1000', '1900000004', '2024', '001', 'AP'::dwp_aura.open_item_type, 'V002', '2024-02-18', 2000000, 'KRW', false, now() - interval '10 minutes'),
  (1, '1000', '1900000005', '2024', '001', 'AR'::dwp_aura.open_item_type, 'C001', '2024-02-19', 300000, 'KRW', false, now() - interval '10 minutes')
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO UPDATE SET
  last_update_ts = EXCLUDED.last_update_ts;

-- 3) Entities (bp_party 3건)
INSERT INTO dwp_aura.bp_party (tenant_id, party_type, party_code, name_display, risk_flags)
VALUES
  (1, 'VENDOR', 'V001', 'Test Vendor Co', '{}'::jsonb),
  (1, 'CUSTOMER', 'C001', 'Test Customer Co', '{}'::jsonb),
  (1, 'VENDOR', 'V002', 'Second Vendor Inc', '{}'::jsonb)
ON CONFLICT (tenant_id, party_type, party_code) DO NOTHING;

-- 4) Agent Cases (3건) - 없을 때만 삽입
-- dedup_key: Detect 배치와 동일 형식(tenant:rule:entityKey) → 배치 실행 시 caseUpdated로 연결, last_detect_run_id 자동 설정
INSERT INTO dwp_aura.agent_case (tenant_id, detected_at, bukrs, belnr, gjahr, buzei, case_type, severity, status, dedup_key)
SELECT 1, now(), '1000', '1900000001', '2024', '001', 'DUPLICATE_INVOICE', 'HIGH', 'OPEN', '1:WINDOW_DOC_ENTRY:1000-1900000001-2024'
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.agent_case WHERE tenant_id=1 AND bukrs='1000' AND belnr='1900000001');
INSERT INTO dwp_aura.agent_case (tenant_id, detected_at, bukrs, belnr, gjahr, buzei, case_type, severity, status, dedup_key)
SELECT 1, now(), '1000', '1900000002', '2024', '001', 'ANOMALY_AMOUNT', 'MEDIUM', 'IN_PROGRESS', '1:WINDOW_DOC_ENTRY:1000-1900000002-2024'
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.agent_case WHERE tenant_id=1 AND bukrs='1000' AND belnr='1900000002');
INSERT INTO dwp_aura.agent_case (tenant_id, detected_at, bukrs, belnr, gjahr, buzei, case_type, severity, status, dedup_key)
SELECT 1, now(), '1000', '1900000003', '2024', '001', 'MISSING_EVIDENCE', 'LOW', 'OPEN', '1:WINDOW_DOC_ENTRY:1000-1900000003-2024'
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.agent_case WHERE tenant_id=1 AND bukrs='1000' AND belnr='1900000003');

-- 5) Agent Actions (2건) - action 없는 case 2건에 삽입
INSERT INTO dwp_aura.agent_action (tenant_id, case_id, action_type, status, planned_at, executed_by)
SELECT 1, sub.case_id, 'PAYMENT_BLOCK', 'PROPOSED', now(), 'PENDING'
FROM (
  SELECT c.case_id FROM dwp_aura.agent_case c
  WHERE c.tenant_id = 1
    AND NOT EXISTS (SELECT 1 FROM dwp_aura.agent_action a WHERE a.case_id = c.case_id)
  ORDER BY c.case_id
  LIMIT 2
) sub;

-- 6) Audit Event Log (10건, 이벤트 종류 다양화)
INSERT INTO dwp_aura.audit_event_log (tenant_id, event_category, event_type, resource_type, resource_id, actor_type, channel, outcome, severity)
VALUES
  (1, 'ACTION', 'CASE_VIEW_LIST', 'CASE_LIST', null, 'HUMAN', 'API', 'SUCCESS', 'INFO'),
  (1, 'CASE', 'STATUS_CHANGE', 'AGENT_CASE', '1', 'HUMAN', 'API', 'SUCCESS', 'INFO'),
  (1, 'CASE', 'CASE_ASSIGN', 'AGENT_CASE', '1', 'HUMAN', 'API', 'SUCCESS', 'INFO'),
  (1, 'CASE', 'CASE_COMMENT_CREATE', 'AGENT_CASE', '1', 'HUMAN', 'API', 'SUCCESS', 'INFO'),
  (1, 'ACTION', 'SIMULATE', 'AGENT_ACTION', '1', 'HUMAN', 'API', 'SUCCESS', 'INFO'),
  (1, 'ACTION', 'APPROVE', 'AGENT_ACTION', '1', 'HUMAN', 'API', 'SUCCESS', 'INFO'),
  (1, 'ACTION', 'EXECUTE', 'AGENT_ACTION', '1', 'HUMAN', 'API', 'SUCCESS', 'INFO'),
  (1, 'POLICY', 'POLICY_CHANGE', 'rule_threshold', '1', 'HUMAN', 'API', 'SUCCESS', 'INFO'),
  (1, 'POLICY', 'GUARDRAIL_CHANGE', 'POLICY_GUARDRAIL', '1', 'HUMAN', 'API', 'SUCCESS', 'INFO'),
  (1, 'INTEGRATION', 'INTEGRATION_OUTBOX_ENQUEUE', 'INTEGRATION_OUTBOX', '1', 'SYSTEM', 'INTEGRATION', 'SUCCESS', 'INFO');

\echo 'Phase2~4 seed 완료 (documents 5, open-items 5, entities 3, cases 3, actions 2, audit 10)'
