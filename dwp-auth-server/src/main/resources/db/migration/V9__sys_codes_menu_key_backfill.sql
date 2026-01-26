-- ========================================
-- V9: sys_codes menu_key NULL 보정
-- 원인: V6에서 group_key별로 한 번만 보정했고, V7/V8에서 추가된 코드는 menu_key 미설정.
--      또한 sys_code_usages에 없는 code_group은 처음부터 null 유지.
-- ========================================

-- 1. MONITORING_CONFIG_KEY 그룹 전체에 사용 메뉴 재적용 (V7/V8에서 추가된 코드 반영)
UPDATE sys_codes
SET menu_key = 'menu.admin.monitoring'
WHERE group_key = 'MONITORING_CONFIG_KEY' AND (menu_key IS NULL OR menu_key = '');

-- 2. 그 외 group_key: sys_code_usages에 있는 code_group_key 기준으로 menu_key 재보정
UPDATE sys_codes c
SET menu_key = sq.resource_key
FROM (
    SELECT DISTINCT ON (code_group_key) code_group_key, resource_key
    FROM sys_code_usages
    ORDER BY code_group_key, tenant_id, resource_key
) sq
WHERE c.group_key = sq.code_group_key AND (c.menu_key IS NULL OR c.menu_key = '');
