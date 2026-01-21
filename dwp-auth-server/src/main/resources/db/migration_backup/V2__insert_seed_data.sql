-- ========================================
-- DWP IAM Seed Data V2
-- 생성일: 2026-01-19
-- 목적: 로컬 개발용 기본 데이터
-- ========================================

-- ========================================
-- 1. 테넌트 (dev)
-- ========================================
INSERT INTO com_tenants (tenant_id, code, name, status, created_at, updated_at)
VALUES
    (1, 'dev', 'Development Tenant', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ========================================
-- 2. 인증 정책 (dev 테넌트용)
-- ========================================
INSERT INTO sys_auth_policies (auth_policy_id, tenant_id, default_login_method, allowed_providers_json, token_ttl_sec, created_at, updated_at)
VALUES
    (1, 1, 'LOCAL', '["local"]', 3600, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ========================================
-- 3. 테넌트 기본 정책 연결
-- ========================================
UPDATE com_tenants SET default_auth_policy_id = 1 WHERE tenant_id = 1;

-- ========================================
-- 4. 부서 (2개)
-- ========================================
INSERT INTO com_departments (department_id, tenant_id, parent_department_id, code, name, status, created_at, updated_at)
VALUES
    (1, 1, NULL, 'HQ', 'Headquarters', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 1, 1, 'DEV', 'Development', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ========================================
-- 5. 사용자 (admin)
-- ========================================
INSERT INTO com_users (user_id, tenant_id, display_name, email, primary_department_id, status, created_at, updated_at)
VALUES
    (1, 1, 'Admin User', 'admin@dev.local', 1, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ========================================
-- 6. 로그인 계정 (LOCAL - admin/admin1234!)
-- password: admin1234! (BCrypt hash)
-- ========================================
INSERT INTO com_user_accounts (user_account_id, tenant_id, user_id, provider_type, provider_id, principal, password_hash, status, created_at, updated_at)
VALUES
    (1, 1, 1, 'LOCAL', 'local', 'admin', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
-- Note: password_hash = BCrypt hash of 'admin1234!'

-- ========================================
-- 7. 권한 (5개)
-- ========================================
INSERT INTO com_permissions (permission_id, code, name, created_at, updated_at)
VALUES
    (1, 'VIEW', '조회', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 'USE', '사용', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 'EDIT', '편집', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, 'APPROVE', '승인', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (5, 'EXECUTE', '실행', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ========================================
-- 8. 역할 (admin)
-- ========================================
INSERT INTO com_roles (role_id, tenant_id, code, name, description, created_at, updated_at)
VALUES
    (1, 1, 'ADMIN', 'Administrator', 'Full system access', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ========================================
-- 9. 역할 할당 (admin user → ADMIN role)
-- ========================================
INSERT INTO com_role_members (role_member_id, tenant_id, role_id, subject_type, subject_id, created_at, updated_at)
VALUES
    (1, 1, 1, 'USER', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ========================================
-- 10. 리소스 (메뉴 3개)
-- ========================================
INSERT INTO com_resources (resource_id, tenant_id, type, key, name, parent_resource_id, metadata_json, enabled, created_at, updated_at)
VALUES
    -- 대시보드
    (1, 1, 'MENU', 'menu.dashboard', 'Dashboard', NULL, '{"route": "/dashboard", "icon": "dashboard"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 메일
    (2, 1, 'MENU', 'menu.mail', 'Mail', NULL, '{"route": "/mail", "icon": "email"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 1, 'MENU', 'menu.mail.inbox', 'Inbox', 2, '{"route": "/mail/inbox"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (4, 1, 'MENU', 'menu.mail.sent', 'Sent', 2, '{"route": "/mail/sent"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- AI Workspace
    (5, 1, 'MENU', 'menu.ai-workspace', 'AI Workspace', NULL, '{"route": "/ai-workspace", "icon": "smart_toy", "remote": "auraRemote"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- 메일 버튼 (UI Component)
    (6, 1, 'UI_COMPONENT', 'btn.mail.send', 'Send Button', 2, '{"component": "SendButton"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (7, 1, 'UI_COMPONENT', 'btn.mail.delete', 'Delete Button', 2, '{"component": "DeleteButton"}', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ========================================
-- 11. 역할-권한 매핑 (ADMIN role → All permissions)
-- ========================================
-- Dashboard: VIEW + USE
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (1, 1, 1, 1, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Dashboard: VIEW
    (2, 1, 1, 1, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Dashboard: USE

-- Mail: VIEW + USE + EDIT
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (3, 1, 1, 2, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Mail: VIEW
    (4, 1, 1, 2, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Mail: USE
    (5, 1, 1, 2, 3, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Mail: EDIT

-- Mail Inbox: VIEW + USE
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (6, 1, 1, 3, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Inbox: VIEW
    (7, 1, 1, 3, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Inbox: USE

-- Mail Sent: VIEW + USE
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (8, 1, 1, 4, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- Sent: VIEW
    (9, 1, 1, 4, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Sent: USE

-- AI Workspace: VIEW + USE + EXECUTE
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (10, 1, 1, 5, 1, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- AI Workspace: VIEW
    (11, 1, 1, 5, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),  -- AI Workspace: USE
    (12, 1, 1, 5, 5, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- AI Workspace: EXECUTE

-- Mail Send Button: USE
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (13, 1, 1, 6, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Send Button: USE

-- Mail Delete Button: USE
INSERT INTO com_role_permissions (role_permission_id, tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
VALUES
    (14, 1, 1, 7, 2, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);  -- Delete Button: USE

-- ========================================
-- 시퀀스 재설정 (Auto Increment 동기화)
-- ========================================
SELECT setval('com_tenants_tenant_id_seq', (SELECT MAX(tenant_id) FROM com_tenants));
SELECT setval('sys_auth_policies_auth_policy_id_seq', (SELECT MAX(auth_policy_id) FROM sys_auth_policies));
SELECT setval('com_departments_department_id_seq', (SELECT MAX(department_id) FROM com_departments));
SELECT setval('com_users_user_id_seq', (SELECT MAX(user_id) FROM com_users));
SELECT setval('com_user_accounts_user_account_id_seq', (SELECT MAX(user_account_id) FROM com_user_accounts));
SELECT setval('com_permissions_permission_id_seq', (SELECT MAX(permission_id) FROM com_permissions));
SELECT setval('com_roles_role_id_seq', (SELECT MAX(role_id) FROM com_roles));
SELECT setval('com_role_members_role_member_id_seq', (SELECT MAX(role_member_id) FROM com_role_members));
SELECT setval('com_resources_resource_id_seq', (SELECT MAX(resource_id) FROM com_resources));
SELECT setval('com_role_permissions_role_permission_id_seq', (SELECT MAX(role_permission_id) FROM com_role_permissions));

-- ========================================
-- Seed 데이터 요약
-- ========================================
-- Tenant: dev (tenant_id=1)
-- Auth Policy: LOCAL only, token TTL 3600s
-- Departments: HQ (1), Development (2)
-- User: admin@dev.local (user_id=1)
-- Account: admin/admin (LOCAL, BCrypt)
-- Role: ADMIN (role_id=1)
-- Permissions: VIEW, USE, EDIT, APPROVE, EXECUTE
-- Resources: Dashboard, Mail (Inbox/Sent), AI Workspace, Buttons
-- Role Permissions: ADMIN role has full access to all resources
-- ========================================
