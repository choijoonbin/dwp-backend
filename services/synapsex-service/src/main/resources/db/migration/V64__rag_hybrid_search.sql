-- V64: RAG Hybrid Search 지원 (BM25 + Vector + RRF + Parent-Child)
-- search_text: prefix 제거된 정제 본문 (임베딩 희석 방지)
-- search_tsv: tsvector 컬럼 (BM25 검색용)
-- parent_id: 부모 청크 ID (조문 단위 그룹핑)
-- node_type: ARTICLE(조문), CLAUSE(항/호), PARAGRAPH(문단)

SET search_path TO dwp_aura, public;

-- 1) search_text 컬럼 추가 (prefix 제거된 정제 본문)
ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS search_text TEXT;

-- 2) search_tsv 컬럼 추가 (tsvector, BM25 검색용)
ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS search_tsv tsvector;

-- 3) parent_id 컬럼 추가 (부모 청크 참조)
ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS parent_id BIGINT;

-- 4) node_type 컬럼 추가 (ARTICLE/CLAUSE/PARAGRAPH)
ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS node_type VARCHAR(20);

-- 5) GIN 인덱스 (BM25 검색 성능)
CREATE INDEX IF NOT EXISTS ix_rag_chunk_search_tsv
  ON dwp_aura.rag_chunk USING GIN(search_tsv);

-- 6) parent_id 인덱스 (Parent-Child 조회 성능)
CREATE INDEX IF NOT EXISTS ix_rag_chunk_parent
  ON dwp_aura.rag_chunk(tenant_id, parent_id)
  WHERE parent_id IS NOT NULL;

-- 7) node_type 인덱스 (필터링 성능)
CREATE INDEX IF NOT EXISTS ix_rag_chunk_node_type
  ON dwp_aura.rag_chunk(tenant_id, node_type)
  WHERE node_type IS NOT NULL;

-- 8) regulation_article + regulation_clause 복합 인덱스 (조항 필터링)
CREATE INDEX IF NOT EXISTS ix_rag_chunk_regulation_filter
  ON dwp_aura.rag_chunk(tenant_id, doc_id, regulation_article, regulation_clause)
  WHERE regulation_article IS NOT NULL;

-- 9) 기존 chunk_text로 search_text 초기화
UPDATE dwp_aura.rag_chunk
SET search_text = chunk_text
WHERE search_text IS NULL AND chunk_text IS NOT NULL;

-- 10) search_tsv 생성 (korean 설정 없으면 simple 사용)
UPDATE dwp_aura.rag_chunk
SET search_tsv = to_tsvector('simple', COALESCE(search_text, ''))
WHERE search_tsv IS NULL;

-- 11) 컬럼 코멘트
COMMENT ON COLUMN dwp_aura.rag_chunk.search_text IS 'prefix 제거된 정제 본문 (BM25/임베딩 검색용)';
COMMENT ON COLUMN dwp_aura.rag_chunk.search_tsv IS 'tsvector 컬럼 (BM25 검색 인덱스)';
COMMENT ON COLUMN dwp_aura.rag_chunk.parent_id IS '부모 청크 ID (조문-항/호 Parent-Child 관계)';
COMMENT ON COLUMN dwp_aura.rag_chunk.node_type IS '노드 유형: ARTICLE(조문), CLAUSE(항/호), PARAGRAPH(문단)';
