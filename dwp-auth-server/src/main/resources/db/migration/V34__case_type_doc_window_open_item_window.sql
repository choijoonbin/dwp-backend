-- V34: Detect 배치용 case_type 추가 (DOC_WINDOW, OPEN_ITEM_WINDOW)
-- 목적: P0 케이스 필드 규칙 — window 종류 기반 분류. sys_codes CASE_TYPE에 ko/en 라벨 시드.
-- 참고: docs/job/PROMPT_BE_CASE_FIELD_RULES_AND_DEDUP_P0.txt

INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('CASE_TYPE', 'DOC_WINDOW', '전표 창', '전표 창', 'Document Window', 'Detect 배치: 전표(fi_doc_header) 창에서 탐지된 케이스', 85, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'OPEN_ITEM_WINDOW', '미결제 창', '미결제 창', 'Open Item Window', 'Detect 배치: 미결제(fi_open_item) 창에서 탐지된 케이스', 86, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;
