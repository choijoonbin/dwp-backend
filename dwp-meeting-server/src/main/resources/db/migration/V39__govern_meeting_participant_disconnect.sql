CREATE TABLE vm_meeting_participant_disconnects (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    room_incarnation UUID NOT NULL,
    provider_room_name VARCHAR(240) NOT NULL,
    participant_user_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    expected_participant_version BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    command_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_vm_participant_disconnect_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT uq_vm_participant_disconnect_scope UNIQUE
        (tenant_id, meeting_id, participant_id, room_incarnation),
    CONSTRAINT uq_vm_participant_disconnect_command UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_vm_participant_disconnect_state CHECK
        (command_state IN ('PENDING', 'RUNNING', 'DISCONNECTED'))
);
CREATE INDEX ix_vm_participant_disconnect_recovery
    ON vm_meeting_participant_disconnects (next_attempt_at, requested_at)
    WHERE command_state <> 'DISCONNECTED';
COMMENT ON TABLE vm_meeting_participant_disconnects IS
    'Current room-incarnation token issuance fence and durable provider disconnect commands. Does not claim self-hosted JWT revocation.';
