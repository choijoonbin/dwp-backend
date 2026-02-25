-- V72: CASE_STATUS 표준 7개로 통합, 나머지 Deprecated
-- 표준 7개: ANALYZING, NEW, IN_REVIEW, PENDING_EXPLANATION, PENDING_APPROVAL, RESOLVED, IGNORED
-- 데이터 이관: OPEN/TRIAGED→NEW, IN_PROGRESS→IN_REVIEW, DISMISSED/REJECTED→IGNORED, APPROVED/ACTIONED/CLOSED→RESOLVED

SET search_path TO dwp_aura, public;

-- enum에 PENDING_APPROVAL 추가 (7개 표준에 필요)
DO $$ BEGIN ALTER TYPE dwp_aura.agent_case_status ADD VALUE 'PENDING_APPROVAL'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 1) agent_case 데이터 이관
UPDATE dwp_aura.agent_case SET status = 'NEW'::dwp_aura.agent_case_status
WHERE status IN ('OPEN', 'TRIAGED');

UPDATE dwp_aura.agent_case SET status = 'IN_REVIEW'::dwp_aura.agent_case_status
WHERE status = 'IN_PROGRESS';

UPDATE dwp_aura.agent_case SET status = 'IGNORED'::dwp_aura.agent_case_status
WHERE status IN ('DISMISSED', 'REJECTED');

UPDATE dwp_aura.agent_case SET status = 'RESOLVED'::dwp_aura.agent_case_status
WHERE status IN ('APPROVED', 'ACTIONED', 'CLOSED');

-- 2) app_codes CASE_STATUS: 표준 7개만 활성, 나머지 Deprecated(is_active=false)
UPDATE dwp_aura.app_codes
SET is_active = false, updated_at = now()
WHERE group_key = 'CASE_STATUS'
  AND code NOT IN ('ANALYZING', 'NEW', 'IN_REVIEW', 'PENDING_EXPLANATION', 'PENDING_APPROVAL', 'RESOLVED', 'IGNORED');

-- 표준 7개 존재 보장 및 활성화
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('CASE_STATUS', 'ANALYZING', 'Analyzing', '분석 중', 3, true, now(), now()),
  ('CASE_STATUS', 'NEW', 'New', '신규', 5, true, now(), now()),
  ('CASE_STATUS', 'IN_REVIEW', 'In Review', '검토중', 20, true, now(), now()),
  ('CASE_STATUS', 'PENDING_EXPLANATION', 'Pending Explanation', '소명 요청', 35, true, now(), now()),
  ('CASE_STATUS', 'PENDING_APPROVAL', 'Pending Approval', '승인대기', 30, true, now(), now()),
  ('CASE_STATUS', 'RESOLVED', 'Resolved', '해결됨', 60, true, now(), now()),
  ('CASE_STATUS', 'IGNORED', 'Ignored', '무시됨', 76, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  sort_order = EXCLUDED.sort_order,
  is_active = true,
  updated_at = now();
