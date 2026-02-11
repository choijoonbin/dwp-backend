-- V38: Phase 1 SynapseX Menu Consolidation — Autonomous Workbench
-- 목적: menu.autonomous-operations.workbench 통합 메뉴 추가, anomalies/cases/actions GNB 비노출 처리
-- Path: /synapse/workbench | GNB 상단(자율 운영 센터 하위 첫 번째) 배치
-- 기존 데이터 유실 없음, PostgreSQL 문법 준수

SET search_path TO public;

-- ========================================
-- 1. sys_menus: 신규 Workbench 메뉴 INSERT
-- ========================================
INSERT INTO sys_menus (tenant_id, menu_key, menu_name, menu_path, menu_icon, menu_group, parent_menu_key, sort_order, depth, is_visible, is_enabled, description, created_at, updated_at)
VALUES
    (1, 'menu.autonomous-operations.workbench', '자율 작업대', '/synapse/workbench', 'solar:widget-bold', 'MANAGEMENT', 'menu.autonomous-operations', 10, 2, 'Y', 'Y', 'Autonomous Workbench — 케이스·이상 징후·조치 통합 뷰', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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

-- i18n 컬럼 있으면 설정 (V27+)
UPDATE sys_menus SET menu_name_ko = '자율 작업대', menu_name_en = 'Autonomous Workbench', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.workbench';

-- ========================================
-- 2. sys_menus: 기존 anomalies, cases, actions GNB 비노출 (is_visible = 'N')
-- ========================================
UPDATE sys_menus
SET is_visible = 'N', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key IN (
    'menu.autonomous-operations.anomalies',
    'menu.autonomous-operations.cases',
    'menu.autonomous-operations.actions'
  );

-- ========================================
-- 3. sys_menus: sort_order — Workbench 최상단(10), 나머지 유지
-- ========================================
-- V20 기준: autonomous-operations=20, cases=11, anomalies=12, optimization=13, actions=14, archive=15
-- workbench=10 으로 이미 INSERT 함. 기존 11~15 유지 (숨긴 메뉴도 순서 유지)
UPDATE sys_menus SET sort_order = 11, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.cases';
UPDATE sys_menus SET sort_order = 12, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.anomalies';
UPDATE sys_menus SET sort_order = 13, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.optimization';
UPDATE sys_menus SET sort_order = 14, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.actions';
UPDATE sys_menus SET sort_order = 15, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 1 AND menu_key = 'menu.autonomous-operations.archive';

-- ========================================
-- 4. com_resources: workbench 리소스 등록
-- ========================================
INSERT INTO com_resources (tenant_id, type, key, name, parent_resource_id, metadata_json, enabled, created_at, updated_at)
SELECT
    1,
    'MENU',
    'menu.autonomous-operations.workbench',
    '자율 작업대',
    (SELECT resource_id FROM com_resources WHERE tenant_id = 1 AND type = 'MENU' AND key = 'menu.autonomous-operations' LIMIT 1),
    '{"route":"/synapse/workbench"}',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    parent_resource_id = EXCLUDED.parent_resource_id,
    metadata_json = EXCLUDED.metadata_json,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 5. role_permissions: ADMIN + Synapse 역할에 workbench 접근 권한
-- (V23 패턴 준수: SA=VIEW+USE+EDIT+APPROVE+EXECUTE, SO=VIEW+USE+EDIT, SV=VIEW, ADMIN=전체)
-- ========================================
-- ADMIN: VIEW (자율 운영 계열과 동일 수준으로 전체 조회 가능)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU' AND res.key = 'menu.autonomous-operations.workbench'
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ADMIN: USE, EDIT, APPROVE, EXECUTE (운영 메뉴와 동일)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU' AND res.key = 'menu.autonomous-operations.workbench'
  AND p.code IN ('USE', 'EDIT', 'APPROVE', 'EXECUTE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- SYNAPSEX_ADMIN: VIEW, USE, EDIT, APPROVE, EXECUTE
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU' AND res.key = 'menu.autonomous-operations.workbench'
  AND p.code IN ('VIEW', 'USE', 'EDIT', 'APPROVE', 'EXECUTE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- SYNAPSEX_OPERATOR: VIEW, USE, EDIT
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_OPERATOR'
  AND res.tenant_id = 1 AND res.type = 'MENU' AND res.key = 'menu.autonomous-operations.workbench'
  AND p.code IN ('VIEW', 'USE', 'EDIT')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- SYNAPSEX_VIEWER: VIEW
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_VIEWER'
  AND res.tenant_id = 1 AND res.type = 'MENU' AND res.key = 'menu.autonomous-operations.workbench'
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 6. 시퀀스 재설정 (선택)
-- ========================================
SELECT setval('sys_menus_sys_menu_id_seq', (SELECT COALESCE(MAX(sys_menu_id), 1) FROM sys_menus));
SELECT setval('com_resources_resource_id_seq', (SELECT COALESCE(MAX(resource_id), 1) FROM com_resources));
SELECT setval('com_role_permissions_role_permission_id_seq', (SELECT COALESCE(MAX(role_permission_id), 1) FROM com_role_permissions));
