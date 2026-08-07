CREATE TABLE sys_auth_sessions (
    session_id UUID PRIMARY KEY,
    token_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    user_id BIGINT NOT NULL REFERENCES com_users(user_id),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    ip_address VARCHAR(50),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

CREATE INDEX idx_sys_auth_sessions_user
    ON sys_auth_sessions(tenant_id, user_id, expires_at DESC);
CREATE INDEX idx_sys_auth_sessions_expiry
    ON sys_auth_sessions(expires_at)
    WHERE revoked_at IS NULL;
