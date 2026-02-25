-- V53: ADMIN 메뉴 VIEW 권한 전체 동기화
-- 목적: 신규 추가 메뉴를 포함해 ADMIN이 sys_menus의 모든 메뉴를 볼 수 있도록 보장
SET search_path TO public;

-- 1) sys_menus -> com_resources (MENU) 동기화
INSERT INTO com_resources (
    tenant_id, type, key, name, parent_resource_id, metadata_json, enabled, created_at, created_by, updated_at, updated_by
)
SELECT
    m.tenant_id,
    'MENU',
    m.menu_key,
    m.menu_name,
    (
        SELECT pr.resource_id
        FROM com_resources pr
        WHERE pr.tenant_id = m.tenant_id
          AND pr.type = 'MENU'
          AND pr.key = m.parent_menu_key
        LIMIT 1
    ),
    CASE WHEN m.menu_path IS NOT NULL THEN ('{"route":"' || m.menu_path || '"}') ELSE '{}' END,
    TRUE,
    CURRENT_TIMESTAMP,
    1,
    CURRENT_TIMESTAMP,
    1
FROM sys_menus m
WHERE m.tenant_id = 1
ON CONFLICT (tenant_id, type, key) DO UPDATE
SET name = EXCLUDED.name,
    parent_resource_id = EXCLUDED.parent_resource_id,
    metadata_json = EXCLUDED.metadata_json,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

-- 2) ADMIN에 모든 MENU VIEW 권한 부여
INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect, created_at, created_by, updated_at, updated_by
)
SELECT
    1,
    r.role_id,
    rs.resource_id,
    p.permission_id,
    'ALLOW',
    CURRENT_TIMESTAMP,
    1,
    CURRENT_TIMESTAMP,
    1
FROM com_roles r
JOIN com_permissions p
  ON p.code = 'VIEW'
JOIN com_resources rs
  ON rs.type = 'MENU'
 AND (rs.tenant_id = 1 OR rs.tenant_id IS NULL)
WHERE r.tenant_id = 1
  AND r.code = 'ADMIN'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE
SET effect = EXCLUDED.effect,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

