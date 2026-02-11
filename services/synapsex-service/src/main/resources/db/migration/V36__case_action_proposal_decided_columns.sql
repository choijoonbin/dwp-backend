-- V36: Phase3 proposal 결정 메타 (decided_by, decided_at, decision_comment)
-- POST decision (APPROVE/REJECT) 시 기록

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.case_action_proposal
ADD COLUMN IF NOT EXISTS decided_by BIGINT,
ADD COLUMN IF NOT EXISTS decided_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS decision_comment TEXT;

COMMENT ON COLUMN dwp_aura.case_action_proposal.decided_by IS '결정자 user_id (승인/거절 시)';
COMMENT ON COLUMN dwp_aura.case_action_proposal.decided_at IS '결정 시각';
COMMENT ON COLUMN dwp_aura.case_action_proposal.decision_comment IS '승인/거절 시 코멘트';
