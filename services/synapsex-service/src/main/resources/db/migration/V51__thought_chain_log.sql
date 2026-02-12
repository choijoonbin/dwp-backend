-- V51: AI 사고 과정 로그 — 시연 후 근거 조회용
-- 목적: Aura Thought Chain 스트리밍 시 수신한 thought/step 이벤트를 DB에 저장하여 상세 페이지에서 재조회 가능

SET search_path TO dwp_aura, public;

CREATE TABLE IF NOT EXISTS dwp_aura.thought_chain_log (
  log_id       BIGSERIAL PRIMARY KEY,
  run_id       UUID NOT NULL REFERENCES dwp_aura.case_analysis_run(run_id) ON DELETE CASCADE,
  tenant_id    BIGINT NOT NULL,
  case_id      BIGINT NOT NULL,
  event_type   VARCHAR(50) NOT NULL,
  data         TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_thought_chain_log_run
ON dwp_aura.thought_chain_log(run_id, created_at);
CREATE INDEX IF NOT EXISTS ix_thought_chain_log_tenant_case
ON dwp_aura.thought_chain_log(tenant_id, case_id);

COMMENT ON TABLE dwp_aura.thought_chain_log IS 'AI 분석 시 사고 과정(Thought Chain) 로그 — run별 시간순 저장';
