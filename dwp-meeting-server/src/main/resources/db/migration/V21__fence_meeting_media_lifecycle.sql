CREATE TABLE vm_meeting_media_operations (
    operation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    operation_type VARCHAR(12) NOT NULL,
    operation_state VARCHAR(16) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    expected_meeting_version BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    execution_fence UUID NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 1,
    provider_code VARCHAR(24) NOT NULL,
    provider_room_name VARCHAR(180) NOT NULL,
    last_failure_code VARCHAR(80),
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_media_operation_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT uk_vm_media_operation_command UNIQUE (
        tenant_id, meeting_id, actor_user_id, operation_type, idempotency_key),
    CONSTRAINT ck_vm_media_operation_type CHECK (operation_type IN ('START', 'END')),
    CONSTRAINT ck_vm_media_operation_state CHECK (
        operation_state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_vm_media_operation_attempt CHECK (attempt_count > 0),
    CONSTRAINT ck_vm_media_operation_lease CHECK (lease_expires_at > requested_at),
    CONSTRAINT ck_vm_media_operation_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_vm_media_operation_terminal CHECK (
        (operation_state = 'SUCCEEDED' AND completed_at IS NOT NULL
            AND last_failure_code IS NULL)
        OR (operation_state = 'FAILED' AND completed_at IS NOT NULL
            AND last_failure_code IS NOT NULL)
        OR (operation_state = 'RUNNING' AND completed_at IS NULL
            AND last_failure_code IS NULL))
);

CREATE UNIQUE INDEX uk_vm_media_operation_active
    ON vm_meeting_media_operations (tenant_id, meeting_id, operation_type)
    WHERE operation_state = 'RUNNING';

CREATE INDEX idx_vm_media_operation_recovery
    ON vm_meeting_media_operations (operation_state, lease_expires_at, requested_at)
    WHERE operation_state = 'RUNNING';

COMMENT ON TABLE vm_meeting_media_operations IS
    'Durable fenced commands around non-transactional media-provider room operations.';
