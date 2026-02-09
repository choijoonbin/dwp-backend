-- V35: Phase2 proposals requiresApproval (Aura 추가 스펙)
-- FE 승인 플로우에서 사용

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.case_action_proposal
ADD COLUMN IF NOT EXISTS requires_approval BOOLEAN;

COMMENT ON COLUMN dwp_aura.case_action_proposal.requires_approval IS '승인 필요 여부 (Aura proposals.requiresApproval)';
