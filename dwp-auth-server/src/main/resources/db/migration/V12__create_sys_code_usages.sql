-- ========================================
-- DWP Code Usage Schema V12
-- 생성일: 2026-01-20
-- 목적: 메뉴(리소스)별 코드 사용 범위 정의 테이블 생성
-- ========================================

-- ========================================
-- sys_code_usages (메뉴별 코드 사용 정의)
-- ========================================
CREATE TABLE sys_code_usages (
    sys_code_usage_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    resource_key VARCHAR(200) NOT NULL,
    code_group_key VARCHAR(100) NOT NULL,
    scope VARCHAR(30) NOT NULL DEFAULT 'MENU',
    enabled BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER,
    remark VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_code_usages_unique UNIQUE (tenant_id, resource_key, code_group_key)
);

CREATE INDEX idx_sys_code_usages_tenant_resource ON sys_code_usages(tenant_id, resource_key);
CREATE INDEX idx_sys_code_usages_tenant_group ON sys_code_usages(tenant_id, code_group_key);

COMMENT ON TABLE sys_code_usages IS '메뉴(리소스)별 코드 사용 범위 정의';
COMMENT ON COLUMN sys_code_usages.sys_code_usage_id IS '코드 사용 정의 식별자 (PK)';
COMMENT ON COLUMN sys_code_usages.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_code_usages.resource_key IS '리소스 키 (예: menu.admin.users, menu.admin.roles)';
COMMENT ON COLUMN sys_code_usages.code_group_key IS '코드 그룹 키 (예: RESOURCE_TYPE, SUBJECT_TYPE, ROLE_CODE)';
COMMENT ON COLUMN sys_code_usages.scope IS '사용 범위 (MENU/PAGE/MODULE, 기본값: MENU)';
COMMENT ON COLUMN sys_code_usages.enabled IS '활성화 여부';
COMMENT ON COLUMN sys_code_usages.sort_order IS '정렬 순서';
COMMENT ON COLUMN sys_code_usages.remark IS '비고';
COMMENT ON COLUMN sys_code_usages.created_at IS '생성일시';
COMMENT ON COLUMN sys_code_usages.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN sys_code_usages.updated_at IS '수정일시';
COMMENT ON COLUMN sys_code_usages.updated_by IS '수정자 user_id (논리적 참조)';
