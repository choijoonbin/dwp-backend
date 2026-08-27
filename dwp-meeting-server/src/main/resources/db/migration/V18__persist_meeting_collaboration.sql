ALTER TABLE vm_tenant_policies
    ADD COLUMN chat_retention_days INTEGER;

UPDATE vm_tenant_policies
   SET chat_retention_days = LEAST(90, retention_days);

ALTER TABLE vm_tenant_policies
    ALTER COLUMN chat_retention_days SET DEFAULT 90,
    ALTER COLUMN chat_retention_days SET NOT NULL,
    ADD CONSTRAINT ck_vm_policy_chat_retention CHECK (
        chat_retention_days BETWEEN 0 AND 365
        AND chat_retention_days <= retention_days);

CREATE TABLE vm_meeting_collaboration_sequences (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id),
    CONSTRAINT fk_vm_collaboration_sequence_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_collaboration_sequence_nonnegative CHECK (last_sequence >= 0)
);

CREATE TABLE vm_meeting_chat_messages (
    message_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    sender_user_id BIGINT NOT NULL,
    sender_person_public_id UUID,
    sender_display_name VARCHAR(160) NOT NULL,
    sender_role VARCHAR(20) NOT NULL,
    created_sequence BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL,
    message_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    message_text VARCHAR(4000),
    retention_until TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    deletion_reason VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_chat_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_vm_chat_participant FOREIGN KEY (tenant_id, meeting_id, participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_vm_chat_sequence UNIQUE (tenant_id, meeting_id, created_sequence),
    CONSTRAINT ck_vm_chat_sender_role CHECK (
        sender_role IN ('ORGANIZER', 'CO_HOST', 'PRESENTER', 'ATTENDEE', 'GUEST')),
    CONSTRAINT ck_vm_chat_state CHECK (message_state IN ('ACTIVE', 'DELETED')),
    CONSTRAINT ck_vm_chat_sequence CHECK (
        created_sequence > 0 AND last_sequence >= created_sequence),
    CONSTRAINT ck_vm_chat_content CHECK (
        (message_state = 'ACTIVE' AND message_text IS NOT NULL
            AND char_length(btrim(message_text)) BETWEEN 1 AND 4000
            AND deleted_at IS NULL AND deleted_by IS NULL)
        OR (message_state = 'DELETED' AND message_text IS NULL
            AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL))
);

CREATE TABLE vm_meeting_hand_requests (
    request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    requester_user_id BIGINT NOT NULL,
    requester_person_public_id UUID,
    requester_display_name VARCHAR(160) NOT NULL,
    requester_role VARCHAR(20) NOT NULL,
    raised_sequence BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL,
    request_state VARCHAR(24) NOT NULL DEFAULT 'RAISED',
    raised_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by BIGINT,
    resolved_at TIMESTAMPTZ,
    resolved_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_hand_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_vm_hand_participant FOREIGN KEY (tenant_id, meeting_id, participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_vm_hand_tenant_meeting_request UNIQUE (tenant_id, meeting_id, request_id),
    CONSTRAINT uk_vm_hand_raised_sequence UNIQUE (tenant_id, meeting_id, raised_sequence),
    CONSTRAINT ck_vm_hand_requester_role CHECK (
        requester_role IN ('ORGANIZER', 'CO_HOST', 'PRESENTER', 'ATTENDEE', 'GUEST')),
    CONSTRAINT ck_vm_hand_state CHECK (
        request_state IN ('RAISED', 'ACKNOWLEDGED', 'LOWERED', 'DISMISSED', 'CLEARED')),
    CONSTRAINT ck_vm_hand_sequence CHECK (
        raised_sequence > 0 AND last_sequence >= raised_sequence),
    CONSTRAINT ck_vm_hand_acknowledgement CHECK (
        (acknowledged_at IS NULL) = (acknowledged_by IS NULL)),
    CONSTRAINT ck_vm_hand_resolution CHECK (
        (resolved_at IS NULL) = (resolved_by IS NULL)),
    CONSTRAINT ck_vm_hand_active_state CHECK (
        (request_state IN ('RAISED', 'ACKNOWLEDGED') AND resolved_at IS NULL)
        OR (request_state IN ('LOWERED', 'DISMISSED', 'CLEARED')
            AND resolved_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_vm_hand_active_participant
    ON vm_meeting_hand_requests (tenant_id, meeting_id, participant_id)
    WHERE request_state IN ('RAISED', 'ACKNOWLEDGED');

CREATE TABLE vm_meeting_hand_events (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    request_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vm_hand_event_request FOREIGN KEY (tenant_id, meeting_id, request_id)
        REFERENCES vm_meeting_hand_requests (tenant_id, meeting_id, request_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_vm_hand_event_sequence UNIQUE (tenant_id, meeting_id, sequence),
    CONSTRAINT ck_vm_hand_event_type CHECK (
        event_type IN ('RAISED', 'ACKNOWLEDGED', 'LOWERED', 'DISMISSED', 'CLEARED'))
);

CREATE TABLE vm_meeting_collaboration_commands (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_resource_id UUID,
    result_sequence BIGINT NOT NULL,
    result_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id, actor_user_id, command_type, idempotency_key),
    CONSTRAINT fk_vm_collaboration_command_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_collaboration_command_type CHECK (command_type IN (
        'CHAT_SEND', 'CHAT_DELETE', 'HAND_RAISE', 'HAND_LOWER',
        'HAND_ACKNOWLEDGE', 'HAND_DISMISS', 'HAND_CLEAR')),
    CONSTRAINT ck_vm_collaboration_command_result CHECK (
        result_sequence >= 0 AND result_count >= 0)
);

CREATE INDEX ix_vm_chat_poll
    ON vm_meeting_chat_messages (tenant_id, meeting_id, last_sequence);
CREATE INDEX ix_vm_chat_retention
    ON vm_meeting_chat_messages (retention_until, message_state);
CREATE INDEX ix_vm_hand_poll
    ON vm_meeting_hand_requests (tenant_id, meeting_id, last_sequence);
CREATE INDEX ix_vm_hand_queue
    ON vm_meeting_hand_requests (tenant_id, meeting_id, raised_sequence)
    WHERE request_state IN ('RAISED', 'ACKNOWLEDGED');
CREATE INDEX ix_vm_hand_event_history
    ON vm_meeting_hand_events (tenant_id, meeting_id, sequence);

COMMENT ON TABLE vm_meeting_chat_messages IS
    'Server-authoritative meeting chat with immutable sender snapshots and deletion tombstones.';
COMMENT ON COLUMN vm_tenant_policies.chat_retention_days IS
    'Days from send time; zero remains readable only while the meeting lifecycle is LIVE.';
COMMENT ON TABLE vm_meeting_hand_requests IS
    'Durable current state for the ordered meeting speaking-right queue.';
COMMENT ON TABLE vm_meeting_hand_events IS
    'Append-only speaking-right state transitions for audit and incremental synchronization.';
