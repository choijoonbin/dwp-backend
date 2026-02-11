-- V47: Global Menu Structure Realignment (Level 4 Depth Optimization)
-- 1) 통합 워크벤치: menu.workbench 로 키 통합, Depth 1, sort 10
-- 2) 지식·정책 허브: Depth 1, sort 20
-- 3) 거버넌스·설정: Depth 1, sort 30; 원천 데이터·이력, 정합성 대사 리포트를 하위(Depth 2)로
-- 4) 관리 서비스(menu.admin): Depth 1, sort 900
-- 5) command-center 제거(비노출), com_resources 키 동기화

SET search_path TO public;

-- ========================================
-- 1. 통합 워크벤치: menu.autonomous-operations.workbench → menu.workbench
-- ========================================
UPDATE sys_menus
SET menu_key         = 'menu.workbench',
    menu_name        = '통합 워크벤치',
    menu_name_ko     = '통합 워크벤치',
    menu_name_en     = COALESCE(menu_name_en, 'Unified Workbench'),
    menu_path        = '/synapse/workbench',
    menu_icon        = 'solar:clapperboard-edit-bold',
    parent_menu_key  = NULL,
    depth            = 1,
    sort_order       = 10,
    is_visible       = 'Y',
    is_enabled       = 'Y',
    updated_at       = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.autonomous-operations.workbench';

UPDATE com_resources
SET key = 'menu.workbench',
    parent_resource_id = NULL,
    name = '통합 워크벤치',
    metadata_json = '{"route":"/synapse/workbench"}',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND type = 'MENU'
  AND key = 'menu.autonomous-operations.workbench';

-- ========================================
-- 2. command-center 비노출 (제거 대신 숨김)
-- ========================================
UPDATE sys_menus
SET is_visible = 'N',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.command-center';

-- ========================================
-- 3. 지식·정책 허브 — Depth 1, sort 20
-- ========================================
UPDATE sys_menus
SET parent_menu_key = NULL,
    depth          = 1,
    sort_order     = 20,
    menu_icon      = 'solar:library-bold',
    menu_name_ko   = '지식·정책 허브',
    menu_name_en   = COALESCE(menu_name_en, 'Knowledge & Policy'),
    is_visible     = 'Y',
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.knowledge-policy';

-- ========================================
-- 4. 거버넌스·설정 — Depth 1, sort 30
-- ========================================
UPDATE sys_menus
SET parent_menu_key = NULL,
    depth          = 1,
    sort_order     = 30,
    menu_icon      = 'solar:settings-minimalistic-bold',
    menu_name_ko   = '거버넌스·설정',
    menu_name_en   = COALESCE(menu_name_en, 'Governance & Config'),
    is_visible     = 'Y',
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.governance-config';

-- ========================================
-- 5. 원천 데이터·이력 — 거버넌스 하위 Depth 2, sort 31
-- ========================================
UPDATE sys_menus
SET parent_menu_key = 'menu.governance-config',
    depth          = 2,
    sort_order     = 31,
    menu_icon      = 'solar:database-bold',
    menu_name      = '원천 데이터·이력',
    menu_name_ko   = '원천 데이터·이력',
    menu_name_en   = COALESCE(menu_name_en, 'Master Data & History'),
    is_visible     = 'Y',
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.master-data-history';

UPDATE sys_menus
SET depth      = 3,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND parent_menu_key = 'menu.master-data-history';

-- ========================================
-- 6. 정합성 대사 리포트(대사·감사 센터) — 거버넌스 하위 Depth 2, sort 32
-- ========================================
UPDATE sys_menus
SET parent_menu_key = 'menu.governance-config',
    depth          = 2,
    sort_order     = 32,
    menu_icon      = 'solar:clipboard-list-bold',
    menu_name      = '정합성 대사 리포트',
    menu_name_ko   = '정합성 대사 리포트',
    menu_name_en   = COALESCE(menu_name_en, 'Reconciliation & Audit'),
    is_visible     = 'Y',
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.reconciliation-audit';

UPDATE sys_menus
SET depth      = 3,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND parent_menu_key = 'menu.reconciliation-audit';

-- ========================================
-- 7. 관리 서비스(menu.admin) — Depth 1, sort 900
-- ========================================
UPDATE sys_menus
SET parent_menu_key = NULL,
    depth          = 1,
    sort_order     = 900,
    menu_icon      = 'solar:shield-user-bold',
    menu_name      = '관리 서비스',
    menu_name_ko   = '관리 서비스',
    menu_name_en   = COALESCE(menu_name_en, 'Admin'),
    is_visible     = 'Y',
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.admin';

-- ========================================
-- 8. 자율 운영 센터 비노출 유지 (중복 그룹 제거)
-- ========================================
UPDATE sys_menus
SET is_visible = 'N',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.autonomous-operations';
