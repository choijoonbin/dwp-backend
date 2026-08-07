CREATE TABLE com_tenants (
    tenant_id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE TABLE com_users (
    user_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    display_name VARCHAR(200) NOT NULL,
    email VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_users_tenant_email UNIQUE (tenant_id, email)
);

CREATE TABLE com_user_accounts (
    user_account_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    user_id BIGINT NOT NULL REFERENCES com_users(user_id),
    provider_type VARCHAR(20) NOT NULL,
    provider_id VARCHAR(100) NOT NULL,
    principal VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_user_accounts_identity
        UNIQUE (tenant_id, provider_type, provider_id, principal)
);

CREATE TABLE sys_auth_policies (
    auth_policy_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL UNIQUE REFERENCES com_tenants(tenant_id),
    default_login_type VARCHAR(30) NOT NULL DEFAULT 'LOCAL',
    allowed_login_types VARCHAR(100) NOT NULL DEFAULT 'LOCAL',
    local_login_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sso_login_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sso_provider_key VARCHAR(100),
    require_mfa BOOLEAN NOT NULL DEFAULT FALSE,
    token_ttl_sec INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE TABLE sys_identity_providers (
    identity_provider_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    provider_type VARCHAR(20) NOT NULL,
    provider_key VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auth_url VARCHAR(500),
    token_url VARCHAR(500),
    user_info_url VARCHAR(500),
    metadata_url VARCHAR(500),
    client_id VARCHAR(255),
    client_secret_env VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_identity_providers_key UNIQUE (tenant_id, provider_key)
);

CREATE TABLE sys_login_histories (
    login_history_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    user_id BIGINT REFERENCES com_users(user_id),
    provider_type VARCHAR(20) NOT NULL,
    provider_id VARCHAR(100) NOT NULL,
    principal VARCHAR(255) NOT NULL,
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(255),
    ip_address VARCHAR(50),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE TABLE com_roles (
    role_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_roles_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE com_role_members (
    role_member_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    role_id BIGINT NOT NULL REFERENCES com_roles(role_id),
    user_id BIGINT NOT NULL REFERENCES com_users(user_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_role_members UNIQUE (tenant_id, role_id, user_id)
);

CREATE TABLE com_resources (
    resource_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT REFERENCES com_tenants(tenant_id),
    type VARCHAR(30) NOT NULL,
    key VARCHAR(255) NOT NULL,
    name VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_resources_key UNIQUE (tenant_id, type, key)
);

CREATE TABLE com_permissions (
    permission_id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE TABLE com_role_permissions (
    role_permission_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    role_id BIGINT NOT NULL REFERENCES com_roles(role_id),
    resource_id BIGINT NOT NULL REFERENCES com_resources(resource_id),
    permission_id BIGINT NOT NULL REFERENCES com_permissions(permission_id),
    effect VARCHAR(10) NOT NULL DEFAULT 'ALLOW',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_com_role_permissions
        UNIQUE (tenant_id, role_id, resource_id, permission_id)
);

CREATE INDEX idx_com_users_tenant ON com_users(tenant_id);
CREATE INDEX idx_com_user_accounts_principal
    ON com_user_accounts(tenant_id, provider_type, principal);
CREATE INDEX idx_sys_login_histories_created
    ON sys_login_histories(tenant_id, created_at DESC);
CREATE INDEX idx_com_role_members_user ON com_role_members(tenant_id, user_id);
CREATE INDEX idx_com_role_permissions_role ON com_role_permissions(tenant_id, role_id);

INSERT INTO com_tenants (tenant_id, code, name)
VALUES (1, 'default', 'Default Tenant');

INSERT INTO com_users (user_id, tenant_id, display_name, email)
VALUES (1, 1, 'Administrator', 'admin@localhost');

INSERT INTO com_user_accounts (
    user_account_id,
    tenant_id,
    user_id,
    provider_type,
    provider_id,
    principal,
    password_hash)
VALUES (
    1,
    1,
    1,
    'LOCAL',
    'local',
    'admin',
    '$2a$10$ms19wna8hc6sLRzidr3VKOtpJ6Pbq/kT6MIpizN79m93qnPyi5hD.');

INSERT INTO sys_auth_policies (
    auth_policy_id,
    tenant_id,
    default_login_type,
    allowed_login_types,
    local_login_enabled,
    sso_login_enabled,
    require_mfa,
    token_ttl_sec)
VALUES (1, 1, 'LOCAL', 'LOCAL', TRUE, FALSE, FALSE, 28800);

INSERT INTO com_roles (role_id, tenant_id, code, name, description)
VALUES (1, 1, 'ADMIN', 'Administrator', 'Foundation administrator role');

INSERT INTO com_role_members (role_member_id, tenant_id, role_id, user_id)
VALUES (1, 1, 1, 1);

INSERT INTO com_permissions (permission_id, code, name)
VALUES
    (1, 'VIEW', 'View'),
    (2, 'CREATE', 'Create'),
    (3, 'UPDATE', 'Update'),
    (4, 'DELETE', 'Delete'),
    (5, 'MANAGE', 'Manage');

SELECT setval(pg_get_serial_sequence('com_tenants', 'tenant_id'), 1, TRUE);
SELECT setval(pg_get_serial_sequence('com_users', 'user_id'), 1, TRUE);
SELECT setval(pg_get_serial_sequence('com_user_accounts', 'user_account_id'), 1, TRUE);
SELECT setval(pg_get_serial_sequence('sys_auth_policies', 'auth_policy_id'), 1, TRUE);
SELECT setval(pg_get_serial_sequence('com_roles', 'role_id'), 1, TRUE);
SELECT setval(pg_get_serial_sequence('com_role_members', 'role_member_id'), 1, TRUE);
SELECT setval(pg_get_serial_sequence('com_permissions', 'permission_id'), 5, TRUE);
