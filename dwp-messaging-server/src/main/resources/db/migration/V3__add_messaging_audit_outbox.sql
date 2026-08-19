CREATE TABLE sys_audit_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(255),
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_msg_audit_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_msg_audit_outbox_status CHECK (
        status IN ('PENDING', 'SENDING', 'FAILED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_msg_audit_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_msg_audit_delivery
    ON sys_audit_outbox (status, available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'SENDING');

CREATE INDEX idx_msg_audit_published
    ON sys_audit_outbox (published_at)
    WHERE status = 'PUBLISHED';

COMMENT ON TABLE sys_audit_outbox IS
    'Service-local durable audit outbox for messaging policy, access, and conversation operations.';
