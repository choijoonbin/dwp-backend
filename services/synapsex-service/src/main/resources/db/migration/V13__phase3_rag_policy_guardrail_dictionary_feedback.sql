-- Phase 3: RAG, policy_guardrail, dictionary, feedback
-- Minimal normalized tables with JSONB payload allowed.

SET search_path TO dwp_aura, public;

-- 1) rag_document
CREATE TABLE IF NOT EXISTS dwp_aura.rag_document (
  doc_id       BIGSERIAL PRIMARY KEY,
  tenant_id    BIGINT NOT NULL,
  title        TEXT NOT NULL,
  source_type  VARCHAR(50) NOT NULL DEFAULT 'UPLOAD',
  s3_key       TEXT,
  url          TEXT,
  checksum     VARCHAR(64),
  status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_rag_document_tenant ON dwp_aura.rag_document(tenant_id);
CREATE INDEX IF NOT EXISTS ix_rag_document_status ON dwp_aura.rag_document(tenant_id, status);

COMMENT ON TABLE dwp_aura.rag_document IS 'RAG 문서 메타데이터. UPLOAD|S3|URL 등 소스.';
COMMENT ON COLUMN dwp_aura.rag_document.status IS 'PENDING|INDEXED|FAILED';

-- 2) rag_chunk
CREATE TABLE IF NOT EXISTS dwp_aura.rag_chunk (
  chunk_id     BIGSERIAL PRIMARY KEY,
  tenant_id   BIGINT NOT NULL,
  doc_id      BIGINT NOT NULL REFERENCES dwp_aura.rag_document(doc_id) ON DELETE CASCADE,
  page_no     INT NOT NULL DEFAULT 1,
  chunk_text  TEXT NOT NULL,
  embedding_id TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_rag_chunk_tenant_doc ON dwp_aura.rag_chunk(tenant_id, doc_id);
CREATE INDEX IF NOT EXISTS ix_rag_chunk_text_gin ON dwp_aura.rag_chunk USING gin(to_tsvector('simple', chunk_text));

COMMENT ON TABLE dwp_aura.rag_chunk IS 'RAG 청크. chunk_text 검색용. embedding_id는 벡터 DB 연동 시 사용.';

-- 3) policy_guardrail (Phase3 flexible guardrail; policy_action_guardrail과 별도)
CREATE TABLE IF NOT EXISTS dwp_aura.policy_guardrail (
  guardrail_id  BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  name          VARCHAR(120) NOT NULL,
  scope         VARCHAR(50) NOT NULL,
  rule_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
  is_enabled    BOOLEAN NOT NULL DEFAULT true,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_policy_guardrail_tenant ON dwp_aura.policy_guardrail(tenant_id);
CREATE INDEX IF NOT EXISTS ix_policy_guardrail_scope ON dwp_aura.policy_guardrail(tenant_id, scope, is_enabled);

COMMENT ON TABLE dwp_aura.policy_guardrail IS '가드레일 규칙. scope: case_type|action_type 등. rule_json에 조건/허용액/승인레벨 등.';

-- 4) dictionary_term
CREATE TABLE IF NOT EXISTS dwp_aura.dictionary_term (
  term_id      BIGSERIAL PRIMARY KEY,
  tenant_id    BIGINT NOT NULL,
  term_key     VARCHAR(120) NOT NULL,
  label_ko     TEXT,
  description  TEXT,
  category     VARCHAR(50),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, term_key)
);

CREATE INDEX IF NOT EXISTS ix_dictionary_term_tenant ON dwp_aura.dictionary_term(tenant_id);
CREATE INDEX IF NOT EXISTS ix_dictionary_term_category ON dwp_aura.dictionary_term(tenant_id, category);

COMMENT ON TABLE dwp_aura.dictionary_term IS '용어 사전. term_key 기준 고유.';

-- 5) feedback_label
CREATE TABLE IF NOT EXISTS dwp_aura.feedback_label (
  feedback_id   BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  target_type   VARCHAR(30) NOT NULL,
  target_id     TEXT NOT NULL,
  label         VARCHAR(30) NOT NULL,
  comment       TEXT,
  created_by    BIGINT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_feedback_label_tenant ON dwp_aura.feedback_label(tenant_id);
CREATE INDEX IF NOT EXISTS ix_feedback_label_target ON dwp_aura.feedback_label(tenant_id, target_type, target_id);

COMMENT ON TABLE dwp_aura.feedback_label IS '피드백 라벨. target_type: CASE|DOC|ENTITY. label: VALID|INVALID|NEEDS_REVIEW.';
