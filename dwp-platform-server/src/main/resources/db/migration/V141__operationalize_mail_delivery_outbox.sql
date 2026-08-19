CREATE TABLE mail_delivery_outbox (
    delivery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    thread_id UUID NOT NULL REFERENCES mail_threads(thread_id) ON DELETE CASCADE,
    message_id UUID NOT NULL UNIQUE REFERENCES mail_messages(message_id) ON DELETE CASCADE,
    idempotency_key UUID NOT NULL,
    delivery_status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(240),
    lease_expires_at TIMESTAMPTZ,
    provider_message_ref VARCHAR(500),
    provider_thread_ref VARCHAR(500),
    last_error_code VARCHAR(160),
    correlation_id VARCHAR(160),
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_delivery_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_mail_delivery_status CHECK (delivery_status IN (
        'QUEUED', 'LEASED', 'RETRY_WAIT', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_mail_delivery_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_mail_delivery_lease CHECK (
        (delivery_status = 'LEASED' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (delivery_status <> 'LEASED' AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT ck_mail_delivery_acceptance CHECK (
        (delivery_status = 'DELIVERED' AND accepted_at IS NOT NULL
            AND provider_message_ref IS NOT NULL AND provider_thread_ref IS NOT NULL)
        OR (delivery_status <> 'DELIVERED' AND accepted_at IS NULL))
);

CREATE INDEX idx_mail_delivery_ready
    ON mail_delivery_outbox (next_attempt_at, created_at)
    WHERE delivery_status IN ('QUEUED', 'RETRY_WAIT');

CREATE INDEX idx_mail_delivery_tenant_status
    ON mail_delivery_outbox (tenant_id, delivery_status, updated_at DESC);

COMMENT ON TABLE mail_delivery_outbox IS
    'Transactional command outbox for provider mail delivery. A stable idempotency key survives worker crashes and retries.';
COMMENT ON COLUMN mail_delivery_outbox.lease_owner IS
    'Short-lived worker lease used with SKIP LOCKED so multiple platform instances cannot send the same message concurrently.';
