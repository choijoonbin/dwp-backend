-- ========================================
-- DWP Auth Policy & Identity Provider Extension V14
-- 생성일: 2026-01-20
-- 목적: 테넌트별 로그인 정책 및 Identity Provider 스키마 확장
-- ========================================

-- ========================================
-- 1. sys_auth_policies 확장
-- ========================================
-- 기존 컬럼 유지하고 새로운 컬럼 추가

-- default_login_type 추가 (기존 default_login_method와 병행, 추후 마이그레이션)
ALTER TABLE sys_auth_policies
ADD COLUMN IF NOT EXISTS default_login_type VARCHAR(30) NOT NULL DEFAULT 'LOCAL';

COMMENT ON COLUMN sys_auth_policies.default_login_type IS '기본 로그인 타입 (LOCAL/SSO)';

-- allowed_login_types 추가 (CSV 형태, 기존 allowed_providers_json과 병행)
ALTER TABLE sys_auth_policies
ADD COLUMN IF NOT EXISTS allowed_login_types VARCHAR(100) NOT NULL DEFAULT 'LOCAL';

COMMENT ON COLUMN sys_auth_policies.allowed_login_types IS '허용된 로그인 타입 목록 (CSV: LOCAL,SSO)';

-- sso_provider_key 추가
ALTER TABLE sys_auth_policies
ADD COLUMN IF NOT EXISTS sso_provider_key VARCHAR(100);

COMMENT ON COLUMN sys_auth_policies.sso_provider_key IS '기본 SSO 제공자 키 (예: OKTA, AZURE_AD, SAML_SKT)';

-- local_login_enabled 추가
ALTER TABLE sys_auth_policies
ADD COLUMN IF NOT EXISTS local_login_enabled BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN sys_auth_policies.local_login_enabled IS '로컬 로그인 활성화 여부';

-- sso_login_enabled 추가
ALTER TABLE sys_auth_policies
ADD COLUMN IF NOT EXISTS sso_login_enabled BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN sys_auth_policies.sso_login_enabled IS 'SSO 로그인 활성화 여부';

-- require_mfa 추가
ALTER TABLE sys_auth_policies
ADD COLUMN IF NOT EXISTS require_mfa BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN sys_auth_policies.require_mfa IS 'MFA 필수 여부';

-- ========================================
-- 2. sys_identity_providers 확장
-- ========================================
-- provider_key 추가 (기존 provider_id와 병행)
ALTER TABLE sys_identity_providers
ADD COLUMN IF NOT EXISTS provider_key VARCHAR(100);

COMMENT ON COLUMN sys_identity_providers.provider_key IS '제공자 키 (예: AZURE_AD, OKTA, SAML_SKT)';

-- auth_url 추가
ALTER TABLE sys_identity_providers
ADD COLUMN IF NOT EXISTS auth_url VARCHAR(500);

COMMENT ON COLUMN sys_identity_providers.auth_url IS '인증 URL (OIDC: authorization_endpoint)';

-- token_url 추가
ALTER TABLE sys_identity_providers
ADD COLUMN IF NOT EXISTS token_url VARCHAR(500);

COMMENT ON COLUMN sys_identity_providers.token_url IS '토큰 URL (OIDC: token_endpoint)';

-- metadata_url 추가
ALTER TABLE sys_identity_providers
ADD COLUMN IF NOT EXISTS metadata_url VARCHAR(500);

COMMENT ON COLUMN sys_identity_providers.metadata_url IS '메타데이터 URL (OIDC: /.well-known/openid-configuration, SAML: metadata)';

-- jwks_url 추가
ALTER TABLE sys_identity_providers
ADD COLUMN IF NOT EXISTS jwks_url VARCHAR(500);

COMMENT ON COLUMN sys_identity_providers.jwks_url IS 'JWKS URL (OIDC: jwks_uri)';

-- client_id 추가
ALTER TABLE sys_identity_providers
ADD COLUMN IF NOT EXISTS client_id VARCHAR(255);

COMMENT ON COLUMN sys_identity_providers.client_id IS '클라이언트 ID (OIDC)';

-- ext1, ext2, ext3 추가
ALTER TABLE sys_identity_providers
ADD COLUMN IF NOT EXISTS ext1 VARCHAR(500);

COMMENT ON COLUMN sys_identity_providers.ext1 IS '확장 필드 1';

ALTER TABLE sys_identity_providers
ADD COLUMN IF NOT EXISTS ext2 VARCHAR(500);

COMMENT ON COLUMN sys_identity_providers.ext2 IS '확장 필드 2';

ALTER TABLE sys_identity_providers
ADD COLUMN IF NOT EXISTS ext3 VARCHAR(500);

COMMENT ON COLUMN sys_identity_providers.ext3 IS '확장 필드 3';

-- ========================================
-- 3. 기존 데이터 마이그레이션
-- ========================================
-- sys_auth_policies 기존 데이터 업데이트
UPDATE sys_auth_policies
SET 
    default_login_type = CASE 
        WHEN default_login_method = 'LOCAL' THEN 'LOCAL'
        WHEN default_login_method = 'SSO_ONLY' THEN 'SSO'
        WHEN default_login_method = 'LOCAL_OR_SSO' THEN 'LOCAL'
        ELSE 'LOCAL'
    END,
    allowed_login_types = CASE
        WHEN default_login_method = 'LOCAL' THEN 'LOCAL'
        WHEN default_login_method = 'SSO_ONLY' THEN 'SSO'
        WHEN default_login_method = 'LOCAL_OR_SSO' THEN 'LOCAL,SSO'
        ELSE 'LOCAL'
    END,
    local_login_enabled = CASE
        WHEN default_login_method IN ('LOCAL', 'LOCAL_OR_SSO') THEN true
        ELSE false
    END,
    sso_login_enabled = CASE
        WHEN default_login_method IN ('SSO_ONLY', 'LOCAL_OR_SSO') THEN true
        ELSE false
    END
WHERE default_login_type = 'LOCAL' AND allowed_login_types = 'LOCAL';

-- sys_identity_providers provider_key 업데이트 (provider_id와 동일하게 설정)
UPDATE sys_identity_providers
SET provider_key = provider_id
WHERE provider_key IS NULL;
