-- ======================================================================
-- SYSTEM_COLUMNS_POLICY 준수: 정책 위반 테이블에 감사 4컬럼 추가
-- 참고: docs/essentials/SYSTEM_COLUMNS_POLICY.md
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ----------------------------------------------------------------------
-- 1) detect_run — started_at/completed_at 외 감사 4컬럼 추가
-- ----------------------------------------------------------------------
ALTER TABLE dwp_aura.detect_run
  ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS created_by BIGINT,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS updated_by BIGINT;

UPDATE dwp_aura.detect_run SET created_at = started_at WHERE created_at IS NULL;
UPDATE dwp_aura.detect_run SET updated_at = COALESCE(completed_at, started_at) WHERE updated_at IS NULL;

ALTER TABLE dwp_aura.detect_run ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE dwp_aura.detect_run ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE dwp_aura.detect_run ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE dwp_aura.detect_run ALTER COLUMN updated_at SET DEFAULT now();

COMMENT ON COLUMN dwp_aura.detect_run.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.detect_run.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.detect_run.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.detect_run.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ----------------------------------------------------------------------
-- 2) ingest_run — 동일
-- ----------------------------------------------------------------------
ALTER TABLE dwp_aura.ingest_run
  ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS created_by BIGINT,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS updated_by BIGINT;

UPDATE dwp_aura.ingest_run SET created_at = started_at WHERE created_at IS NULL;
UPDATE dwp_aura.ingest_run SET updated_at = COALESCE(completed_at, started_at) WHERE updated_at IS NULL;

ALTER TABLE dwp_aura.ingest_run ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE dwp_aura.ingest_run ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE dwp_aura.ingest_run ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE dwp_aura.ingest_run ALTER COLUMN updated_at SET DEFAULT now();

COMMENT ON COLUMN dwp_aura.ingest_run.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.ingest_run.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.ingest_run.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.ingest_run.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ----------------------------------------------------------------------
-- 3) agent_activity_log — created_at 있음, created_by/updated_at/updated_by 추가
-- ----------------------------------------------------------------------
ALTER TABLE dwp_aura.agent_activity_log
  ADD COLUMN IF NOT EXISTS created_by BIGINT,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS updated_by BIGINT;

UPDATE dwp_aura.agent_activity_log SET updated_at = occurred_at WHERE updated_at IS NULL;

ALTER TABLE dwp_aura.agent_activity_log ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE dwp_aura.agent_activity_log ALTER COLUMN updated_at SET DEFAULT now();

COMMENT ON COLUMN dwp_aura.agent_activity_log.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.agent_activity_log.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.agent_activity_log.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';

-- ----------------------------------------------------------------------
-- 4) idempotency_key — created_at 있음, created_by/updated_at/updated_by 추가
-- ----------------------------------------------------------------------
ALTER TABLE dwp_aura.idempotency_key
  ADD COLUMN IF NOT EXISTS created_by BIGINT,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS updated_by BIGINT;

UPDATE dwp_aura.idempotency_key SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE dwp_aura.idempotency_key ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE dwp_aura.idempotency_key ALTER COLUMN updated_at SET DEFAULT now();

COMMENT ON COLUMN dwp_aura.idempotency_key.created_by IS '생성자 user_id (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN dwp_aura.idempotency_key.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.idempotency_key.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id)';
