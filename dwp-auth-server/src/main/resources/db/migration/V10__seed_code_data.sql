-- ========================================
-- DWP Code Management Seed Data V10
-- 생성일: 2026-01-19
-- 목적: 공통 코드 기본 데이터 삽입
-- ========================================

-- ========================================
-- 1. 코드 그룹 삽입
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('RESOURCE_TYPE', '리소스 유형', '시스템 리소스의 유형 (메뉴, UI 컴포넌트, 페이지, API 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('SUBJECT_TYPE', '멤버 대상 유형', '역할 멤버의 대상 유형 (사용자, 부서 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ROLE_CODE', '역할 코드', '시스템 역할 코드 (관리자, 일반 사용자 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('IDP_PROVIDER_TYPE', '인증 제공자 타입', '인증 제공자 유형 (로컬, SAML, OIDC 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PERMISSION_CODE', '권한 코드', '권한 코드 표준 (조회, 사용, 편집, 승인, 실행 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. 코드 삽입: RESOURCE_TYPE
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('RESOURCE_TYPE', 'MENU', '메뉴', '사이드바 메뉴 리소스', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_TYPE', 'UI_COMPONENT', 'UI 컴포넌트', '버튼, 폼 등 UI 컴포넌트 리소스', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_TYPE', 'PAGE', '페이지', '전체 페이지 리소스', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_TYPE', 'API', 'API', 'REST API 엔드포인트 리소스', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 3. 코드 삽입: SUBJECT_TYPE
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('SUBJECT_TYPE', 'USER', '사용자', '개별 사용자', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('SUBJECT_TYPE', 'DEPARTMENT', '부서', '부서 단위', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 4. 코드 삽입: ROLE_CODE
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('ROLE_CODE', 'ADMIN', '관리자', '시스템 관리자 역할', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ROLE_CODE', 'USER', '일반 사용자', '기본 사용자 역할', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 5. 코드 삽입: IDP_PROVIDER_TYPE
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('IDP_PROVIDER_TYPE', 'LOCAL', '로컬 인증', '로컬 DB 기반 인증', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('IDP_PROVIDER_TYPE', 'SAML', 'SAML', 'SAML 2.0 기반 SSO 인증', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('IDP_PROVIDER_TYPE', 'OIDC', 'OIDC', 'OpenID Connect 기반 SSO 인증', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 6. 코드 삽입: PERMISSION_CODE
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('PERMISSION_CODE', 'VIEW', '조회', '리소스 조회 권한', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PERMISSION_CODE', 'USE', '사용', '리소스 사용 권한', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PERMISSION_CODE', 'EDIT', '편집', '리소스 편집 권한', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PERMISSION_CODE', 'APPROVE', '승인', '리소스 승인 권한', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PERMISSION_CODE', 'EXECUTE', '실행', '리소스 실행 권한', 50, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 시퀀스 재설정
-- ========================================
SELECT setval('sys_code_groups_sys_code_group_id_seq', (SELECT MAX(sys_code_group_id) FROM sys_code_groups));
SELECT setval('sys_codes_sys_code_id_seq', (SELECT MAX(sys_code_id) FROM sys_codes));

-- ========================================
-- Seed 데이터 요약
-- ========================================
-- 그룹 (5개):
--   - RESOURCE_TYPE (4개 코드)
--   - SUBJECT_TYPE (2개 코드)
--   - ROLE_CODE (2개 코드)
--   - IDP_PROVIDER_TYPE (3개 코드)
--   - PERMISSION_CODE (5개 코드)
-- 총 코드 수: 16개
-- ========================================
