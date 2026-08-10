ALTER TABLE com_tenants
    ADD CONSTRAINT ck_com_tenants_status
        CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'RETIRED'));

ALTER TABLE com_users
    ADD CONSTRAINT ck_com_users_status
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'INACTIVE'));

ALTER TABLE com_user_accounts
    ADD CONSTRAINT ck_com_user_accounts_status
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'LOCKED', 'RETIRED'));

CREATE TABLE sys_account_activation_tokens (
    activation_token_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    user_id BIGINT NOT NULL REFERENCES com_users(user_id),
    user_account_id BIGINT NOT NULL REFERENCES com_user_accounts(user_account_id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    CONSTRAINT ck_sys_account_activation_tokens_state
        CHECK (lifecycle_state IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_sys_account_activation_tokens_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_sys_account_activation_tokens_used
        CHECK ((lifecycle_state = 'USED' AND used_at IS NOT NULL) OR lifecycle_state <> 'USED'),
    CONSTRAINT ck_sys_account_activation_tokens_revoked
        CHECK ((lifecycle_state = 'REVOKED' AND revoked_at IS NOT NULL) OR lifecycle_state <> 'REVOKED')
);

CREATE INDEX idx_sys_account_activation_tokens_lookup
    ON sys_account_activation_tokens(token_hash, lifecycle_state, expires_at);
CREATE INDEX idx_sys_account_activation_tokens_account
    ON sys_account_activation_tokens(tenant_id, user_account_id, created_at DESC);
