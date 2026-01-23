-- ========================================
-- Flyway 마이그레이션 상태 확인 쿼리
-- ========================================

-- 1. Flyway 스키마 히스토리 확인
SELECT 
    installed_rank,
    version,
    description,
    type,
    script,
    installed_on,
    execution_time,
    success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 10;

-- 2. V3 마이그레이션이 실행되었는지 확인
SELECT 
    version,
    description,
    installed_on,
    success,
    execution_time
FROM flyway_schema_history
WHERE version = '3'
   OR description LIKE '%add_admin_menu_resources%';

-- 3. 새로 추가된 메뉴 확인
SELECT 
    menu_key,
    menu_name,
    menu_path,
    sort_order,
    is_visible,
    is_enabled,
    created_at
FROM sys_menus
WHERE tenant_id = 1
  AND menu_key IN ('menu.admin.menus', 'menu.admin.codes', 'menu.admin.code-usages')
ORDER BY sort_order;

-- 4. menu.admin.resources 숨김 처리 확인
SELECT 
    menu_key,
    menu_name,
    is_visible,
    updated_at
FROM sys_menus
WHERE tenant_id = 1
  AND menu_key = 'menu.admin.resources';

-- 5. 새로 추가된 리소스 확인
SELECT 
    resource_id,
    key,
    name,
    type,
    enabled,
    created_at
FROM com_resources
WHERE tenant_id = 1
  AND key IN ('menu.admin.menus', 'menu.admin.codes', 'menu.admin.code-usages')
ORDER BY resource_id;

-- 6. 권한 부여 확인
SELECT 
    r.key AS resource_key,
    p.code AS permission_code,
    rp.effect,
    rp.created_at
FROM com_role_permissions rp
JOIN com_resources r ON rp.resource_id = r.resource_id
JOIN com_permissions p ON rp.permission_id = p.permission_id
WHERE rp.tenant_id = 1
  AND rp.role_id = 1
  AND r.key IN ('menu.admin.menus', 'menu.admin.codes', 'menu.admin.code-usages')
ORDER BY r.key, p.code;
