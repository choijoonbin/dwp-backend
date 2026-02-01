-- Aura CSV 대량 적재: sap_dummy 폴더의 CSV 5종 → dwp_aura 스키마
-- self_healing_load_csv_v1.sql 을 dwp_aura 스키마에 맞게 적용
--
-- 실행: ./scripts/run-aura-load-csv.sh
--   (기본 CSV 디렉터리: services/aura-service/src/main/resources/sap_dummy)
--   __CSVDIR__ 은 스크립트에서 실제 경로로 치환됨
-- ------------------------------------------------------------

\echo '==[1/6] setting search_path =='
SET search_path TO dwp_aura, public;

\echo '==[2/6] optional: truncate (dev only, __TRUNCATE__ 치환 시 실행) =='
__TRUNCATE__

\echo '==[3/6] load bp_party.csv =='
\copy dwp_aura.bp_party (tenant_id, party_type, party_code, name_display, country, created_on, is_one_time, risk_flags, last_change_ts, raw_event_id, updated_at) FROM '__CSVDIR__/bp_party.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

\echo '==[4/6] load fi_doc_header.csv =='
\copy dwp_aura.fi_doc_header (tenant_id, bukrs, belnr, gjahr, doc_source, budat, bldat, cpudt, cputm, usnam, tcode, blart, waers, kursf, xblnr, bktxt, status_code, reversal_belnr, last_change_ts, raw_event_id, created_at, updated_at) FROM '__CSVDIR__/fi_doc_header.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

\echo '==[5/6] load fi_doc_item.csv =='
\copy dwp_aura.fi_doc_item (tenant_id, bukrs, belnr, gjahr, buzei, hkont, bschl, shkzg, lifnr, kunnr, wrbtr, dmbtr, waers, mwskz, kostl, prctr, aufnr, zterm, zfbdt, due_date, payment_block, dispute_flag, zuonr, sgtxt, last_change_ts, raw_event_id, created_at) FROM '__CSVDIR__/fi_doc_item.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

\echo '==[6/6] load fi_open_item.csv =='
\copy dwp_aura.fi_open_item (tenant_id, bukrs, belnr, gjahr, buzei, item_type, lifnr, kunnr, baseline_date, zterm, due_date, open_amount, currency, cleared, clearing_date, payment_block, dispute_flag, last_change_ts, raw_event_id, last_update_ts) FROM '__CSVDIR__/fi_open_item.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

\echo '==[7/6] load sap_change_log.csv =='
\copy dwp_aura.sap_change_log (tenant_id, objectclas, objectid, changenr, username, udate, utime, tabname, fname, value_old, value_new, last_change_ts, raw_event_id) FROM '__CSVDIR__/sap_change_log.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

\echo '== done (agent_case_seed.csv 는 run-aura-seed.sh 로 별도 적재) =='
