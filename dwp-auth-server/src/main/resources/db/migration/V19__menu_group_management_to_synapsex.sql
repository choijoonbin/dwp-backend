-- V19: menu_group = 'MANAGEMENT' → 'SynapseX' 변경, menu.dashboard 비노출 처리
-- 1) MANAGEMENT 그룹 전체를 SynapseX로 통일
-- 2) menu.dashboard는 미사용이므로 is_visible/is_enabled = 'N' → tree API 응답에서 제외됨

UPDATE sys_menus
SET menu_group = 'SynapseX',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_group = 'MANAGEMENT';

UPDATE sys_menus
SET is_visible = 'N',
    is_enabled = 'N',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND menu_key = 'menu.dashboard';
