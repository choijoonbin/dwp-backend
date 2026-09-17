CREATE TABLE usr_personal_setting_favorites (
    personal_setting_favorite_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    setting_key VARCHAR(64) NOT NULL,
    favorite BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_usr_personal_setting_favorite UNIQUE (tenant_id, user_id, setting_key),
    CONSTRAINT ck_usr_personal_setting_favorite_key CHECK (setting_key <> '')
);

CREATE INDEX idx_usr_personal_setting_favorite_owner
    ON usr_personal_setting_favorites (tenant_id, user_id, favorite, updated_at DESC);

CREATE TABLE usr_personal_setting_activity (
    personal_setting_activity_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    setting_key VARCHAR(64) NOT NULL,
    activity_type VARCHAR(16) NOT NULL,
    changed_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_usr_personal_setting_activity_type
        CHECK (activity_type IN ('VIEW', 'CHANGE')),
    CONSTRAINT ck_usr_personal_setting_activity_fields
        CHECK (jsonb_typeof(changed_fields) = 'array')
);

CREATE INDEX idx_usr_personal_setting_activity_owner
    ON usr_personal_setting_activity (tenant_id, user_id, occurred_at DESC);

CREATE TABLE usr_personal_privacy_consents (
    personal_privacy_consent_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    purpose_key VARCHAR(64) NOT NULL,
    consent_state VARCHAR(16) NOT NULL,
    notice_version VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_usr_personal_privacy_consent_state
        CHECK (consent_state IN ('GRANTED', 'WITHDRAWN')),
    CONSTRAINT ck_usr_personal_privacy_consent_source
        CHECK (source IN ('ACCOUNT_SETTINGS'))
);

CREATE INDEX idx_usr_personal_privacy_consent_owner
    ON usr_personal_privacy_consents (tenant_id, user_id, purpose_key, occurred_at DESC);

CREATE TABLE usr_personal_privacy_requests (
    personal_privacy_request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_type VARCHAR(32) NOT NULL,
    request_state VARCHAR(24) NOT NULL,
    requested_scope VARCHAR(64) NOT NULL,
    reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_usr_personal_privacy_request_type
        CHECK (request_type IN ('DATA_EXPORT', 'ACCOUNT_DELETION')),
    CONSTRAINT ck_usr_personal_privacy_request_state
        CHECK (request_state IN ('RECEIVED', 'CANCELLED'))
);

CREATE INDEX idx_usr_personal_privacy_request_owner
    ON usr_personal_privacy_requests (tenant_id, user_id, created_at DESC);

CREATE UNIQUE INDEX uk_usr_personal_privacy_request_open
    ON usr_personal_privacy_requests (tenant_id, user_id, request_type)
    WHERE request_state = 'RECEIVED';

COMMENT ON TABLE usr_personal_setting_activity IS
    'Tenant- and user-bound recent personal-settings views and committed setting changes.';
COMMENT ON TABLE usr_personal_privacy_consents IS
    'Immutable user consent ledger; required processing under tenant policy is not represented as consent.';
COMMENT ON TABLE usr_personal_privacy_requests IS
    'Internal request intake only. External collection, deletion, archive creation, and fulfillment remain outside this table.';
