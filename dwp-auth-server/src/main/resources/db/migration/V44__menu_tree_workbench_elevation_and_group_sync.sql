-- V44: Final Menu Tree API Normalization
-- 1) Workbench 최상위 승격 (Depth 1, 통합 워크벤치, sort_order=1)
-- 2) 자율 운영 센터 그룹 비노출 (is_visible='N')
-- 3) 통합 관제 센터 경로 통일 + 비노출 (단일 진입점: /synapse/workbench)
-- 4) SynapseX 관련 메뉴 group_code(menu_group) 일괄 'SynapseX'

SET search_path TO public;

-- ========================================
-- 1. Workbench Elevation: 최상위(Depth 1), 명칭·순서
-- ========================================
UPDATE sys_menus
SET parent_menu_key = NULL,
    depth          = 1,
    menu_name      = '통합 워크벤치',
    menu_name_ko   = '통합 워크벤치',
    menu_name_en   = COALESCE(menu_name_en, 'Unified Workbench'),
    sort_order     = 1,
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.autonomous-operations.workbench';

-- ========================================
-- 2. Obsolete Group: 자율 운영 센터 비노출
-- ========================================
UPDATE sys_menus
SET is_visible = 'N',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.autonomous-operations';

-- ========================================
-- 3. Path Alignment: 통합 관제 센터 → /synapse/workbench, 비노출
-- ========================================
UPDATE sys_menus
SET menu_path  = '/synapse/workbench',
    is_visible = 'N',
    updated_at  = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.command-center';

-- ========================================
-- 4. Group Sync: SynapseX 관련 메뉴 menu_group = 'SynapseX'
-- ========================================
UPDATE sys_menus
SET menu_group = 'SynapseX',
    updated_at  = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND (
    menu_key = 'menu.command-center'
    OR menu_key LIKE 'menu.autonomous-operations%'
    OR menu_key LIKE 'menu.master-data-history%'
    OR menu_key LIKE 'menu.knowledge-policy%'
    OR menu_key LIKE 'menu.reconciliation-audit%'
    OR menu_key LIKE 'menu.governance-config%'
  );
