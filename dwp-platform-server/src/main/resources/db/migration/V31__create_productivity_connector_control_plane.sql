CREATE TABLE int_productivity_connectors (
    productivity_connector_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connector_key VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    auth_mode VARCHAR(24) NOT NULL,
    provider_tenant_id VARCHAR(160),
    client_id VARCHAR(160),
    credential_reference VARCHAR(255),
    redirect_uri VARCHAR(1000),
    requested_scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    health_state VARCHAR(40) NOT NULL DEFAULT 'CONFIGURATION_REQUIRED',
    policy_state VARCHAR(32) NOT NULL DEFAULT 'REVIEW_REQUIRED',
    safe_error_code VARCHAR(80),
    last_configuration_check_at TIMESTAMPTZ,
    last_successful_sync_at TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_productivity_connector_key UNIQUE (tenant_id, connector_key),
    CONSTRAINT ck_productivity_connector_provider
        CHECK (provider_type IN ('MICROSOFT_GRAPH')),
    CONSTRAINT ck_productivity_connector_auth_mode
        CHECK (auth_mode IN ('DELEGATED')),
    CONSTRAINT ck_productivity_connector_lifecycle
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_productivity_connector_health
        CHECK (health_state IN (
            'CONFIGURATION_REQUIRED', 'HEALTHY', 'DEGRADED',
            'AUTHENTICATION_REQUIRED', 'UNAVAILABLE')),
    CONSTRAINT ck_productivity_connector_policy
        CHECK (policy_state IN ('REVIEW_REQUIRED', 'APPROVED', 'BLOCKED')),
    CONSTRAINT ck_productivity_connector_failures
        CHECK (consecutive_failures >= 0)
);

CREATE TABLE int_productivity_subjects (
    productivity_subject_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    productivity_connector_id UUID NOT NULL
        REFERENCES int_productivity_connectors(productivity_connector_id),
    user_id BIGINT NOT NULL,
    provider_subject_ref_hash VARCHAR(64),
    encrypted_refresh_token TEXT,
    granted_scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    consent_state VARCHAR(40) NOT NULL DEFAULT 'NOT_CONNECTED',
    token_expires_at TIMESTAMPTZ,
    last_successful_sync_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_productivity_subject UNIQUE (
        tenant_id, productivity_connector_id, user_id),
    CONSTRAINT ck_productivity_subject_consent
        CHECK (consent_state IN (
            'NOT_CONNECTED', 'CONNECTED', 'REAUTHORIZATION_REQUIRED', 'REVOKED'))
);

CREATE TABLE int_productivity_oauth_transactions (
    oauth_transaction_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    productivity_connector_id UUID NOT NULL
        REFERENCES int_productivity_connectors(productivity_connector_id),
    user_id BIGINT NOT NULL,
    state_hash VARCHAR(64) NOT NULL UNIQUE,
    encrypted_pkce_verifier TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_productivity_oauth_expiry CHECK (expires_at > created_at)
);

CREATE TABLE int_productivity_sync_streams (
    productivity_sync_stream_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    productivity_subject_id UUID NOT NULL
        REFERENCES int_productivity_subjects(productivity_subject_id) ON DELETE CASCADE,
    resource_kind VARCHAR(24) NOT NULL,
    encrypted_cursor TEXT,
    cursor_fingerprint VARCHAR(64),
    calendar_window_start TIMESTAMPTZ,
    calendar_window_end TIMESTAMPTZ,
    stream_state VARCHAR(32) NOT NULL DEFAULT 'READY',
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_productivity_sync_stream UNIQUE (
        tenant_id, productivity_subject_id, resource_kind),
    CONSTRAINT ck_productivity_stream_resource
        CHECK (resource_kind IN ('MAIL', 'CALENDAR')),
    CONSTRAINT ck_productivity_stream_state
        CHECK (stream_state IN (
            'READY', 'SYNCING', 'STALE', 'RESET_REQUIRED',
            'AUTHENTICATION_REQUIRED', 'SUSPENDED')),
    CONSTRAINT ck_productivity_calendar_window
        CHECK (resource_kind <> 'CALENDAR'
            OR (calendar_window_start IS NOT NULL AND calendar_window_end IS NOT NULL))
);

CREATE TABLE int_productivity_sync_runs (
    productivity_sync_run_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    productivity_connector_id UUID NOT NULL
        REFERENCES int_productivity_connectors(productivity_connector_id),
    productivity_subject_id UUID NOT NULL
        REFERENCES int_productivity_subjects(productivity_subject_id),
    resource_kind VARCHAR(24) NOT NULL,
    sync_mode VARCHAR(24) NOT NULL,
    run_state VARCHAR(24) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    upsert_count INTEGER NOT NULL DEFAULT 0,
    delete_count INTEGER NOT NULL DEFAULT 0,
    skip_count INTEGER NOT NULL DEFAULT 0,
    error_count INTEGER NOT NULL DEFAULT 0,
    partial_result BOOLEAN NOT NULL DEFAULT FALSE,
    retry_after_at TIMESTAMPTZ,
    safe_error_code VARCHAR(80),
    correlation_id VARCHAR(128),
    initiated_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_productivity_run_resource
        CHECK (resource_kind IN ('MAIL', 'CALENDAR')),
    CONSTRAINT ck_productivity_run_mode
        CHECK (sync_mode IN ('INITIAL', 'DELTA', 'RESET')),
    CONSTRAINT ck_productivity_run_state
        CHECK (run_state IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'BLOCKED')),
    CONSTRAINT ck_productivity_run_counts
        CHECK (upsert_count >= 0 AND delete_count >= 0
            AND skip_count >= 0 AND error_count >= 0)
);

CREATE TABLE int_productivity_sync_errors (
    productivity_sync_error_id UUID PRIMARY KEY,
    productivity_sync_run_id UUID NOT NULL
        REFERENCES int_productivity_sync_runs(productivity_sync_run_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    item_reference_hash VARCHAR(64),
    error_code VARCHAR(80) NOT NULL,
    safe_message VARCHAR(500) NOT NULL,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE wrk_productivity_items (
    productivity_item_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    productivity_connector_id UUID NOT NULL
        REFERENCES int_productivity_connectors(productivity_connector_id),
    resource_kind VARCHAR(24) NOT NULL,
    source_id_hash VARCHAR(64) NOT NULL,
    encrypted_title TEXT NOT NULL,
    encrypted_source_url TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    importance VARCHAR(20),
    read_state BOOLEAN,
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    classification VARCHAR(24) NOT NULL DEFAULT 'CONFIDENTIAL',
    permission_reference_hash VARCHAR(64) NOT NULL,
    source_version VARCHAR(160),
    tombstoned_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_productivity_item_source UNIQUE (
        tenant_id, user_id, productivity_connector_id, resource_kind, source_id_hash),
    CONSTRAINT ck_productivity_item_resource
        CHECK (resource_kind IN ('MAIL', 'CALENDAR')),
    CONSTRAINT ck_productivity_item_classification
        CHECK (classification IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);

CREATE INDEX idx_productivity_connector_tenant_state
    ON int_productivity_connectors(tenant_id, lifecycle_state, health_state);
CREATE INDEX idx_productivity_subject_tenant_state
    ON int_productivity_subjects(tenant_id, consent_state, last_successful_sync_at DESC);
CREATE INDEX idx_productivity_oauth_expiry
    ON int_productivity_oauth_transactions(expires_at)
    WHERE consumed_at IS NULL;
CREATE INDEX idx_productivity_stream_due
    ON int_productivity_sync_streams(tenant_id, stream_state, last_success_at);
CREATE INDEX idx_productivity_run_tenant_time
    ON int_productivity_sync_runs(tenant_id, started_at DESC);
CREATE INDEX idx_productivity_run_subject_time
    ON int_productivity_sync_runs(productivity_subject_id, started_at DESC);
CREATE INDEX idx_productivity_item_user_time
    ON wrk_productivity_items(tenant_id, user_id, resource_kind, occurred_at DESC)
    WHERE tombstoned_at IS NULL;

INSERT INTO int_productivity_connectors (
    productivity_connector_id, tenant_id, connector_key, display_name,
    provider_type, auth_mode, provider_tenant_id, requested_scopes,
    capabilities, lifecycle_state, health_state, policy_state,
    safe_error_code, created_by, updated_by)
VALUES (
    '7bca4684-a8f1-4f6a-8b65-c046fc883ea5', 1, 'MICROSOFT_365', 'Microsoft 365',
    'MICROSOFT_GRAPH', 'DELEGATED', 'organizations',
    '["openid","profile","offline_access","User.Read","Mail.ReadBasic","Calendars.Read"]'::jsonb,
    '["MAIL_METADATA","CALENDAR_EVENTS","DELTA_SYNC","DEEP_LINK"]'::jsonb,
    'DRAFT', 'CONFIGURATION_REQUIRED', 'REVIEW_REQUIRED',
    'MISSING_CLIENT_CONFIGURATION', 1, 1)
ON CONFLICT (tenant_id, connector_key) DO NOTHING;

COMMENT ON COLUMN int_productivity_connectors.credential_reference IS
    'Reference such as env:DWP_MS_GRAPH_CLIENT_SECRET. Secret values are never stored here.';
COMMENT ON COLUMN int_productivity_sync_streams.encrypted_cursor IS
    'Opaque provider delta/next cursor encrypted with tenant and subject bound AAD.';
COMMENT ON TABLE wrk_productivity_items IS
    'User-scoped minimum productivity projection. Message bodies and attachments are prohibited.';
