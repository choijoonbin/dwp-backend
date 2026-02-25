-- V74: agent_case 소유권 상속 컬럼 추가 + case_explanation 조회 인덱스 보강
SET search_path TO dwp_aura, public;

-- 1) 케이스 소유자 컬럼 추가
ALTER TABLE dwp_aura.agent_case
  ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2) 기존 케이스 backfill (전표 소유자 상속)
UPDATE dwp_aura.agent_case c
SET user_id = h.user_id
FROM dwp_aura.fi_doc_header h
WHERE c.user_id IS NULL
  AND c.tenant_id = h.tenant_id
  AND c.bukrs = h.bukrs
  AND c.belnr = h.belnr
  AND c.gjahr = h.gjahr;

CREATE INDEX IF NOT EXISTS idx_agent_case_user_id
  ON dwp_aura.agent_case (user_id);

COMMENT ON COLUMN dwp_aura.agent_case.user_id IS '케이스 소유자 식별자 (전표 작성자 user_id 상속)';

-- 3) 소명 조회 경로 단순화용 인덱스
CREATE INDEX IF NOT EXISTS idx_case_explanation_case_id
  ON dwp_aura.case_explanation (case_id);
