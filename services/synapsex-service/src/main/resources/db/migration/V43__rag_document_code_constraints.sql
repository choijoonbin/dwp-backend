-- V43: rag_document doc_type / status 코드 그룹 정렬 (Phase 6+)
-- 목적: RAG_DOC_TYPE, RAG_PROC_STATUS(sys_codes)와 동일 값만 허용하도록 CHECK 제약 추가.
--       doc_type: REGULATION, MANUAL, POLICY, GENERAL (NULL 허용)
--       status: READY, PROCESSING, COMPLETED, FAILED (레거시 PENDING 허용)

SET search_path TO dwp_aura, public;

-- doc_type: RAG_DOC_TYPE 코드 값 또는 NULL
ALTER TABLE dwp_aura.rag_document
  DROP CONSTRAINT IF EXISTS chk_rag_document_doc_type;

ALTER TABLE dwp_aura.rag_document
  ADD CONSTRAINT chk_rag_document_doc_type
  CHECK (doc_type IS NULL OR doc_type IN ('REGULATION', 'MANUAL', 'POLICY', 'GENERAL'));

-- status: RAG_PROC_STATUS 코드 값 + 레거시 PENDING
ALTER TABLE dwp_aura.rag_document
  DROP CONSTRAINT IF EXISTS chk_rag_document_status;

ALTER TABLE dwp_aura.rag_document
  ADD CONSTRAINT chk_rag_document_status
  CHECK (status IN ('READY', 'PROCESSING', 'COMPLETED', 'FAILED', 'PENDING'));

COMMENT ON COLUMN dwp_aura.rag_document.doc_type IS '문서 유형: RAG_DOC_TYPE(REGULATION, MANUAL, POLICY, GENERAL). Aura ingest 메타데이터용.';
COMMENT ON COLUMN dwp_aura.rag_document.status IS '처리 상태: RAG_PROC_STATUS(READY, PROCESSING, COMPLETED, FAILED). 레거시 PENDING 허용.';
