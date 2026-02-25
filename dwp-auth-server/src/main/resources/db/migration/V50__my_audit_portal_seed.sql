-- V50: 일반 사용자(synapsex_operator) + 나의 감사 포털 메뉴 시드
SET search_path TO public;

-- 1) synapsex_operator 사용자 보강 (없으면 생성)
INSERT INTO com_users (tenant_id, display_name, email, primary_department_id, status, created_at, created_by, updated_at, updated_by)
SELECT 1, '일반사용자(운영)', 'synapsex_operator@dev.local', 1, 'ACTIVE', CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 1
WHERE NOT EXISTS (
  SELECT 1 FROM com_users WHERE tenant_id = 1 AND email = 'synapsex_operator@dev.local'
);

-- 2) LOCAL 계정 보강
INSERT INTO com_user_accounts (tenant_id, user_id, provider_type, provider_id, principal, password_hash, status, created_at, updated_at)
SELECT 1, u.user_id, 'LOCAL', 'local', 'synapsex_operator',
       '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi',
       'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_users u
WHERE u.tenant_id = 1 AND u.email = 'synapsex_operator@dev.local'
ON CONFLICT (tenant_id, provider_type, provider_id, principal) DO UPDATE
SET user_id = EXCLUDED.user_id,
    updated_at = CURRENT_TIMESTAMP;

-- 3) 나의 감사 포털 메뉴
INSERT INTO sys_menus (
  tenant_id, menu_key, menu_name, menu_name_ko, menu_name_en,
  menu_path, menu_icon, menu_group, parent_menu_key,
  sort_order, depth, is_visible, is_enabled, description,
  created_at, created_by, updated_at, updated_by
)
VALUES
  (1, 'menu.my-audit', '나의 감사 포털', '나의 감사 포털', 'My Audit Portal', '/my-audit', 'solar:user-id-bold', 'SYNAPSEX', NULL,
   10, 1, 'Y', 'Y', '개인 감사 포털', CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 1),
  (1, 'menu.my-audit.clarification', '소명 요청 내역', '소명 요청 내역', 'Clarification Requests', '/my-audit/clarification', 'solar:document-text-bold', 'SYNAPSEX', 'menu.my-audit',
   1, 2, 'Y', 'Y', '소명 요청 내역 조회', CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 1),
  (1, 'menu.my-audit.expenses', '나의 전표 현황', '나의 전표 현황', 'My Expenses', '/my-audit/expenses', 'solar:card-bold', 'SYNAPSEX', 'menu.my-audit',
   2, 2, 'Y', 'Y', '본인 전표 현황 조회', CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 1)
ON CONFLICT (tenant_id, menu_key) DO UPDATE
SET menu_name = EXCLUDED.menu_name,
    menu_name_ko = EXCLUDED.menu_name_ko,
    menu_name_en = EXCLUDED.menu_name_en,
    menu_path = EXCLUDED.menu_path,
    menu_icon = EXCLUDED.menu_icon,
    menu_group = EXCLUDED.menu_group,
    parent_menu_key = EXCLUDED.parent_menu_key,
    sort_order = EXCLUDED.sort_order,
    depth = EXCLUDED.depth,
    is_visible = EXCLUDED.is_visible,
    is_enabled = EXCLUDED.is_enabled,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;
