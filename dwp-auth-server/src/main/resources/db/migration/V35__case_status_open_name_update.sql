-- V35: CASE_STATUS OPEN 코드 표시명 변경
-- 목적: OPEN 상태를 "신규/오픈(미해결)"로 관리

UPDATE sys_codes
SET name = '신규/오픈(미해결)',
    name_ko = '신규/오픈(미해결)',
    name_en = 'New/Open (Unresolved)',
    updated_at = CURRENT_TIMESTAMP
WHERE group_key = 'CASE_STATUS' AND code = 'OPEN';
