-- V26: Admin 모니터링·배치 모니터링 메뉴 보강
-- 목적: 프론트 메뉴 트리에서 모니터링 메뉴가 admin 권한으로 노출되도록 sys_menus, com_resources, com_role_permissions 보강

SET search_path TO public;

-- ========================================
-- 1. sys_menus: 통합 모니터링·배치 모니터링 보강
-- ========================================
-- menu.admin.monitoring: 기존 있으면 유지, 없으면 추가
INSERT INTO sys_menus (tenant_id, menu_key, menu_name, menu_path, menu_icon, menu_group, parent_menu_key, sort_order, depth, is_visible, is_enabled, description, created_at, updated_at)
VALUES
    (1, 'menu.admin.monitoring', '통합 모니터링', '/admin/monitoring', 'solar:chart-2-bold', 'MANAGEMENT', 'menu.admin', 201, 2, 'Y', 'Y', '시스템 모니터링 대시보드', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.batch-monitoring', '배치 모니터링', '/admin/batch-monitoring', 'solar:calendar-minimalistic-bold', 'MANAGEMENT', 'menu.admin', 209, 2, 'Y', 'Y', 'Detect/Ingest Run 관제, 스케줄러 상태', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
-- 2. com_resources: menu.admin.monitoring, menu.admin.batch-monitoring
-- ========================================
INSERT INTO com_resources (tenant_id, type, key, name, parent_resource_id, metadata_json, enabled, created_at, updated_at)
SELECT
    1,
    'MENU',
    m.menu_key,
    m.menu_name,
    (SELECT c.resource_id FROM com_resources c WHERE c.tenant_id = 1 AND c.type = 'MENU' AND c.key = 'menu.admin' LIMIT 1),
    ('{"route":"' || m.menu_path || '","icon":"' || COALESCE(m.menu_icon, '') || '"}'),
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM sys_menus m
WHERE m.tenant_id = 1 AND m.menu_key IN ('menu.admin.monitoring', 'menu.admin.batch-monitoring')
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    parent_resource_id = EXCLUDED.parent_resource_id,
    metadata_json = EXCLUDED.metadata_json,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 3. com_role_permissions: ADMIN에 VIEW/USE/EDIT/EXECUTE 부여
-- ========================================
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.admin.monitoring', 'menu.admin.batch-monitoring')
  AND p.code IN ('VIEW', 'USE', 'EDIT', 'EXECUTE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 4. sys_menus sort_order 정렬 (menu.admin 하위 201~209)
-- ========================================
UPDATE sys_menus SET sort_order = 201, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.monitoring';
UPDATE sys_menus SET sort_order = 209, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.admin.batch-monitoring';
