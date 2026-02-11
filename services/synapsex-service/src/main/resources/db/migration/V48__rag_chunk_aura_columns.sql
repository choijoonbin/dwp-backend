-- V48: rag_chunk — Aura 벡터화 결과 수용 (백엔드 단일 소유)
-- chunk_index: 문서 내 순서. embedding: pgvector(1536). metadata_json: 페이지/경로 등.
-- pgvector 확장은 DB별 1회 필요(public 스키마에 생성).

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS chunk_index INT;

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS embedding vector(1536);

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS metadata_json JSONB;

COMMENT ON COLUMN dwp_aura.rag_chunk.chunk_index IS '문서 내 청크 순서 (추론 시 문맥 파악용)';
COMMENT ON COLUMN dwp_aura.rag_chunk.embedding IS 'OpenAI embedding 벡터 (1536차원, pgvector)';
COMMENT ON COLUMN dwp_aura.rag_chunk.metadata_json IS '페이지 번호, 파일 경로 등 부가 메타데이터';

-- 기존 행에 chunk_index 백필 (tenant_id, doc_id, page_no, chunk_id 순으로 0부터 부여)
WITH ordered AS (
  SELECT chunk_id,
         ROW_NUMBER() OVER (PARTITION BY tenant_id, doc_id ORDER BY page_no ASC, chunk_id ASC) - 1 AS idx
  FROM dwp_aura.rag_chunk
  WHERE chunk_index IS NULL
)
UPDATE dwp_aura.rag_chunk c
SET chunk_index = ordered.idx
FROM ordered
WHERE c.chunk_id = ordered.chunk_id;
