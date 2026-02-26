-- V78: Aura analysis quality grounding fields + replay gate run table

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.case_analysis_result
  ADD COLUMN IF NOT EXISTS sentence_citation_map JSONB;

ALTER TABLE dwp_aura.case_analysis_result
  ADD COLUMN IF NOT EXISTS analysis_score_breakdown JSONB;

ALTER TABLE dwp_aura.case_analysis_result
  ADD COLUMN IF NOT EXISTS quality_gate_codes JSONB;

ALTER TABLE dwp_aura.case_analysis_result
  ADD COLUMN IF NOT EXISTS grounding_coverage_ratio NUMERIC(5,4);

ALTER TABLE dwp_aura.case_analysis_result
  ADD COLUMN IF NOT EXISTS ungrounded_claim_sentences INTEGER;

CREATE INDEX IF NOT EXISTS ix_case_analysis_result_created_at
  ON dwp_aura.case_analysis_result (created_at DESC);

CREATE INDEX IF NOT EXISTS ix_case_analysis_result_quality_gate_codes
  ON dwp_aura.case_analysis_result USING GIN (quality_gate_codes);

CREATE TABLE IF NOT EXISTS dwp_aura.analysis_replay_gate_run (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  run_key VARCHAR(128) NOT NULL,
  gate_passed BOOLEAN NOT NULL,
  result_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_analysis_replay_gate_run_tenant_created
  ON dwp_aura.analysis_replay_gate_run (tenant_id, created_at DESC);
