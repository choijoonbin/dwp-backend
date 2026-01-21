-- ========================================
-- DWP Admin Menu Resources V5
-- 생성일: 2026-01-19
-- 목적: Admin Remote 앱 메뉴 리소스 추가
-- ========================================

-- ========================================
-- 1. Admin 메뉴 리소스 추가
-- ========================================
INSERT INTO com_resources (resource_id, tenant_id, type, key, name, parent_resource_id, metadata_json, enabled, created_at, updated_at)
VALUES
    -- Admin 메인 메뉴
    (8, 1, 'MENU', 'menu.admin', 'Admin', NULL, '{"route": "/admin", "icon": "admin_panel_settings", "remote": "adminRemote"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 통합 모니터링
    (9, 1, 'MENU', 'menu.admin.monitoring', '통합 모니터링', 8, '{"route": "/admin/monitoring", "icon": "monitoring"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 사용자 관리
    (10, 1, 'MENU', 'menu.admin.users', '사용자 관리', 8, '{"route": "/admin/users", "icon": "people"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 역할 관리
    (11, 1, 'MENU', 'menu.admin.roles', '역할 관리', 8, '{"route": "/admin/roles", "icon": "badge"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 리소스 관리
    (12, 1, 'MENU', 'menu.admin.resources', '리소스 관리', 8, '{"route": "/admin/resources", "icon": "folder"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 감사 로그
    (13, 1, 'MENU', 'menu.admin.audit', '감사 로그', 8, '{"route": "/admin/audit", "icon": "history"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ========================================
-- 2. ADMIN 역할에 Admin 메뉴 권한 부여
-- ========================================
-- Admin 메인 메뉴: VIEW + USE
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (15, 1, 1, 8, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Admin: VIEW
    (16, 1, 1, 8, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Admin: USE

-- 통합 모니터링: VIEW + USE
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (17, 1, 1, 9, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Monitoring: VIEW
    (18, 1, 1, 9, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Monitoring: USE

-- 사용자 관리: VIEW + USE + EDIT
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (19, 1, 1, 10, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Users: VIEW
    (20, 1, 1, 10, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Users: USE
    (21, 1, 1, 10, 3, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Users: EDIT

-- 역할 관리: VIEW + USE + EDIT
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (22, 1, 1, 11, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Roles: VIEW
    (23, 1, 1, 11, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Roles: USE
    (24, 1, 1, 11, 3, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Roles: EDIT

-- 리소스 관리: VIEW + USE + EDIT
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (25, 1, 1, 12, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Resources: VIEW
    (26, 1, 1, 12, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Resources: USE
    (27, 1, 1, 12, 3, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Resources: EDIT

-- 감사 로그: VIEW + USE
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (28, 1, 1, 13, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Audit: VIEW
    (29, 1, 1, 13, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Audit: USE

-- ========================================
-- 3. 시퀀스 재설정 (Auto Increment 동기화)
-- ========================================
SELECT setval('com_resources_resource_id_seq', (SELECT MAX(resource_id) FROM com_resources));
SELECT setval('com_role_permissions_role_permission_id_seq', (SELECT MAX(role_permission_id) FROM com_role_permissions));

-- ========================================
-- 추가된 리소스 요약
-- ========================================
-- Admin 메뉴 (resource_id=8)
--   - 통합 모니터링 (resource_id=9)
--   - 사용자 관리 (resource_id=10)
--   - 역할 관리 (resource_id=11)
--   - 리소스 관리 (resource_id=12)
--   - 감사 로그 (resource_id=13)
-- ADMIN role에 모든 Admin 메뉴에 대한 VIEW, USE, EDIT 권한 부여
-- ========================================
