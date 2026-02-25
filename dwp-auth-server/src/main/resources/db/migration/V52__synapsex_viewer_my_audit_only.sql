-- V52: SYNAPSEX_VIEWER 메뉴 권한을 'my-audit' 3개로 제한
-- 요구사항: 신규 추가 메뉴만 노출, 기존 메뉴 권한 제거
SET search_path TO public;

-- 1) my-audit 메뉴 리소스 보강 (없으면 생성/있으면 갱신)
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
  AND m.menu_key IN ('menu.my-audit', 'menu.my-audit.clarification', 'menu.my-audit.expenses')
ON CONFLICT (tenant_id, type, key) DO UPDATE
SET name = EXCLUDED.name,
    parent_resource_id = EXCLUDED.parent_resource_id,
    metadata_json = EXCLUDED.metadata_json,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

-- 2) SYNAPSEX_VIEWER의 기존 MENU 권한 전부 제거 (VIEW/USE/EDIT 등 포함)
DELETE FROM com_role_permissions rp
USING com_roles r, com_resources rs
WHERE rp.tenant_id = 1
  AND rp.role_id = r.role_id
  AND r.tenant_id = 1
  AND r.code = 'SYNAPSEX_VIEWER'
  AND rp.resource_id = rs.resource_id
  AND rs.type = 'MENU'
  AND (rs.tenant_id = 1 OR rs.tenant_id IS NULL);

-- 3) SYNAPSEX_VIEWER에 my-audit 3개만 VIEW 허용
WITH target_role AS (
    SELECT role_id
    FROM com_roles
    WHERE tenant_id = 1 AND code = 'SYNAPSEX_VIEWER'
),
target_permission AS (
    SELECT permission_id
    FROM com_permissions
    WHERE code = 'VIEW'
),
target_resources AS (
    SELECT DISTINCT ON (key) resource_id
    FROM com_resources
    WHERE type = 'MENU'
      AND key IN ('menu.my-audit', 'menu.my-audit.clarification', 'menu.my-audit.expenses')
      AND (tenant_id = 1 OR tenant_id IS NULL)
    ORDER BY key, CASE WHEN tenant_id = 1 THEN 0 ELSE 1 END
)
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
FROM target_role r
CROSS JOIN target_permission p
CROSS JOIN target_resources rs
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE
SET effect = EXCLUDED.effect,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

