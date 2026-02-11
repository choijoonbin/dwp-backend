-- Document metadata categorization: doc_type (REGULATION, MANUAL, POLICY 등)
-- Aura 벡터화 시 doc_id + doc_type 기반 메타데이터 인덱싱용.

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS doc_type VARCHAR(30);

COMMENT ON COLUMN dwp_aura.rag_document.doc_type IS '문서 성격: REGULATION, MANUAL, POLICY 등. Aura ingest 시 메타데이터 인덱싱용.';
