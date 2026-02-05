-- Post-PhaseB: ingest_run — 원천데이터 적재 실행 단위
-- 적재 배치가 언제/어느 범위로 몇 건 적재했는지 조회 가능

SET search_path TO dwp_aura, public;

CREATE TABLE IF NOT EXISTS dwp_aura.ingest_run (
  run_id         BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,
  batch_id       TEXT,
  window_from    TIMESTAMPTZ,
  window_to      TIMESTAMPTZ,
  record_count   INT,
  status         VARCHAR(20) NOT NULL DEFAULT 'STARTED',
  error_message  TEXT,
  started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_ingest_run_tenant_created
ON dwp_aura.ingest_run(tenant_id, started_at DESC);

CREATE INDEX IF NOT EXISTS ix_ingest_run_tenant_status
ON dwp_aura.ingest_run(tenant_id, status);

COMMENT ON TABLE dwp_aura.ingest_run IS '원천데이터 적재 실행 단위. window_from/to, record_count, status.';
COMMENT ON COLUMN dwp_aura.ingest_run.status IS 'STARTED | COMPLETED | FAILED';
COMMENT ON COLUMN dwp_aura.ingest_run.record_count IS '적재 건수';

-- P1: agent_case.last_detect_run_id (detect_run ↔ case 연결성)
ALTER TABLE dwp_aura.agent_case
  ADD COLUMN IF NOT EXISTS last_detect_run_id BIGINT REFERENCES dwp_aura.detect_run(run_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ix_agent_case_last_detect_run
ON dwp_aura.agent_case(last_detect_run_id) WHERE last_detect_run_id IS NOT NULL;

COMMENT ON COLUMN dwp_aura.agent_case.last_detect_run_id IS 'P1: 마지막 탐지 배치 run_id. 역추적용.';
