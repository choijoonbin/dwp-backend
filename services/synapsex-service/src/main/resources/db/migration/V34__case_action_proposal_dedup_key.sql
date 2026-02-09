-- V34: Phase2 202 action_proposal 멱등성 - dedup_key + UNIQUE
-- 동일 run 내 중복 제안 방지: UNIQUE(case_id, run_id, dedup_key)

SET search_path TO dwp_aura, public;

-- 1. dedup_key 컬럼 추가 (nullable initially for backfill)
ALTER TABLE dwp_aura.case_action_proposal
ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(64);

-- 2. 기존 행 backfill: proposal_id 기반 고유값 (기존 데이터 보존)
UPDATE dwp_aura.case_action_proposal
SET dedup_key = 'legacy-' || proposal_id::text
WHERE dedup_key IS NULL;

-- 3. NOT NULL + UNIQUE 제약
ALTER TABLE dwp_aura.case_action_proposal
ALTER COLUMN dedup_key SET NOT NULL;

-- 4. 중복 방지: (case_id, run_id, dedup_key) UNIQUE
-- run_id NULL인 legacy는 배제 (새 proposal은 항상 run_id 있음)
CREATE UNIQUE INDEX IF NOT EXISTS uk_case_action_proposal_case_run_dedup
ON dwp_aura.case_action_proposal(case_id, run_id, dedup_key)
WHERE run_id IS NOT NULL;

-- run_id NULL인 기존 데이터: dedup_key만 unique로 (legacy)
CREATE UNIQUE INDEX IF NOT EXISTS uk_case_action_proposal_legacy_dedup
ON dwp_aura.case_action_proposal(case_id, dedup_key)
WHERE run_id IS NULL;

COMMENT ON COLUMN dwp_aura.case_action_proposal.dedup_key IS '멱등 키: sha256(lower(type)|canonicalize(payload)|normalize(rationale))';
