-- V46: 사이드바 1뎁스 승격 및 구조화 (Standard)
-- 1) 통합 워크벤치: 최상위(Root) 1뎁스 유지 (V44에서 이미 승격됨, 하위 중복 '워크벤치' 없음)
-- 2) 지식·정책 허브: 최상위(Root) 1뎁스 유지 (V16부터 parent=NULL)
-- 3) 원천 데이터·이력 허브: '거버넌스·설정' 그룹 하위로 이동하여 워크벤치 복잡도 감소

SET search_path TO public;

-- ========================================
-- 1. 통합 워크벤치 — Root 1뎁스 확인 (이미 V44에서 적용)
-- ========================================
UPDATE sys_menus
SET menu_name_ko = '통합 워크벤치',
    menu_name_en = COALESCE(menu_name_en, 'Unified Workbench'),
    parent_menu_key = NULL,
    depth          = 1,
    sort_order     = 1,
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.autonomous-operations.workbench';

-- ========================================
-- 2. 지식·정책 허브 — Root 1뎁스 확인
-- ========================================
UPDATE sys_menus
SET menu_name_ko = '지식·정책 허브',
    menu_name_en = COALESCE(menu_name_en, 'Knowledge & Policy'),
    parent_menu_key = NULL,
    depth          = 1,
    sort_order     = 2,
    updated_at     = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.knowledge-policy';

-- ========================================
-- 3. 원천 데이터·이력 허브 → 거버넌스·설정 하위로 이동
-- ========================================
UPDATE sys_menus
SET parent_menu_key = 'menu.governance-config',
    depth           = 2,
    sort_order      = 255,
    updated_at      = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.master-data-history';

-- 3.1 원천 데이터·이력 하위 메뉴 depth = 3
UPDATE sys_menus
SET depth = 3,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND parent_menu_key = 'menu.master-data-history';
