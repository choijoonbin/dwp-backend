-- V39: Phase3 execute(sim) 스펙 확정 — request_json, action_type 저장, proposal_id nullable (대안 B)
-- POST /api/synapse/actions/execute 대안 B(actionType+payload) 시 proposal 없이 실행 기록 가능

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.case_action_execution
  ADD COLUMN IF NOT EXISTS request_json JSONB,
  ADD COLUMN IF NOT EXISTS action_type VARCHAR(64);

ALTER TABLE dwp_aura.case_action_execution
  ALTER COLUMN proposal_id DROP NOT NULL;

COMMENT ON COLUMN dwp_aura.case_action_execution.request_json IS '요청 본문(멱등/감사용)';
COMMENT ON COLUMN dwp_aura.case_action_execution.action_type IS '실행한 액션 유형(PAYMENT_BLOCK 등), proposal 없을 때 필수';

CREATE INDEX IF NOT EXISTS ix_case_action_execution_tenant_case_run
  ON dwp_aura.case_action_execution(tenant_id, case_id, run_id, executed_at DESC);
