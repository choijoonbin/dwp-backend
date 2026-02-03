-- Phase 4: Reconciliation, Action-recon, Analytics

SET search_path TO dwp_aura, public;

-- 1) recon_run
CREATE TABLE IF NOT EXISTS dwp_aura.recon_run (
  run_id        BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  run_type      VARCHAR(50) NOT NULL,
  started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at      TIMESTAMPTZ,
  status        VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  summary_json   JSONB
);

CREATE INDEX IF NOT EXISTS ix_recon_run_tenant ON dwp_aura.recon_run(tenant_id);
CREATE INDEX IF NOT EXISTS ix_recon_run_started ON dwp_aura.recon_run(tenant_id, started_at DESC);

COMMENT ON TABLE dwp_aura.recon_run IS 'Reconciliation 실행. run_type: DOC_OPENITEM_MATCH, ACTION_EFFECT, etc.';
COMMENT ON COLUMN dwp_aura.recon_run.status IS 'RUNNING | COMPLETED | FAILED';

-- 2) recon_result
CREATE TABLE IF NOT EXISTS dwp_aura.recon_result (
  result_id     BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  run_id        BIGINT NOT NULL REFERENCES dwp_aura.recon_run(run_id) ON DELETE CASCADE,
  resource_type VARCHAR(50) NOT NULL,
  resource_key  TEXT NOT NULL,
  status        VARCHAR(10) NOT NULL,
  detail_json   JSONB
);

CREATE INDEX IF NOT EXISTS ix_recon_result_run ON dwp_aura.recon_result(run_id);
CREATE INDEX IF NOT EXISTS ix_recon_result_tenant_run ON dwp_aura.recon_result(tenant_id, run_id);

COMMENT ON TABLE dwp_aura.recon_result IS 'Reconciliation 결과. status: PASS | FAIL.';
COMMENT ON COLUMN dwp_aura.recon_result.resource_key IS 'bukrs-belnr-gjahr-buzei 등 리소스 식별 키';

-- 3) analytics_kpi_daily
CREATE TABLE IF NOT EXISTS dwp_aura.analytics_kpi_daily (
  tenant_id     BIGINT NOT NULL,
  ymd           DATE NOT NULL,
  metric_key    VARCHAR(80) NOT NULL,
  metric_value  NUMERIC(18,4) NOT NULL,
  dims_json     JSONB DEFAULT '{}'::jsonb,
  dims_hash     VARCHAR(64) NOT NULL DEFAULT '',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, ymd, metric_key, dims_hash)
);

CREATE INDEX IF NOT EXISTS ix_analytics_kpi_tenant_ymd ON dwp_aura.analytics_kpi_daily(tenant_id, ymd);

COMMENT ON TABLE dwp_aura.analytics_kpi_daily IS '일별 KPI 메트릭. savings_estimate, prevented_loss, median_triage_time, automation_rate 등.';
