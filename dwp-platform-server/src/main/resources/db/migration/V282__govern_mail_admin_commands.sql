-- Durable idempotency custody for administrator mutations that are not already
-- backed by a purpose-specific command table. A key is actor scoped and bound
-- to one canonical request fingerprint so retries cannot drift to another target.
CREATE TABLE mail_admin_command_receipts (
    receipt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_kind VARCHAR(48) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    aggregate_type VARCHAR(48),
    aggregate_id UUID,
    correlation_id VARCHAR(160),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_admin_command_receipt
        UNIQUE (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT ck_mail_admin_command_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mail_admin_command_aggregate
        CHECK ((aggregate_type IS NULL) = (aggregate_id IS NULL))
);

CREATE INDEX idx_mail_admin_command_receipt_time
    ON mail_admin_command_receipts (tenant_id, created_at DESC);

COMMENT ON TABLE mail_admin_command_receipts IS
    'Actor-scoped idempotency ownership for mail administrator mutations. A reused key with a different fingerprint fails closed.';
