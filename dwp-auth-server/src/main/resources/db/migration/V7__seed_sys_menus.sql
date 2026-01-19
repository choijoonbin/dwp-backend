-- ========================================
-- DWP Menu Tree Seed Data V7
-- 생성일: 2026-01-19
-- 목적: dev tenant 기준 기본 메뉴 트리 seed
-- ========================================

-- ========================================
-- sys_menus seed 데이터 (dev tenant, tenant_id=1)
-- ========================================
-- UPSERT 방식으로 안정성 확보 (ON CONFLICT DO UPDATE)
INSERT INTO sys_menus (tenant_id, menu_key, menu_name, menu_path, menu_icon, menu_group, parent_menu_key, sort_order, depth, is_visible, is_enabled, description, created_at, updated_at)
VALUES
    -- 루트 메뉴 (depth=1)
    (1, 'menu.dashboard', 'Dashboard', '/dashboard', 'solar:home-2-bold', 'APPS', NULL, 10, 1, 'Y', 'Y', '대시보드 메인 페이지', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.mail', 'Mail', '/mail', 'solar:letter-bold', 'APPS', NULL, 20, 1, 'Y', 'Y', '메일 관리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.ai-workspace', 'AI Workspace', '/ai-workspace', 'solar:smartphone-2-bold', 'APPS', NULL, 30, 1, 'Y', 'Y', 'AI 워크스페이스', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin', 'Admin', '/admin', 'solar:settings-bold', 'MANAGEMENT', NULL, 100, 1, 'Y', 'Y', '관리자 메뉴', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    
    -- Mail 하위 메뉴 (depth=2)
    (1, 'menu.mail.inbox', 'Inbox', '/mail/inbox', 'solar:inbox-bold', 'APPS', 'menu.mail', 21, 2, 'Y', 'Y', '받은 메일함', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.mail.sent', 'Sent', '/mail/sent', 'solar:letter-opened-bold', 'APPS', 'menu.mail', 22, 2, 'Y', 'Y', '보낸 메일함', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    
    -- Admin 하위 메뉴 (depth=2)
    (1, 'menu.admin.monitoring', '통합 모니터링', '/admin/monitoring', 'solar:chart-2-bold', 'MANAGEMENT', 'menu.admin', 101, 2, 'Y', 'Y', '시스템 모니터링 대시보드', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.users', '사용자 관리', '/admin/users', 'solar:users-group-rounded-bold', 'MANAGEMENT', 'menu.admin', 102, 2, 'Y', 'Y', '사용자 계정 관리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.roles', '역할 관리', '/admin/roles', 'solar:shield-check-bold', 'MANAGEMENT', 'menu.admin', 103, 2, 'Y', 'Y', '역할 및 권한 관리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.resources', '리소스 관리', '/admin/resources', 'solar:folder-bold', 'MANAGEMENT', 'menu.admin', 104, 2, 'Y', 'Y', '리소스 및 메뉴 관리', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.audit', '감사 로그', '/admin/audit', 'solar:history-bold', 'MANAGEMENT', 'menu.admin', 105, 2, 'Y', 'Y', '시스템 감사 로그', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, menu_key) DO UPDATE SET
    menu_name = EXCLUDED.menu_name,
    menu_path = EXCLUDED.menu_path,
    menu_icon = EXCLUDED.menu_icon,
    menu_group = EXCLUDED.menu_group,
    parent_menu_key = EXCLUDED.parent_menu_key,
    sort_order = EXCLUDED.sort_order,
    depth = EXCLUDED.depth,
    is_visible = EXCLUDED.is_visible,
    is_enabled = EXCLUDED.is_enabled,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 시퀀스 재설정 (Auto Increment 동기화)
-- ========================================
SELECT setval('sys_menus_sys_menu_id_seq', (SELECT MAX(sys_menu_id) FROM sys_menus));

-- ========================================
-- Seed 데이터 요약
-- ========================================
-- Tenant: dev (tenant_id=1)
-- 루트 메뉴 (4개):
--   - menu.dashboard (APPS)
--   - menu.mail (APPS)
--   - menu.ai-workspace (APPS)
--   - menu.admin (MANAGEMENT)
-- 하위 메뉴:
--   - menu.mail.inbox, menu.mail.sent
--   - menu.admin.monitoring, menu.admin.users, menu.admin.roles, menu.admin.resources, menu.admin.audit
-- ========================================
