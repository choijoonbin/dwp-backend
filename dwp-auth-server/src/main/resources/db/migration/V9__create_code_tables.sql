-- ========================================
-- DWP Code Management Schema V9
-- 생성일: 2026-01-19
-- 목적: 공통 코드 관리 테이블 생성 (Code Master)
-- ========================================

-- ========================================
-- 1. sys_code_groups (코드 그룹 마스터)
-- ========================================
CREATE TABLE sys_code_groups (
    sys_code_group_id BIGSERIAL PRIMARY KEY,
    group_key VARCHAR(100) NOT NULL,
    group_name VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_code_groups_group_key UNIQUE (group_key)
);

COMMENT ON TABLE sys_code_groups IS '코드 그룹 마스터 테이블';
COMMENT ON COLUMN sys_code_groups.sys_code_group_id IS '코드 그룹 식별자 (PK)';
COMMENT ON COLUMN sys_code_groups.group_key IS '그룹 키 (예: RESOURCE_TYPE, SUBJECT_TYPE, ROLE_CODE, IDP_PROVIDER_TYPE)';
COMMENT ON COLUMN sys_code_groups.group_name IS '그룹명 (예: 리소스 유형, 멤버 대상 유형, 역할 코드, 인증 제공자 타입)';
COMMENT ON COLUMN sys_code_groups.description IS '그룹 설명';
COMMENT ON COLUMN sys_code_groups.is_active IS '활성화 여부';
COMMENT ON COLUMN sys_code_groups.created_at IS '생성일시';
COMMENT ON COLUMN sys_code_groups.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN sys_code_groups.updated_at IS '수정일시';
COMMENT ON COLUMN sys_code_groups.updated_by IS '수정자 user_id (논리적 참조)';

-- ========================================
-- 2. sys_codes (코드 마스터)
-- ========================================
CREATE TABLE sys_codes (
    sys_code_id BIGSERIAL PRIMARY KEY,
    group_key VARCHAR(100) NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    ext1 VARCHAR(500),
    ext2 VARCHAR(500),
    ext3 VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_codes_group_code UNIQUE (group_key, code)
);

COMMENT ON TABLE sys_codes IS '코드 마스터 테이블';
COMMENT ON COLUMN sys_codes.sys_code_id IS '코드 식별자 (PK)';
COMMENT ON COLUMN sys_codes.group_key IS '그룹 키 (논리적 참조: sys_code_groups.group_key)';
COMMENT ON COLUMN sys_codes.code IS '코드 값 (예: MENU, UI_COMPONENT, USER, DEPARTMENT, ADMIN, LOCAL)';
COMMENT ON COLUMN sys_codes.name IS '코드 표시명';
COMMENT ON COLUMN sys_codes.description IS '코드 설명';
COMMENT ON COLUMN sys_codes.sort_order IS '정렬 순서 (낮을수록 앞)';
COMMENT ON COLUMN sys_codes.is_active IS '활성화 여부';
COMMENT ON COLUMN sys_codes.ext1 IS '확장 필드 1';
COMMENT ON COLUMN sys_codes.ext2 IS '확장 필드 2';
COMMENT ON COLUMN sys_codes.ext3 IS '확장 필드 3';
COMMENT ON COLUMN sys_codes.created_at IS '생성일시';
COMMENT ON COLUMN sys_codes.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN sys_codes.updated_at IS '수정일시';
COMMENT ON COLUMN sys_codes.updated_by IS '수정자 user_id (논리적 참조)';

-- ========================================
-- 인덱스 생성
-- ========================================
CREATE INDEX idx_sys_codes_group_key ON sys_codes(group_key);
CREATE INDEX idx_sys_codes_code ON sys_codes(code);
CREATE INDEX idx_sys_codes_group_active ON sys_codes(group_key, is_active);
