-- V69: 데모 시연용 전표에 위험 유형 가이드라인 저장 (Aura 오판 방지)
-- DOC_SOURCE='DEMO' 인 fi_doc_header에만 설정되며, agent_case.evidence_json에 intended_risk_type으로 전달됨.
SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.fi_doc_header
  ADD COLUMN IF NOT EXISTS intended_risk_type VARCHAR(50);

COMMENT ON COLUMN dwp_aura.fi_doc_header.intended_risk_type IS '데모 생성 시 시나리오 유형(예: SPLIT_PAYMENT, HOLIDAY_USAGE). Aura 엔진에 evidence_json.intended_risk_type으로 전달.';
