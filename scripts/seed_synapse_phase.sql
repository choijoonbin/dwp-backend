-- ======================================================================
-- Synapse Phase 시드 스크립트 (tenant=1 기준)
-- 실행: psql -h localhost -U dwp_user -d dwp_aura -f scripts/seed_synapse_phase.sql
-- 전제: Flyway 마이그레이션 완료 후 실행
-- 목적: documents, open-items, entities, cases, actions, audit 최소 데이터
--       seed가 없는 화면은 empty-state로 통과, 500 금지
-- ======================================================================

SET search_path TO dwp_aura, public;

-- 1) FI 전표 (documents)
INSERT INTO dwp_aura.fi_doc_header (tenant_id, bukrs, belnr, gjahr, doc_source, budat, waers, xblnr)
VALUES (1, '1000', '1900000001', '2024', 'SAP', '2024-01-15', 'KRW', 'INV-001')
ON CONFLICT (tenant_id, bukrs, belnr, gjahr) DO NOTHING;

INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers)
SELECT 1, '1000', '1900000001', '2024', '001', '600000', 'V001', 1000000, 'KRW'
WHERE EXISTS (SELECT 1 FROM dwp_aura.fi_doc_header WHERE tenant_id=1 AND bukrs='1000' AND belnr='1900000001' AND gjahr='2024')
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

-- 2) Open Items (AP/AR)
INSERT INTO dwp_aura.fi_open_item (tenant_id, bukrs, belnr, gjahr, buzei, item_type, lifnr, due_date, open_amount, currency, cleared)
SELECT 1, '1000', '1900000001', '2024', '001', 'AP'::dwp_aura.open_item_type, 'V001', '2024-02-15', 1000000, 'KRW', false
WHERE EXISTS (SELECT 1 FROM dwp_aura.fi_doc_header WHERE tenant_id=1 AND bukrs='1000' AND belnr='1900000001' AND gjahr='2024')
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

-- 3) Business Partner (entities)
INSERT INTO dwp_aura.bp_party (tenant_id, party_type, party_code, name_display, risk_flags)
VALUES (1, 'VENDOR', 'V001', 'Test Vendor Co', '{}'::jsonb)
ON CONFLICT (tenant_id, party_type, party_code) DO NOTHING;

INSERT INTO dwp_aura.bp_party (tenant_id, party_type, party_code, name_display, risk_flags)
VALUES (1, 'CUSTOMER', 'C001', 'Test Customer Co', '{}'::jsonb)
ON CONFLICT (tenant_id, party_type, party_code) DO NOTHING;

-- 4) Agent Case (cases) - 없을 때만 삽입
INSERT INTO dwp_aura.agent_case (tenant_id, detected_at, bukrs, belnr, gjahr, buzei, case_type, severity, status)
SELECT 1, now(), '1000', '1900000001', '2024', '001', 'DUPLICATE_INVOICE', 'HIGH', 'OPEN'
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.agent_case WHERE tenant_id=1 AND bukrs='1000' AND belnr='1900000001');

-- 5) Agent Action (actions) - case 존재 시 action 없는 case에 1건 삽입
INSERT INTO dwp_aura.agent_action (tenant_id, case_id, action_type, status, planned_at, executed_by)
SELECT 1, sub.case_id, 'PAYMENT_BLOCK', 'PROPOSED', now(), 'PENDING'
FROM (
  SELECT c.case_id FROM dwp_aura.agent_case c
  WHERE c.tenant_id = 1
    AND NOT EXISTS (SELECT 1 FROM dwp_aura.agent_action a WHERE a.case_id = c.case_id)
  ORDER BY c.case_id
  LIMIT 1
) sub;

-- 6) Audit Event Log (audit)
INSERT INTO dwp_aura.audit_event_log (tenant_id, event_category, event_type, resource_type, resource_id, actor_type, channel, outcome, severity)
VALUES (1, 'ACTION', 'CASE_VIEW_LIST', 'CASE_LIST', null, 'HUMAN', 'API', 'SUCCESS', 'INFO');

\echo 'Synapse Phase seed 완료 (tenant=1)'
