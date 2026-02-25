-- V51: SYNAPSEX_OPERATOR 역할 기반 '나의 감사 포털' 메뉴 권한 확정
-- 목표: synapsex_operator 로그인 시 menu tree에 my-audit 3개 메뉴만 노출
SET search_path TO public;

-- 1) 사용자-역할 매핑 보강
INSERT INTO com_role_members (
    tenant_id, role_id, subject_type, subject_id, created_at, created_by, updated_at, updated_by
)
SELECT
    1,
    r.role_id,
    'USER',
    a.user_id,
    CURRENT_TIMESTAMP,
    1,
    CURRENT_TIMESTAMP,
    1
FROM com_roles r
JOIN com_user_accounts a
  ON a.tenant_id = r.tenant_id
WHERE r.tenant_id = 1
  AND r.code = 'SYNAPSEX_OPERATOR'
  AND a.provider_type = 'LOCAL'
  AND a.provider_id = 'local'
  AND lower(a.principal) = 'synapsex_operator'
ON CONFLICT (tenant_id, role_id, subject_type, subject_id) DO UPDATE
SET updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

-- 2) my-audit 메뉴 리소스 upsert (sys_menus -> com_resources)
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

-- 3) SYNAPSEX_OPERATOR의 기존 MENU VIEW 권한 제거
DELETE FROM com_role_permissions rp
USING com_roles r, com_permissions p, com_resources rs
WHERE rp.tenant_id = 1
  AND rp.role_id = r.role_id
  AND r.tenant_id = 1
  AND r.code = 'SYNAPSEX_OPERATOR'
  AND rp.permission_id = p.permission_id
  AND p.code = 'VIEW'
  AND rp.resource_id = rs.resource_id
  AND rs.type = 'MENU'
  AND (rs.tenant_id = 1 OR rs.tenant_id IS NULL);

-- 4) my-audit 3개 메뉴만 VIEW=ALLOW 부여
WITH target_role AS (
    SELECT role_id
    FROM com_roles
    WHERE tenant_id = 1 AND code = 'SYNAPSEX_OPERATOR'
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

