-- ========================================
-- DWP Main Service - agent_tasks 감사(audit) 컬럼 추가
-- 생성일: 2026-01-23
-- 목적: 시스템 컬럼 정책에 따라 created_by, updated_by 추가
-- ========================================

-- agent_tasks: created_by, updated_by 추가 (created_at, updated_at은 기존 유지)
ALTER TABLE agent_tasks ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE agent_tasks ADD COLUMN IF NOT EXISTS updated_by BIGINT;

COMMENT ON COLUMN agent_tasks.created_by IS '생성자 user_id (논리적 참조: com_users.user_id, auth 서비스)';
COMMENT ON COLUMN agent_tasks.updated_by IS '수정자 user_id (논리적 참조: com_users.user_id, auth 서비스)';
