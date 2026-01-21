-- ========================================
-- DWP Admin Menu Resources V3
-- 생성일: 2026-01-21
-- 목적: Admin UI Publishing을 위한 메뉴 및 리소스 추가
-- 
-- 추가 메뉴:
--   - menu.admin.menus (메뉴 관리)
--   - menu.admin.codes (코드 관리)
--   - menu.admin.code-usages (코드 사용정의)
-- 
-- 기존 메뉴 숨김:
--   - menu.admin.resources (is_visible = 'N', 하드 삭제 금지)
-- ========================================

-- ========================================
-- 1. sys_menus에 새 메뉴 추가 (tenant_id=1)
-- ========================================
INSERT INTO sys_menus (tenant_id, menu_key, menu_name, menu_path, menu_icon, menu_group, parent_menu_key, sort_order, depth, is_visible, is_enabled, description, created_at, updated_at)
VALUES
    -- 메뉴 관리 (sort_order=106)
    (1, 'menu.admin.menus', '메뉴 관리', '/admin/menus', 'solar:folder-bold', 'MANAGEMENT', 'menu.admin', 106, 2, 'Y', 'Y', '메뉴 트리 관리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 코드 관리 (sort_order=107)
    (1, 'menu.admin.codes', '코드 관리', '/admin/codes', 'solar:code-bold', 'MANAGEMENT', 'menu.admin', 107, 2, 'Y', 'Y', '공통 코드 관리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 코드 사용정의 (sort_order=108)
    (1, 'menu.admin.code-usages', '코드 사용정의', '/admin/code-usages', 'solar:list-check-bold', 'MANAGEMENT', 'menu.admin', 108, 2, 'Y', 'Y', '메뉴별 코드 사용 정의 관리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, menu_key) DO UPDATE SET
    menu_name = EXCLUDED.menu_name,
    menu_path = EXCLUDED.menu_path,
    menu_icon = EXCLUDED.menu_icon,
    menu_group = EXCLUDED.menu_group,
    parent_menu_key = EXCLUDED.parent_menu_key,
    sort_order = EXCLUDED.sort_order,
    depth = EXCLUDED.depth,
    is_visible = EXCLUDED.is_visible,
    is_enabled = EXCLUDED.is_enabled,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. menu.admin.resources 숨김 처리 (하드 삭제 금지)
-- ========================================
UPDATE sys_menus
SET is_visible = 'N',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.admin.resources';

-- ========================================
-- 3. com_resources에 새 리소스 추가 (tenant_id=1)
-- ========================================
-- resource_id는 자동 증가하므로 명시하지 않음
INSERT INTO com_resources (tenant_id, type, key, name, parent_resource_id, metadata_json, enabled, created_at, updated_at)
VALUES
    -- 메뉴 관리 리소스
    (1, 'MENU', 'menu.admin.menus', '메뉴 관리', 8, '{"route": "/admin/menus", "icon": "folder"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 코드 관리 리소스
    (1, 'MENU', 'menu.admin.codes', '코드 관리', 8, '{"route": "/admin/codes", "icon": "code"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 코드 사용정의 리소스
    (1, 'MENU', 'menu.admin.code-usages', '코드 사용정의', 8, '{"route": "/admin/code-usages", "icon": "list_check"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    parent_resource_id = EXCLUDED.parent_resource_id,
    metadata_json = EXCLUDED.metadata_json,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 4. ADMIN 역할(role_id=1)에 새 리소스 권한 부여
-- ========================================
-- 각 리소스에 VIEW 권한 부여 (최소 권한, 메뉴 트리 표시용)
-- 필요시 USE, EDIT 권한도 추가 가능

-- 메뉴 관리: VIEW + USE + EDIT (메뉴 CRUD 필요)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 
    1,  -- tenant_id
    1,  -- role_id (ADMIN)
    r.resource_id,  -- resource_id (menu.admin.menus)
    p.permission_id,  -- permission_id (VIEW=1, USE=2, EDIT=3)
    'ALLOW',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM com_resources r
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1
  AND r.key = 'menu.admin.menus'
  AND p.code IN ('VIEW', 'USE', 'EDIT')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 코드 관리: VIEW + USE + EDIT (코드 CRUD 필요)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 
    1,  -- tenant_id
    1,  -- role_id (ADMIN)
    r.resource_id,  -- resource_id (menu.admin.codes)
    p.permission_id,  -- permission_id (VIEW=1, USE=2, EDIT=3)
    'ALLOW',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM com_resources r
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1
  AND r.key = 'menu.admin.codes'
  AND p.code IN ('VIEW', 'USE', 'EDIT')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 코드 사용정의: VIEW + USE + EDIT (코드 사용정의 CRUD 필요)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 
    1,  -- tenant_id
    1,  -- role_id (ADMIN)
    r.resource_id,  -- resource_id (menu.admin.code-usages)
    p.permission_id,  -- permission_id (VIEW=1, USE=2, EDIT=3)
    'ALLOW',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM com_resources r
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1
  AND r.key = 'menu.admin.code-usages'
  AND p.code IN ('VIEW', 'USE', 'EDIT')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 5. 시퀀스 재설정 (Auto Increment 동기화)
-- ========================================
SELECT setval('sys_menus_sys_menu_id_seq', (SELECT MAX(sys_menu_id) FROM sys_menus));
SELECT setval('com_resources_resource_id_seq', (SELECT MAX(resource_id) FROM com_resources));
SELECT setval('com_role_permissions_role_permission_id_seq', (SELECT MAX(role_permission_id) FROM com_role_permissions));

-- ========================================
-- 마이그레이션 완료 요약
-- ========================================
-- 추가된 메뉴 (sys_menus):
--   - menu.admin.menus (sort_order=106)
--   - menu.admin.codes (sort_order=107)
--   - menu.admin.code-usages (sort_order=108)
-- 
-- 숨김 처리된 메뉴:
--   - menu.admin.resources (is_visible='N')
-- 
-- 추가된 리소스 (com_resources):
--   - menu.admin.menus
--   - menu.admin.codes
--   - menu.admin.code-usages
-- 
-- 권한 부여 (com_role_permissions):
--   - ADMIN 역할(role_id=1)에 각 리소스에 대해 VIEW, USE, EDIT 권한 부여
-- ========================================
