-- V79: case_analysis_result tenant scope column

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.case_analysis_result
  ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

UPDATE dwp_aura.case_analysis_result r
SET tenant_id = ar.tenant_id
FROM dwp_aura.case_analysis_run ar
WHERE r.run_id = ar.run_id
  AND r.tenant_id IS NULL;

ALTER TABLE dwp_aura.case_analysis_result
  ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_case_analysis_result_tenant_created
  ON dwp_aura.case_analysis_result (tenant_id, created_at DESC);

COMMENT ON COLUMN dwp_aura.case_analysis_result.tenant_id IS '테넌트 식별자 (case_analysis_run.tenant_id 상속)';
