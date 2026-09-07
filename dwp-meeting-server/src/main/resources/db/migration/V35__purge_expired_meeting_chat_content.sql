CREATE TABLE vm_meeting_chat_retention_health (
    health_key VARCHAR(32) PRIMARY KEY,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_failure_code VARCHAR(48),
    overdue_remaining BOOLEAN NOT NULL DEFAULT FALSE,
    active_fence UUID,
    active_worker_id VARCHAR(120),
    active_lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_vm_chat_retention_health_key CHECK (
        health_key = 'CHAT_RETENTION'),
    CONSTRAINT ck_vm_chat_retention_failure CHECK (
        (last_failure_at IS NULL AND last_failure_code IS NULL)
        OR (last_failure_at IS NOT NULL
            AND last_failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$')),
    CONSTRAINT ck_vm_chat_retention_worker CHECK (
        active_worker_id IS NULL
        OR active_worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'),
    CONSTRAINT ck_vm_chat_retention_lease CHECK (
        (active_fence IS NULL AND active_worker_id IS NULL
            AND active_lease_expires_at IS NULL)
        OR (active_fence IS NOT NULL AND active_worker_id IS NOT NULL
            AND active_lease_expires_at IS NOT NULL))
);

INSERT INTO vm_meeting_chat_retention_health (health_key)
VALUES ('CHAT_RETENTION');

CREATE TABLE vm_meeting_chat_retention_evidence (
    deletion_id UUID PRIMARY KEY,
    execution_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    message_id UUID NOT NULL,
    deletion_reason VARCHAR(32) NOT NULL,
    fence_token UUID NOT NULL,
    worker_id VARCHAR(120) NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_vm_chat_retention_message UNIQUE (
        tenant_id, meeting_id, message_id),
    CONSTRAINT ck_vm_chat_retention_reason CHECK (
        deletion_reason = 'RETENTION_EXPIRED'),
    CONSTRAINT ck_vm_chat_retention_evidence_worker CHECK (
        worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$')
);

CREATE INDEX ix_vm_chat_retention_evidence_execution
    ON vm_meeting_chat_retention_evidence (execution_id, deleted_at);

COMMENT ON TABLE vm_meeting_chat_retention_evidence IS
    'Content-free evidence that expired chat plaintext was physically removed.';
COMMENT ON COLUMN vm_meeting_chat_retention_evidence.message_id IS
    'Opaque identifier only; message text, sender snapshots, and content hashes are prohibited.';
