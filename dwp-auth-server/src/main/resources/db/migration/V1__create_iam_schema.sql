-- ========================================
-- DWP IAM Complete Schema V1 (통합)
-- 생성일: 2026-01-21
-- 목적: 모든 마이그레이션을 하나로 통합 (V1~V20)
-- 
-- 통합 내용:
-- - V1: 기본 IAM 스키마 생성 (17개 테이블)
-- - V2: Seed 데이터 삽입
-- - V3~V20: 기능 추가 및 수정 (모니터링, 메뉴, 코드, 이벤트 로그 등)
-- 
-- 주의: 개발 환경에서만 사용. 프로덕션 배포 전에는 팀과 협의 필요.
-- 백업 위치: db/migration/backup/
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

-- ========================================
-- V1 내용 종료
-- ========================================

-- ========================================
-- V2 내용 시작
-- ========================================
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

-- ========================================
-- V2 내용 종료
-- ========================================

-- ========================================
-- V3 내용 시작
-- ========================================
-- ========================================
-- DWP Monitoring Schema 확장 V3
-- 생성일: 2026-01-19
-- 목적: PV/UV 이외의 이벤트(클릭 등) 수집을 위한 컬럼 확장
-- ========================================

ALTER TABLE sys_page_view_events ADD COLUMN event_type VARCHAR(50);
ALTER TABLE sys_page_view_events ADD COLUMN event_name VARCHAR(100);
ALTER TABLE sys_page_view_events ADD COLUMN target_key VARCHAR(255);
ALTER TABLE sys_page_view_events ADD COLUMN metadata_json TEXT;

COMMENT ON COLUMN sys_page_view_events.event_type IS '이벤트 타입 (PAGE_VIEW, BUTTON_CLICK 등)';
COMMENT ON COLUMN sys_page_view_events.event_name IS '이벤트명 (화면명 또는 기능명)';
COMMENT ON COLUMN sys_page_view_events.target_key IS '대상 식별자 (버튼 ID 등)';
COMMENT ON COLUMN sys_page_view_events.metadata_json IS '추가 메타데이터 (JSON)';

-- ========================================
-- V3 내용 종료
-- ========================================

-- ========================================
-- V4 내용 시작
-- ========================================
-- ========================================
-- DWP Monitoring Schema 수정 V4
-- 생성일: 2026-01-19
-- 목적: latency_ms 컬럼 타입을 INTEGER에서 BIGINT로 변경
-- ========================================

ALTER TABLE sys_api_call_histories ALTER COLUMN latency_ms TYPE BIGINT;

COMMENT ON COLUMN sys_api_call_histories.latency_ms IS '처리 시간 (ms, BIGINT로 변경)';

-- ========================================
-- V4 내용 종료
-- ========================================

-- ========================================
-- V5 내용 시작
-- ========================================
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

-- ========================================
-- V5 내용 종료
-- ========================================

-- ========================================
-- V6 내용 시작
-- ========================================
-- ========================================
-- DWP Menu Tree Schema V6
-- 생성일: 2026-01-19
-- 목적: 메뉴 트리 관리 테이블 생성
-- ========================================

-- ========================================
-- sys_menus (메뉴 트리 메타 테이블)
-- ========================================
CREATE TABLE sys_menus (
    sys_menu_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    menu_key VARCHAR(255) NOT NULL,
    menu_name VARCHAR(200) NOT NULL,
    menu_path VARCHAR(500),
    menu_icon VARCHAR(100),
    menu_group VARCHAR(50),
    parent_menu_key VARCHAR(255),
    sort_order INTEGER NOT NULL DEFAULT 0,
    depth INTEGER NOT NULL DEFAULT 1,
    is_visible VARCHAR(1) NOT NULL DEFAULT 'Y',
    is_enabled VARCHAR(1) NOT NULL DEFAULT 'Y',
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_menus_tenant_key UNIQUE (tenant_id, menu_key)
);

COMMENT ON TABLE sys_menus IS '메뉴 트리 메타 테이블 (권한은 com_resources + com_role_permissions에서 관리)';
COMMENT ON COLUMN sys_menus.sys_menu_id IS '메뉴 식별자 (PK)';
COMMENT ON COLUMN sys_menus.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN sys_menus.menu_key IS '메뉴 키 (com_resources.resource_key와 매칭, 예: menu.admin.users)';
COMMENT ON COLUMN sys_menus.menu_name IS '화면 노출명 (예: 사용자 관리)';
COMMENT ON COLUMN sys_menus.menu_path IS '라우트 경로 (예: /admin/users)';
COMMENT ON COLUMN sys_menus.menu_icon IS '아이콘 키 (예: solar:settings-bold)';
COMMENT ON COLUMN sys_menus.menu_group IS '메뉴 그룹 (MANAGEMENT/APPS 등)';
COMMENT ON COLUMN sys_menus.parent_menu_key IS '상위 메뉴 키 (루트면 NULL, 예: menu.admin)';
COMMENT ON COLUMN sys_menus.sort_order IS '정렬 순서 (낮을수록 앞)';
COMMENT ON COLUMN sys_menus.depth IS '메뉴 깊이 (1=루트, 2=하위, 3=하하위)';
COMMENT ON COLUMN sys_menus.is_visible IS '노출 여부 (Y/N, 권한과 별개로 시스템에서 숨김 가능)';
COMMENT ON COLUMN sys_menus.is_enabled IS '활성화 여부 (Y/N)';
COMMENT ON COLUMN sys_menus.description IS '메뉴 설명';
COMMENT ON COLUMN sys_menus.created_at IS '생성일시';
COMMENT ON COLUMN sys_menus.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN sys_menus.updated_at IS '수정일시';
COMMENT ON COLUMN sys_menus.updated_by IS '수정자 user_id (논리적 참조)';

-- ========================================
-- 인덱스 생성
-- ========================================
CREATE INDEX idx_sys_menus_tenant_id ON sys_menus(tenant_id);
CREATE INDEX idx_sys_menus_menu_key ON sys_menus(menu_key);
CREATE INDEX idx_sys_menus_parent_key ON sys_menus(parent_menu_key);
CREATE INDEX idx_sys_menus_tenant_parent ON sys_menus(tenant_id, parent_menu_key);

-- ========================================
-- V6 내용 종료
-- ========================================

-- ========================================
-- V7 내용 시작
-- ========================================
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

-- ========================================
-- V7 내용 종료
-- ========================================

-- ========================================
-- V8 내용 시작
-- ========================================
-- ========================================
-- DWP Menu Table Column Type Fix V8
-- 생성일: 2026-01-19
-- 목적: sys_menus 테이블의 is_visible, is_enabled 컬럼 타입 수정 (CHAR(1) → VARCHAR(1))
-- ========================================

-- ========================================
-- 컬럼 타입 변경
-- ========================================
ALTER TABLE sys_menus
ALTER COLUMN is_visible TYPE VARCHAR(1) USING is_visible::VARCHAR(1);

ALTER TABLE sys_menus
ALTER COLUMN is_enabled TYPE VARCHAR(1) USING is_enabled::VARCHAR(1);

-- ========================================
-- 주석 업데이트
-- ========================================
COMMENT ON COLUMN sys_menus.is_visible IS '노출 여부 (Y/N, 권한과 별개로 시스템에서 숨김 가능)';
COMMENT ON COLUMN sys_menus.is_enabled IS '활성화 여부 (Y/N)';

-- ========================================
-- V8 내용 종료
-- ========================================

-- ========================================
-- V9 내용 시작
-- ========================================
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

-- ========================================
-- V9 내용 종료
-- ========================================

-- ========================================
-- V10 내용 시작
-- ========================================
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

-- ========================================
-- V10 내용 종료
-- ========================================

-- ========================================
-- V11 내용 시작
-- ========================================
-- ========================================
-- DWP Event Logs Schema V11
-- 생성일: 2026-01-19
-- 목적: 이벤트 로그 전용 테이블 생성 (운영 확장성 및 조회 성능 향상)
-- ========================================

-- ========================================
-- sys_event_logs (이벤트 로그)
-- ========================================
CREATE TABLE sys_event_logs (
    sys_event_log_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_type VARCHAR(50) NOT NULL,
    resource_key VARCHAR(255) NOT NULL,
    action VARCHAR(100) NOT NULL,
    label VARCHAR(200),
    visitor_id VARCHAR(255),
    user_id BIGINT,
    path VARCHAR(500),
    metadata JSONB,
    ip_address VARCHAR(50),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

COMMENT ON TABLE sys_event_logs IS '이벤트 로그 테이블 (클릭, 실행 등 사용자 액션 추적)';
COMMENT ON COLUMN sys_event_logs.sys_event_log_id IS '이벤트 로그 식별자 (PK)';
COMMENT ON COLUMN sys_event_logs.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_event_logs.occurred_at IS '이벤트 발생 시간 (클라이언트 기준 허용, 기본값: 서버 현재 시간)';
COMMENT ON COLUMN sys_event_logs.event_type IS '이벤트 타입 (view/click/execute 등)';
COMMENT ON COLUMN sys_event_logs.resource_key IS '리소스 키 (menu.xxx / btn.xxx 등)';
COMMENT ON COLUMN sys_event_logs.action IS '액션 (view_users / click_send 등)';
COMMENT ON COLUMN sys_event_logs.label IS 'UI 표시용 라벨';
COMMENT ON COLUMN sys_event_logs.visitor_id IS '방문자 식별자 (없으면 null)';
COMMENT ON COLUMN sys_event_logs.user_id IS '로그인 사용자 ID (없으면 null)';
COMMENT ON COLUMN sys_event_logs.path IS '경로 (/admin/users 등)';
COMMENT ON COLUMN sys_event_logs.metadata IS '추가 데이터 (JSONB)';
COMMENT ON COLUMN sys_event_logs.ip_address IS '접속 IP (서버가 파악한 IP)';
COMMENT ON COLUMN sys_event_logs.user_agent IS 'User-Agent (서버가 파악한 UA)';
COMMENT ON COLUMN sys_event_logs.created_at IS '생성일시';
COMMENT ON COLUMN sys_event_logs.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN sys_event_logs.updated_at IS '수정일시';
COMMENT ON COLUMN sys_event_logs.updated_by IS '수정자 user_id (논리적 참조)';

-- ========================================
-- 인덱스 생성
-- ========================================
CREATE INDEX idx_sys_event_logs_tenant_occurred ON sys_event_logs(tenant_id, occurred_at DESC);
CREATE INDEX idx_sys_event_logs_tenant_visitor ON sys_event_logs(tenant_id, visitor_id);
CREATE INDEX idx_sys_event_logs_tenant_resource ON sys_event_logs(tenant_id, resource_key);
CREATE INDEX idx_sys_event_logs_tenant_user ON sys_event_logs(tenant_id, user_id);
CREATE INDEX idx_sys_event_logs_event_type ON sys_event_logs(event_type);

-- ========================================
-- V11 내용 종료
-- ========================================

-- ========================================
-- V12 내용 시작
-- ========================================
-- ========================================
-- DWP Code Usage Schema V12
-- 생성일: 2026-01-20
-- 목적: 메뉴(리소스)별 코드 사용 범위 정의 테이블 생성
-- ========================================

-- ========================================
-- sys_code_usages (메뉴별 코드 사용 정의)
-- ========================================
CREATE TABLE sys_code_usages (
    sys_code_usage_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    resource_key VARCHAR(200) NOT NULL,
    code_group_key VARCHAR(100) NOT NULL,
    scope VARCHAR(30) NOT NULL DEFAULT 'MENU',
    enabled BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER,
    remark VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_code_usages_unique UNIQUE (tenant_id, resource_key, code_group_key)
);

CREATE INDEX idx_sys_code_usages_tenant_resource ON sys_code_usages(tenant_id, resource_key);
CREATE INDEX idx_sys_code_usages_tenant_group ON sys_code_usages(tenant_id, code_group_key);

COMMENT ON TABLE sys_code_usages IS '메뉴(리소스)별 코드 사용 범위 정의';
COMMENT ON COLUMN sys_code_usages.sys_code_usage_id IS '코드 사용 정의 식별자 (PK)';
COMMENT ON COLUMN sys_code_usages.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_code_usages.resource_key IS '리소스 키 (예: menu.admin.users, menu.admin.roles)';
COMMENT ON COLUMN sys_code_usages.code_group_key IS '코드 그룹 키 (예: RESOURCE_TYPE, SUBJECT_TYPE, ROLE_CODE)';
COMMENT ON COLUMN sys_code_usages.scope IS '사용 범위 (MENU/PAGE/MODULE, 기본값: MENU)';
COMMENT ON COLUMN sys_code_usages.enabled IS '활성화 여부';
COMMENT ON COLUMN sys_code_usages.sort_order IS '정렬 순서';
COMMENT ON COLUMN sys_code_usages.remark IS '비고';
COMMENT ON COLUMN sys_code_usages.created_at IS '생성일시';
COMMENT ON COLUMN sys_code_usages.created_by IS '생성자 user_id (논리적 참조)';
COMMENT ON COLUMN sys_code_usages.updated_at IS '수정일시';
COMMENT ON COLUMN sys_code_usages.updated_by IS '수정자 user_id (논리적 참조)';

-- ========================================
-- V12 내용 종료
-- ========================================

-- ========================================
-- V13 내용 시작
-- ========================================
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

-- ========================================
-- V13 내용 종료
-- ========================================

-- ========================================
-- V14 내용 시작
-- ========================================
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

-- ========================================
-- V14 내용 종료
-- ========================================

-- ========================================
-- V15 내용 시작
-- ========================================
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

-- ========================================
-- V15 내용 종료
-- ========================================

-- ========================================
-- V16 내용 시작
-- ========================================
-- ========================================
-- DWP com_resources 확장 (화면 이벤트 추적 강화) V16
-- 생성일: 2026-01-20
-- 목적: com_resource 세분화 및 이벤트 추적 표준화
-- ========================================

-- ========================================
-- 1. com_resources 컬럼 추가
-- ========================================
ALTER TABLE com_resources
    ADD COLUMN IF NOT EXISTS resource_category VARCHAR(50) NOT NULL DEFAULT 'MENU',
    ADD COLUMN IF NOT EXISTS resource_kind VARCHAR(50) NOT NULL DEFAULT 'PAGE',
    ADD COLUMN IF NOT EXISTS event_key VARCHAR(120),
    ADD COLUMN IF NOT EXISTS event_actions JSONB,
    ADD COLUMN IF NOT EXISTS tracking_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS ui_scope VARCHAR(30);

-- ========================================
-- 2. COMMENT 추가
-- ========================================
COMMENT ON COLUMN com_resources.resource_category IS '리소스 대분류 (MENU/UI_COMPONENT) - 기존 type과 동기화';
COMMENT ON COLUMN com_resources.resource_kind IS '리소스 세부 분류 (MENU_GROUP/PAGE/BUTTON/TAB/SELECT/FILTER/SEARCH/TABLE_ACTION/DOWNLOAD/UPLOAD/MODAL/API_ACTION)';
COMMENT ON COLUMN com_resources.event_key IS '이벤트 추적 표준 키 (예: menu.admin.users:view, btn.mail.send:click)';
COMMENT ON COLUMN com_resources.event_actions IS '허용되는 action 목록 JSON 배열 (예: ["VIEW","CLICK","SUBMIT","DOWNLOAD"])';
COMMENT ON COLUMN com_resources.tracking_enabled IS '이벤트 추적 활성화 여부 (false면 silent ignore)';
COMMENT ON COLUMN com_resources.ui_scope IS '적용 범위 (GLOBAL/MENU/PAGE/COMPONENT)';

-- ========================================
-- 3. 인덱스 추가
-- ========================================
CREATE INDEX IF NOT EXISTS idx_com_resources_resource_category ON com_resources(resource_category);
CREATE INDEX IF NOT EXISTS idx_com_resources_resource_kind ON com_resources(resource_kind);
CREATE INDEX IF NOT EXISTS idx_com_resources_event_key ON com_resources(event_key);
CREATE INDEX IF NOT EXISTS idx_com_resources_tracking_enabled ON com_resources(tracking_enabled);

-- ========================================
-- 4. 기존 데이터 마이그레이션 (무손실)
-- ========================================

-- 4.1 MENU 리소스 마이그레이션
-- parent_id 없는 MENU는 MENU_GROUP으로 설정
UPDATE com_resources
SET 
    resource_category = 'MENU',
    resource_kind = CASE 
        WHEN parent_resource_id IS NULL THEN 'MENU_GROUP'
        ELSE 'PAGE'
    END,
    event_key = key || ':view',
    event_actions = '["VIEW","USE"]'::jsonb,
    tracking_enabled = true,
    ui_scope = CASE 
        WHEN parent_resource_id IS NULL THEN 'MENU'
        ELSE 'PAGE'
    END
WHERE type = 'MENU';

-- 4.2 UI_COMPONENT 리소스 마이그레이션 (btn.*)
UPDATE com_resources
SET 
    resource_category = 'UI_COMPONENT',
    resource_kind = 'BUTTON',
    event_key = key || ':click',
    event_actions = '["CLICK","USE"]'::jsonb,
    tracking_enabled = true,
    ui_scope = 'COMPONENT'
WHERE type = 'UI_COMPONENT';

-- ========================================
-- 5. type과 resource_category 동기화 트리거 (선택사항)
-- ========================================
-- 향후 type 컬럼 변경 시 resource_category도 자동 동기화
-- 현재는 수동으로 관리 (하위 호환성 유지)

-- ========================================
-- 완료
-- ========================================
-- 기존 13건 데이터 모두 마이그레이션 완료
-- - MENU (11건): resource_category=MENU, resource_kind=MENU_GROUP 또는 PAGE
-- - UI_COMPONENT (2건): resource_category=UI_COMPONENT, resource_kind=BUTTON
-- ========================================

-- ========================================
-- V16 내용 종료
-- ========================================

-- ========================================
-- V17 내용 시작
-- ========================================
-- ========================================
-- DWP sys_codes 테넌트 지원 확장 V17
-- 생성일: 2026-01-20
-- 목적: sys_codes에 tenant_id 추가하여 테넌트별 커스텀 코드 지원
-- ========================================

-- ========================================
-- 1. sys_codes에 tenant_id 컬럼 추가
-- ========================================
ALTER TABLE sys_codes
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

-- ========================================
-- 2. COMMENT 추가
-- ========================================
COMMENT ON COLUMN sys_codes.tenant_id IS '테넌트 식별자 (null이면 전사 공통 코드, 값이 있으면 테넌트별 커스텀 코드)';

-- ========================================
-- 3. 인덱스 추가
-- ========================================
CREATE INDEX IF NOT EXISTS idx_sys_codes_tenant_id ON sys_codes(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sys_codes_group_tenant_active ON sys_codes(group_key, tenant_id, is_active);

-- ========================================
-- 4. 기존 데이터는 전사 공통 코드로 유지 (tenant_id = null)
-- ========================================
-- 기존 데이터는 이미 tenant_id가 null이므로 별도 업데이트 불필요

-- ========================================
-- 5. UNIQUE 제약조건 수정 (tenant_id 포함)
-- ========================================
-- 기존: uk_sys_codes_group_code (group_key, code)
-- 신규: tenant_id를 포함한 복합 UNIQUE 제약조건 필요
-- 하지만 기존 데이터와의 호환성을 위해 단계적으로 진행

-- 우선 기존 제약조건 유지하고, 향후 필요시 아래와 같이 변경 가능:
-- ALTER TABLE sys_codes DROP CONSTRAINT IF EXISTS uk_sys_codes_group_code;
-- ALTER TABLE sys_codes ADD CONSTRAINT uk_sys_codes_group_tenant_code 
--     UNIQUE (group_key, COALESCE(tenant_id, -1), code);

-- ========================================
-- 완료
-- ========================================
-- sys_codes 테이블이 테넌트별 커스텀 코드를 지원할 수 있도록 확장됨
-- - tenant_id = null: 전사 공통 코드 (기존 데이터)
-- - tenant_id = 값: 테넌트별 커스텀 코드 (향후 확장)
-- ========================================

-- ========================================
-- V17 내용 종료
-- ========================================

-- ========================================
-- V18 내용 시작
-- ========================================
-- ========================================
-- DWP 리소스 추적 코드 Seed V18
-- 생성일: 2026-01-20
-- 목적: RESOURCE_CATEGORY, RESOURCE_KIND, UI_ACTION 코드 그룹 추가
-- ========================================

-- ========================================
-- 1. 코드 그룹 추가
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('RESOURCE_CATEGORY', '리소스 카테고리', '리소스 대분류 (MENU/UI_COMPONENT)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', '리소스 종류', '리소스 세부 분류 (MENU_GROUP/PAGE/BUTTON/TAB/SELECT/FILTER/SEARCH/TABLE_ACTION/DOWNLOAD/UPLOAD/MODAL/API_ACTION)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'UI 액션', 'UI 이벤트 액션 타입 (VIEW/CLICK/SUBMIT/DOWNLOAD/SEARCH/FILTER/OPEN/CLOSE/EXECUTE/SCROLL)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. RESOURCE_CATEGORY 코드 삽입
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('RESOURCE_CATEGORY', 'MENU', '메뉴', '메뉴 리소스 카테고리', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_CATEGORY', 'UI_COMPONENT', 'UI 컴포넌트', 'UI 컴포넌트 리소스 카테고리', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 3. RESOURCE_KIND 코드 삽입
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('RESOURCE_KIND', 'MENU_GROUP', '메뉴 그룹', '상위 메뉴 그룹 (하위 메뉴 포함)', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'PAGE', '페이지', '라우팅되는 화면/페이지', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'BUTTON', '버튼', '버튼 컴포넌트', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'TAB', '탭', '탭 컴포넌트', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'SELECT', '콤보박스', '드롭다운/셀렉트 박스', 50, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'FILTER', '필터', '필터 토글/칩', 60, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'SEARCH', '검색', '검색 입력 필드', 70, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'TABLE_ACTION', '테이블 액션', '테이블 행 액션 (수정/삭제 등)', 80, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'DOWNLOAD', '다운로드', '파일 다운로드', 90, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'UPLOAD', '업로드', '파일 업로드', 100, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'MODAL', '모달', '팝업/모달', 110, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'API_ACTION', 'API 액션', '화면에서 특정 API 실행 트리거', 120, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 4. UI_ACTION 코드 삽입
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('UI_ACTION', 'VIEW', '조회', '화면/페이지 조회', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'CLICK', '클릭', '버튼/링크 클릭', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'EXECUTE', '실행', '액션 실행', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'SCROLL', '스크롤', '페이지 스크롤', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'SEARCH', '검색', '검색 실행', 50, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'FILTER', '필터', '필터 적용', 60, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'DOWNLOAD', '다운로드', '파일 다운로드', 70, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'OPEN', '열기', '모달/팝업 열기', 80, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'CLOSE', '닫기', '모달/팝업 닫기', 90, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'SUBMIT', '제출', '폼 제출', 100, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 5. sys_code_usages에 UI_ACTION 매핑 추가 (menu.admin.monitoring)
-- ========================================
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.admin.monitoring', 'UI_ACTION', 'MENU', true, 10, 'Events 탭 필터용 UI 액션 코드', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    scope = EXCLUDED.scope,
    enabled = EXCLUDED.enabled,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 완료
-- ========================================
-- 코드 그룹 추가:
-- - RESOURCE_CATEGORY (2개 코드)
-- - RESOURCE_KIND (12개 코드)
-- - UI_ACTION (10개 코드)
-- sys_code_usages에 menu.admin.monitoring → UI_ACTION 매핑 추가
-- ========================================

-- ========================================
-- V18 내용 종료
-- ========================================

-- ========================================
-- V19 내용 시작
-- ========================================
-- ========================================
-- DWP sys_event_logs 확장 (resource_kind 추가) V19
-- 생성일: 2026-01-20
-- 목적: 이벤트 로그에 resource_kind 추가하여 추적성 강화
-- ========================================

-- ========================================
-- 1. sys_event_logs에 resource_kind 컬럼 추가
-- ========================================
ALTER TABLE sys_event_logs
    ADD COLUMN IF NOT EXISTS resource_kind VARCHAR(50);

-- ========================================
-- 2. COMMENT 추가
-- ========================================
COMMENT ON COLUMN sys_event_logs.resource_kind IS '리소스 종류 (MENU_GROUP/PAGE/BUTTON/TAB/SELECT/FILTER/SEARCH/TABLE_ACTION/DOWNLOAD/UPLOAD/MODAL/API_ACTION) - com_resources.resource_kind와 동기화';

-- ========================================
-- 3. 인덱스 추가
-- ========================================
CREATE INDEX IF NOT EXISTS idx_sys_event_logs_resource_kind ON sys_event_logs(resource_kind);
CREATE INDEX IF NOT EXISTS idx_sys_event_logs_resource_key_kind ON sys_event_logs(resource_key, resource_kind);

-- ========================================
-- 완료
-- ========================================
-- sys_event_logs 테이블에 resource_kind 컬럼 추가됨
-- 향후 이벤트 수집 시 com_resources에서 resource_kind를 조회하여 저장
-- ========================================

-- ========================================
-- V19 내용 종료
-- ========================================

-- ========================================
-- V20 내용 시작
-- ========================================
-- ========================================
-- V20: Fix bytea columns to VARCHAR
-- ========================================
-- 목적: bytea로 저장된 컬럼을 VARCHAR로 변환
-- 작성일: 2026-01-20

-- ========================================
-- 1. com_users 테이블 컬럼 타입 확인 및 수정
-- ========================================
-- display_name이 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_users' 
        AND column_name = 'display_name' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_users ALTER COLUMN display_name TYPE VARCHAR(200) USING display_name::text;
        RAISE NOTICE 'com_users.display_name converted from bytea to VARCHAR(200)';
    END IF;
END $$;

-- email이 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_users' 
        AND column_name = 'email' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_users ALTER COLUMN email TYPE VARCHAR(255) USING email::text;
        RAISE NOTICE 'com_users.email converted from bytea to VARCHAR(255)';
    END IF;
END $$;

-- ========================================
-- 2. com_user_accounts 테이블 컬럼 타입 확인 및 수정
-- ========================================
-- principal이 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_user_accounts' 
        AND column_name = 'principal' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_user_accounts ALTER COLUMN principal TYPE VARCHAR(255) USING principal::text;
        RAISE NOTICE 'com_user_accounts.principal converted from bytea to VARCHAR(255)';
    END IF;
END $$;

-- provider_type이 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_user_accounts' 
        AND column_name = 'provider_type' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_user_accounts ALTER COLUMN provider_type TYPE VARCHAR(20) USING provider_type::text;
        RAISE NOTICE 'com_user_accounts.provider_type converted from bytea to VARCHAR(20)';
    END IF;
END $$;

-- status가 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_user_accounts' 
        AND column_name = 'status' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_user_accounts ALTER COLUMN status TYPE VARCHAR(20) USING status::text;
        RAISE NOTICE 'com_user_accounts.status converted from bytea to VARCHAR(20)';
    END IF;
END $$;

-- ========================================
-- 3. 검증
-- ========================================
-- 변환된 컬럼 타입 확인
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN 
        SELECT table_name, column_name, data_type, character_maximum_length
        FROM information_schema.columns 
        WHERE table_name IN ('com_users', 'com_user_accounts')
        AND column_name IN ('display_name', 'email', 'principal', 'provider_type', 'status')
        ORDER BY table_name, column_name
    LOOP
        RAISE NOTICE '%.% : % (%)', rec.table_name, rec.column_name, rec.data_type, rec.character_maximum_length;
    END LOOP;
END $$;

-- ========================================
-- V20 내용 종료
-- ========================================
