-- V17: V16 엔터프라이즈 메뉴(MANAGEMENT)에 대한 com_resources 및 ADMIN 역할 권한 부여
-- 목적: GET /api/auth/menus/tree 호출 시 V16에서 추가한 메뉴가 권한 기반으로 노출되도록 함
-- - com_resources: MENU 타입 리소스 추가 (menu_key = sys_menus.menu_key)
-- - com_role_permissions: role_id=1(ADMIN)에 VIEW 권한 부여

-- ========================================
-- 1. com_resources에 루트 메뉴 추가 (depth=1, parent_menu_key IS NULL)
-- ========================================
INSERT INTO com_resources (tenant_id, type, key, name, parent_resource_id, metadata_json, enabled, created_at, updated_at)
SELECT
    1,
    'MENU',
    m.menu_key,
    m.menu_name,
    NULL,
    CASE WHEN m.menu_path IS NOT NULL THEN ('{"route":"' || m.menu_path || '"}')::TEXT ELSE '{}' END,
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM sys_menus m
WHERE m.tenant_id = 1
  AND m.menu_group = 'MANAGEMENT'
  AND m.parent_menu_key IS NULL
  AND m.sort_order >= 200
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    metadata_json = EXCLUDED.metadata_json,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. com_resources에 하위 메뉴 추가 (depth=2, parent_menu_key IS NOT NULL)
-- ========================================
INSERT INTO com_resources (tenant_id, type, key, name, parent_resource_id, metadata_json, enabled, created_at, updated_at)
SELECT
    1,
    'MENU',
    m.menu_key,
    m.menu_name,
    (SELECT c.resource_id FROM com_resources c WHERE c.tenant_id = 1 AND c.type = 'MENU' AND c.key = m.parent_menu_key LIMIT 1),
    CASE WHEN m.menu_path IS NOT NULL THEN ('{"route":"' || m.menu_path || '"}')::TEXT ELSE '{}' END,
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM sys_menus m
WHERE m.tenant_id = 1
  AND m.menu_group = 'MANAGEMENT'
  AND m.parent_menu_key IS NOT NULL
  AND m.sort_order >= 200
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    parent_resource_id = EXCLUDED.parent_resource_id,
    metadata_json = EXCLUDED.metadata_json,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 3. ADMIN 역할(role_id=1)에 V16 메뉴 리소스 VIEW 권한 부여
-- ========================================
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT
    1,
    1,
    r.resource_id,
    p.permission_id,
    'ALLOW',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM com_resources r
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1
  AND r.type = 'MENU'
  AND r.key IN (
    'menu.command-center',
    'menu.autonomous-operations',
    'menu.master-data-history',
    'menu.knowledge-policy',
    'menu.reconciliation-audit',
    'menu.governance-config',
    'menu.autonomous-operations.cases',
    'menu.autonomous-operations.anomalies',
    'menu.autonomous-operations.optimization',
    'menu.autonomous-operations.actions',
    'menu.autonomous-operations.archive',
    'menu.master-data-history.documents',
    'menu.master-data-history.open-items',
    'menu.master-data-history.entities',
    'menu.master-data-history.lineage',
    'menu.knowledge-policy.rag',
    'menu.knowledge-policy.policies',
    'menu.knowledge-policy.guardrails',
    'menu.knowledge-policy.dictionary',
    'menu.knowledge-policy.feedback',
    'menu.reconciliation-audit.reconciliation',
    'menu.reconciliation-audit.action-recon',
    'menu.reconciliation-audit.audit',
    'menu.reconciliation-audit.analytics',
    'menu.governance-config.governance',
    'menu.governance-config.agent-config',
    'menu.governance-config.integrations',
    'menu.governance-config.admin'
  )
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 4. 시퀀스 재설정 (선택)
-- ========================================
SELECT setval('com_resources_resource_id_seq', (SELECT COALESCE(MAX(resource_id), 1) FROM com_resources));
SELECT setval('com_role_permissions_role_permission_id_seq', (SELECT COALESCE(MAX(role_permission_id), 1) FROM com_role_permissions));
