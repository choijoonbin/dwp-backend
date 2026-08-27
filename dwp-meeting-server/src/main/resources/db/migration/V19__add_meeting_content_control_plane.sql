CREATE TABLE vm_meeting_content_plans (
    plan_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    recording_requested BOOLEAN NOT NULL DEFAULT FALSE,
    transcription_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ai_summary_requested BOOLEAN NOT NULL DEFAULT FALSE,
    e2ee_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    plan_state VARCHAR(20) NOT NULL DEFAULT 'DISABLED',
    current_notice_id UUID,
    notice_revision INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_vm_content_plan_meeting UNIQUE (tenant_id, meeting_id),
    CONSTRAINT fk_vm_content_plan_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_content_plan_state CHECK (
        (plan_state = 'DISABLED'
            AND NOT recording_requested
            AND NOT transcription_requested
            AND NOT ai_summary_requested)
        OR (plan_state IN ('BLOCKED', 'READY')
            AND (recording_requested OR transcription_requested OR ai_summary_requested))),
    CONSTRAINT ck_vm_content_plan_notice_revision CHECK (notice_revision >= 0),
    CONSTRAINT ck_vm_content_plan_ai_dependency CHECK (
        NOT ai_summary_requested OR transcription_requested)
);

CREATE TABLE vm_meeting_content_notices (
    notice_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    notice_revision INTEGER NOT NULL,
    notice_state VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    disclosure_code VARCHAR(48) NOT NULL DEFAULT 'MEETING_CONTENT_PROCESSING',
    recording_disclosed BOOLEAN NOT NULL,
    transcription_disclosed BOOLEAN NOT NULL,
    ai_summary_disclosed BOOLEAN NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by BIGINT NOT NULL,
    superseded_at TIMESTAMPTZ,
    CONSTRAINT uk_vm_content_notice_scope UNIQUE (tenant_id, meeting_id, notice_id),
    CONSTRAINT uk_vm_content_notice_revision UNIQUE (
        tenant_id, meeting_id, notice_revision),
    CONSTRAINT fk_vm_content_notice_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_content_notice_state CHECK (
        notice_state IN ('PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT ck_vm_content_notice_disclosure CHECK (
        recording_disclosed OR transcription_disclosed OR ai_summary_disclosed),
    CONSTRAINT ck_vm_content_notice_superseded CHECK (
        (notice_state = 'PUBLISHED' AND superseded_at IS NULL)
        OR (notice_state = 'SUPERSEDED' AND superseded_at IS NOT NULL))
);

ALTER TABLE vm_meeting_content_plans
    ADD CONSTRAINT fk_vm_content_plan_current_notice
    FOREIGN KEY (tenant_id, meeting_id, current_notice_id)
    REFERENCES vm_meeting_content_notices (tenant_id, meeting_id, notice_id),
    ADD CONSTRAINT ck_vm_content_plan_current_notice CHECK (
        ((recording_requested OR transcription_requested OR ai_summary_requested)
            AND current_notice_id IS NOT NULL)
        OR (NOT recording_requested AND NOT transcription_requested
            AND NOT ai_summary_requested AND current_notice_id IS NULL));

CREATE TABLE vm_meeting_content_notice_acknowledgements (
    acknowledgement_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    notice_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    acknowledged_by BIGINT NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_vm_content_notice_ack UNIQUE (
        tenant_id, meeting_id, notice_id, participant_id),
    CONSTRAINT fk_vm_content_notice_ack_notice FOREIGN KEY (
        tenant_id, meeting_id, notice_id)
        REFERENCES vm_meeting_content_notices (tenant_id, meeting_id, notice_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_vm_content_notice_ack_participant FOREIGN KEY (
        tenant_id, meeting_id, participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id)
        ON DELETE CASCADE
);

CREATE TABLE vm_meeting_recording_sessions (
    recording_session_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    plan_version BIGINT NOT NULL,
    notice_id UUID NOT NULL,
    recording_state VARCHAR(24) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    requested_by BIGINT NOT NULL,
    stop_requested_at TIMESTAMPTZ,
    stop_requested_by BIGINT,
    started_at TIMESTAMPTZ,
    stopped_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    failure_code VARCHAR(48),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_vm_recording_session_scope UNIQUE (
        tenant_id, meeting_id, recording_session_id),
    CONSTRAINT fk_vm_recording_session_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_vm_recording_session_notice FOREIGN KEY (
        tenant_id, meeting_id, notice_id)
        REFERENCES vm_meeting_content_notices (tenant_id, meeting_id, notice_id),
    CONSTRAINT ck_vm_recording_session_state CHECK (
        recording_state IN ('REQUESTED', 'STARTING', 'RECORDING',
            'STOP_REQUESTED', 'STOPPED', 'FAILED')),
    CONSTRAINT ck_vm_recording_session_stop_request CHECK (
        (stop_requested_at IS NULL) = (stop_requested_by IS NULL)),
    CONSTRAINT ck_vm_recording_session_failure CHECK (
        (recording_state = 'FAILED') = (failed_at IS NOT NULL AND failure_code IS NOT NULL)),
    CONSTRAINT ck_vm_recording_session_stopped CHECK (
        recording_state <> 'STOPPED' OR stopped_at IS NOT NULL)
);

CREATE UNIQUE INDEX uk_vm_recording_session_active
    ON vm_meeting_recording_sessions (tenant_id, meeting_id)
    WHERE recording_state IN ('REQUESTED', 'STARTING', 'RECORDING', 'STOP_REQUESTED');

CREATE TABLE vm_meeting_content_commands (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    command_outcome VARCHAR(20) NOT NULL,
    http_status INTEGER NOT NULL,
    blocker_codes VARCHAR(1000) NOT NULL DEFAULT '',
    result_resource_id UUID,
    result_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id, actor_user_id, command_type, idempotency_key),
    CONSTRAINT fk_vm_content_command_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_content_command_type CHECK (command_type IN (
        'PLAN_UPDATE', 'NOTICE_ACK', 'RECORDING_REQUEST', 'RECORDING_STOP')),
    CONSTRAINT ck_vm_content_command_outcome CHECK (
        command_outcome IN ('ACCEPTED', 'BLOCKED')),
    CONSTRAINT ck_vm_content_command_http CHECK (http_status IN (200, 409, 503)),
    CONSTRAINT ck_vm_content_command_blockers CHECK (
        blocker_codes = '' OR blocker_codes ~ '^[A-Z_]+(,[A-Z_]+)*$'),
    CONSTRAINT ck_vm_content_command_consistency CHECK (
        (command_outcome = 'ACCEPTED' AND http_status = 200 AND blocker_codes = '')
        OR (command_outcome = 'BLOCKED' AND http_status IN (409, 503)
            AND blocker_codes <> ''))
);

CREATE INDEX ix_vm_content_notice_ack_count
    ON vm_meeting_content_notice_acknowledgements (tenant_id, meeting_id, notice_id);
CREATE INDEX ix_vm_recording_session_history
    ON vm_meeting_recording_sessions (tenant_id, meeting_id, requested_at DESC);

INSERT INTO vm_meeting_content_plans (
    plan_id, tenant_id, meeting_id, created_by, updated_by)
SELECT gen_random_uuid(), tenant_id, meeting_id, organizer_user_id, organizer_user_id
  FROM vm_meetings
ON CONFLICT (tenant_id, meeting_id) DO NOTHING;

COMMENT ON TABLE vm_meeting_content_plans IS
    'Server-authoritative intent and readiness only; it never proves media processing occurred.';
COMMENT ON TABLE vm_meeting_content_notices IS
    'Versioned, content-free disclosure facts acknowledged before governed processing.';
COMMENT ON TABLE vm_meeting_recording_sessions IS
    'Control-plane command state; artifact availability remains independently evidenced.';
COMMENT ON TABLE vm_meeting_content_commands IS
    'Content-free idempotency receipts including fail-closed blocker codes.';
