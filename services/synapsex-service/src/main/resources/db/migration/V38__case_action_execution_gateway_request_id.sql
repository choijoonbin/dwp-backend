-- V38: Phase3 gatewayRequestId 저장 및 멱등 추적 (정책 의사결정 반영)
-- case_action_execution.gateway_request_id, UNIQUE(tenant_id, gateway_request_id) WHERE gateway_request_id IS NOT NULL

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.case_action_execution
  ADD COLUMN IF NOT EXISTS gateway_request_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_case_action_execution_tenant_gateway_request_id
  ON dwp_aura.case_action_execution(tenant_id, gateway_request_id)
  WHERE gateway_request_id IS NOT NULL;

COMMENT ON COLUMN dwp_aura.case_action_execution.gateway_request_id IS '요청 추적/멱등용 (FE 또는 Gateway에서 전달)';
