CREATE TABLE vm_meeting_recording_provider_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    recording_session_id UUID NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    command_state VARCHAR(16) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    execution_fence UUID,
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 1,
    provider_code VARCHAR(48) NOT NULL,
    provider_command_id VARCHAR(160),
    failure_code VARCHAR(48),
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_vm_recording_provider_command UNIQUE (
        tenant_id, meeting_id, recording_session_id, command_type),
    CONSTRAINT uk_vm_recording_provider_idempotency UNIQUE (
        tenant_id, meeting_id, actor_user_id, command_type, idempotency_key),
    CONSTRAINT fk_vm_recording_provider_session FOREIGN KEY (
        tenant_id, meeting_id, recording_session_id)
        REFERENCES vm_meeting_recording_sessions (
            tenant_id, meeting_id, recording_session_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_recording_provider_type CHECK (
        command_type IN ('START', 'STOP')),
    CONSTRAINT ck_vm_recording_provider_state CHECK (
        command_state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_vm_recording_provider_request CHECK (
        idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$'
        AND request_sha256 ~ '^[0-9a-f]{64}$'
        AND char_length(correlation_id) BETWEEN 1 AND 160),
    CONSTRAINT ck_vm_recording_provider_attempt CHECK (attempt_count > 0),
    CONSTRAINT ck_vm_recording_provider_code CHECK (
        provider_code ~ '^[A-Z][A-Z0-9_-]{2,47}$'),
    CONSTRAINT ck_vm_recording_provider_terminal CHECK (
        (command_state = 'RUNNING' AND execution_fence IS NOT NULL
            AND lease_expires_at IS NOT NULL AND completed_at IS NULL
            AND failure_code IS NULL)
        OR (command_state = 'SUCCEEDED' AND execution_fence IS NULL
            AND lease_expires_at IS NULL AND completed_at IS NOT NULL
            AND failure_code IS NULL AND provider_command_id IS NOT NULL)
        OR (command_state = 'FAILED' AND execution_fence IS NULL
            AND lease_expires_at IS NULL AND completed_at IS NOT NULL
            AND failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$'))
);

CREATE INDEX ix_vm_recording_provider_recovery
    ON vm_meeting_recording_provider_commands (
        command_state, lease_expires_at, requested_at)
    WHERE command_state IN ('RUNNING', 'FAILED');

ALTER TABLE vm_meeting_artifacts
    ADD COLUMN registration_idempotency_key VARCHAR(160),
    ADD COLUMN registration_request_sha256 CHAR(64),
    ADD COLUMN registered_at TIMESTAMPTZ,
    ADD COLUMN registered_by BIGINT,
    ADD CONSTRAINT ck_vm_artifact_registration_evidence CHECK (
        (registration_idempotency_key IS NULL
            AND registration_request_sha256 IS NULL
            AND registered_at IS NULL AND registered_by IS NULL)
        OR (artifact_type = 'TRANSCRIPT'
            AND artifact_state IN ('PROCESSING', 'AVAILABLE', 'FAILED', 'DELETED')
            AND registration_idempotency_key
                ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$'
            AND registration_request_sha256 ~ '^[0-9a-f]{64}$'
            AND sha256 ~ '^[0-9a-f]{64}$'
            AND retention_until IS NOT NULL AND retention_until > registered_at
            AND server_side_processing_allowed = TRUE
            AND processing_region IS NOT NULL AND content_notice_id IS NOT NULL
            AND consent_snapshot_sha256 ~ '^[0-9a-f]{64}$'
            AND registered_at IS NOT NULL AND registered_by > 0))
;

CREATE UNIQUE INDEX uk_vm_artifact_registration_idempotency
    ON vm_meeting_artifacts (
        tenant_id, meeting_id, registration_idempotency_key)
    WHERE registration_idempotency_key IS NOT NULL;

COMMENT ON TABLE vm_meeting_recording_provider_commands IS
    'Content-free durable Egress/STT commands fenced across external provider calls.';
COMMENT ON COLUMN vm_meeting_artifacts.registration_request_sha256 IS
    'Semantic request hash only; raw transcript text and storage credentials are prohibited.';
