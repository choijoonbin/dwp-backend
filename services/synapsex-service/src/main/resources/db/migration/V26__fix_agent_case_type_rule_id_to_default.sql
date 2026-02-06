-- V26: agent_case case_type가 rule_id(WINDOW_DOC_ENTRY, WINDOW_OPEN_ITEM)인 경우 DEFAULT로 보정
-- 목적: caseType은 sys_codes CASE_TYPE 코드여야 FE에서 라벨 조회 가능. rule_id는 dedup_key에만 사용.

UPDATE dwp_aura.agent_case
SET case_type = 'DEFAULT'
WHERE case_type IN ('WINDOW_DOC_ENTRY', 'WINDOW_OPEN_ITEM');
