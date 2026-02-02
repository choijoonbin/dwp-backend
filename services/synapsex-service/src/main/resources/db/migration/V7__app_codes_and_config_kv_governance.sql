-- ======================================================================
-- SynapseX 앱 전용 코드 테이블 + 거버넌스 config_kv 시드
-- 스키마: dwp_aura
-- 구조: Auth sys_code_groups / sys_codes 와 유사, 시스템 컬럼 포함
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ======================================================================
-- 1) app_code_groups (코드 그룹 마스터)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.app_code_groups (
    app_code_group_id BIGSERIAL PRIMARY KEY,
    group_key         VARCHAR(100) NOT NULL,
    group_name        VARCHAR(200),
    description       VARCHAR(500),
    is_active         BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        BIGINT,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        BIGINT,
    CONSTRAINT uk_app_code_groups_group_key UNIQUE (group_key)
);

CREATE INDEX IF NOT EXISTS ix_app_code_groups_active ON dwp_aura.app_code_groups(is_active);

COMMENT ON TABLE dwp_aura.app_code_groups IS 'SynapseX 앱 전용 코드 그룹 마스터';
COMMENT ON COLUMN dwp_aura.app_code_groups.group_key IS '그룹 키 (예: SECURITY_ACCESS_MODEL, PII_HANDLING)';
COMMENT ON COLUMN dwp_aura.app_code_groups.group_name IS '그룹 표시명';
COMMENT ON COLUMN dwp_aura.app_code_groups.description IS '그룹 설명';
COMMENT ON COLUMN dwp_aura.app_code_groups.is_active IS '활성화 여부';
COMMENT ON COLUMN dwp_aura.app_code_groups.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.app_code_groups.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN dwp_aura.app_code_groups.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.app_code_groups.updated_by IS '수정자 user_id (논리적 참조)';

-- ======================================================================
-- 2) app_codes (코드 마스터)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.app_codes (
    app_code_id   BIGSERIAL PRIMARY KEY,
    group_key     VARCHAR(100) NOT NULL,
    code          VARCHAR(100) NOT NULL,
    name          VARCHAR(200) NOT NULL,
    description   VARCHAR(500),
    sort_order    INTEGER NOT NULL DEFAULT 0,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    BIGINT,
    CONSTRAINT uk_app_codes_group_code UNIQUE (group_key, code)
);

CREATE INDEX IF NOT EXISTS ix_app_codes_group_key ON dwp_aura.app_codes(group_key);
CREATE INDEX IF NOT EXISTS ix_app_codes_group_active ON dwp_aura.app_codes(group_key, is_active);

COMMENT ON TABLE dwp_aura.app_codes IS 'SynapseX 앱 전용 코드 마스터 (UI 라벨/선택지)';
COMMENT ON COLUMN dwp_aura.app_codes.group_key IS '그룹 키 (논리적 참조: app_code_groups.group_key)';
COMMENT ON COLUMN dwp_aura.app_codes.code IS '코드 값 (대문자 스네이크, 예: RBAC, ENFORCED)';
COMMENT ON COLUMN dwp_aura.app_codes.name IS '기본 라벨 (UI 표시, i18n 분리 가능)';
COMMENT ON COLUMN dwp_aura.app_codes.sort_order IS '정렬 순서';
COMMENT ON COLUMN dwp_aura.app_codes.is_active IS '활성화 여부';
COMMENT ON COLUMN dwp_aura.app_codes.created_at IS '생성일시';
COMMENT ON COLUMN dwp_aura.app_codes.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN dwp_aura.app_codes.updated_at IS '수정일시';
COMMENT ON COLUMN dwp_aura.app_codes.updated_by IS '수정자 user_id (논리적 참조)';

-- ======================================================================
-- 3) 코드 그룹 시드 (4개)
-- ======================================================================
INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('SECURITY_ACCESS_MODEL', 'Security Access Model', '접근 제어 방식 (RBAC 등)', true, now(), now()),
    ('SECURITY_SOD_MODE', 'SoD Mode', 'Segregation of Duties 적용 단계', true, now(), now()),
    ('UX_SAVED_VIEWS_SCOPE', 'Saved Views Scope', '저장 뷰 범위 (개인/조직)', true, now(), now()),
    ('PII_HANDLING', 'PII Handling', 'PII 처리 방식 (마스킹/해시/암호화 등)', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = now();

-- ======================================================================
-- 4) 코드 시드 (그룹별)
-- ======================================================================
-- SECURITY_ACCESS_MODEL: RBAC
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES ('SECURITY_ACCESS_MODEL', 'RBAC', 'Role-based', '역할 기반 접근 제어', 10, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

-- SECURITY_SOD_MODE: PLANNED, BASELINE, ENFORCED
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('SECURITY_SOD_MODE', 'PLANNED', 'Planned', '계획 단계', 10, true, now(), now()),
    ('SECURITY_SOD_MODE', 'BASELINE', 'Baseline', '기준 적용', 20, true, now(), now()),
    ('SECURITY_SOD_MODE', 'ENFORCED', 'Enforced', '강제 적용', 30, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

-- UX_SAVED_VIEWS_SCOPE: PERSONAL, ORG
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('UX_SAVED_VIEWS_SCOPE', 'PERSONAL', 'Personal', '개인 범위', 10, true, now(), now()),
    ('UX_SAVED_VIEWS_SCOPE', 'ORG', 'Org-scoped', '조직 범위', 20, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

-- PII_HANDLING: MASK, HASH_ONLY, ENCRYPT, FORBID, ALLOW
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('PII_HANDLING', 'MASK', 'Mask', '마스킹', 10, true, now(), now()),
    ('PII_HANDLING', 'HASH_ONLY', 'Hash only', '해시만', 20, true, now(), now()),
    ('PII_HANDLING', 'ENCRYPT', 'Encrypt', '암호화', 30, true, now(), now()),
    ('PII_HANDLING', 'FORBID', 'Forbid', '접근 금지', 40, true, now(), now()),
    ('PII_HANDLING', 'ALLOW', 'Allow', '허용', 50, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

-- ======================================================================
-- 5) config_kv 시드 (4건) — 기본 프로파일 기준, key/value 현재값
-- ======================================================================
WITH p AS (
    SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND is_default = true LIMIT 1
)
INSERT INTO dwp_aura.config_kv (tenant_id, profile_id, config_key, config_value, created_at, updated_at)
SELECT 1, p.profile_id, 'SECURITY_ACCESS_MODEL', '"RBAC"'::jsonb, now(), now() FROM p
ON CONFLICT (tenant_id, profile_id, config_key) DO UPDATE SET config_value = EXCLUDED.config_value, updated_at = now();

WITH p AS (
    SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND is_default = true LIMIT 1
)
INSERT INTO dwp_aura.config_kv (tenant_id, profile_id, config_key, config_value, created_at, updated_at)
SELECT 1, p.profile_id, 'SECURITY_SOD_MODE', '"ENFORCED"'::jsonb, now(), now() FROM p
ON CONFLICT (tenant_id, profile_id, config_key) DO UPDATE SET config_value = EXCLUDED.config_value, updated_at = now();

WITH p AS (
    SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND is_default = true LIMIT 1
)
INSERT INTO dwp_aura.config_kv (tenant_id, profile_id, config_key, config_value, created_at, updated_at)
SELECT 1, p.profile_id, 'UX_SAVED_VIEWS_SCOPE', '"ORG"'::jsonb, now(), now() FROM p
ON CONFLICT (tenant_id, profile_id, config_key) DO UPDATE SET config_value = EXCLUDED.config_value, updated_at = now();

WITH p AS (
    SELECT profile_id FROM dwp_aura.config_profile WHERE tenant_id = 1 AND is_default = true LIMIT 1
)
INSERT INTO dwp_aura.config_kv (tenant_id, profile_id, config_key, config_value, created_at, updated_at)
SELECT 1, p.profile_id, 'PII_HANDLING', '"MASK"'::jsonb, now(), now() FROM p
ON CONFLICT (tenant_id, profile_id, config_key) DO UPDATE SET config_value = EXCLUDED.config_value, updated_at = now();
