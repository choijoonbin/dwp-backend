-- ======================================================================
-- fi_dummy_additions 충돌 점검 스크립트
-- ======================================================================
-- 더미 추가 데이터(bp_party_add, fi_doc_header_add, fi_doc_item_add, fi_open_item_add)
-- 삽입 전 기존 데이터와 키 충돌 여부를 확인합니다.
--
-- 실행: psql -U dwp_user -d dwp_aura -f scripts/seed/fi_dummy_additions_conflict_check.sql
-- ======================================================================

SET search_path TO dwp_aura, public;

\echo '==[1/4] bp_party 충돌 점검 =='
\echo '  기존 party_code (tenant_id=1, 최대 20건):'
SELECT party_type, party_code FROM dwp_aura.bp_party WHERE tenant_id = 1 ORDER BY party_type, party_code LIMIT 20;
\echo '  추가 예정: V101500~V101549, C200800~C200849'
\echo '  충돌 여부: 기존에 V101xxx 또는 C200xxx 있으면 충돌'
SELECT COUNT(*) AS "충돌_가능_건수(V101/C200_범위)" FROM dwp_aura.bp_party WHERE tenant_id = 1
  AND ((party_type = 'VENDOR' AND party_code ~ '^V101[0-9]{3}$') OR (party_type = 'CUSTOMER' AND party_code ~ '^C200[0-9]{3}$'));

\echo ''
\echo '==[2/4] fi_doc_header 충돌 점검 =='
\echo '  기존 belnr 범위 (tenant_id=1):'
SELECT COALESCE(MIN(belnr), '(없음)') AS min_belnr, COALESCE(MAX(belnr), '(없음)') AS max_belnr FROM dwp_aura.fi_doc_header WHERE tenant_id = 1;
\echo '  추가 예정: belnr 1000200000~1000200499'
SELECT COUNT(*) AS "충돌_건수(belnr_100020xxxx)" FROM dwp_aura.fi_doc_header
WHERE tenant_id = 1 AND belnr ~ '^100020[0-9]{4}$';

\echo ''
\echo '==[3/4] fi_doc_item 충돌 점검 =='
SELECT COUNT(*) AS "충돌_건수(belnr_100020xxxx)" FROM dwp_aura.fi_doc_item
WHERE tenant_id = 1 AND belnr ~ '^100020[0-9]{4}$';

\echo ''
\echo '==[4/4] fi_open_item 충돌 점검 =='
SELECT COUNT(*) AS "충돌_건수(belnr_100020xxxx)" FROM dwp_aura.fi_open_item
WHERE tenant_id = 1 AND belnr ~ '^100020[0-9]{4}$';

\echo ''
\echo '== 점검 완료 =='
\echo '  위 4개 충돌_건수가 모두 0이면 fi_dummy_additions_load.sql 실행 가능'
