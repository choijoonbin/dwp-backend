-- ========================================
-- DWP Event Logs Schema V11
-- 생성일: 2026-01-19
-- 목적: 이벤트 로그 전용 테이블 생성 (운영 확장성 및 조회 성능 향상)
-- ========================================

-- ========================================
-- sys_event_logs (이벤트 로그)
-- ========================================
CREATE TABLE sys_event_logs (
    sys_event_log_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_type VARCHAR(50) NOT NULL,
    resource_key VARCHAR(255) NOT NULL,
    action VARCHAR(100) NOT NULL,
    label VARCHAR(200),
    visitor_id VARCHAR(255),
    user_id BIGINT,
    path VARCHAR(500),
    metadata JSONB,
    ip_address VARCHAR(50),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

COMMENT ON TABLE sys_event_logs IS '이벤트 로그 테이블 (클릭, 실행 등 사용자 액션 추적)';
COMMENT ON COLUMN sys_event_logs.sys_event_log_id IS '이벤트 로그 식별자 (PK)';
COMMENT ON COLUMN sys_event_logs.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_event_logs.occurred_at IS '이벤트 발생 시간 (클라이언트 기준 허용, 기본값: 서버 현재 시간)';
COMMENT ON COLUMN sys_event_logs.event_type IS '이벤트 타입 (view/click/execute 등)';
COMMENT ON COLUMN sys_event_logs.resource_key IS '리소스 키 (menu.xxx / btn.xxx 등)';
COMMENT ON COLUMN sys_event_logs.action IS '액션 (view_users / click_send 등)';
COMMENT ON COLUMN sys_event_logs.label IS 'UI 표시용 라벨';
COMMENT ON COLUMN sys_event_logs.visitor_id IS '방문자 식별자 (없으면 null)';
COMMENT ON COLUMN sys_event_logs.user_id IS '로그인 사용자 ID (없으면 null)';
COMMENT ON COLUMN sys_event_logs.path IS '경로 (/admin/users 등)';
COMMENT ON COLUMN sys_event_logs.metadata IS '추가 데이터 (JSONB)';
COMMENT ON COLUMN sys_event_logs.ip_address IS '접속 IP (서버가 파악한 IP)';
COMMENT ON COLUMN sys_event_logs.user_agent IS 'User-Agent (서버가 파악한 UA)';
COMMENT ON COLUMN sys_event_logs.created_at IS '생성일시';
COMMENT ON COLUMN sys_event_logs.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN sys_event_logs.updated_at IS '수정일시';
COMMENT ON COLUMN sys_event_logs.updated_by IS '수정자 user_id (논리적 참조)';

-- ========================================
-- 인덱스 생성
-- ========================================
CREATE INDEX idx_sys_event_logs_tenant_occurred ON sys_event_logs(tenant_id, occurred_at DESC);
CREATE INDEX idx_sys_event_logs_tenant_visitor ON sys_event_logs(tenant_id, visitor_id);
CREATE INDEX idx_sys_event_logs_tenant_resource ON sys_event_logs(tenant_id, resource_key);
CREATE INDEX idx_sys_event_logs_tenant_user ON sys_event_logs(tenant_id, user_id);
CREATE INDEX idx_sys_event_logs_event_type ON sys_event_logs(event_type);
