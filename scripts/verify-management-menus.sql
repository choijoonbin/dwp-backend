-- V16 적용 후 MANAGEMENT 그룹 메뉴 검증 (27건: 대메뉴 6 + 하위 21)
-- 사용: psql -U <user> -d <auth_db> -f scripts/verify-management-menus.sql

SELECT menu_group, depth, sort_order, menu_key, menu_name, menu_path, parent_menu_key
FROM sys_menus
WHERE menu_group = 'MANAGEMENT'
ORDER BY sort_order;

SELECT COUNT(*) AS management_menu_count FROM sys_menus WHERE menu_group = 'MANAGEMENT';
-- 기대값: 27
