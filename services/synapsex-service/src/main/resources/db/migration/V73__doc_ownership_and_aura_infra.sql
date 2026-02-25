-- V73: Phase1 전표 소유권 확립 + 지능형 분석 인프라
SET search_path TO dwp_aura, public;

-- 1) fi_doc_header 확장
ALTER TABLE dwp_aura.fi_doc_header
  ADD COLUMN IF NOT EXISTS user_id BIGINT,
  ADD COLUMN IF NOT EXISTS budget_exceeded_flag CHAR(1) DEFAULT 'N',
  ADD COLUMN IF NOT EXISTS created_by BIGINT,
  ADD COLUMN IF NOT EXISTS updated_by BIGINT;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = 'dwp_aura'
      AND table_name = 'fi_doc_header'
      AND column_name = 'budget_exceeded'
  ) THEN
    UPDATE dwp_aura.fi_doc_header
    SET budget_exceeded_flag = CASE
      WHEN budget_exceeded IS TRUE THEN 'Y'
      ELSE 'N'
    END
    WHERE budget_exceeded_flag IS NULL
       OR budget_exceeded_flag NOT IN ('Y', 'N');

    ALTER TABLE dwp_aura.fi_doc_header DROP COLUMN budget_exceeded;
  END IF;
END $$;

UPDATE dwp_aura.fi_doc_header
SET budget_exceeded_flag = 'N'
WHERE budget_exceeded_flag IS NULL OR budget_exceeded_flag NOT IN ('Y', 'N');

ALTER TABLE dwp_aura.fi_doc_header
  ALTER COLUMN budget_exceeded_flag SET DEFAULT 'N';

CREATE INDEX IF NOT EXISTS idx_fi_doc_header_owner
  ON dwp_aura.fi_doc_header (user_id, tenant_id);

COMMENT ON COLUMN dwp_aura.fi_doc_header.user_id IS '전표 소유자 식별자 (public.com_users.user_id)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN dwp_aura.fi_doc_header.budget_exceeded_flag IS '예산 초과 여부 (Y/N)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.fi_doc_header.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- 2) MCC 마스터
CREATE TABLE IF NOT EXISTS dwp_aura.mcc_master (
  mcc_code VARCHAR(4) NOT NULL,
  mcc_name VARCHAR(100) NOT NULL,
  risk_category VARCHAR(20) NOT NULL,
  related_article VARCHAR(100),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by BIGINT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by BIGINT,
  CONSTRAINT mcc_master_pkey PRIMARY KEY (mcc_code)
);

COMMENT ON TABLE dwp_aura.mcc_master IS '업종 코드(MCC) 마스터 및 규정 매핑';

-- 3) 사용자 지출 패턴 (Aura RAG)
CREATE TABLE IF NOT EXISTS dwp_aura.user_expense_patterns (
  pattern_id BIGSERIAL NOT NULL,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  mcc_code VARCHAR(4),
  avg_amount NUMERIC(18,2) DEFAULT 0,
  max_amount NUMERIC(18,2) DEFAULT 0,
  frequency_count INT4 DEFAULT 0,
  last_analyzed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by BIGINT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by BIGINT,
  CONSTRAINT user_expense_patterns_pkey PRIMARY KEY (pattern_id)
);

CREATE INDEX IF NOT EXISTS idx_user_expense_patterns_owner
  ON dwp_aura.user_expense_patterns (tenant_id, user_id, mcc_code);

COMMENT ON TABLE dwp_aura.user_expense_patterns IS '사용자별/업종별 과거 지출 패턴 통계';

-- 4) 사용자 소명
CREATE TABLE IF NOT EXISTS dwp_aura.case_explanation (
  explanation_id BIGSERIAL NOT NULL,
  tenant_id BIGINT NOT NULL,
  case_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  explanation_text TEXT NOT NULL,
  evidence_attachment_id VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by BIGINT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by BIGINT,
  CONSTRAINT case_explanation_pkey PRIMARY KEY (explanation_id)
);

CREATE INDEX IF NOT EXISTS idx_case_explanation_case
  ON dwp_aura.case_explanation (tenant_id, case_id, user_id, created_at DESC);

COMMENT ON TABLE dwp_aura.case_explanation IS '이상 징후 전표에 대한 사용자의 소명 내역';
