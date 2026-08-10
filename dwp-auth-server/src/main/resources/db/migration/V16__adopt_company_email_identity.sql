ALTER TABLE com_users
    DROP CONSTRAINT IF EXISTS uk_com_users_tenant_email;

UPDATE com_users
SET email = 'admin@dwp.local', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND user_id = 1
  AND email = 'admin@localhost';

ALTER TABLE com_users
    ADD COLUMN email_normalized VARCHAR(255)
        GENERATED ALWAYS AS (LOWER(BTRIM(email))) STORED;

CREATE UNIQUE INDEX uk_com_users_tenant_email_normalized
    ON com_users(tenant_id, email_normalized)
    WHERE email_normalized IS NOT NULL;

UPDATE com_user_accounts account
SET principal = LOWER(BTRIM(user_record.email)),
    updated_at = CURRENT_TIMESTAMP
FROM com_users user_record
WHERE account.tenant_id = user_record.tenant_id
  AND account.user_id = user_record.user_id
  AND account.provider_type = 'LOCAL'
  AND account.provider_id = 'local'
  AND user_record.email IS NOT NULL
  AND account.principal <> LOWER(BTRIM(user_record.email));

CREATE UNIQUE INDEX uk_com_user_accounts_local_email
    ON com_user_accounts(tenant_id, LOWER(BTRIM(principal)))
    WHERE provider_type = 'LOCAL' AND provider_id = 'local';

ALTER TABLE com_user_accounts
    ADD COLUMN issuer_uri VARCHAR(500),
    ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_failed_at TIMESTAMPTZ,
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD CONSTRAINT ck_com_user_accounts_failed_count CHECK (failed_login_count >= 0);

ALTER TABLE sys_identity_providers
    ADD COLUMN issuer_uri VARCHAR(500);

UPDATE sys_identity_providers
SET issuer_uri = REGEXP_REPLACE(
        metadata_url,
        '/\\.well-known/openid-configuration/?$',
        '')
WHERE provider_type = 'OIDC'
  AND issuer_uri IS NULL
  AND metadata_url IS NOT NULL;

UPDATE com_user_accounts account
SET issuer_uri = provider.issuer_uri,
    updated_at = CURRENT_TIMESTAMP
FROM sys_identity_providers provider
WHERE account.tenant_id = provider.tenant_id
  AND account.provider_type = 'OIDC'
  AND account.provider_id = provider.provider_key
  AND account.issuer_uri IS NULL
  AND provider.issuer_uri IS NOT NULL;

CREATE UNIQUE INDEX uk_com_user_accounts_oidc_subject
    ON com_user_accounts(tenant_id, issuer_uri, principal)
    WHERE provider_type = 'OIDC' AND issuer_uri IS NOT NULL;

CREATE TABLE sys_identity_sync_receipts (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    source_type VARCHAR(20) NOT NULL,
    person_public_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_identity_sync_source CHECK (source_type IN ('HRIS', 'SCIM'))
);

CREATE INDEX idx_sys_identity_sync_receipts_tenant_time
    ON sys_identity_sync_receipts(tenant_id, processed_at DESC);
