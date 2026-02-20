-- V62: rag_chunk — 규정 조항 추출 컬럼 추가 (Aura 요구사항)
-- regulation_article: 규정 조항 (예: "제11조")
-- regulation_clause: 규정 항목 (예: "2항")

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS regulation_article VARCHAR(100);

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS regulation_clause VARCHAR(100);

CREATE INDEX IF NOT EXISTS ix_rag_chunk_regulation 
  ON dwp_aura.rag_chunk(tenant_id, regulation_article, regulation_clause) 
  WHERE regulation_article IS NOT NULL;

COMMENT ON COLUMN dwp_aura.rag_chunk.regulation_article IS '규정 조항 (예: "제11조", "제3장")';
COMMENT ON COLUMN dwp_aura.rag_chunk.regulation_clause IS '규정 항목 (예: "2항", "제1호")';
