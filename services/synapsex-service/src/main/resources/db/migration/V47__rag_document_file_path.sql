-- V47: 로컬 파일 저장 경로 (Pre-Test). Aura 벡터화 시 document_path 전달용.
ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS file_path TEXT;

COMMENT ON COLUMN dwp_aura.rag_document.file_path IS '로컬 절대 경로. source_type=UPLOAD 시 사용, Aura document_path로 전달.';
