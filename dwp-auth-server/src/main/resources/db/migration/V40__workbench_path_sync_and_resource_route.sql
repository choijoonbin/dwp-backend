-- V40: 통합 워크벤치 메뉴 경로·명칭 동기화 및 com_resources route 일치
-- 목적: menu_path=/synapse/workbench, menu_name_ko='통합 워크벤치' 확정; com_resources metadata_json.route 동기화
-- RBAC: role_permissions는 변경 없음 (기존 workbench 리소스 키·권한 유지)

SET search_path TO public;

-- ========================================
-- 1. sys_menus: 통합 워크벤치 경로·한글명 확정
-- ========================================
UPDATE sys_menus
SET menu_path   = '/synapse/workbench',
    menu_name   = '통합 워크벤치',
    menu_name_ko = '통합 워크벤치',
    menu_name_en = COALESCE(menu_name_en, 'Unified Workbench'),
    updated_at  = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.autonomous-operations.workbench';

-- ========================================
-- 2. com_resources: workbench 리소스 name·metadata_json.route 동기화
-- ========================================
UPDATE com_resources
SET name          = '통합 워크벤치',
    metadata_json = '{"route":"/synapse/workbench"}',
    updated_at    = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND type = 'MENU'
  AND key = 'menu.autonomous-operations.workbench';
