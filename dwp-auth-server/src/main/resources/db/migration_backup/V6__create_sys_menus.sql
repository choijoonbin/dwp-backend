-- ========================================
-- DWP Menu Tree Schema V6
-- 생성일: 2026-01-19
-- 목적: 메뉴 트리 관리 테이블 생성
-- ========================================

-- ========================================
-- sys_menus (메뉴 트리 메타 테이블)
-- ========================================
CREATE TABLE sys_menus (
    sys_menu_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    menu_key VARCHAR(255) NOT NULL,
    menu_name VARCHAR(200) NOT NULL,
    menu_path VARCHAR(500),
    menu_icon VARCHAR(100),
    menu_group VARCHAR(50),
    parent_menu_key VARCHAR(255),
    sort_order INTEGER NOT NULL DEFAULT 0,
    depth INTEGER NOT NULL DEFAULT 1,
    is_visible VARCHAR(1) NOT NULL DEFAULT 'Y',
    is_enabled VARCHAR(1) NOT NULL DEFAULT 'Y',
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_menus_tenant_key UNIQUE (tenant_id, menu_key)
);

COMMENT ON TABLE sys_menus IS '메뉴 트리 메타 테이블 (권한은 com_resources + com_role_permissions에서 관리)';
COMMENT ON COLUMN sys_menus.sys_menu_id IS '메뉴 식별자 (PK)';
COMMENT ON COLUMN sys_menus.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN sys_menus.menu_key IS '메뉴 키 (com_resources.resource_key와 매칭, 예: menu.admin.users)';
COMMENT ON COLUMN sys_menus.menu_name IS '화면 노출명 (예: 사용자 관리)';
COMMENT ON COLUMN sys_menus.menu_path IS '라우트 경로 (예: /admin/users)';
COMMENT ON COLUMN sys_menus.menu_icon IS '아이콘 키 (예: solar:settings-bold)';
COMMENT ON COLUMN sys_menus.menu_group IS '메뉴 그룹 (MANAGEMENT/APPS 등)';
COMMENT ON COLUMN sys_menus.parent_menu_key IS '상위 메뉴 키 (루트면 NULL, 예: menu.admin)';
COMMENT ON COLUMN sys_menus.sort_order IS '정렬 순서 (낮을수록 앞)';
COMMENT ON COLUMN sys_menus.depth IS '메뉴 깊이 (1=루트, 2=하위, 3=하하위)';
COMMENT ON COLUMN sys_menus.is_visible IS '노출 여부 (Y/N, 권한과 별개로 시스템에서 숨김 가능)';
COMMENT ON COLUMN sys_menus.is_enabled IS '활성화 여부 (Y/N)';
COMMENT ON COLUMN sys_menus.description IS '메뉴 설명';
COMMENT ON COLUMN sys_menus.created_at IS '생성일시';
COMMENT ON COLUMN sys_menus.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN sys_menus.updated_at IS '수정일시';
COMMENT ON COLUMN sys_menus.updated_by IS '수정자 user_id (논리적 참조)';

-- ========================================
-- 인덱스 생성
-- ========================================
CREATE INDEX idx_sys_menus_tenant_id ON sys_menus(tenant_id);
CREATE INDEX idx_sys_menus_menu_key ON sys_menus(menu_key);
CREATE INDEX idx_sys_menus_parent_key ON sys_menus(parent_menu_key);
CREATE INDEX idx_sys_menus_tenant_parent ON sys_menus(tenant_id, parent_menu_key);
