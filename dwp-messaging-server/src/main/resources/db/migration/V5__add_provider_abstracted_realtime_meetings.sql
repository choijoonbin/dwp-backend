ALTER TABLE msg_conversations
    ADD CONSTRAINT uk_msg_conversation_tenant_id
    UNIQUE (tenant_id, conversation_id);

CREATE TABLE msg_meeting_sessions (
    session_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    room_name VARCHAR(180) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    started_by BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_by BIGINT,
    ended_at TIMESTAMPTZ,
    correlation_id VARCHAR(120),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_msg_meeting_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES msg_conversations(tenant_id, conversation_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_msg_meeting_provider_room UNIQUE (provider, room_name),
    CONSTRAINT ck_msg_meeting_provider CHECK (provider IN ('LIVEKIT', 'TEAMS', 'GOOGLE_MEET')),
    CONSTRAINT ck_msg_meeting_state CHECK (lifecycle_state IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_msg_meeting_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_msg_meeting_end_state CHECK (
        (lifecycle_state = 'ACTIVE' AND ended_by IS NULL AND ended_at IS NULL)
        OR (lifecycle_state = 'ENDED' AND ended_by IS NOT NULL AND ended_at IS NOT NULL))
);

CREATE UNIQUE INDEX ux_msg_meeting_one_active_per_conversation
    ON msg_meeting_sessions (tenant_id, conversation_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX ix_msg_meeting_tenant_activity
    ON msg_meeting_sessions (tenant_id, started_at DESC);

CREATE TABLE msg_meeting_events (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    session_id UUID NOT NULL REFERENCES msg_meeting_sessions(session_id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_msg_meeting_event_type CHECK (event_type IN (
        'STARTED', 'TOKEN_ISSUED', 'ENDED')),
    CONSTRAINT ck_msg_meeting_event_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX ix_msg_meeting_event_session_time
    ON msg_meeting_events (tenant_id, session_id, occurred_at DESC);

COMMENT ON TABLE msg_meeting_sessions IS
    'Tenant-scoped realtime meeting lifecycle; room credentials remain provider-owned.';
COMMENT ON TABLE msg_meeting_events IS
    'Operational meeting metadata only. Message bodies and media are never persisted here.';
