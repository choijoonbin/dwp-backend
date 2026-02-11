-- V40: dwp_aura 전표/케이스/로그 데이터 정리 및 시연용 골든 시나리오 시드
-- 1) Purge: Master Data 제외, 트랜잭션/케이스/로그 테이블 TRUNCATE (FK 순서 준수)
-- 2) Seed: 시연용 전표 3종(중복·정책위반·지연미결) — sap_raw_events + fi_doc_header

SET search_path TO dwp_aura, public;

-- ========================================
-- Part 1: Data Purge (TRUNCATE, 의존성 순서)
-- Master Data 제외: config_profile, app_codes, policy_*, md_*, tenant_*_scope,
--   rag_document, dictionary_term, feedback_label, bp_party 등 유지
-- ========================================

-- 자식 먼저 (FK 참조 제거)
TRUNCATE TABLE dwp_aura.case_action_execution RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.case_action_proposal RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.case_analysis_result RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.case_analysis_run RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.agent_action RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.case_comment RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.policy_suggestion RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.agent_case RESTART IDENTITY CASCADE;

TRUNCATE TABLE dwp_aura.audit_event_log RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.agent_activity_log RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.synapse_audit_event_log RESTART IDENTITY CASCADE;

TRUNCATE TABLE dwp_aura.detect_run RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.ingest_run RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.recon_result RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.recon_run RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.analytics_kpi_daily RESTART IDENTITY CASCADE;

TRUNCATE TABLE dwp_aura.integration_outbox RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.idempotency_key RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.agent_action_simulation RESTART IDENTITY CASCADE;

TRUNCATE TABLE dwp_aura.ingestion_errors RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.fi_doc_item RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.fi_open_item RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.fi_doc_header RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.sap_change_log RESTART IDENTITY CASCADE;
TRUNCATE TABLE dwp_aura.sap_raw_events RESTART IDENTITY CASCADE;

-- ========================================
-- Part 2: Demo Seed — 시연용 전표 3종 (Golden Scenario)
-- 중복(DUPLICATE), 정책위반(THRESHOLD_BREACH), 지연미결(OPEN_ITEM 지연)
-- ========================================

-- 2.1 sap_raw_events 3건 (checksum 상이로 유니크 충족)
INSERT INTO dwp_aura.sap_raw_events (tenant_id, source_system, interface_name, extract_date, payload_format, checksum, status, created_at)
VALUES
  (1, 'SAP_ECC', 'FI_DOCUMENT', CURRENT_DATE, 'JSON', 'V40-DEMO-SEED-01', 'RECEIVED', CURRENT_TIMESTAMP),
  (1, 'SAP_ECC', 'FI_DOCUMENT', CURRENT_DATE, 'JSON', 'V40-DEMO-SEED-02', 'RECEIVED', CURRENT_TIMESTAMP),
  (1, 'SAP_ECC', 'FI_DOCUMENT', CURRENT_DATE, 'JSON', 'V40-DEMO-SEED-03', 'RECEIVED', CURRENT_TIMESTAMP);

-- 2.2 fi_doc_header 3건 (중복/정책위반/지연미결 시연용, raw_event_id 연결)
INSERT INTO dwp_aura.fi_doc_header (
  tenant_id, bukrs, belnr, gjahr, doc_source, budat, waers, xblnr, status_code,
  raw_event_id, created_at, updated_at
)
SELECT 1, '1000', 'DEMO00001', '2025', 'SAP', CURRENT_DATE - 5, 'KRW', 'INV-DUP-001', 'POSTED',
  (SELECT id FROM dwp_aura.sap_raw_events WHERE tenant_id = 1 AND checksum = 'V40-DEMO-SEED-01' LIMIT 1),
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP;

INSERT INTO dwp_aura.fi_doc_header (
  tenant_id, bukrs, belnr, gjahr, doc_source, budat, waers, xblnr, status_code,
  raw_event_id, created_at, updated_at
)
SELECT 1, '1000', 'DEMO00002', '2025', 'SAP', CURRENT_DATE - 3, 'KRW', 'INV-POL-002', 'POSTED',
  (SELECT id FROM dwp_aura.sap_raw_events WHERE tenant_id = 1 AND checksum = 'V40-DEMO-SEED-02' LIMIT 1),
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP;

INSERT INTO dwp_aura.fi_doc_header (
  tenant_id, bukrs, belnr, gjahr, doc_source, budat, waers, xblnr, status_code,
  raw_event_id, created_at, updated_at
)
SELECT 1, '1000', 'DEMO00003', '2025', 'SAP', CURRENT_DATE - 30, 'KRW', 'INV-OVD-003', 'POSTED',
  (SELECT id FROM dwp_aura.sap_raw_events WHERE tenant_id = 1 AND checksum = 'V40-DEMO-SEED-03' LIMIT 1),
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP;

-- 2.3 fi_doc_item (전표별 1라인 — 시연용)
INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, created_at)
SELECT h.tenant_id, h.bukrs, h.belnr, h.gjahr, '001', '600000', 'V001', 1500000, 'KRW', h.created_at
FROM dwp_aura.fi_doc_header h
WHERE h.tenant_id = 1 AND h.bukrs = '1000' AND h.belnr IN ('DEMO00001','DEMO00002','DEMO00003');
