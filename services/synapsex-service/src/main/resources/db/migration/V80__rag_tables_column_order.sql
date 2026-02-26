-- V80: RAG 테이블 컬럼 순서 재정렬 (업무 중요도·관련도 높은 순)
-- rag_document: 식별자 → 비즈니스 분류 → 유효기간 → 소스 경로 → 감사/품질
-- rag_chunk: 식별자 → 콘텐츠 → 규정 조항 → 계층 → 순서 → 벡터/검색 → 메타
-- PostgreSQL은 컬럼 순서 변경을 지원하지 않으므로 테이블 재생성

SET search_path TO dwp_aura, public;

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

-- 1) rag_document: 새 테이블 생성 (컬럼 순서 변경)
CREATE TABLE IF NOT EXISTS dwp_aura.rag_document_new (
    doc_id bigserial NOT NULL,
    tenant_id int8 NOT NULL,
    title text NOT NULL,
    status varchar(20) DEFAULT 'PENDING'::character varying NOT NULL,
    doc_type varchar(30) NULL,
    source_type varchar(50) DEFAULT 'UPLOAD'::character varying NOT NULL,
    effective_from date NULL,
    effective_to date NULL,
    lifecycle_status varchar(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    active_from timestamptz NULL,
    active_to timestamptz NULL,
    file_path text NULL,
    s3_key text NULL,
    url text NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    checksum varchar(64) NULL,
    version varchar(64) NULL,
    quality_gate_passed bool DEFAULT false NOT NULL,
    last_quality_score numeric(5, 4) NULL,
    last_quality_report_json jsonb NULL,
    CONSTRAINT rag_document_new_pkey PRIMARY KEY (doc_id),
    CONSTRAINT chk_rag_document_new_doc_type CHECK (doc_type IS NULL OR doc_type IN (
        'HIERARCHICAL', 'SEQUENTIAL', 'POLICY', 'GENERAL', 'REGULATION', 'MANUAL')),
    CONSTRAINT chk_rag_document_new_lifecycle_status CHECK (lifecycle_status IN ('ACTIVE', 'INACTIVE', 'DEPRECATED')),
    CONSTRAINT chk_rag_document_new_status CHECK (status IN ('READY', 'PROCESSING', 'COMPLETED', 'FAILED', 'PENDING'))
);

INSERT INTO dwp_aura.rag_document_new (
    doc_id, tenant_id, title, status, doc_type, source_type,
    effective_from, effective_to, lifecycle_status, active_from, active_to,
    file_path, s3_key, url, created_at, updated_at, checksum, version,
    quality_gate_passed, last_quality_score, last_quality_report_json
)
SELECT
    doc_id, tenant_id, title, status, doc_type, source_type,
    effective_from, effective_to, lifecycle_status, active_from, active_to,
    file_path, s3_key, url, created_at, updated_at, checksum, version,
    quality_gate_passed, last_quality_score, last_quality_report_json
FROM dwp_aura.rag_document;

SELECT setval(pg_get_serial_sequence('dwp_aura.rag_document_new', 'doc_id'), COALESCE((SELECT MAX(doc_id) FROM dwp_aura.rag_document_new), 1));

-- 2) rag_chunk 백업
CREATE TEMP TABLE rag_chunk_backup AS SELECT * FROM dwp_aura.rag_chunk;

-- 3) FK 제거 (rag_document 참조)
ALTER TABLE dwp_aura.agent_document_mapping DROP CONSTRAINT IF EXISTS fk_agent_document_mapping_document;
ALTER TABLE dwp_aura.rag_document_quality_report DROP CONSTRAINT IF EXISTS rag_document_quality_report_doc_id_fkey;

-- 4) rag_chunk 삭제 후 rag_document 교체
DROP TABLE dwp_aura.rag_chunk;
DROP TABLE dwp_aura.rag_document;

ALTER TABLE dwp_aura.rag_document_new RENAME TO rag_document;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_sequences WHERE schemaname = 'dwp_aura' AND sequencename = 'rag_document_new_doc_id_seq') THEN
        ALTER SEQUENCE dwp_aura.rag_document_new_doc_id_seq RENAME TO rag_document_doc_id_seq;
        ALTER TABLE dwp_aura.rag_document ALTER COLUMN doc_id SET DEFAULT nextval('dwp_aura.rag_document_doc_id_seq'::regclass);
    END IF;
END $$;

-- 5) rag_chunk 새 순서로 생성
CREATE TABLE dwp_aura.rag_chunk (
    chunk_id bigserial NOT NULL,
    tenant_id int8 NOT NULL,
    doc_id int8 NOT NULL,
    chunk_text text NOT NULL,
    search_text text NULL,
    regulation_article varchar(100) NULL,
    regulation_clause varchar(100) NULL,
    parent_article varchar(64) NULL,
    parent_title varchar(255) NULL,
    node_type varchar(20) NULL,
    parent_id int8 NULL,
    parent_chunk_id varchar(128) NULL,
    child_index int4 NULL,
    chunk_level varchar(16) NULL,
    chunk_index int4 NULL,
    page_no int4 DEFAULT 1 NOT NULL,
    embedding_id text NULL,
    embedding vector(1536) NULL,
    search_tsv tsvector NULL,
    metadata_json jsonb NULL,
    version varchar(64) NULL,
    effective_from date NULL,
    effective_to date NULL,
    is_active bool DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT rag_chunk_pkey PRIMARY KEY (chunk_id),
    CONSTRAINT rag_chunk_doc_id_fkey FOREIGN KEY (doc_id) REFERENCES dwp_aura.rag_document(doc_id) ON DELETE CASCADE,
    CONSTRAINT chk_rag_chunk_level CHECK (chunk_level IS NULL OR chunk_level IN ('root', 'child'))
);

INSERT INTO dwp_aura.rag_chunk (
    chunk_id, tenant_id, doc_id, chunk_text, search_text,
    regulation_article, regulation_clause, parent_article, parent_title,
    node_type, parent_id, parent_chunk_id, child_index, chunk_level,
    chunk_index, page_no, embedding_id, embedding, search_tsv, metadata_json,
    version, effective_from, effective_to, is_active, created_at
)
SELECT
    chunk_id, tenant_id, doc_id, chunk_text, search_text,
    regulation_article, regulation_clause, parent_article, parent_title,
    node_type, parent_id, parent_chunk_id, child_index, chunk_level,
    chunk_index, page_no, embedding_id, embedding, search_tsv, metadata_json,
    version, effective_from, effective_to, is_active, created_at
FROM rag_chunk_backup;

SELECT setval('dwp_aura.rag_chunk_chunk_id_seq', COALESCE((SELECT MAX(chunk_id) FROM dwp_aura.rag_chunk), 1));

-- 6) rag_document 인덱스
CREATE INDEX ix_rag_document_status ON dwp_aura.rag_document USING btree (tenant_id, status);
CREATE INDEX ix_rag_document_tenant ON dwp_aura.rag_document USING btree (tenant_id);

-- 7) rag_chunk 인덱스
CREATE INDEX ix_rag_chunk_doc_id ON dwp_aura.rag_chunk USING btree (doc_id);
CREATE INDEX ix_rag_chunk_node_type ON dwp_aura.rag_chunk USING btree (tenant_id, node_type) WHERE node_type IS NOT NULL;
CREATE INDEX ix_rag_chunk_parent ON dwp_aura.rag_chunk USING btree (tenant_id, parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX ix_rag_chunk_regulation ON dwp_aura.rag_chunk USING btree (tenant_id, regulation_article, regulation_clause) WHERE regulation_article IS NOT NULL;
CREATE INDEX ix_rag_chunk_regulation_filter ON dwp_aura.rag_chunk USING btree (tenant_id, doc_id, regulation_article, regulation_clause) WHERE regulation_article IS NOT NULL;
CREATE INDEX ix_rag_chunk_search_tsv ON dwp_aura.rag_chunk USING gin (search_tsv);
CREATE INDEX ix_rag_chunk_tenant_doc ON dwp_aura.rag_chunk USING btree (tenant_id, doc_id);
CREATE INDEX ix_rag_chunk_tenant_doc_active ON dwp_aura.rag_chunk USING btree (tenant_id, doc_id, is_active);
CREATE INDEX ix_rag_chunk_tenant_effective_range ON dwp_aura.rag_chunk USING btree (tenant_id, effective_from, effective_to);
CREATE INDEX ix_rag_chunk_tenant_meta_article ON dwp_aura.rag_chunk USING btree (tenant_id, ((metadata_json ->> 'regulation_article'::text)));
CREATE INDEX ix_rag_chunk_tenant_version ON dwp_aura.rag_chunk USING btree (tenant_id, version);
CREATE INDEX ix_rag_chunk_text_gin ON dwp_aura.rag_chunk USING gin (to_tsvector('simple'::regconfig, chunk_text));

-- 8) FK 재생성
ALTER TABLE dwp_aura.agent_document_mapping
    ADD CONSTRAINT fk_agent_document_mapping_document
    FOREIGN KEY (doc_id) REFERENCES dwp_aura.rag_document(doc_id) ON DELETE CASCADE;

ALTER TABLE dwp_aura.rag_document_quality_report
    ADD CONSTRAINT rag_document_quality_report_doc_id_fkey
    FOREIGN KEY (doc_id) REFERENCES dwp_aura.rag_document(doc_id) ON DELETE CASCADE;

-- 9) 테이블·컬럼 코멘트
COMMENT ON TABLE dwp_aura.rag_document IS 'RAG 문서 메타데이터. UPLOAD|S3|URL 등 소스.';
COMMENT ON COLUMN dwp_aura.rag_document.doc_id IS '문서 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.rag_document.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.rag_document.title IS '문서 제목';
COMMENT ON COLUMN dwp_aura.rag_document.status IS '처리 상태: RAG_PROC_STATUS(READY, PROCESSING, COMPLETED, FAILED). 레거시 PENDING 허용.';
COMMENT ON COLUMN dwp_aura.rag_document.doc_type IS '문서 유형: DOC_TYPE. HIERARCHICAL|SEQUENTIAL|POLICY|GENERAL 및 호환용 REGULATION|MANUAL.';
COMMENT ON COLUMN dwp_aura.rag_document.source_type IS '소스 유형 (UPLOAD, S3, URL)';
COMMENT ON COLUMN dwp_aura.rag_document.file_path IS '로컬 절대 경로. source_type=UPLOAD 시 사용, Aura document_path로 전달.';
COMMENT ON COLUMN dwp_aura.rag_document.s3_key IS 'S3 객체 키';
COMMENT ON COLUMN dwp_aura.rag_document.url IS 'URL';
COMMENT ON COLUMN dwp_aura.rag_document.checksum IS '체크섬';
COMMENT ON COLUMN dwp_aura.rag_document.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.rag_document.updated_at IS '수정일시';

COMMENT ON TABLE dwp_aura.rag_chunk IS 'RAG 청크. chunk_text 검색용. embedding_id는 벡터 DB 연동 시 사용.';
COMMENT ON COLUMN dwp_aura.rag_chunk.chunk_id IS '청크 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.rag_chunk.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.rag_chunk.doc_id IS '문서 ID (FK)';
COMMENT ON COLUMN dwp_aura.rag_chunk.chunk_text IS '청크 텍스트';
COMMENT ON COLUMN dwp_aura.rag_chunk.search_text IS 'prefix 제거된 정제 본문 (BM25/임베딩 검색용)';
COMMENT ON COLUMN dwp_aura.rag_chunk.regulation_article IS '규정 조항 (예: "제11조", "제3장")';
COMMENT ON COLUMN dwp_aura.rag_chunk.regulation_clause IS '규정 항목 (예: "2항", "제1호")';
COMMENT ON COLUMN dwp_aura.rag_chunk.parent_article IS '상위 조문 번호';
COMMENT ON COLUMN dwp_aura.rag_chunk.parent_title IS '상위 조문 제목';
COMMENT ON COLUMN dwp_aura.rag_chunk.node_type IS '노드 유형: ARTICLE(조문), CLAUSE(항/호), PARAGRAPH(문단)';
COMMENT ON COLUMN dwp_aura.rag_chunk.parent_id IS '부모 청크 ID (조문-항/호 Parent-Child 관계)';
COMMENT ON COLUMN dwp_aura.rag_chunk.chunk_index IS '문서 내 청크 순서 (추론 시 문맥 파악용)';
COMMENT ON COLUMN dwp_aura.rag_chunk.page_no IS '페이지 번호';
COMMENT ON COLUMN dwp_aura.rag_chunk.embedding_id IS '임베딩 ID (벡터 DB 연동)';
COMMENT ON COLUMN dwp_aura.rag_chunk.embedding IS 'OpenAI embedding 벡터 (1536차원, pgvector)';
COMMENT ON COLUMN dwp_aura.rag_chunk.metadata_json IS '페이지 번호, 파일 경로 등 부가 메타데이터';
COMMENT ON COLUMN dwp_aura.rag_chunk.search_tsv IS 'tsvector 컬럼 (BM25 검색 인덱스)';
COMMENT ON COLUMN dwp_aura.rag_chunk.created_at IS '생성일시';
