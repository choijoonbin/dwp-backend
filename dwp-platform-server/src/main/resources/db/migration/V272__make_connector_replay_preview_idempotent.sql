CREATE TABLE wp_connector_replay_preview_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connector_kind VARCHAR(24) NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    preview_id UUID NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_wp_connector_preview_command_idempotency
        UNIQUE (tenant_id, requested_by, idempotency_key),
    FOREIGN KEY (tenant_id, connector_kind, preview_id)
        REFERENCES wp_connector_replay_previews(tenant_id, connector_kind, preview_id),
    CONSTRAINT ck_wp_connector_preview_command_key
        CHECK (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_connector_preview_command_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_connector_preview_command_correlation
        CHECK (length(trim(correlation_id)) BETWEEN 1 AND 160)
);

COMMENT ON TABLE wp_connector_replay_preview_commands IS
    'Durable replay-preview command receipts. The referenced immutable preview row is the exact response snapshot returned for retries.';
