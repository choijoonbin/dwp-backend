CREATE TABLE wp_resource_favorites (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    resource_id UUID NOT NULL REFERENCES wp_resources(resource_id),
    favorite BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, resource_id),
    CONSTRAINT ck_wp_resource_favorite_user CHECK (user_id > 0),
    CONSTRAINT ck_wp_resource_favorite_version CHECK (version > 0)
);

CREATE INDEX idx_wp_resource_favorites_user
    ON wp_resource_favorites (tenant_id, user_id, updated_at DESC)
    WHERE favorite;

CREATE TABLE wp_resource_favorite_commands (
    command_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    resource_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    result_favorite BOOLEAN NOT NULL,
    result_version BIGINT NOT NULL,
    result_updated_at TIMESTAMPTZ NOT NULL,
    audit_event_id UUID NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_resource_favorite_command UNIQUE (tenant_id, user_id, idempotency_key),
    CONSTRAINT ck_wp_resource_favorite_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_resource_favorite_command_version CHECK (result_version > 0)
);

COMMENT ON TABLE wp_resource_favorites IS
    'Tenant-scoped user resource favorites. Unfavoriting keeps a versioned tombstone for CAS semantics.';
COMMENT ON TABLE wp_resource_favorite_commands IS
    'Replay-safe receipts for resource favorite mutations; no shared-link or credential material is stored.';
