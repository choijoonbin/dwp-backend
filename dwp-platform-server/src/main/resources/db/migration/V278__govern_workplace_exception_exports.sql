CREATE TABLE wp_exception_export_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    row_count INTEGER NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    UNIQUE (tenant_id, preview_id),
    CONSTRAINT ck_wp_exception_export_preview_actor CHECK (actor_user_id > 0),
    CONSTRAINT ck_wp_exception_export_preview_purpose CHECK
        (length(btrim(purpose)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_exception_export_preview_rows CHECK (row_count BETWEEN 0 AND 250),
    CONSTRAINT ck_wp_exception_export_preview_hashes CHECK
        (content_sha256 ~ '^[0-9a-f]{64}$' AND request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_exception_export_preview_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_exception_export_preview_expiry CHECK (expires_at > created_at)
);

CREATE TABLE wp_exception_export_commands (
    command_id UUID PRIMARY KEY,
    preview_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    row_count INTEGER NOT NULL,
    csv_content TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, command_id),
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, preview_id)
        REFERENCES wp_exception_export_previews(tenant_id, preview_id),
    CONSTRAINT ck_wp_exception_export_command_actor CHECK (actor_user_id > 0),
    CONSTRAINT ck_wp_exception_export_command_reason CHECK
        (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_exception_export_command_rows CHECK (row_count BETWEEN 0 AND 250),
    CONSTRAINT ck_wp_exception_export_command_hashes CHECK
        (content_sha256 ~ '^[0-9a-f]{64}$' AND request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_exception_export_command_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_exception_export_command_expiry CHECK (expires_at > accepted_at)
);

CREATE INDEX idx_wp_exception_export_previews_expiry
    ON wp_exception_export_previews (tenant_id, expires_at);
CREATE INDEX idx_wp_exception_export_commands_expiry
    ON wp_exception_export_commands (tenant_id, expires_at);

COMMENT ON TABLE wp_exception_export_previews IS
    'Short-lived, actor-bound previews for sanitized Workplace exception exports.';
COMMENT ON TABLE wp_exception_export_commands IS
    'Audited, idempotent export receipts with short-lived sanitized CSV content.';
