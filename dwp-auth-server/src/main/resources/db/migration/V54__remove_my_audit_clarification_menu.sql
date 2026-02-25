-- V54: '소명 요청 내역(menu.my-audit.clarification)' 메뉴 제거
SET search_path TO public;

-- 1) 메뉴 리소스 권한 제거 (모든 역할 대상)
DELETE FROM com_role_permissions rp
USING com_resources rs
WHERE rp.resource_id = rs.resource_id
  AND rs.type = 'MENU'
  AND rs.key = 'menu.my-audit.clarification'
  AND (rs.tenant_id = 1 OR rs.tenant_id IS NULL);

-- 2) 메뉴 리소스 제거
DELETE FROM com_resources
WHERE type = 'MENU'
  AND key = 'menu.my-audit.clarification'
  AND (tenant_id = 1 OR tenant_id IS NULL);

-- 3) 메뉴 제거
DELETE FROM sys_menus
WHERE tenant_id = 1
  AND menu_key = 'menu.my-audit.clarification';

-- 4) 남은 하위 메뉴 정렬 보정 (나의 전표 현황을 1순위로)
UPDATE sys_menus
SET sort_order = 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE tenant_id = 1
  AND menu_key = 'menu.my-audit.expenses';
