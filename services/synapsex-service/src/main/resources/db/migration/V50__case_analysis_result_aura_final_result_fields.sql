-- V50: Aura 콜백 finalResult 신규 4필드 (risk_score, violation_clause, reasoning_summary, recommended_action)
-- Aura 전달 문서(aura.txt) §2: 케이스 종결·대시보드 표시용

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.case_analysis_result
  ADD COLUMN IF NOT EXISTS risk_score INTEGER,
  ADD COLUMN IF NOT EXISTS violation_clause TEXT,
  ADD COLUMN IF NOT EXISTS reasoning_summary TEXT,
  ADD COLUMN IF NOT EXISTS recommended_action TEXT;

COMMENT ON COLUMN dwp_aura.case_analysis_result.risk_score IS '위험 점수 0~100 (Aura finalResult)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.violation_clause IS '위반 규정 조항 (예: 제11조 2항)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.reasoning_summary IS '판단 근거 요약 (reasonText 동일 내용)';
COMMENT ON COLUMN dwp_aura.case_analysis_result.recommended_action IS '권고 조치 요약 (proposals rationale 연결)';
