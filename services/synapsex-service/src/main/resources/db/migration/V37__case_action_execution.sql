-- V37: Phase3 case_action_execution — 실행(시뮬) 결과 저장
-- POST .../action-proposals/{proposalId}/execute 시 기록

SET search_path TO dwp_aura, public;

CREATE TABLE IF NOT EXISTS dwp_aura.case_action_execution (
  execution_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      BIGINT NOT NULL,
  case_id        BIGINT NOT NULL,
  run_id         UUID REFERENCES dwp_aura.case_analysis_run(run_id) ON DELETE SET NULL,
  proposal_id    UUID NOT NULL REFERENCES dwp_aura.case_action_proposal(proposal_id) ON DELETE CASCADE,
  mode           VARCHAR(20) NOT NULL DEFAULT 'SIMULATION',
  status         VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
  result_json    JSONB,
  error_message  TEXT,
  executed_by    BIGINT,
  executed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_case_action_execution_tenant_case
ON dwp_aura.case_action_execution(tenant_id, case_id);
CREATE INDEX IF NOT EXISTS ix_case_action_execution_proposal
ON dwp_aura.case_action_execution(proposal_id);

COMMENT ON TABLE dwp_aura.case_action_execution IS 'Phase3: 액션 제안 실행(시뮬) 결과';
COMMENT ON COLUMN dwp_aura.case_action_execution.mode IS 'SIMULATION | LIVE';
COMMENT ON COLUMN dwp_aura.case_action_execution.status IS 'COMPLETED | FAILED';
