-- V81: rag_document 컬럼 코멘트 추가
SET search_path TO dwp_aura, public;

COMMENT ON COLUMN dwp_aura.rag_document.version IS '문서/규정 버전 식별자 (예: v2.0)';
COMMENT ON COLUMN dwp_aura.rag_document.effective_from IS '해당 버전 효력 시작일시';
COMMENT ON COLUMN dwp_aura.rag_document.effective_to IS '해당 버전 효력 종료일시 (NULL이면 현재 유효)';
COMMENT ON COLUMN dwp_aura.rag_document.lifecycle_status IS '문서 생명주기 상태 (ACTIVE, INACTIVE, DEPRECATED 등)';
COMMENT ON COLUMN dwp_aura.rag_document.active_from IS '시스템에서 실제 활성화된 시작일시';
COMMENT ON COLUMN dwp_aura.rag_document.active_to IS '시스템에서 실제 비활성화된 종료일시';
COMMENT ON COLUMN dwp_aura.rag_document.quality_gate_passed IS '품질 게이트 통과 여부 (true/false)';
COMMENT ON COLUMN dwp_aura.rag_document.last_quality_score IS '최근 품질 평가 점수(요약 수치)';
COMMENT ON COLUMN dwp_aura.rag_document.last_quality_report_json IS '최근 품질 리포트 원문(JSON)';
COMMENT ON COLUMN dwp_aura.rag_document.updated_at IS '마지막 수정 시각 (감사/동기화 기준 시각)';
