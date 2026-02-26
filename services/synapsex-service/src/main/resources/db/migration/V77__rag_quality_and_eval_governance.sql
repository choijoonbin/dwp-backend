-- V77: RAG versioning / quality history / evaluation gate governance
-- NOTE:
-- - rag_document.status is already used as processing status(READY/PROCESSING/COMPLETED/FAILED).
-- - lifecycle status is introduced as lifecycle_status(ACTIVE/INACTIVE/DEPRECATED).

SET search_path TO dwp_aura, public;

-- 1) rag_document extensions
ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS version VARCHAR(64);

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS effective_from DATE;

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS effective_to DATE;

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS active_from TIMESTAMPTZ;

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS active_to TIMESTAMPTZ;

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS quality_gate_passed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS last_quality_score NUMERIC(5,4);

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS last_quality_report_json JSONB;

ALTER TABLE dwp_aura.rag_document
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE dwp_aura.rag_document
  DROP CONSTRAINT IF EXISTS chk_rag_document_lifecycle_status;

ALTER TABLE dwp_aura.rag_document
  ADD CONSTRAINT chk_rag_document_lifecycle_status
  CHECK (lifecycle_status IN ('ACTIVE', 'INACTIVE', 'DEPRECATED'));

-- 2) rag_chunk extensions
ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS parent_chunk_id VARCHAR(128);

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS parent_article VARCHAR(64);

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS parent_title VARCHAR(255);

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS child_index INTEGER;

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS chunk_level VARCHAR(16);

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS version VARCHAR(64);

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS effective_from DATE;

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS effective_to DATE;

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE dwp_aura.rag_chunk
  ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE dwp_aura.rag_chunk
  DROP CONSTRAINT IF EXISTS chk_rag_chunk_level;

ALTER TABLE dwp_aura.rag_chunk
  ADD CONSTRAINT chk_rag_chunk_level
  CHECK (chunk_level IS NULL OR chunk_level IN ('root', 'child'));

-- 3) quality report history
CREATE TABLE IF NOT EXISTS dwp_aura.rag_document_quality_report (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  doc_id BIGINT NOT NULL REFERENCES dwp_aura.rag_document(doc_id) ON DELETE CASCADE,
  run_id UUID,
  quality_gate_passed BOOLEAN NOT NULL,
  input_chunks INTEGER NOT NULL,
  final_chunks INTEGER NOT NULL,
  article_coverage NUMERIC(5,4) NOT NULL,
  noise_rate NUMERIC(5,4) NOT NULL,
  duplicate_rate NUMERIC(5,4) NOT NULL,
  short_chunk_rate NUMERIC(5,4) NOT NULL,
  removed_empty INTEGER NOT NULL DEFAULT 0,
  removed_heading_only INTEGER NOT NULL DEFAULT 0,
  removed_duplicate_exact INTEGER NOT NULL DEFAULT 0,
  removed_duplicate_near INTEGER NOT NULL DEFAULT 0,
  missing_required JSONB,
  errors JSONB,
  raw_report_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 4) replay evaluation run
CREATE TABLE IF NOT EXISTS dwp_aura.rag_eval_run (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  run_key VARCHAR(128) NOT NULL,
  zero_rate NUMERIC(5,4) NOT NULL,
  hit_at_k NUMERIC(5,4) NOT NULL,
  strict_hit_top1 NUMERIC(5,4) NOT NULL,
  total_cases INTEGER NOT NULL,
  result_json JSONB NOT NULL,
  gate_passed BOOLEAN NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 5) indexes
CREATE INDEX IF NOT EXISTS ix_rag_chunk_tenant_doc_active
  ON dwp_aura.rag_chunk (tenant_id, doc_id, is_active);

CREATE INDEX IF NOT EXISTS ix_rag_chunk_tenant_version
  ON dwp_aura.rag_chunk (tenant_id, version);

CREATE INDEX IF NOT EXISTS ix_rag_chunk_tenant_effective_range
  ON dwp_aura.rag_chunk (tenant_id, effective_from, effective_to);

CREATE INDEX IF NOT EXISTS ix_rag_chunk_tenant_meta_article
  ON dwp_aura.rag_chunk (tenant_id, (metadata_json->>'regulation_article'));

CREATE INDEX IF NOT EXISTS ix_rag_doc_quality_tenant_doc_created
  ON dwp_aura.rag_document_quality_report (tenant_id, doc_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_rag_eval_run_tenant_created
  ON dwp_aura.rag_eval_run (tenant_id, created_at DESC);
