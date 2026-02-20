-- V63: rag_document doc_type 체크 제약 조건 수정
-- 문제: V43에서 REGULATION, MANUAL을 허용했으나, V54 app_codes는 HIERARCHICAL, SEQUENTIAL을 사용
-- 해결: 체크 제약 조건을 app_codes DOC_TYPE과 일치하도록 수정 (HIERARCHICAL, SEQUENTIAL, POLICY, GENERAL)

SET search_path TO dwp_aura, public;

-- doc_type: app_codes DOC_TYPE과 일치 (HIERARCHICAL, SEQUENTIAL, POLICY, GENERAL)
ALTER TABLE dwp_aura.rag_document
  DROP CONSTRAINT IF EXISTS chk_rag_document_doc_type;

ALTER TABLE dwp_aura.rag_document
  ADD CONSTRAINT chk_rag_document_doc_type
  CHECK (doc_type IS NULL OR doc_type IN ('HIERARCHICAL', 'SEQUENTIAL', 'POLICY', 'GENERAL'));

COMMENT ON COLUMN dwp_aura.rag_document.doc_type IS '문서 유형: DOC_TYPE(HIERARCHICAL, SEQUENTIAL, POLICY, GENERAL). app_codes와 일치.';
