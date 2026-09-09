CREATE TABLE ntf_user_delivery_endpoints (
    endpoint_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL
        CHECK (channel IN ('WEB_PUSH', 'MOBILE_PUSH')),
    display_name VARCHAR(120) NOT NULL
        CHECK (length(trim(display_name)) > 0),
    platform VARCHAR(30) NOT NULL
        CHECK (platform IN ('WEB', 'IOS', 'ANDROID')),
    endpoint_hint VARCHAR(120) NOT NULL
        CHECK (length(trim(endpoint_hint)) > 0),
    endpoint_fingerprint CHAR(64) NOT NULL
        CHECK (endpoint_fingerprint ~ '^[a-f0-9]{64}$'),
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (state IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_delivery_endpoint_owner
        UNIQUE (tenant_id, user_id, endpoint_id),
    CONSTRAINT uq_ntf_delivery_endpoint_fingerprint
        UNIQUE (tenant_id, user_id, channel, endpoint_fingerprint),
    CONSTRAINT ck_ntf_delivery_endpoint_revocation CHECK (
        (state = 'ACTIVE' AND revoked_at IS NULL)
        OR (state IN ('REVOKED', 'EXPIRED') AND revoked_at IS NOT NULL)
    )
);

CREATE INDEX ix_ntf_delivery_endpoint_active
    ON ntf_user_delivery_endpoints (tenant_id, user_id, last_seen_at DESC)
    WHERE state = 'ACTIVE';

ALTER TABLE ntf_user_delivery_endpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_delivery_endpoints FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_delivery_endpoint_scope ON ntf_user_delivery_endpoints
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );

GRANT SELECT, UPDATE ON ntf_user_delivery_endpoints TO dwp_notification_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON ntf_user_delivery_endpoints
    TO dwp_notification_worker;

COMMENT ON TABLE ntf_user_delivery_endpoints IS
    'User-owned push endpoint inventory. Provider tokens and encryption material live outside this table.';
COMMENT ON COLUMN ntf_user_delivery_endpoints.endpoint_hint IS
    'Non-secret masked label safe for display. It must never contain a raw push token or subscription URL.';
COMMENT ON COLUMN ntf_user_delivery_endpoints.endpoint_fingerprint IS
    'One-way digest used for identity and deduplication; it is never returned by the public API.';
