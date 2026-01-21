-- ========================================
-- DWP Auth Policy & Identity Provider Seed V15
-- 생성일: 2026-01-20
-- 목적: 테넌트별 로그인 정책 및 Identity Provider 기본 데이터 삽입
-- ========================================

-- ========================================
-- 1. 코드 그룹 및 코드 추가 (LOGIN_TYPE)
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('LOGIN_TYPE', '로그인 타입', '로그인 방식 타입 (LOCAL, SSO)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('LOGIN_TYPE', 'LOCAL', '로컬 로그인', '로컬 DB 기반 인증', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('LOGIN_TYPE', 'SSO', 'SSO 로그인', 'Single Sign-On 인증', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. sys_auth_policies 업데이트 (dev tenant)
-- ========================================
UPDATE sys_auth_policies
SET
    default_login_type = 'LOCAL',
    allowed_login_types = 'LOCAL',
    local_login_enabled = true,
    sso_login_enabled = false,
    sso_provider_key = NULL,
    require_mfa = false,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1;

-- ========================================
-- 3. sys_identity_providers Seed (disabled 상태로 예시 추가)
-- ========================================
INSERT INTO sys_identity_providers (
    tenant_id, provider_type, provider_id, provider_key, name, enabled,
    auth_url, token_url, metadata_url, jwks_url, client_id,
    config_json, created_at, updated_at
)
VALUES
    (
        1, 
        'OIDC', 
        'AZURE_AD', 
        'AZURE_AD',
        'Azure Active Directory',
        false,  -- enabled=false (비활성화 상태)
        'https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize',
        'https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token',
        'https://login.microsoftonline.com/{tenant}/v2.0/.well-known/openid-configuration',
        'https://login.microsoftonline.com/{tenant}/discovery/v2.0/keys',
        NULL,
        '{"issuer": "https://login.microsoftonline.com/{tenant}/v2.0", "scopes": ["openid", "profile", "email"]}',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    )
ON CONFLICT (tenant_id, provider_id) DO UPDATE SET
    provider_key = EXCLUDED.provider_key,
    name = EXCLUDED.name,
    auth_url = EXCLUDED.auth_url,
    token_url = EXCLUDED.token_url,
    metadata_url = EXCLUDED.metadata_url,
    jwks_url = EXCLUDED.jwks_url,
    updated_at = CURRENT_TIMESTAMP;
