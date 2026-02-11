-- V41: 정상(규정 준수) 대조군 전표 2건 추가
-- DEMO_NORM_01: 평일 12:30 식대 인당 15,000원 (제14조 준수)
-- DEMO_NORM_02: 평일 14:00 사무용품 50,000원 (정상 지출)
-- belnr는 VARCHAR(10) 제한으로 NORM00001, NORM00002 사용; 시맨틱명은 xblnr에 표기

SET search_path TO dwp_aura, public;

-- 1. sap_raw_events 2건 (유니크: tenant_id, source_system, interface_name, extract_date, checksum)
INSERT INTO dwp_aura.sap_raw_events (tenant_id, source_system, interface_name, extract_date, payload_format, checksum, status, created_at)
VALUES
  (1, 'SAP_ECC', 'FI_DOCUMENT', CURRENT_DATE, 'JSON', 'V41-DEMO-NORM-01', 'RECEIVED', CURRENT_TIMESTAMP),
  (1, 'SAP_ECC', 'FI_DOCUMENT', CURRENT_DATE, 'JSON', 'V41-DEMO-NORM-02', 'RECEIVED', CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, source_system, interface_name, extract_date, checksum) DO NOTHING;

-- 2. fi_doc_header 2건 (정상 전표)
INSERT INTO dwp_aura.fi_doc_header (
  tenant_id, bukrs, belnr, gjahr, doc_source, budat, waers, xblnr, status_code,
  raw_event_id, created_at, updated_at
)
SELECT 1, '1000', 'NORM00001', '2025', 'SAP', CURRENT_DATE - 1, 'KRW', 'DEMO_NORM_01 Article14', 'POSTED',
  (SELECT id FROM dwp_aura.sap_raw_events WHERE tenant_id = 1 AND checksum = 'V41-DEMO-NORM-01' LIMIT 1),
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.fi_doc_header WHERE tenant_id = 1 AND bukrs = '1000' AND belnr = 'NORM00001' AND gjahr = '2025');

INSERT INTO dwp_aura.fi_doc_header (
  tenant_id, bukrs, belnr, gjahr, doc_source, budat, waers, xblnr, status_code,
  raw_event_id, created_at, updated_at
)
SELECT 1, '1000', 'NORM00002', '2025', 'SAP', CURRENT_DATE - 1, 'KRW', 'DEMO_NORM_02 Office 50K', 'POSTED',
  (SELECT id FROM dwp_aura.sap_raw_events WHERE tenant_id = 1 AND checksum = 'V41-DEMO-NORM-02' LIMIT 1),
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM dwp_aura.fi_doc_header WHERE tenant_id = 1 AND bukrs = '1000' AND belnr = 'NORM00002' AND gjahr = '2025');

-- 3. fi_doc_item (전표별 1라인: 식대 15,000원 / 사무용품 50,000원)
INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, created_at)
SELECT 1, '1000', 'NORM00001', '2025', '001', '600000', 'V001', 15000, 'KRW', CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM dwp_aura.fi_doc_header WHERE tenant_id = 1 AND bukrs = '1000' AND belnr = 'NORM00001' AND gjahr = '2025')
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;

INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, lifnr, wrbtr, waers, created_at)
SELECT 1, '1000', 'NORM00002', '2025', '001', '600000', 'V002', 50000, 'KRW', CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM dwp_aura.fi_doc_header WHERE tenant_id = 1 AND bukrs = '1000' AND belnr = 'NORM00002' AND gjahr = '2025')
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;
