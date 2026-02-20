-- agent_document_mapping 테이블에 시스템 컬럼 추가
-- SYSTEM_COLUMNS_POLICY 준수: created_by, updated_at, updated_by 추가

SET search_path TO dwp_aura, public;

-- 시스템 컬럼 추가
ALTER TABLE dwp_aura.agent_document_mapping
  ADD COLUMN IF NOT EXISTS created_by BIGINT,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS updated_by BIGINT;

-- 기존 행에 updated_at 채우기 (created_at 값 사용)
UPDATE dwp_aura.agent_document_mapping SET updated_at = created_at WHERE updated_at IS NULL;

-- NOT NULL 제약조건 추가
ALTER TABLE dwp_aura.agent_document_mapping 
  ALTER COLUMN updated_at SET NOT NULL,
  ALTER COLUMN updated_at SET DEFAULT now();

-- COMMENT 추가
COMMENT ON COLUMN dwp_aura.agent_document_mapping.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.agent_document_mapping.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.agent_document_mapping.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.agent_document_mapping.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';
