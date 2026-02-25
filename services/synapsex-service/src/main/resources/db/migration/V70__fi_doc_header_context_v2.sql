-- V70: 규정 v2.0 Context — 시연 데이터 생성기/Aura 전달용
-- hr_status: 근무/휴가, mcc_code: 업종(MCC), budget_exceeded: 한도초과여부
SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.fi_doc_header
  ADD COLUMN IF NOT EXISTS hr_status VARCHAR(20),
  ADD COLUMN IF NOT EXISTS mcc_code VARCHAR(20),
  ADD COLUMN IF NOT EXISTS budget_exceeded BOOLEAN;

COMMENT ON COLUMN dwp_aura.fi_doc_header.hr_status IS '규정 v2.0: 근무/휴가 (WORK, LEAVE 등). Aura evidence_json/metadata 전달용.';
COMMENT ON COLUMN dwp_aura.fi_doc_header.mcc_code IS '규정 v2.0: 업종 코드 또는 라벨 (예: RESTAURANT, BAR, GOLF). Aura 전달용.';
COMMENT ON COLUMN dwp_aura.fi_doc_header.budget_exceeded IS '규정 v2.0: 한도초과 여부. Aura evidence_json 전달용.';
