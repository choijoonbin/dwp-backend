-- V66: rag_document doc_type에 REGULATION, MANUAL 재허용
-- V63에서 HIERARCHICAL, SEQUENTIAL만 허용했으나, FE/재청킹(strategy=REGULATION) 및 sys_codes RAG_DOC_TYPE(REGULATION, MANUAL)과 호환 유지

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.rag_document
  DROP CONSTRAINT IF EXISTS chk_rag_document_doc_type;

ALTER TABLE dwp_aura.rag_document
  ADD CONSTRAINT chk_rag_document_doc_type
  CHECK (doc_type IS NULL OR doc_type IN (
    'HIERARCHICAL', 'SEQUENTIAL', 'POLICY', 'GENERAL',
    'REGULATION', 'MANUAL'
  ));

COMMENT ON COLUMN dwp_aura.rag_document.doc_type IS '문서 유형: DOC_TYPE. HIERARCHICAL|SEQUENTIAL|POLICY|GENERAL 및 호환용 REGULATION|MANUAL.';
