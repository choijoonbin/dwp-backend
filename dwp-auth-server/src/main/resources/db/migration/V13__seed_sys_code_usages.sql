-- ========================================
-- DWP Code Usage Seed Data V13
-- 생성일: 2026-01-20
-- 목적: Admin 메뉴별 코드 사용 범위 기본 매핑 데이터 삽입
-- ========================================

-- ========================================
-- 추가 코드 그룹 및 코드 삽입 (필요한 경우)
-- ========================================
-- USER_STATUS (사용자 상태)
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('USER_STATUS', '사용자 상태', '사용자 계정 상태 (ACTIVE, INACTIVE, LOCKED 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('USER_STATUS', 'ACTIVE', '활성', '활성 사용자', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('USER_STATUS', 'INACTIVE', '비활성', '비활성 사용자', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('USER_STATUS', 'LOCKED', '잠금', '잠금된 사용자', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- EFFECT_TYPE (권한 효과)
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('EFFECT_TYPE', '권한 효과', '권한 효과 타입 (ALLOW, DENY)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('EFFECT_TYPE', 'ALLOW', '허용', '권한 허용', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('EFFECT_TYPE', 'DENY', '거부', '권한 거부', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- RESOURCE_STATUS (리소스 상태)
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('RESOURCE_STATUS', '리소스 상태', '리소스 활성화 상태 (ENABLED, DISABLED)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('RESOURCE_STATUS', 'ENABLED', '활성', '활성화된 리소스', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_STATUS', 'DISABLED', '비활성', '비활성화된 리소스', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 메뉴별 코드 사용 범위 매핑 (tenant_id=1, dev)
-- ========================================
-- menu.admin.users → SUBJECT_TYPE, USER_STATUS, IDP_PROVIDER_TYPE
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.admin.users', 'SUBJECT_TYPE', 'MENU', true, 10, '사용자 관리 화면에서 사용', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.users', 'USER_STATUS', 'MENU', true, 20, '사용자 상태 선택', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.users', 'IDP_PROVIDER_TYPE', 'MENU', true, 30, '인증 제공자 타입 선택', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = EXCLUDED.enabled,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

-- menu.admin.roles → ROLE_CODE, SUBJECT_TYPE, PERMISSION_CODE, EFFECT_TYPE
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.admin.roles', 'ROLE_CODE', 'MENU', true, 10, '역할 코드 선택', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.roles', 'SUBJECT_TYPE', 'MENU', true, 20, '멤버 대상 유형 선택', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.roles', 'PERMISSION_CODE', 'MENU', true, 30, '권한 코드 선택', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.roles', 'EFFECT_TYPE', 'MENU', true, 40, '권한 효과 선택', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = EXCLUDED.enabled,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

-- menu.admin.resources → RESOURCE_TYPE, RESOURCE_STATUS
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.admin.resources', 'RESOURCE_TYPE', 'MENU', true, 10, '리소스 타입 선택', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.resources', 'RESOURCE_STATUS', 'MENU', true, 20, '리소스 상태 선택', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = EXCLUDED.enabled,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

-- menu.admin.codes → (코드 관리 화면은 전체 그룹 조회 가능하므로 매핑 없음)
-- 또는 모든 그룹을 매핑할 수도 있음 (운영 정책에 따라)

-- ========================================
-- 시퀀스 재설정
-- ========================================
SELECT setval('sys_code_usages_sys_code_usage_id_seq', (SELECT MAX(sys_code_usage_id) FROM sys_code_usages));

-- ========================================
-- Seed 데이터 요약
-- ========================================
-- 추가 코드 그룹 (3개):
--   - USER_STATUS (3개 코드)
--   - EFFECT_TYPE (2개 코드)
--   - RESOURCE_STATUS (2개 코드)
-- 
-- 코드 사용 매핑:
--   - menu.admin.users: 3개 그룹
--   - menu.admin.roles: 4개 그룹
--   - menu.admin.resources: 2개 그룹
-- ========================================
