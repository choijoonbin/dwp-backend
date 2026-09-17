ALTER TABLE sys_scim_connectors
    ADD COLUMN purpose VARCHAR(300),
    ADD COLUMN owner_user_id BIGINT,
    ADD COLUMN credential_issued_at TIMESTAMPTZ,
    ADD COLUMN credential_expires_at TIMESTAMPTZ,
    ADD COLUMN credential_rotated_at TIMESTAMPTZ;

UPDATE sys_scim_connectors
   SET purpose = 'SCIM identity provisioning',
       owner_user_id = created_by,
       credential_issued_at = COALESCE(created_at, CURRENT_TIMESTAMP),
       credential_expires_at = GREATEST(
           COALESCE(created_at, CURRENT_TIMESTAMP) + INTERVAL '180 days',
           CURRENT_TIMESTAMP + INTERVAL '30 days'
       );

ALTER TABLE sys_scim_connectors
    ALTER COLUMN purpose SET DEFAULT 'SCIM identity provisioning',
    ALTER COLUMN purpose SET NOT NULL,
    ALTER COLUMN credential_issued_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN credential_issued_at SET NOT NULL,
    ALTER COLUMN credential_expires_at SET DEFAULT (CURRENT_TIMESTAMP + INTERVAL '180 days'),
    ALTER COLUMN credential_expires_at SET NOT NULL,
    ADD CONSTRAINT ck_sys_scim_connectors_purpose
        CHECK (length(trim(purpose)) BETWEEN 1 AND 300),
    ADD CONSTRAINT ck_sys_scim_connectors_allowed_operations
        CHECK (jsonb_typeof(allowed_operations) = 'array'
            AND allowed_operations IN (
                '["USERS"]'::jsonb,
                '["GROUPS"]'::jsonb,
                '["USERS", "GROUPS"]'::jsonb,
                '["GROUPS", "USERS"]'::jsonb
            )),
    ADD CONSTRAINT ck_sys_scim_connectors_credential_period
        CHECK (credential_expires_at > credential_issued_at
            AND (credential_rotated_at IS NULL
                OR credential_rotated_at >= credential_issued_at));

CREATE INDEX idx_sys_scim_connectors_credential_expiry
    ON sys_scim_connectors(tenant_id, credential_expires_at)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON COLUMN sys_scim_connectors.token_hash IS
    'SHA-256 digest of a high-entropy, one-time-revealed bearer token. Raw token material is never persisted.';
COMMENT ON COLUMN sys_scim_connectors.allowed_operations IS
    'Runtime-enforced SCIM resource scopes. USERS and GROUPS are evaluated independently on every request.';
COMMENT ON COLUMN sys_scim_connectors.credential_expires_at IS
    'Hard authentication expiry. Legacy rows receive at least 30 days of migration grace; an ACTIVE connector is still rejected after this instant.';
