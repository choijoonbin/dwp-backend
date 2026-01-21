-- ========================================
-- DWP IAM Schema V1
-- 생성일: 2026-01-19
-- 목적: 멀티테넌시 IAM + 권한 관리 + 운영로그
-- ========================================

-- ========================================
-- 1. com_tenants (회사/테넌트 마스터)
-- ========================================
CREATE TABLE com_tenants (
    tenant_id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    default_auth_policy_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_tenants_code UNIQUE (code)
);

COMMENT ON TABLE com_tenants IS '회사/테넌트 마스터';
COMMENT ON COLUMN com_tenants.tenant_id IS '테넌트 식별자 (PK)';
COMMENT ON COLUMN com_tenants.code IS '테넌트 코드 (서브도메인 key, unique)';
COMMENT ON COLUMN com_tenants.name IS '테넌트명';
COMMENT ON COLUMN com_tenants.status IS '상태 (ACTIVE/SUSPENDED)';
COMMENT ON COLUMN com_tenants.default_auth_policy_id IS '기본 인증 정책 ID (논리적 참조: sys_auth_policies.auth_policy_id)';
COMMENT ON COLUMN com_tenants.created_at IS '생성일시';
COMMENT ON COLUMN com_tenants.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN com_tenants.updated_at IS '수정일시';
COMMENT ON COLUMN com_tenants.updated_by IS '수정자 user_id (논리적 참조)';

-- ========================================
-- 2. com_departments (부서/조직도)
-- ========================================
CREATE TABLE com_departments (
    department_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    parent_department_id BIGINT,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_departments_tenant_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_com_departments_tenant_id ON com_departments(tenant_id);
CREATE INDEX idx_com_departments_parent_id ON com_departments(parent_department_id);

COMMENT ON TABLE com_departments IS '부서/조직도';
COMMENT ON COLUMN com_departments.department_id IS '부서 식별자 (PK)';
COMMENT ON COLUMN com_departments.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN com_departments.parent_department_id IS '상위 부서 ID (논리적 참조: com_departments.department_id)';
COMMENT ON COLUMN com_departments.code IS '부서 코드 (테넌트 범위 유니크)';
COMMENT ON COLUMN com_departments.name IS '부서명';
COMMENT ON COLUMN com_departments.status IS '상태 (ACTIVE/INACTIVE)';
COMMENT ON COLUMN com_departments.created_at IS '생성일시';
COMMENT ON COLUMN com_departments.created_by IS '생성자 user_id';
COMMENT ON COLUMN com_departments.updated_at IS '수정일시';
COMMENT ON COLUMN com_departments.updated_by IS '수정자 user_id';

-- ========================================
-- 3. com_users (사용자 프로필)
-- ========================================
CREATE TABLE com_users (
    user_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    email VARCHAR(255),
    primary_department_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_users_tenant_email UNIQUE (tenant_id, email)
);

CREATE INDEX idx_com_users_tenant_id ON com_users(tenant_id);
CREATE INDEX idx_com_users_email ON com_users(email);
CREATE INDEX idx_com_users_department_id ON com_users(primary_department_id);

COMMENT ON TABLE com_users IS '사용자 프로필 (사람)';
COMMENT ON COLUMN com_users.user_id IS '사용자 식별자 (PK)';
COMMENT ON COLUMN com_users.tenant_id IS '테넌트 식별자 (논리적 참조)';
COMMENT ON COLUMN com_users.display_name IS '표시명';
COMMENT ON COLUMN com_users.email IS '이메일 (테넌트 범위 유니크 옵션)';
COMMENT ON COLUMN com_users.primary_department_id IS '대표 부서 ID (논리적 참조)';
COMMENT ON COLUMN com_users.status IS '상태 (ACTIVE/LOCKED/INVITED/DEPROVISIONED)';
COMMENT ON COLUMN com_users.created_at IS '생성일시';
COMMENT ON COLUMN com_users.created_by IS '생성자 user_id';
COMMENT ON COLUMN com_users.updated_at IS '수정일시';
COMMENT ON COLUMN com_users.updated_by IS '수정자 user_id';

-- ========================================
-- 4. com_user_accounts (로그인 계정)
-- ========================================
CREATE TABLE com_user_accounts (
    user_account_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    provider_type VARCHAR(20) NOT NULL,
    provider_id VARCHAR(100) NOT NULL,
    principal VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    metadata_json TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_user_accounts_unique UNIQUE (tenant_id, provider_type, provider_id, principal)
);

CREATE INDEX idx_com_user_accounts_tenant_id ON com_user_accounts(tenant_id);
CREATE INDEX idx_com_user_accounts_user_id ON com_user_accounts(user_id);
CREATE INDEX idx_com_user_accounts_principal ON com_user_accounts(principal);

COMMENT ON TABLE com_user_accounts IS '로그인 계정 (LOCAL/SSO 확장)';
COMMENT ON COLUMN com_user_accounts.user_account_id IS '로그인 계정 식별자 (PK)';
COMMENT ON COLUMN com_user_accounts.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN com_user_accounts.user_id IS '사용자 ID (논리적 참조: com_users.user_id)';
COMMENT ON COLUMN com_user_accounts.provider_type IS '인증 유형 (LOCAL/OIDC/SAML/LDAP)';
COMMENT ON COLUMN com_user_accounts.provider_id IS '제공자 식별자 (local, azuread-oidc, okta-saml 등)';
COMMENT ON COLUMN com_user_accounts.principal IS '로그인 주체 (username/email/sub/nameId)';
COMMENT ON COLUMN com_user_accounts.password_hash IS '비밀번호 해시 (LOCAL 전용, BCrypt)';
COMMENT ON COLUMN com_user_accounts.metadata_json IS '외부 인증 메타 (JSON)';
COMMENT ON COLUMN com_user_accounts.status IS '상태 (ACTIVE/LOCKED)';
COMMENT ON COLUMN com_user_accounts.created_at IS '생성일시';
COMMENT ON COLUMN com_user_accounts.created_by IS '생성자 user_id';
COMMENT ON COLUMN com_user_accounts.updated_at IS '수정일시';
COMMENT ON COLUMN com_user_accounts.updated_by IS '수정자 user_id';

-- ========================================
-- 5. sys_auth_policies (테넌트별 로그인 정책)
-- ========================================
CREATE TABLE sys_auth_policies (
    auth_policy_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    default_login_method VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    allowed_providers_json TEXT,
    token_ttl_sec INTEGER NOT NULL DEFAULT 3600,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE INDEX idx_sys_auth_policies_tenant_id ON sys_auth_policies(tenant_id);

COMMENT ON TABLE sys_auth_policies IS '테넌트별 로그인 정책';
COMMENT ON COLUMN sys_auth_policies.auth_policy_id IS '인증 정책 식별자 (PK)';
COMMENT ON COLUMN sys_auth_policies.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_auth_policies.default_login_method IS '기본 로그인 방식 (LOCAL/SSO_ONLY/LOCAL_OR_SSO)';
COMMENT ON COLUMN sys_auth_policies.allowed_providers_json IS '허용 IdP 목록 (JSON)';
COMMENT ON COLUMN sys_auth_policies.token_ttl_sec IS '토큰 TTL (초)';
COMMENT ON COLUMN sys_auth_policies.created_at IS '생성일시';
COMMENT ON COLUMN sys_auth_policies.created_by IS '생성자 user_id';
COMMENT ON COLUMN sys_auth_policies.updated_at IS '수정일시';
COMMENT ON COLUMN sys_auth_policies.updated_by IS '수정자 user_id';

-- ========================================
-- 6. sys_identity_providers (SSO IdP 설정)
-- ========================================
CREATE TABLE sys_identity_providers (
    identity_provider_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    provider_type VARCHAR(20) NOT NULL,
    provider_id VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    config_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_identity_providers_tenant_provider UNIQUE (tenant_id, provider_id)
);

CREATE INDEX idx_sys_identity_providers_tenant_id ON sys_identity_providers(tenant_id);

COMMENT ON TABLE sys_identity_providers IS 'SSO IdP 설정 (SAML/OIDC)';
COMMENT ON COLUMN sys_identity_providers.identity_provider_id IS 'IdP 설정 식별자 (PK)';
COMMENT ON COLUMN sys_identity_providers.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_identity_providers.provider_type IS '제공자 타입 (SAML/OIDC/LDAP)';
COMMENT ON COLUMN sys_identity_providers.provider_id IS '제공자 코드 (테넌트 범위 유니크)';
COMMENT ON COLUMN sys_identity_providers.name IS '제공자명';
COMMENT ON COLUMN sys_identity_providers.enabled IS '활성여부 (true/false)';
COMMENT ON COLUMN sys_identity_providers.config_json IS '제공자 설정 (JSON: issuer, metadata, client 등)';
COMMENT ON COLUMN sys_identity_providers.created_at IS '생성일시';
COMMENT ON COLUMN sys_identity_providers.created_by IS '생성자 user_id';
COMMENT ON COLUMN sys_identity_providers.updated_at IS '수정일시';
COMMENT ON COLUMN sys_identity_providers.updated_by IS '수정자 user_id';

-- ========================================
-- 7. com_roles (권한그룹/역할)
-- ========================================
CREATE TABLE com_roles (
    role_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_roles_tenant_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_com_roles_tenant_id ON com_roles(tenant_id);

COMMENT ON TABLE com_roles IS '권한그룹/역할';
COMMENT ON COLUMN com_roles.role_id IS '역할 식별자 (PK)';
COMMENT ON COLUMN com_roles.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN com_roles.code IS '역할 코드 (테넌트 범위 유니크)';
COMMENT ON COLUMN com_roles.name IS '역할명 (Admin/User 등)';
COMMENT ON COLUMN com_roles.description IS '설명';
COMMENT ON COLUMN com_roles.created_at IS '생성일시';
COMMENT ON COLUMN com_roles.created_by IS '생성자 user_id';
COMMENT ON COLUMN com_roles.updated_at IS '수정일시';
COMMENT ON COLUMN com_roles.updated_by IS '수정자 user_id';

-- ========================================
-- 8. com_role_members (역할 할당)
-- ========================================
CREATE TABLE com_role_members (
    role_member_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    subject_type VARCHAR(20) NOT NULL,
    subject_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_role_members_unique UNIQUE (tenant_id, role_id, subject_type, subject_id)
);

CREATE INDEX idx_com_role_members_tenant_id ON com_role_members(tenant_id);
CREATE INDEX idx_com_role_members_role_id ON com_role_members(role_id);
CREATE INDEX idx_com_role_members_subject ON com_role_members(subject_type, subject_id);

COMMENT ON TABLE com_role_members IS '역할 할당 (사용자/부서)';
COMMENT ON COLUMN com_role_members.role_member_id IS '역할 할당 식별자 (PK)';
COMMENT ON COLUMN com_role_members.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN com_role_members.role_id IS '역할 ID (논리적 참조: com_roles.role_id)';
COMMENT ON COLUMN com_role_members.subject_type IS '대상 타입 (USER/DEPARTMENT)';
COMMENT ON COLUMN com_role_members.subject_id IS '대상 ID (user_id 또는 department_id)';
COMMENT ON COLUMN com_role_members.created_at IS '생성일시';
COMMENT ON COLUMN com_role_members.created_by IS '생성자 user_id';
COMMENT ON COLUMN com_role_members.updated_at IS '수정일시';
COMMENT ON COLUMN com_role_members.updated_by IS '수정자 user_id';

-- ========================================
-- 9. com_resources (리소스: 메뉴/버튼/섹션/API)
-- ========================================
CREATE TABLE com_resources (
    resource_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT,
    type VARCHAR(20) NOT NULL,
    key VARCHAR(255) NOT NULL,
    name VARCHAR(200) NOT NULL,
    parent_resource_id BIGINT,
    metadata_json TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_resources_tenant_type_key UNIQUE (tenant_id, type, key)
);

CREATE INDEX idx_com_resources_tenant_id ON com_resources(tenant_id);
CREATE INDEX idx_com_resources_type ON com_resources(type);
CREATE INDEX idx_com_resources_parent_id ON com_resources(parent_resource_id);

COMMENT ON TABLE com_resources IS '리소스 (메뉴/버튼/섹션/API)';
COMMENT ON COLUMN com_resources.resource_id IS '리소스 식별자 (PK)';
COMMENT ON COLUMN com_resources.tenant_id IS '테넌트 식별자 (null이면 global)';
COMMENT ON COLUMN com_resources.type IS '타입 (MENU/UI_COMPONENT/PAGE_SECTION/API)';
COMMENT ON COLUMN com_resources.key IS '리소스 키 (menu.mail.inbox, btn.mail.send 등)';
COMMENT ON COLUMN com_resources.name IS '리소스명';
COMMENT ON COLUMN com_resources.parent_resource_id IS '상위 리소스 ID (논리적 참조)';
COMMENT ON COLUMN com_resources.metadata_json IS 'route/icon/remote/component (JSON)';
COMMENT ON COLUMN com_resources.enabled IS '사용여부 (true/false)';
COMMENT ON COLUMN com_resources.created_at IS '생성일시';
COMMENT ON COLUMN com_resources.created_by IS '생성자 user_id';
COMMENT ON COLUMN com_resources.updated_at IS '수정일시';
COMMENT ON COLUMN com_resources.updated_by IS '수정자 user_id';

-- ========================================
-- 10. com_permissions (권한 행위)
-- ========================================
CREATE TABLE com_permissions (
    permission_id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_permissions_code UNIQUE (code)
);

COMMENT ON TABLE com_permissions IS '권한 행위';
COMMENT ON COLUMN com_permissions.permission_id IS '권한 식별자 (PK)';
COMMENT ON COLUMN com_permissions.code IS '권한 코드 (VIEW/USE/EDIT/APPROVE/EXECUTE)';
COMMENT ON COLUMN com_permissions.name IS '권한명';
COMMENT ON COLUMN com_permissions.created_at IS '생성일시';
COMMENT ON COLUMN com_permissions.created_by IS '생성자 user_id';
COMMENT ON COLUMN com_permissions.updated_at IS '수정일시';
COMMENT ON COLUMN com_permissions.updated_by IS '수정자 user_id';

-- ========================================
-- 11. com_role_permissions (역할-리소스-권한 매핑)
-- ========================================
CREATE TABLE com_role_permissions (
    role_permission_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    effect VARCHAR(10) NOT NULL DEFAULT 'ALLOW',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_role_permissions_unique UNIQUE (tenant_id, role_id, resource_id, permission_id)
);

CREATE INDEX idx_com_role_permissions_tenant_id ON com_role_permissions(tenant_id);
CREATE INDEX idx_com_role_permissions_role_id ON com_role_permissions(role_id);
CREATE INDEX idx_com_role_permissions_resource_id ON com_role_permissions(resource_id);

COMMENT ON TABLE com_role_permissions IS '역할-리소스-권한 매핑';
COMMENT ON COLUMN com_role_permissions.role_permission_id IS '매핑 식별자 (PK)';
COMMENT ON COLUMN com_role_permissions.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN com_role_permissions.role_id IS '역할 ID (논리적 참조)';
COMMENT ON COLUMN com_role_permissions.resource_id IS '리소스 ID (논리적 참조)';
COMMENT ON COLUMN com_role_permissions.permission_id IS '권한 ID (논리적 참조)';
COMMENT ON COLUMN com_role_permissions.effect IS '효과 (ALLOW/DENY)';
COMMENT ON COLUMN com_role_permissions.created_at IS '생성일시';
COMMENT ON COLUMN com_role_permissions.created_by IS '생성자 user_id';
COMMENT ON COLUMN com_role_permissions.updated_at IS '수정일시';
COMMENT ON COLUMN com_role_permissions.updated_by IS '수정자 user_id';

-- ========================================
-- 12. com_audit_logs (감사 로그)
-- ========================================
CREATE TABLE com_audit_logs (
    audit_log_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100),
    resource_id BIGINT,
    metadata_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE INDEX idx_com_audit_logs_tenant_id ON com_audit_logs(tenant_id);
CREATE INDEX idx_com_audit_logs_actor_user_id ON com_audit_logs(actor_user_id);
CREATE INDEX idx_com_audit_logs_action ON com_audit_logs(action);
CREATE INDEX idx_com_audit_logs_created_at ON com_audit_logs(created_at);

COMMENT ON TABLE com_audit_logs IS '감사 로그 (관리/변경 추적)';
COMMENT ON COLUMN com_audit_logs.audit_log_id IS '감사 로그 식별자 (PK)';
COMMENT ON COLUMN com_audit_logs.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN com_audit_logs.actor_user_id IS '행위 사용자 ID (논리적 참조, nullable)';
COMMENT ON COLUMN com_audit_logs.action IS '액션 (LOGIN_SUCCESS, ROLE_GRANT, HITL_APPROVE 등)';
COMMENT ON COLUMN com_audit_logs.resource_type IS '대상 타입';
COMMENT ON COLUMN com_audit_logs.resource_id IS '대상 ID';
COMMENT ON COLUMN com_audit_logs.metadata_json IS '추가정보 (JSON)';
COMMENT ON COLUMN com_audit_logs.created_at IS '생성일시';
COMMENT ON COLUMN com_audit_logs.created_by IS '생성자 user_id';
COMMENT ON COLUMN com_audit_logs.updated_at IS '수정일시';
COMMENT ON COLUMN com_audit_logs.updated_by IS '수정자 user_id';

-- ========================================
-- 13. sys_user_sessions (세션/강제 로그아웃)
-- ========================================
CREATE TABLE sys_user_sessions (
    user_session_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    CONSTRAINT uk_sys_user_sessions_session_id UNIQUE (session_id)
);

CREATE INDEX idx_sys_user_sessions_tenant_id ON sys_user_sessions(tenant_id);
CREATE INDEX idx_sys_user_sessions_user_id ON sys_user_sessions(user_id);
CREATE INDEX idx_sys_user_sessions_expires_at ON sys_user_sessions(expires_at);

COMMENT ON TABLE sys_user_sessions IS '세션/강제 로그아웃';
COMMENT ON COLUMN sys_user_sessions.user_session_id IS '세션 식별자 (PK)';
COMMENT ON COLUMN sys_user_sessions.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_user_sessions.user_id IS '사용자 ID';
COMMENT ON COLUMN sys_user_sessions.session_id IS '세션 키';
COMMENT ON COLUMN sys_user_sessions.created_at IS '생성일시';
COMMENT ON COLUMN sys_user_sessions.created_by IS '생성자 user_id';
COMMENT ON COLUMN sys_user_sessions.updated_at IS '수정일시';
COMMENT ON COLUMN sys_user_sessions.updated_by IS '수정자 user_id';
COMMENT ON COLUMN sys_user_sessions.expires_at IS '만료일시';
COMMENT ON COLUMN sys_user_sessions.revoked_at IS '폐기일시';

-- ========================================
-- 14. sys_login_histories (로그인 이력)
-- ========================================
CREATE TABLE sys_login_histories (
    login_history_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT,
    provider_type VARCHAR(20) NOT NULL,
    provider_id VARCHAR(100) NOT NULL,
    principal VARCHAR(255) NOT NULL,
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(255),
    ip_address VARCHAR(50),
    user_agent TEXT,
    trace_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE INDEX idx_sys_login_histories_tenant_id ON sys_login_histories(tenant_id);
CREATE INDEX idx_sys_login_histories_user_id ON sys_login_histories(user_id);
CREATE INDEX idx_sys_login_histories_success ON sys_login_histories(success);
CREATE INDEX idx_sys_login_histories_created_at ON sys_login_histories(created_at);

COMMENT ON TABLE sys_login_histories IS '로그인 이력 (성공/실패)';
COMMENT ON COLUMN sys_login_histories.login_history_id IS '로그인 이력 식별자 (PK)';
COMMENT ON COLUMN sys_login_histories.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_login_histories.user_id IS '사용자 ID (성공 시), 실패면 nullable';
COMMENT ON COLUMN sys_login_histories.provider_type IS '로그인 방식 (LOCAL/OIDC/SAML)';
COMMENT ON COLUMN sys_login_histories.provider_id IS '제공자 (local/azuread/okta 등)';
COMMENT ON COLUMN sys_login_histories.principal IS '입력된 계정 (username/email/sub)';
COMMENT ON COLUMN sys_login_histories.success IS '성공 여부 (true/false)';
COMMENT ON COLUMN sys_login_histories.failure_reason IS '실패 사유 (INVALID_PASSWORD, USER_NOT_FOUND, LOCKED, TENANT_MISMATCH)';
COMMENT ON COLUMN sys_login_histories.ip_address IS '접속 IP';
COMMENT ON COLUMN sys_login_histories.user_agent IS 'User-Agent';
COMMENT ON COLUMN sys_login_histories.trace_id IS '추적 ID (게이트웨이/로그 연계)';
COMMENT ON COLUMN sys_login_histories.created_at IS '생성일시';
COMMENT ON COLUMN sys_login_histories.created_by IS '생성자 user_id';
COMMENT ON COLUMN sys_login_histories.updated_at IS '수정일시';
COMMENT ON COLUMN sys_login_histories.updated_by IS '수정자 user_id';

-- ========================================
-- 15. sys_api_call_histories (API 호출 이력)
-- ========================================
CREATE TABLE sys_api_call_histories (
    api_call_history_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT,
    agent_id VARCHAR(100),
    source VARCHAR(20),
    method VARCHAR(10) NOT NULL,
    path VARCHAR(500) NOT NULL,
    query_string TEXT,
    status_code INTEGER NOT NULL,
    latency_ms INTEGER,
    request_size_bytes BIGINT,
    response_size_bytes BIGINT,
    ip_address VARCHAR(50),
    user_agent TEXT,
    trace_id VARCHAR(100),
    error_code VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE INDEX idx_sys_api_call_histories_tenant_id ON sys_api_call_histories(tenant_id);
CREATE INDEX idx_sys_api_call_histories_user_id ON sys_api_call_histories(user_id);
CREATE INDEX idx_sys_api_call_histories_created_at ON sys_api_call_histories(created_at);

COMMENT ON TABLE sys_api_call_histories IS 'API 호출 이력 (Gateway/서비스 추적)';
COMMENT ON COLUMN sys_api_call_histories.api_call_history_id IS 'API 호출 이력 식별자 (PK)';
COMMENT ON COLUMN sys_api_call_histories.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_api_call_histories.user_id IS '사용자 ID (nullable)';
COMMENT ON COLUMN sys_api_call_histories.agent_id IS '에이전트 ID (nullable)';
COMMENT ON COLUMN sys_api_call_histories.source IS '호출 출처 (FRONTEND/AURA/INTERNAL/BATCH)';
COMMENT ON COLUMN sys_api_call_histories.method IS 'HTTP Method (GET/POST/PUT/DELETE)';
COMMENT ON COLUMN sys_api_call_histories.path IS '요청 경로 (/api/...)';
COMMENT ON COLUMN sys_api_call_histories.query_string IS '쿼리스트링 (optional)';
COMMENT ON COLUMN sys_api_call_histories.status_code IS '응답 코드 (200/401/500 등)';
COMMENT ON COLUMN sys_api_call_histories.latency_ms IS '처리 시간 (ms)';
COMMENT ON COLUMN sys_api_call_histories.request_size_bytes IS '요청 크기 (optional)';
COMMENT ON COLUMN sys_api_call_histories.response_size_bytes IS '응답 크기 (optional)';
COMMENT ON COLUMN sys_api_call_histories.ip_address IS '접속 IP';
COMMENT ON COLUMN sys_api_call_histories.user_agent IS 'User-Agent';
COMMENT ON COLUMN sys_api_call_histories.trace_id IS '추적 ID';
COMMENT ON COLUMN sys_api_call_histories.error_code IS '표준 에러 코드 (optional)';
COMMENT ON COLUMN sys_api_call_histories.created_at IS '생성일시';
COMMENT ON COLUMN sys_api_call_histories.created_by IS '생성자 user_id';
COMMENT ON COLUMN sys_api_call_histories.updated_at IS '수정일시';
COMMENT ON COLUMN sys_api_call_histories.updated_by IS '수정자 user_id';

-- ========================================
-- 16. sys_page_view_events (PV/UV Raw 이벤트)
-- ========================================
CREATE TABLE sys_page_view_events (
    page_view_event_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT,
    session_id VARCHAR(255),
    page_key VARCHAR(255) NOT NULL,
    referrer VARCHAR(500),
    duration_ms INTEGER,
    ip_address VARCHAR(50),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE INDEX idx_sys_page_view_events_tenant_id ON sys_page_view_events(tenant_id);
CREATE INDEX idx_sys_page_view_events_user_id ON sys_page_view_events(user_id);
CREATE INDEX idx_sys_page_view_events_created_at ON sys_page_view_events(created_at);

COMMENT ON TABLE sys_page_view_events IS 'PV/UV Raw 이벤트 (대용량)';
COMMENT ON COLUMN sys_page_view_events.page_view_event_id IS '페이지뷰 이벤트 식별자 (PK)';
COMMENT ON COLUMN sys_page_view_events.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_page_view_events.user_id IS '사용자 ID (nullable)';
COMMENT ON COLUMN sys_page_view_events.session_id IS '세션 ID (optional)';
COMMENT ON COLUMN sys_page_view_events.page_key IS '화면 키 (/ai-workspace, menu.mail.inbox 등)';
COMMENT ON COLUMN sys_page_view_events.referrer IS '이전 페이지 (optional)';
COMMENT ON COLUMN sys_page_view_events.duration_ms IS '체류시간 (optional)';
COMMENT ON COLUMN sys_page_view_events.ip_address IS '접속 IP';
COMMENT ON COLUMN sys_page_view_events.user_agent IS 'User-Agent';
COMMENT ON COLUMN sys_page_view_events.created_at IS '생성일시';
COMMENT ON COLUMN sys_page_view_events.created_by IS '생성자 user_id';
COMMENT ON COLUMN sys_page_view_events.updated_at IS '수정일시';
COMMENT ON COLUMN sys_page_view_events.updated_by IS '수정자 user_id';

-- ========================================
-- 17. sys_page_view_daily_stats (PV/UV 집계)
-- ========================================
CREATE TABLE sys_page_view_daily_stats (
    page_view_daily_stat_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    page_key VARCHAR(255) NOT NULL,
    pv_count BIGINT NOT NULL DEFAULT 0,
    uv_count BIGINT NOT NULL DEFAULT 0,
    unique_session_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_page_view_daily_stats_unique UNIQUE (tenant_id, stat_date, page_key)
);

CREATE INDEX idx_sys_page_view_daily_stats_tenant_id ON sys_page_view_daily_stats(tenant_id);
CREATE INDEX idx_sys_page_view_daily_stats_stat_date ON sys_page_view_daily_stats(stat_date);

COMMENT ON TABLE sys_page_view_daily_stats IS 'PV/UV 집계 (일 단위)';
COMMENT ON COLUMN sys_page_view_daily_stats.page_view_daily_stat_id IS '일별 집계 식별자 (PK)';
COMMENT ON COLUMN sys_page_view_daily_stats.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_page_view_daily_stats.stat_date IS '집계 일자 (YYYY-MM-DD)';
COMMENT ON COLUMN sys_page_view_daily_stats.page_key IS '화면 키';
COMMENT ON COLUMN sys_page_view_daily_stats.pv_count IS 'PV 수';
COMMENT ON COLUMN sys_page_view_daily_stats.uv_count IS 'UV 수 (유니크 사용자 수)';
COMMENT ON COLUMN sys_page_view_daily_stats.unique_session_count IS '유니크 세션 수 (optional)';
COMMENT ON COLUMN sys_page_view_daily_stats.created_at IS '생성일시';
COMMENT ON COLUMN sys_page_view_daily_stats.created_by IS '생성자 user_id';
COMMENT ON COLUMN sys_page_view_daily_stats.updated_at IS '수정일시';
COMMENT ON COLUMN sys_page_view_daily_stats.updated_by IS '수정자 user_id';
