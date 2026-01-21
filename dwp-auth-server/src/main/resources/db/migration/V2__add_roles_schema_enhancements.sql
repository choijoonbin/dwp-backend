-- ========================================
-- DWP Roles Schema Enhancements V2
-- 생성일: 2026-01-21
-- 목적: Roles 화면(운영급 UI) 대응을 위한 스키마 보완
-- 
-- 변경 사항:
-- 1. com_roles.status 추가 (ACTIVE/INACTIVE)
-- 2. com_permissions.sort_order + description 추가
-- 3. com_resources.sort_order 추가
-- 4. ROLE_STATUS 코드 그룹 추가
-- ========================================

-- ========================================
-- 1. com_roles.status 추가
-- ========================================
ALTER TABLE com_roles 
ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

COMMENT ON COLUMN com_roles.status IS '역할 상태 (ACTIVE/INACTIVE)';

-- 기존 데이터는 모두 ACTIVE로 설정
UPDATE com_roles SET status = 'ACTIVE' WHERE status IS NULL;

-- 인덱스 추가 (status 필터링 성능 향상)
CREATE INDEX IF NOT EXISTS idx_com_roles_status ON com_roles(status);
CREATE INDEX IF NOT EXISTS idx_com_roles_tenant_status ON com_roles(tenant_id, status);

-- ========================================
-- 2. com_permissions.sort_order + description 추가
-- ========================================
ALTER TABLE com_permissions 
ADD COLUMN IF NOT EXISTS sort_order INTEGER DEFAULT 0;

ALTER TABLE com_permissions 
ADD COLUMN IF NOT EXISTS description TEXT;

COMMENT ON COLUMN com_permissions.sort_order IS '정렬 순서 (매트릭스 컬럼 순서)';
COMMENT ON COLUMN com_permissions.description IS '권한 설명 (툴팁용)';

-- 기존 데이터 정렬 순서 설정 (기본값: code 기준)
UPDATE com_permissions SET sort_order = 
    CASE code
        WHEN 'VIEW' THEN 10
        WHEN 'USE' THEN 20
        WHEN 'EDIT' THEN 30
        WHEN 'APPROVE' THEN 40
        WHEN 'EXECUTE' THEN 50
        ELSE 100
    END
WHERE sort_order IS NULL OR sort_order = 0;

-- ========================================
-- 3. com_resources.sort_order 추가
-- ========================================
ALTER TABLE com_resources 
ADD COLUMN IF NOT EXISTS sort_order INTEGER DEFAULT 0;

COMMENT ON COLUMN com_resources.sort_order IS '정렬 순서 (리소스 트리 정렬)';

-- 기존 데이터는 name 기준으로 정렬 순서 설정 (임시)
-- 실제 정렬 순서는 운영 중에 조정 필요
UPDATE com_resources SET sort_order = 0 WHERE sort_order IS NULL;

-- 인덱스 추가 (트리 정렬 성능 향상)
CREATE INDEX IF NOT EXISTS idx_com_resources_parent_sort ON com_resources(parent_resource_id, sort_order);

-- ========================================
-- 4. ROLE_STATUS 코드 그룹 추가
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('ROLE_STATUS', '역할 상태', '역할 활성화 상태 (ACTIVE, INACTIVE)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('ROLE_STATUS', 'ACTIVE', '활성', '활성화된 역할', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ROLE_STATUS', 'INACTIVE', '비활성', '비활성화된 역할', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 5. menu.admin.roles에 ROLE_STATUS 코드 사용 범위 추가
-- ========================================
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.admin.roles', 'ROLE_STATUS', 'MENU', true, 5, '역할 상태 필터/표시', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = EXCLUDED.enabled,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 6. 검증 쿼리
-- ========================================
DO $$
DECLARE
    role_status_count INTEGER;
BEGIN
    -- ROLE_STATUS 코드 그룹 확인
    SELECT COUNT(*) INTO role_status_count
    FROM sys_code_groups
    WHERE group_key = 'ROLE_STATUS';
    
    IF role_status_count = 0 THEN
        RAISE EXCEPTION 'ROLE_STATUS 코드 그룹이 생성되지 않았습니다.';
    END IF;
    
    RAISE NOTICE 'ROLE_STATUS 코드 그룹 생성 완료';
END $$;
