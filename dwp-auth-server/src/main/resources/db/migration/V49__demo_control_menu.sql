-- V49: 시연 데이터 제어 메뉴 (거버넌스·설정 하위, 감사 추적 로그 다음)
-- menu_key: menu.demo-control, menu_path: /synapse/demo-control, sort_order: 35

SET search_path TO public;

-- 1. sys_menus: 시연 데이터 제어 (depth=2, parent=menu.governance-config, sort_order=35)
INSERT INTO sys_menus (tenant_id, menu_key, menu_name, menu_name_ko, menu_name_en, menu_path, menu_icon, menu_group, parent_menu_key, sort_order, depth, is_visible, is_enabled, description, created_at, updated_at)
VALUES (
    1,
    'menu.demo-control',
    '시연 데이터 제어',
    '시연 데이터 제어',
    'Demo Data Control',
    '/synapse/demo-control',
    'solar:play-circle-bold',
    'MANAGEMENT',
    'menu.governance-config',
    35,
    2,
    'Y',
    'Y',
    '시연용 위반/정상 시나리오 데이터 생성 및 탐지 트리거',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (tenant_id, menu_key) DO UPDATE SET
    menu_name       = EXCLUDED.menu_name,
    menu_name_ko    = EXCLUDED.menu_name_ko,
    menu_name_en    = EXCLUDED.menu_name_en,
    menu_path       = EXCLUDED.menu_path,
    menu_icon       = EXCLUDED.menu_icon,
    parent_menu_key = EXCLUDED.parent_menu_key,
    sort_order      = EXCLUDED.sort_order,
    depth           = EXCLUDED.depth,
    is_visible      = EXCLUDED.is_visible,
    is_enabled      = EXCLUDED.is_enabled,
    description     = EXCLUDED.description,
    updated_at       = CURRENT_TIMESTAMP;

-- 2. com_resources (MENU, parent = menu.governance-config)
INSERT INTO com_resources (tenant_id, type, key, name, parent_resource_id, metadata_json, enabled, created_at, updated_at)
SELECT
    1,
    'MENU',
    'menu.demo-control',
    '시연 데이터 제어',
    (SELECT resource_id FROM com_resources WHERE tenant_id = 1 AND type = 'MENU' AND key = 'menu.governance-config' LIMIT 1),
    '{"route":"/synapse/demo-control"}',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM com_resources WHERE tenant_id = 1 AND type = 'MENU' AND key = 'menu.demo-control')
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name                = EXCLUDED.name,
    parent_resource_id   = EXCLUDED.parent_resource_id,
    metadata_json        = EXCLUDED.metadata_json,
    enabled              = EXCLUDED.enabled,
    updated_at           = CURRENT_TIMESTAMP;

-- 3. ADMIN(role_id=1) VIEW 권한
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, 1, r.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_resources r
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.type = 'MENU' AND r.key = 'menu.demo-control'
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;
