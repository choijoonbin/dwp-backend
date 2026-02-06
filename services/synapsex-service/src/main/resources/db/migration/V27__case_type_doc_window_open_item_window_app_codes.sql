-- V27: Detect 배치용 case_type (DOC_WINDOW, OPEN_ITEM_WINDOW) app_codes 추가
-- 목적: Synapse 앱 전용 코드 — drill-down 필터 검증. 라벨은 auth sys_codes(CASE_TYPE) SoT.
-- 참고: docs/job/PROMPT_BE_CASE_FIELD_RULES_AND_DEDUP_P0.txt

SET search_path TO dwp_aura, public;

-- DRIVER_TYPE에 DOC_WINDOW, OPEN_ITEM_WINDOW 추가 (caseType 필터 검증용)
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('DRIVER_TYPE', 'DOC_WINDOW', 'Document Window', '전표 창에서 탐지', 55, true, now(), now()),
    ('DRIVER_TYPE', 'OPEN_ITEM_WINDOW', 'Open Item Window', '미결제 창에서 탐지', 56, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();
