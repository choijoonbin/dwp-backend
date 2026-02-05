-- Phase B: Detect Run — 탐지 배치 실행 단위
-- window_from/to 기반, case upsert 결과 요약 저장

SET search_path TO dwp_aura, public;

CREATE TABLE IF NOT EXISTS dwp_aura.detect_run (
  run_id         BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  window_from    TIMESTAMPTZ NOT NULL,
  window_to      TIMESTAMPTZ NOT NULL,
  status         VARCHAR(20) NOT NULL DEFAULT 'STARTED',
  counts_json    JSONB,
  error_message  TEXT,
  started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_detect_run_tenant_created
ON dwp_aura.detect_run(tenant_id, started_at DESC);

CREATE INDEX IF NOT EXISTS ix_detect_run_tenant_status
ON dwp_aura.detect_run(tenant_id, status);

COMMENT ON TABLE dwp_aura.detect_run IS 'Phase B: 탐지 배치 실행. window_from/to, case_created/updated 요약.';
COMMENT ON COLUMN dwp_aura.detect_run.status IS 'STARTED | COMPLETED | FAILED';
COMMENT ON COLUMN dwp_aura.detect_run.counts_json IS '{"caseCreated":N,"caseUpdated":N}';

-- agent_case: dedup_key 추가 (Phase B Case Upsert)
ALTER TABLE dwp_aura.agent_case
  ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(200);

CREATE UNIQUE INDEX IF NOT EXISTS ux_agent_case_dedup_key
ON dwp_aura.agent_case(tenant_id, dedup_key) WHERE dedup_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_agent_case_dedup_key
ON dwp_aura.agent_case(tenant_id, dedup_key);

COMMENT ON COLUMN dwp_aura.agent_case.dedup_key IS 'Phase B: tenant+rule+entity 복합키. 중복 케이스 방지.';
