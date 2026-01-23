-- ========================================
-- DWP Main Service - AgentTask & HITL Tables
-- 생성일: 2026-01-22
-- 목적: AI 에이전트 작업 관리 및 HITL 승인 프로세스
-- ========================================

-- ========================================
-- 1. agent_tasks 테이블
-- ========================================
CREATE TABLE agent_tasks (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(36) UNIQUE NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    task_type VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    description TEXT,
    input_data TEXT,
    result_data TEXT,
    plan_steps TEXT,
    hitl_request_id VARCHAR(100),
    error_message TEXT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ========================================
-- 2. agent_tasks 인덱스
-- ========================================
CREATE INDEX idx_agent_tasks_user_id ON agent_tasks(user_id);
CREATE INDEX idx_agent_tasks_tenant_id ON agent_tasks(tenant_id);
CREATE INDEX idx_agent_tasks_status ON agent_tasks(status);
CREATE INDEX idx_agent_tasks_created_at ON agent_tasks(created_at);
CREATE INDEX idx_agent_tasks_task_id ON agent_tasks(task_id);

-- ========================================
-- 3. 테이블 코멘트
-- ========================================
COMMENT ON TABLE agent_tasks IS 'AI 에이전트가 수행하는 장기 실행 작업의 상태를 저장';
COMMENT ON COLUMN agent_tasks.task_id IS '작업 고유 식별자 (UUID)';
COMMENT ON COLUMN agent_tasks.user_id IS '작업을 요청한 사용자 ID';
COMMENT ON COLUMN agent_tasks.tenant_id IS '테넌트 ID (멀티테넌시)';
COMMENT ON COLUMN agent_tasks.task_type IS '작업 유형 (예: data_analysis, report_generation)';
COMMENT ON COLUMN agent_tasks.status IS '작업 상태 (REQUESTED, IN_PROGRESS, COMPLETED, FAILED, WAITING_APPROVAL)';
COMMENT ON COLUMN agent_tasks.progress IS '작업 진척도 (0~100)';
COMMENT ON COLUMN agent_tasks.plan_steps IS 'AI 에이전트의 실행 계획 단계 (JSON 형식)';
COMMENT ON COLUMN agent_tasks.hitl_request_id IS 'HITL 승인 요청 ID (Redis 키 연동)';

-- ========================================
-- 마이그레이션 요약
-- ========================================
-- 테이블: 1개 (agent_tasks)
-- 인덱스: 5개
-- HITL은 Redis 기반으로 별도 테이블 없음
-- ========================================
