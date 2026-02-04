-- ======================================================================
-- fi_dummy_additions 더미 데이터 적재 (dwp_aura 스키마)
-- ======================================================================
-- bp_party_add, fi_doc_header_add, fi_doc_item_add, fi_open_item_add CSV를
-- dwp_aura 테이블에 추가 삽입합니다. 충돌 시 ON CONFLICT DO NOTHING으로 건너뜀.
--
-- 실행: ./scripts/run-fi-dummy-additions.sh [-y] /path/to/fi_dummy_additions
-- ======================================================================

SET search_path TO dwp_aura, public;

-- 1) bp_party: 스테이징 후 ON CONFLICT DO NOTHING
\echo '==[1/4] bp_party_add.csv 적재 =='
CREATE TEMP TABLE _staging_bp_party (
  tenant_id BIGINT, party_type VARCHAR(10), party_code VARCHAR(40), name_display VARCHAR(200), country VARCHAR(3), created_on DATE, is_one_time BOOLEAN DEFAULT false, risk_flags JSONB DEFAULT '{}', last_change_ts TIMESTAMPTZ, raw_event_id BIGINT, updated_at TIMESTAMPTZ
);
\copy _staging_bp_party (tenant_id, party_type, party_code, name_display, country, created_on, is_one_time, risk_flags, last_change_ts, raw_event_id, updated_at) FROM '__CSVDIR__/bp_party_add.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8', NULL '');
INSERT INTO dwp_aura.bp_party (tenant_id, party_type, party_code, name_display, country, created_on, is_one_time, risk_flags, last_change_ts, raw_event_id, updated_at)
SELECT tenant_id, party_type, party_code, name_display, country, created_on, is_one_time, risk_flags, last_change_ts, raw_event_id, updated_at FROM _staging_bp_party
ON CONFLICT (tenant_id, party_type, party_code) DO NOTHING;
DROP TABLE _staging_bp_party;

-- 2) fi_doc_header: 스테이징 후 ON CONFLICT DO NOTHING (reversal_belnr VARCHAR(10) 초과 시 앞 10자리만)
\echo '==[2/4] fi_doc_header_add.csv 적재 =='
CREATE TEMP TABLE _staging_fi_doc_header (LIKE dwp_aura.fi_doc_header INCLUDING DEFAULTS);
ALTER TABLE _staging_fi_doc_header ALTER COLUMN reversal_belnr TYPE VARCHAR(20);
\copy _staging_fi_doc_header (tenant_id, bukrs, belnr, gjahr, doc_source, budat, bldat, cpudt, cputm, usnam, tcode, blart, waers, kursf, xblnr, bktxt, status_code, reversal_belnr, last_change_ts, raw_event_id, created_at, updated_at) FROM '__CSVDIR__/fi_doc_header_add.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8', NULL '');
INSERT INTO dwp_aura.fi_doc_header (tenant_id, bukrs, belnr, gjahr, doc_source, budat, bldat, cpudt, cputm, usnam, tcode, blart, waers, kursf, xblnr, bktxt, status_code, reversal_belnr, last_change_ts, raw_event_id, created_at, updated_at)
SELECT tenant_id, bukrs, belnr, gjahr, doc_source, budat, bldat, cpudt, cputm, usnam, tcode, blart, waers, kursf, xblnr, bktxt, status_code, LEFT(reversal_belnr, 10), last_change_ts, raw_event_id, created_at, updated_at FROM _staging_fi_doc_header
ON CONFLICT (tenant_id, bukrs, belnr, gjahr) DO NOTHING;
DROP TABLE _staging_fi_doc_header;

-- 3) fi_doc_item: 스테이징 후 ON CONFLICT DO NOTHING (fi_doc_header 선행 필요)
\echo '==[3/4] fi_doc_item_add.csv 적재 =='
CREATE TEMP TABLE _staging_fi_doc_item (LIKE dwp_aura.fi_doc_item INCLUDING DEFAULTS);
\copy _staging_fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, bschl, shkzg, lifnr, kunnr, wrbtr, dmbtr, waers, mwskz, kostl, prctr, aufnr, zterm, zfbdt, due_date, payment_block, dispute_flag, zuonr, sgtxt, last_change_ts, raw_event_id, created_at) FROM '__CSVDIR__/fi_doc_item_add.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8', NULL '');
INSERT INTO dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, bschl, shkzg, lifnr, kunnr, wrbtr, dmbtr, waers, mwskz, kostl, prctr, aufnr, zterm, zfbdt, due_date, payment_block, dispute_flag, zuonr, sgtxt, last_change_ts, raw_event_id, created_at)
SELECT tenant_id, bukrs, belnr, gjahr, buzei, hkont, bschl, shkzg, lifnr, kunnr, wrbtr, dmbtr, waers, mwskz, kostl, prctr, aufnr, zterm, zfbdt, due_date, payment_block, dispute_flag, zuonr, sgtxt, last_change_ts, raw_event_id, created_at FROM _staging_fi_doc_item
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;
DROP TABLE _staging_fi_doc_item;

-- 4) fi_open_item: 스테이징 후 ON CONFLICT DO NOTHING
\echo '==[4/4] fi_open_item_add.csv 적재 =='
CREATE TEMP TABLE _staging_fi_open_item (LIKE dwp_aura.fi_open_item INCLUDING DEFAULTS);
\copy _staging_fi_open_item (tenant_id, bukrs, belnr, gjahr, buzei, item_type, lifnr, kunnr, baseline_date, zterm, due_date, open_amount, currency, cleared, clearing_date, payment_block, dispute_flag, last_change_ts, raw_event_id, last_update_ts) FROM '__CSVDIR__/fi_open_item_add.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8', NULL '');
INSERT INTO dwp_aura.fi_open_item (tenant_id, bukrs, belnr, gjahr, buzei, item_type, lifnr, kunnr, baseline_date, zterm, due_date, open_amount, currency, cleared, clearing_date, payment_block, dispute_flag, last_change_ts, raw_event_id, last_update_ts)
SELECT tenant_id, bukrs, belnr, gjahr, buzei, item_type, lifnr, kunnr, baseline_date, zterm, due_date, open_amount, currency, cleared, clearing_date, payment_block, dispute_flag, last_change_ts, raw_event_id, last_update_ts FROM _staging_fi_open_item
ON CONFLICT (tenant_id, bukrs, belnr, gjahr, buzei) DO NOTHING;
DROP TABLE _staging_fi_open_item;

\echo '== fi_dummy_additions 적재 완료 =='
