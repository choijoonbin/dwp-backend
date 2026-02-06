-- V28: dedup_key/case_type P0 형식으로 보정
-- 목적: tenant:case_type:sourceType:bukrs-belnr-gjahr-buzei 형식 + DOC_WINDOW/OPEN_ITEM_WINDOW
-- 참고: docs/job/PROMPT_BE_CASE_FIELD_RULES_AND_DEDUP_P0.txt

SET search_path TO dwp_aura, public;

-- 1) WINDOW_DOC_ENTRY 형식 → DOC_WINDOW:DOC
UPDATE dwp_aura.agent_case
SET
    case_type = 'DOC_WINDOW',
    dedup_key = tenant_id || ':DOC_WINDOW:DOC:' || bukrs || '-' || belnr || '-' || gjahr || '-_'
WHERE dedup_key LIKE '%:WINDOW_DOC_ENTRY:%'
   OR (case_type = 'WINDOW_DOC_ENTRY');

-- 2) WINDOW_OPEN_ITEM 형식 → OPEN_ITEM_WINDOW:OPEN_ITEM
UPDATE dwp_aura.agent_case
SET
    case_type = 'OPEN_ITEM_WINDOW',
    dedup_key = tenant_id || ':OPEN_ITEM_WINDOW:OPEN_ITEM:' || bukrs || '-' || belnr || '-' || gjahr || '-' || COALESCE(buzei, '001')
WHERE dedup_key LIKE '%:WINDOW_OPEN_ITEM:%'
   OR (case_type = 'WINDOW_OPEN_ITEM');
